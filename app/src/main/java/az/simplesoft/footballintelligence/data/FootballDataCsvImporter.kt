package az.simplesoft.footballintelligence.data

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

data class ImportedFootballResult(
    val competitionCode: String,
    val eventDate: LocalDate,
    val homeTeam: String,
    val awayTeam: String,
    val homeGoals: Int,
    val awayGoals: Int,
)

data class FootballCsvImportReport(
    val results: List<ImportedFootballResult>,
    val rowsRead: Int,
    val rejectedRows: Int,
    val duplicateRows: Int,
    val examples: List<String>,
)

data class StoredFootballCsvImport(
    val insertedRows: Int,
    val duplicateRows: Int,
)

data class ImportedHistoryRow(
    val id: Long,
    val competitionCode: String,
    val eventDate: LocalDate,
    val availableAtMillis: Long,
    val homeTeam: String,
    val awayTeam: String,
    val homeGoals: Int,
    val awayGoals: Int,
)

/** Parser for user-selected Football-Data.co.uk style results CSV files. */
object FootballDataCsvImporter {
    private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE
    private val legacyCsvCharset = Charset.forName("windows-1252")

    /** Source files have a calendar date but no timezone; don't use a result until two days later. */
    fun conservativeAvailabilityMillis(eventDate: LocalDate): Long = eventDate.plusDays(2)
        .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Date-only historical targets are evaluated at the start of their listed calendar date. */
    fun dateBucketForecastCutoffMillis(eventDate: LocalDate): Long = eventDate
        .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    fun decode(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        String(bytes, legacyCsvCharset)
    }

    fun parse(csv: String, fallbackCompetitionCode: String? = null): FootballCsvImportReport {
        require(csv.isNotBlank()) { "CSV file is empty." }
        val rows = readCsvRows(csv)
        require(rows.isNotEmpty()) { "CSV file has no rows." }

        val headers = rows.first().mapIndexedNotNull { index, raw ->
            val header = raw.removePrefix("\uFEFF").trim()
            normalizeHeader(header).takeIf { it.isNotEmpty() }?.let { it to index }
        }.toMap()
        fun column(vararg aliases: String): Int? = aliases.firstNotNullOfOrNull { headers[normalizeHeader(it)] }
        val dateColumn = column("Date", "MatchDate") ?: error("Required column Date is missing.")
        val homeColumn = column("HomeTeam", "Home Team") ?: error("Required column HomeTeam is missing.")
        val awayColumn = column("AwayTeam", "Away Team") ?: error("Required column AwayTeam is missing.")
        val homeGoalsColumn = column("FTHG", "HG", "FullTimeHomeGoals") ?: error("Required column FTHG/HG is missing.")
        val awayGoalsColumn = column("FTAG", "AG", "FullTimeAwayGoals") ?: error("Required column FTAG/AG is missing.")
        val competitionColumn = column("Div", "Division", "Competition")
        val fallback = fallbackCompetitionCode?.trim()?.takeIf { it.isNotEmpty() }

        val results = mutableListOf<ImportedFootballResult>()
        val seen = mutableSetOf<String>()
        val examples = mutableListOf<String>()
        var rowsRead = 0
        var rejected = 0
        var duplicates = 0

        for ((zeroIndex, row) in rows.drop(1).withIndex()) {
            if (row.all { it.isBlank() }) continue
            rowsRead++
            val lineNumber = zeroIndex + 2
            try {
                fun value(index: Int): String = row.getOrNull(index)?.trim().orEmpty()
                val date = parseDate(value(dateColumn)) ?: error("invalid or missing Date")
                val home = value(homeColumn).takeIf { it.isNotBlank() } ?: error("missing HomeTeam")
                val away = value(awayColumn).takeIf { it.isNotBlank() } ?: error("missing AwayTeam")
                require(normalizeTeam(home) != normalizeTeam(away)) { "HomeTeam and AwayTeam are identical" }
                val homeGoals = value(homeGoalsColumn).toIntOrNull()?.takeIf { it >= 0 } ?: error("invalid FTHG")
                val awayGoals = value(awayGoalsColumn).toIntOrNull()?.takeIf { it >= 0 } ?: error("invalid FTAG")
                val competition = competitionColumn?.let(::value)?.takeIf { it.isNotBlank() } ?: fallback
                    ?: error("missing Div; provide a competition code")

                val key = listOf(competition, date.toString(), normalizeTeam(home), normalizeTeam(away))
                    .joinToString("|") { it.lowercase(Locale.ROOT) }
                if (!seen.add(key)) {
                    duplicates++
                    if (examples.size < 5) examples.add("Строка $lineNumber: дубль пропущен")
                    continue
                }
                results.add(ImportedFootballResult(competition, date, home, away, homeGoals, awayGoals))
            } catch (e: Exception) {
                rejected++
                if (examples.size < 5) examples.add("Строка $lineNumber: ${e.message ?: "ошибка данных"}")
            }
        }
        return FootballCsvImportReport(results, rowsRead, rejected, duplicates, examples)
    }

    private fun readCsvRows(text: String): List<List<String>> {
        val firstLine = text.substringBefore('\n')
        val delimiter = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
            } else {
                when {
                    c == '"' && field.isEmpty() -> quoted = true
                    c == delimiter -> { row.add(field.toString()); field.setLength(0) }
                    c == '\n' || c == '\r' -> {
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        row.add(field.toString()); field.setLength(0)
                        rows.add(row.toList()); row.clear()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        require(!quoted) { "CSV has an unterminated quoted field." }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row.toList())
        }
        return rows
    }

    private fun parseDate(raw: String): LocalDate? {
        if (raw.isBlank()) return null
        runCatching { LocalDate.parse(raw, isoDate) }.getOrNull()?.let { return it }
        val parts = raw.split('/')
        if (parts.size != 3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val yearPart = parts[2].toIntOrNull() ?: return null
        val year = when (parts[2].length) {
            2 -> if (yearPart >= 70) 1900 + yearPart else 2000 + yearPart
            4 -> yearPart
            else -> return null
        }
        return try { LocalDate.of(year, month, day) } catch (_: Exception) { null }
    }

    private fun normalizeHeader(value: String): String = value.trim().lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }

    private fun normalizeTeam(value: String): String = value.trim().lowercase(Locale.ROOT)
        .split(Regex("\\s+")).joinToString(" ")

}
