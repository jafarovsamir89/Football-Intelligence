package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.data.ProviderFileCache
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicJsonClientTest {
    @Test fun parsesHttpDateHeaderForProviderClockValidation() {
        assertEquals(
            Instant.parse("2026-11-12T00:00:00Z").toEpochMilli(),
            parseHttpDateMillis("Thu, 12 Nov 2026 00:00:00 GMT"),
        )
        assertNull(parseHttpDateMillis("not an HTTP date"))
        assertNull(parseHttpDateMillis(null))
    }

    @Test fun retryAfterAcceptsSecondsAndHttpDateAndRejectsMalformedValues() {
        val nowMillis = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()
        val retryAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis + 45_000L), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)

        assertEquals(12_000L, parseRetryAfterDelayMillis(" 12 ", nowMillis))
        assertEquals(45_000L, parseRetryAfterDelayMillis(retryAt, nowMillis))
        assertEquals(0L, parseRetryAfterDelayMillis("Wed, 11 Nov 2026 11:59:00 GMT", nowMillis))
        assertNull(parseRetryAfterDelayMillis("not a date", nowMillis))
        assertNull(parseRetryAfterDelayMillis("-1", nowMillis))
        assertNull(parseRetryAfterDelayMillis("9223372036854776", nowMillis))
    }

    @Test fun sendsCachedValidatorsAndReturnsNotModifiedWithoutParsingABody() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val receivedHeaders = AtomicReference<Map<String, String>?>(null)
        val serverFailure = AtomicReference<Throwable?>(null)
        val serverThread = thread(name = "conditional-http-test", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = BufferedReader(
                        InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1),
                    )
                    reader.readLine() ?: error("HTTP request line was missing.")
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            headers[line.substring(0, separator).lowercase()] =
                                line.substring(separator + 1).trim()
                        }
                    }
                    receivedHeaders.set(headers)
                    socket.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 304 Not Modified\r\n" +
                                    "ETag: \"catalog-v2\"\r\n" +
                                    "Last-Modified: Thu, 12 Nov 2026 00:00:00 GMT\r\n" +
                                    "Date: Thu, 12 Nov 2026 00:00:00 GMT\r\n" +
                                    "Content-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(StandardCharsets.ISO_8859_1),
                        )
                        flush()
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }

        try {
            val cached = ProviderFileCache(
                etag = "\"catalog-v1\"",
                lastModified = "Wed, 11 Nov 2026 00:00:00 GMT",
                lastCheckedAtMillis = 1L,
                recordCount = 4,
                cachedBody = "cached catalog",
            )
            val response = PublicJsonClient().get("http://127.0.0.1:${server.localPort}/catalog", cached)

            serverThread.join(5_000)
            assertFalse("Local HTTP response thread did not finish.", serverThread.isAlive)
            assertNull("Local HTTP response failed: ${serverFailure.get()}", serverFailure.get())
            val headers = receivedHeaders.get()
            assertEquals("\"catalog-v1\"", headers?.get("if-none-match"))
            assertEquals("Wed, 11 Nov 2026 00:00:00 GMT", headers?.get("if-modified-since"))
            assertEquals(304, response.statusCode)
            assertEquals("", response.body)
            assertEquals("\"catalog-v2\"", response.etag)
            assertEquals("Thu, 12 Nov 2026 00:00:00 GMT", response.lastModified)
            assertEquals(Instant.parse("2026-11-12T00:00:00Z").toEpochMilli(), response.serverDateMillis)
        } finally {
            server.close()
            serverThread.join(5_000)
        }
    }

    @Test fun recordsOddsApiQuotaHeadersAndServerDateWithoutLoggingRequestUrl() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverFailure = AtomicReference<Throwable?>(null)
        val requestLine = AtomicReference<String?>(null)
        val receivedApiKey = AtomicReference<String?>(null)
        val serverThread = thread(name = "odds-quota-http-test", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                    requestLine.set(reader.readLine() ?: error("HTTP request line was missing."))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0 && line.substring(0, separator).equals("x-api-key", ignoreCase = true)) {
                            receivedApiKey.set(line.substring(separator + 1).trim())
                        }
                    }
                    socket.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Date: Thu, 12 Nov 2026 00:00:00 GMT\r\n" +
                                    "x-requests-remaining: 499\r\n" +
                                    "x-requests-used: 1\r\n" +
                                    "x-requests-last: 1\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: 2\r\n" +
                                    "Connection: close\r\n\r\n[]"
                                ).toByteArray(StandardCharsets.ISO_8859_1),
                        )
                        flush()
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }

        try {
            val response = PublicJsonClient().getWithHeaders(
                "http://127.0.0.1:${server.localPort}/odds",
                mapOf("x-api-key" to "private-test-key"),
            )

            serverThread.join(5_000)
            assertFalse("Local HTTP response thread did not finish.", serverThread.isAlive)
            assertNull("Local HTTP response failed: ${serverFailure.get()}", serverFailure.get())
            assertEquals(200, response.statusCode)
            assertFalse(requestLine.get().orEmpty().contains("private-test-key"))
            assertEquals("private-test-key", receivedApiKey.get())
            assertEquals("[]", response.body)
            assertEquals(499, response.oddsApiRequestsRemaining)
            assertEquals(1, response.oddsApiRequestsUsed)
            assertEquals(1, response.oddsApiRequestCost)
            assertEquals(Instant.parse("2026-11-12T00:00:00Z").toEpochMilli(), response.serverDateMillis)
        } finally {
            server.close()
            serverThread.join(5_000)
        }
    }

    @Test fun rateLimitResponseCarriesRetryAfterWithoutReadingOrRetryingIt() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverFailure = AtomicReference<Throwable?>(null)
        val serverThread = thread(name = "retry-after-http-test", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = BufferedReader(
                        InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1),
                    )
                    reader.readLine() ?: error("HTTP request line was missing.")
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 429 Too Many Requests\r\n" +
                                    "Retry-After: 12\r\n" +
                                    "Content-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(StandardCharsets.ISO_8859_1),
                        )
                        flush()
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }

        try {
            val failure = runCatching {
                PublicJsonClient().get("http://127.0.0.1:${server.localPort}/limited")
            }.exceptionOrNull()

            serverThread.join(5_000)
            assertTrue("Expected a typed HTTP provider failure.", failure is ProviderHttpException)
            assertEquals(429, (failure as ProviderHttpException).statusCode)
            assertEquals(12_000L, failure.retryAfterDelayMillis)
            assertNull("Local HTTP response failed: ${serverFailure.get()}", serverFailure.get())
        } finally {
            server.close()
            serverThread.join(5_000)
        }
    }
}
