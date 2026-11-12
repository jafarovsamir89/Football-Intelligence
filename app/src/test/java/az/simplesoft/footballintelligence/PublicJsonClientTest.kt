package az.simplesoft.footballintelligence

import az.simplesoft.footballintelligence.data.ProviderFileCache
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PublicJsonClientTest {
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
        } finally {
            server.close()
            serverThread.join(5_000)
        }
    }
}
