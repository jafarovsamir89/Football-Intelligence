package az.simplesoft.footballintelligence.analytics

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Parses provider timestamps as UTC when they omit a zone and preserves explicit offsets. */
fun normalizeProviderTimestampUtc(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return try {
        Instant.parse(value).toString()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toString()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

fun isFreshQuote(capturedAtMillis: Long, nowMillis: Long, maxAgeMillis: Long = 600_000L): Boolean {
    require(maxAgeMillis >= 0L)
    return capturedAtMillis <= nowMillis && nowMillis - capturedAtMillis <= maxAgeMillis
}
