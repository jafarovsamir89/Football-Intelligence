package az.simplesoft.footballintelligence

import android.os.Bundle
import androidx.compose.ui.platform.LocalContext
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.Context
import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import az.simplesoft.footballintelligence.analytics.MarginMethod
import az.simplesoft.footballintelligence.analytics.ProbabilityEngine
import az.simplesoft.footballintelligence.analytics.isFreshQuote
import az.simplesoft.footballintelligence.analytics.normalizeProviderTimestampUtc

data class Match(val id: Int, val league: String, val home: String, val away: String,
                 val kickoff: String, val finished: Boolean, val homeScore: Int?, val awayScore: Int?)
data class Quote(val id: Long, val matchId: Int, val bookmaker: String,
                 val h: Double, val d: Double, val a: Double, val capturedAt: Long)
data class Pick(val id: Long, val matchId: Int, val source: String, val outcome: String, val timestamp: Long)
data class Ticket(val id: Long, val matchId: Int, val outcome: String, val price: Double,
                  val stake: Double, val bookmaker: String, val createdAt: Long)
fun labelOutcome(outcome: String) = when(outcome) { "H" -> "П1"; "D" -> "X"; else -> "П2" }
fun niceTime(raw: String): String = try {
    val instant = Instant.parse(raw)
    DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru"))
        .withZone(ZoneId.systemDefault()).format(instant)
} catch (_: Exception) { raw.take(16) }
fun scoreResult(m: Match): String? = if (!m.finished || m.homeScore == null || m.awayScore == null) null
    else if (m.homeScore > m.awayScore) "H" else if (m.homeScore < m.awayScore) "A" else "D"
fun fairProbabilities(q: Quote, method: MarginMethod = MarginMethod.BASIC): Triple<Double, Double, Double> {
    val p = ProbabilityEngine.removeMargin(q.h, q.d, q.a, method)
    return Triple(p.home, p.draw, p.away)
}

