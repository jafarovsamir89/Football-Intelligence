package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.data.ProviderSyncSummary
import az.simplesoft.footballintelligence.data.OddsProviderStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class AutomaticFootballSyncOverview(
    val status: String,
    val detail: String,
    val healthy: Boolean?,
)

internal data class HomeOddsProviderOverview(
    val status: String,
    val detail: String,
    val healthy: Boolean?,
)

/** Explains what the last public-page capture found without implying background refresh. */
internal fun homeOddsProviderOverview(
    state: BookmakerSyncState?,
    freshExactLinkedCount: Int,
    nowMillis: Long,
): HomeOddsProviderOverview {
    require(freshExactLinkedCount >= 0)
    val checkedAt = state?.lastCheckedAtMillis
    val checkedInFuture = checkedAt != null && checkedAt > nowMillis + 60_000L
    val captureIsFresh = checkedAt != null && !checkedInFuture && nowMillis - checkedAt <= 30L * 60 * 1000
    val status = when {
        state == null -> "Ещё не проверялся"
        checkedAt == null -> "Нет времени проверки"
        checkedInFuture -> "Время проверки некорректно"
        state.status == OddsProviderStatus.ERROR -> "Ошибка проверки"
        state.status == OddsProviderStatus.BLOCKED -> "Доступ ограничен источником"
        state.status == OddsProviderStatus.STALE || !captureIsFresh -> "Последняя проверка устарела"
        state.status == OddsProviderStatus.LIVE && state.recordsStored > 0 -> "Видимые линии получены"
        else -> "Будущих линий не найдено"
    }
    val detail = if (state == null) {
        "Проверка открытой страницы выполняется при работе приложения; Android не гарантирует её в фоне."
    } else {
        val checked = checkedAt?.takeUnless { checkedInFuture }?.let(::formatHomeOddsTime)
        val timestamp = checked?.let { "Проверка $it" } ?: "Время проверки неизвестно"
        val rows = "видимых линий: ${state.recordsStored}"
        val linked = "свежих точных совпадений: $freshExactLinkedCount"
        val sourceDetail = state.error?.takeIf(String::isNotBlank)
        listOfNotNull(timestamp, rows, linked, sourceDetail).joinToString(" · ")
    }
    val healthy = when {
        state == null -> null
        state.status == OddsProviderStatus.ERROR || state.status == OddsProviderStatus.BLOCKED -> false
        captureIsFresh && state.status == OddsProviderStatus.LIVE && freshExactLinkedCount > 0 -> true
        else -> null
    }
    return HomeOddsProviderOverview(status, detail, healthy)
}

private fun formatHomeOddsTime(timestampMillis: Long): String =
    DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru"))
        .withZone(ZoneId.of("Asia/Baku"))
        .format(Instant.ofEpochMilli(timestampMillis))

/** A short, truthful Home summary derived from the latest persisted state of football providers. */
internal fun automaticFootballSyncOverview(
    running: Boolean,
    queued: Boolean,
    providers: List<ProviderSyncSummary>,
): AutomaticFootballSyncOverview {
    if (running) {
        return AutomaticFootballSyncOverview(
            status = "Идёт обновление",
            detail = "Загружаются расписание и результаты; сохранённая база остаётся доступна офлайн.",
            healthy = null,
        )
    }
    if (queued) {
        return AutomaticFootballSyncOverview(
            status = "Ожидает запуска Android",
            detail = "WorkManager запустит обновление при подходящей сети и системных условиях.",
            healthy = null,
        )
    }

    fun isCurrentAttempt(provider: ProviderSyncSummary): Boolean =
        provider.lastSuccessAtMillis == null || provider.lastCheckedAtMillis >= provider.lastSuccessAtMillis

    val failures = providers.filter { it.status.equals("ERROR", ignoreCase = true) && isCurrentAttempt(it) }
    if (failures.isNotEmpty()) {
        val reasons = failures.joinToString("; ") { provider ->
            "${providerName(provider.providerId)}: ${provider.error?.takeIf(String::isNotBlank) ?: "последний запрос не прошёл"}"
        }
        return AutomaticFootballSyncOverview(
            status = "Ошибка источника",
            detail = "$reasons. Ранее сохранённые матчи остаются доступны офлайн.",
            healthy = false,
        )
    }

    val partial = providers.filter { it.status.equals("PARTIAL", ignoreCase = true) && isCurrentAttempt(it) }
    if (partial.isNotEmpty()) {
        return AutomaticFootballSyncOverview(
            status = "Импорт частичный",
            detail = "${partial.joinToString { providerName(it.providerId) }}: часть данных получена; продолжение или повтор будет автоматическим.",
            healthy = null,
        )
    }

    val deferred = providers.filter { it.status.equals("DEFERRED", ignoreCase = true) && isCurrentAttempt(it) }
    if (deferred.isNotEmpty()) {
        return AutomaticFootballSyncOverview(
            status = "Импорт продолжится автоматически",
            detail = "${deferred.joinToString { providerName(it.providerId) }} ожидает следующего сегмента WorkManager.",
            healthy = null,
        )
    }

    val latestSuccess = providers.mapNotNull { it.lastSuccessAtMillis }.maxOrNull()
    if (latestSuccess != null) {
        val time = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru"))
            .withZone(ZoneId.of("Asia/Baku"))
            .format(Instant.ofEpochMilli(latestSuccess))
        return AutomaticFootballSyncOverview(
            status = "Обновлено $time",
            detail = "Матчи и результаты сохранены на устройстве и доступны офлайн.",
            healthy = true,
        )
    }

    return AutomaticFootballSyncOverview(
        status = "Ожидается первый ответ источника",
        detail = "При доступной сети приложение само запросит футбольные данные.",
        healthy = null,
    )
}

internal fun providerSyncDetails(providers: List<ProviderSyncSummary>): String {
    if (providers.isEmpty()) return "Состояние источников ещё не сохранено."
    return providers.sortedBy { it.providerId }.joinToString("\n") { provider ->
        val checked = provider.lastCheckedAtMillis.takeIf { it > 0L }
            ?.let { " · проверка ${formatSyncTime(it)}" }.orEmpty()
        val success = provider.lastSuccessAtMillis
            ?.let { " · успех ${formatSyncTime(it)}" }.orEmpty()
        val error = provider.error?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        "${providerName(provider.providerId)} · ${provider.status} · записей ${provider.recordsStored}$checked$success$error"
    }
}

private fun formatSyncTime(timestampMillis: Long): String =
    DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru"))
        .withZone(ZoneId.of("Asia/Baku"))
        .format(Instant.ofEpochMilli(timestampMillis))

private fun providerName(providerId: String): String = when (providerId.lowercase(Locale.ROOT)) {
    "openfootball" -> "OpenFootball"
    "openligadb" -> "OpenLigaDB"
    else -> providerId
}