class Store(context: Context) : SQLiteOpenHelper(context, "football_intelligence.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE matches (id INTEGER PRIMARY KEY, league TEXT NOT NULL, home TEXT NOT NULL, away TEXT NOT NULL, kickoff TEXT NOT NULL, finished INTEGER NOT NULL DEFAULT 0, home_score INTEGER, away_score INTEGER)")
        db.execSQL("CREATE TABLE quotes (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, bookmaker TEXT NOT NULL, h REAL NOT NULL, d REAL NOT NULL, a REAL NOT NULL, captured_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE picks (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, source TEXT NOT NULL, outcome TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tickets (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, outcome TEXT NOT NULL, price REAL NOT NULL, stake REAL NOT NULL, bookmaker TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun upsert(matches: List<Match>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in matches) {
                // Never replace/delete a row: tickets and other local records refer to this match ID.
                // An incomplete later provider response must not erase a result already used for settlement.
                val values = ContentValues().apply {
                    put("league", m.league); put("home", m.home); put("away", m.away); put("kickoff", m.kickoff)
                    put("finished", if (m.finished) 1 else 0)
                    if (m.homeScore == null) putNull("home_score") else put("home_score", m.homeScore)
                    if (m.awayScore == null) putNull("away_score") else put("away_score", m.awayScore)
                }
                val updated = db.update("matches", values,
                    "id=? AND (finished=0 OR (?=1 AND ? IS NOT NULL AND ? IS NOT NULL))",
                    arrayOf(m.id.toString(), if (m.finished) "1" else "0", m.homeScore?.toString(), m.awayScore?.toString()))
                if (updated == 0) {
                    values.put("id", m.id)
                    db.insertWithOnConflict("matches", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun matches(): List<Match> {
        val result=mutableListOf<Match>()
        readableDatabase.rawQuery("SELECT id,league,home,away,kickoff,finished,home_score,away_score FROM matches ORDER BY finished ASC,kickoff ASC LIMIT 250",null).use { c ->
            while(c.moveToNext()) result.add(Match(c.getInt(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5)==1,
                if(c.isNull(6)) null else c.getInt(6),if(c.isNull(7)) null else c.getInt(7)))
        }
        return result
    }
    fun quotes(matchId:Int):List<Quote> {
        val result=mutableListOf<Quote>()
        readableDatabase.rawQuery("SELECT id,match_id,bookmaker,h,d,a,captured_at FROM quotes WHERE match_id=? ORDER BY captured_at DESC",arrayOf(matchId.toString())).use { c ->
            while(c.moveToNext()) result.add(Quote(c.getLong(0),c.getInt(1),c.getString(2),c.getDouble(3),c.getDouble(4),c.getDouble(5),c.getLong(6)))
        }
        return result
    }
    fun picks(matchId:Int):List<Pick> {
        val result=mutableListOf<Pick>()
        readableDatabase.rawQuery("SELECT id,match_id,source,outcome,created_at FROM picks WHERE match_id=? ORDER BY created_at DESC",arrayOf(matchId.toString())).use { c ->
            while(c.moveToNext()) result.add(Pick(c.getLong(0),c.getInt(1),c.getString(2),c.getString(3),c.getLong(4)))
        }
        return result
    }
    fun tickets():List<Ticket> {
        val result=mutableListOf<Ticket>()
        readableDatabase.rawQuery("SELECT id,match_id,outcome,price,stake,bookmaker,created_at FROM tickets ORDER BY id DESC",null).use { c ->
            while(c.moveToNext()) result.add(Ticket(c.getLong(0),c.getInt(1),c.getString(2),c.getDouble(3),c.getDouble(4),c.getString(5),c.getLong(6)))
        }
        return result
    }
    fun ticketMatches():Map<Int,Match> {
        val result=linkedMapOf<Int,Match>()
        readableDatabase.rawQuery("SELECT DISTINCT m.id,m.league,m.home,m.away,m.kickoff,m.finished,m.home_score,m.away_score FROM matches m INNER JOIN tickets t ON t.match_id=m.id",null).use { c ->
            while(c.moveToNext()) result[c.getInt(0)]=Match(c.getInt(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5)==1,
                if(c.isNull(6)) null else c.getInt(6),if(c.isNull(7)) null else c.getInt(7))
        }
        return result
    }
    fun saveQuote(matchId:Int, book:String, h:Double,d:Double,a:Double) =
        writableDatabase.execSQL("INSERT INTO quotes(match_id,bookmaker,h,d,a,captured_at) VALUES(?,?,?,?,?,?)",arrayOf(matchId,book,h,d,a,System.currentTimeMillis()))
    fun savePick(matchId:Int, source:String,outcome:String) =
        writableDatabase.execSQL("INSERT INTO picks(match_id,source,outcome,created_at) VALUES(?,?,?,?)",arrayOf(matchId,source,outcome,System.currentTimeMillis()))
    fun saveTicket(matchId:Int,outcome:String,price:Double,stake:Double,book:String) =
        writableDatabase.execSQL("INSERT INTO tickets(match_id,outcome,price,stake,bookmaker,created_at) VALUES(?,?,?,?,?,?)",arrayOf(matchId,outcome,price,stake,book,System.currentTimeMillis()))
}

fun parseMatches(data: JSONArray, league: String): List<Match> = buildList {
    for (i in 0 until data.length()) {
        val o=data.optJSONObject(i) ?: continue
        val id=o.optInt("matchID",0)
        val h=o.optJSONObject("team1")?.optString("teamName").orEmpty()
        val a=o.optJSONObject("team2")?.optString("teamName").orEmpty()
        val rawDate=o.optString("matchDateTimeUTC","")
        val date=normalizeProviderTimestampUtc(rawDate)
        if(id==0 || h.isBlank() || a.isBlank() || date==null) continue
        val goals=o.optJSONArray("matchResults")
        var hs:Int?=null; var as_:Int?=null
        if(goals!=null) {
            for(j in 0 until goals.length()) {
                val r=goals.optJSONObject(j) ?: continue
                if(r.optInt("resultTypeID")==2) {
                    hs=r.optInt("pointsTeam1"); as_=r.optInt("pointsTeam2");break
                }
            }
        }
        val done=o.optBoolean("matchIsFinished",false)
        add(Match(id,league,h,a,date,done,if(done) hs else null,if(done) as_ else null))
    }
}

class MainActivity:ComponentActivity() {
    private lateinit var store:Store
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        store=Store(this)
        setContent { FootballScreen(store) }
    }
}

@Composable fun FootballScreen(store:Store) {
    val activity = LocalContext.current as ComponentActivity
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(-1) }
    var tab by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf(false) }
    var marginMethod by remember { mutableStateOf(MarginMethod.BASIC) }
    var message by remember { mutableStateOf("Выбери лигу и обнови матчи. Интернет нужен только для обновления.") }
    val matches=remember(revision) { store.matches() }
    val active=matches.firstOrNull { it.id==selected }
    val quoteList=remember(selected,revision) { if(selected>=0) store.quotes(selected) else emptyList() }
    val pickList=remember(selected,revision) { if(selected>=0) store.picks(selected) else emptyList() }
    val tickets=remember(revision) { store.tickets() }
    val ticketMatches=remember(revision) { store.ticketMatches() }
    val mapped=matches.associateBy { it.id }
    val settled=tickets.mapNotNull { t -> ticketMatches[t.matchId]?.let { m -> scoreResult(m)?.let { it to t } } }
    val exposure=settled.sumOf { it.second.stake }
    val returned=settled.sumOf { (r,t)->if(r==t.outcome) t.stake*t.price else 0.0 }
    val roi=if(exposure>0) (returned/exposure-1)*100 else 0.0
    var book by remember(selected) { mutableStateOf("") }
    var h by remember(selected) { mutableStateOf("") }
    var d by remember(selected) { mutableStateOf("") }
    var a by remember(selected) { mutableStateOf("") }
    var source by remember(selected) { mutableStateOf("") }
    var outcome by remember(selected) { mutableStateOf("H") }
    var stake by remember(selected) { mutableStateOf("10") }
    var warning by remember(selected) { mutableStateOf("") }
    val blue=MaterialTheme.colorScheme.primary

    fun download(league:String, season:Int) {
        if(downloading) return
        downloading=true;message="Получение $league $season..."
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url=URL("https://api.openligadb.de/getmatchdata/$league/$season")
                connection=url.openConnection() as HttpURLConnection
                connection.connectTimeout=12000; connection.readTimeout=16000
                connection.setRequestProperty("Accept","application/json")
                val body=connection.inputStream.bufferedReader().use { it.readText() }
                val list=parseMatches(JSONArray(body),league)
                store.upsert(list)
                activity.runOnUiThread { revision++; downloading=false;message="Сохранено матчей: ${list.size} ($league). Все данные доступны офлайн." }
            } catch(e:Exception) {
                activity.runOnUiThread { downloading=false;message="Ошибка сети или данных: ${e.message}" }
            } finally { connection?.disconnect() }
        }.start()
    }

    MaterialTheme {
      Scaffold(topBar={ Surface(color=MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("FOOTBALL INTELLIGENCE",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("Личный аналитический журнал • Бесплатно • Без реальных ставок",style=MaterialTheme.typography.bodySmall)
        }
      }}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Матчи","Анализ","Виртуальные ставки","О проекте").forEachIndexed { i,title ->
                    FilterChip(selected=tab==i,onClick={tab=i},label={Text(title)})
                }
            }
            when(tab) {
              0 -> {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={download("bl1",2026)},enabled=!downloading){Text("Бундеслига")}
                    OutlinedButton(onClick={download("bl2",2026)},enabled=!downloading){Text("2. Бундеслига")}
                    OutlinedButton(onClick={download("bl1",2025)},enabled=!downloading){Text("Сезон 2025/26")}
                }
                Text(message,Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
                if(downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    items(matches,key={it.id}) { m ->
                        ElevatedCard(onClick={selected=m.id;tab=1},modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("${m.league} · ${niceTime(m.kickoff)}",style=MaterialTheme.typography.labelMedium,color=blue)
                                Spacer(Modifier.height(5.dp))
                                Text("${m.home} — ${m.away}",fontWeight=FontWeight.SemiBold)
                                Text(if(m.finished) "Завершён: ${m.homeScore ?: "?"} : ${m.awayScore ?: "?"}" else "Ожидается · Нажми для анализа",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
              }
              1 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                if(active==null) { Text("Сначала выбери матч на вкладке «Матчи».") }
                else {
                  Text("${active.home} — ${active.away}",style=MaterialTheme.typography.titleLarge)
                  Text("${niceTime(active.kickoff)} · ${if(active.finished) "Завершён" else "До начала"}",style=MaterialTheme.typography.labelSmall)
                  HorizontalDivider()
                  Text("Снимок линии букмекера (1X2)",fontWeight=FontWeight.Bold)
                  Text("Введи все три исхода одного букмекера. Сохраняется время снимка. Данные не проверяются автоматически.",style=MaterialTheme.typography.bodySmall)
                  Text("Удаление маржи",fontWeight=FontWeight.SemiBold)
                  Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                      listOf(MarginMethod.BASIC to "Нормализация", MarginMethod.POWER to "Power", MarginMethod.SHIN to "Shin").forEach { (method, label) ->
                          FilterChip(selected=marginMethod==method,onClick={marginMethod=method},label={Text(label)})
                      }
                  }
                  OutlinedTextField(value=book,onValueChange={book=it},label={Text("Название букмекера")},singleLine=true,modifier=Modifier.fillMaxWidth())
                  Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                      OutlinedTextField(value=h,onValueChange={h=it},label={Text("П1")},singleLine=true,modifier=Modifier.weight(1f))
                      OutlinedTextField(value=d,onValueChange={d=it},label={Text("X")},singleLine=true,modifier=Modifier.weight(1f))
                      OutlinedTextField(value=a,onValueChange={a=it},label={Text("П2")},singleLine=true,modifier=Modifier.weight(1f))
                  }
                  Button(onClick={
                      val hh=h.replace(',','.').toDoubleOrNull();val dd=d.replace(',','.').toDoubleOrNull();val aa=a.replace(',','.').toDoubleOrNull()
                      if(book.isBlank()||listOf(hh,dd,aa).any { it==null || it<=1.0 || !it.isFinite() }) warning="Укажи букмекера и три корректных коэффициента больше 1."
                      else if(active.finished || runCatching { Instant.parse(active.kickoff).toEpochMilli() <= System.currentTimeMillis() }.getOrDefault(true)) warning="Для честной проверки нельзя фиксировать новую линию после старта."
                      else {store.saveQuote(active.id,book.trim(),hh!!,dd!!,aa!!);revision++;warning="Линия сохранена";h="";d="";a=""}
                  },modifier=Modifier.fillMaxWidth()){Text("Сохранить линию")}
                  if(warning.isNotBlank()) Text(warning,style=MaterialTheme.typography.bodySmall)
                  if(quoteList.isNotEmpty()) {
                    Text("Сохранённые линии",fontWeight=FontWeight.Bold)
                    quoteList.forEach { q ->
                        val p=fairProbabilities(q, marginMethod)
                        ElevatedCard {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(q.bookmaker,fontWeight=FontWeight.SemiBold)
                                Text("П1 ${q.h} · X ${q.d} · П2 ${q.a}")
                                Text("${marginMethod.name} • без маржи: ${"%.1f".format(p.first*100)}% / ${"%.1f".format(p.second*100)}% / ${"%.1f".format(p.third*100)}%",style=MaterialTheme.typography.bodySmall)
                                val fresh=isFreshQuote(q.capturedAt,System.currentTimeMillis())
                                Text("${if(fresh) "Свежая запись" else "Устарела или время некорректно"} · ${niceTime(Instant.ofEpochMilli(q.capturedAt).toString())}",style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    val now=System.currentTimeMillis()
                    val freshByBook=quoteList.filter { isFreshQuote(it.capturedAt,now) }
                        .groupBy { it.bookmaker.trim().lowercase(Locale.ROOT) }
                        .values.mapNotNull { rows -> rows.maxByOrNull { it.capturedAt } }
                    if(freshByBook.isNotEmpty()) {
                        val consensus=freshByBook.map { fairProbabilities(it,marginMethod) }
                        val consensusH=consensus.map{it.first}.average()
                        val consensusD=consensus.map{it.second}.average()
                        val consensusA=consensus.map{it.third}.average()
                        Text("Среднее записанных линий по одному последнему снимку на букмекера (${freshByBook.size}): ${"%.1f".format(consensusH*100)}% / ${"%.1f".format(consensusD*100)}% / ${"%.1f".format(consensusA*100)}%",fontWeight=FontWeight.SemiBold)
                        Text("Простое среднее введённых 1X2 линий после удаления маржи; не проверено у букмекеров.",style=MaterialTheme.typography.bodySmall)
                        Text("Лучшие записанные цены по П1/X/П2 могут относиться к разным букмекерам; проверяй каждый коэффициент отдельно: ${freshByBook.maxOf{it.h}} · ${freshByBook.maxOf{it.d}} · ${freshByBook.maxOf{it.a}}",style=MaterialTheme.typography.bodySmall)
                        Text("Расхождение котировок не является доказательством положительного EV.",style=MaterialTheme.typography.bodySmall)
                    }
                  }
                  HorizontalDivider()
                  Text("Прогнозы других игроков",fontWeight=FontWeight.Bold)
                  OutlinedTextField(value=source,onValueChange={source=it},label={Text("Автор / источник прогноза")},singleLine=true,modifier=Modifier.fillMaxWidth())
                  OutcomeSelect(outcome,{outcome=it})
                  Button(onClick={
                      if(source.isBlank()) warning="Укажи источник прогноза."
                      else if(active.finished || runCatching { Instant.parse(active.kickoff).toEpochMilli() <= System.currentTimeMillis() }.getOrDefault(true)) warning="Нельзя задним числом добавить прогноз."
                      else {store.savePick(active.id,source.trim(),outcome);revision++;source="";warning="Прогноз сохранён (не подтверждён реальной ставкой)."}
                  },modifier=Modifier.fillMaxWidth()){Text("Добавить прогноз")}
                  Text("Прогнозы: ${pickList.size}; П1: ${pickList.count{it.outcome=="H"}} · X: ${pickList.count{it.outcome=="D"}} · П2: ${pickList.count{it.outcome=="A"}}")
                  Text("Повторные авторы и перепечатки могут искажать консенсус. Здесь не проверяется независимость.",style=MaterialTheme.typography.bodySmall)
                  HorizontalDivider()
                  Text("Виртуальная одиночная ставка",fontWeight=FontWeight.Bold)
                  OutcomeSelect(outcome,{outcome=it})
                  OutlinedTextField(value=stake,onValueChange={stake=it},label={Text("Условная сумма (₼)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                  val now=System.currentTimeMillis()
                  val available=quoteList.filter { isFreshQuote(it.capturedAt,now) }
                      .groupBy { it.bookmaker.trim().lowercase(Locale.ROOT) }
                      .values.mapNotNull { rows -> rows.maxByOrNull { it.capturedAt } }
                  val best=available.maxByOrNull{if(outcome=="H") it.h else if(outcome=="D") it.d else it.a}
                  val price=best?.let{if(outcome=="H") it.h else if(outcome=="D") it.d else it.a}
                  Text(if(best==null) "Нет свежей линии за последние 10 минут" else "Линия: ${best.bookmaker} • коэффициент ${price}",style=MaterialTheme.typography.bodySmall)
                  Button(enabled=best!=null&&!active.finished,onClick={
                      val amount=stake.replace(',','.').toDoubleOrNull()
                      val starts=runCatching{Instant.parse(active.kickoff).toEpochMilli()}.getOrNull()
                      if(amount==null||!amount.isFinite()||amount<=0) warning="Некорректная условная сумма."
                      else if(starts==null||starts<=System.currentTimeMillis()) warning="Матч уже начался или время неизвестно."
                      else if(best!=null&&price!=null) {store.saveTicket(active.id,outcome,price,amount,best.bookmaker);revision++;warning="Виртуальная ставка зафиксирована."}
                  },modifier=Modifier.fillMaxWidth()){Text("Записать виртуальную ставку")}
                }
              }
              2 -> Column(Modifier.fillMaxSize().padding(14.dp)) {
                Text("Проверка стратегии",style=MaterialTheme.typography.titleLarge)
                Text("Закрытых: ${settled.size} из ${tickets.size} · оборот: ${"%.2f".format(exposure)} ₼ · возврат: ${"%.2f".format(returned)} ₼")
                Text("ROI: ${"%.2f".format(roi)}% (только закрытые ставки)",fontWeight=FontWeight.Bold)
                Text("Нет виртуальных денег? Отмечай ставки в «Анализе» до начала матча. После обновления результатов ставки рассчитываются автоматически.",style=MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(tickets,key={it.id}) { t ->
                        val m=ticketMatches[t.matchId] ?: mapped[t.matchId]
                        val result=m?.let{scoreResult(it)}
                        val state=if(result==null) "Ожидается" else if(result==t.outcome) "Выигрыш +${"%.2f".format(t.stake*(t.price-1))}" else "Проигрыш −${"%.2f".format(t.stake)}"
                        ElevatedCard {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("${m?.home ?: "Матч"} — ${m?.away ?: t.matchId}",fontWeight=FontWeight.Bold)
                                Text("${labelOutcome(t.outcome)} · ${t.bookmaker} · кф ${t.price} · ${t.stake} ₼")
                                Text(state,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
              }
              else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                  Text("Football Intelligence Lab",style=MaterialTheme.typography.titleLarge)
                  Text("Личная лаборатория исследования футбольных ставок, без оплаты и обязательных ключей.")
                  Text("Матчи: OpenLigaDB (открытый источник). Коэффициенты и прогнозы добавляет пользователь. Ставки исключительно виртуальные.")
                  Text("Ограничения версии 0.1: только поддерживаемые лиги OpenLigaDB, ручные коэффициенты 1X2, без экспрессов, без полноценного статистического преимущества, без подтверждения сторонних прогнозов.")
                  Text("Система не выполняет ставки, не входит в букмекерские аккаунты и не обещает доход.")
                  Text("Открытые источники могут временно не отвечать. Уже сохранённые данные доступны без сети.")
              }
            }
        }
      }
    }
}
@Composable fun OutcomeSelect(value:String,onChange:(String)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("H","D","A").forEach { v -> FilterChip(selected=value==v,onClick={onChange(v)},label={Text(labelOutcome(v))}) }
    }
}
