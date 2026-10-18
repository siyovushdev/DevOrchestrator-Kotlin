package dev.orchestrator.github

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the same streaming decoder as HttpGitHubJsonRead, not ofString(). */
class BoundedGitHubHttpBodyTest {
    @Test fun generatedHugeBodyIsStoppedAfterAtMostLimitPlusOneByte() {
        val oversized = object : InputStream() {
            var delivered = 0
            override fun read(): Int {
                delivered++
                return 'x'.code
            }
            override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
                java.util.Arrays.fill(buffer,offset,offset + len,'x'.code.toByte())
                delivered += len
                return len
            }
        }
        assertFailsWith<IOException> {
            BoundedGitHubResponse.read(oversized, maxBytes = 1024)
        }
        assertEquals(1025, oversized.delivered)
    }

    @Test fun validAtBoundAndInvalidUtf8AreHandledWithoutPostReadSizeCheck() {
        val text = "{\"hello\":\"world\"}"
        assertEquals(text, BoundedGitHubResponse.read(
            text.byteInputStream(StandardCharsets.UTF_8), text.toByteArray().size))
        assertFailsWith<IOException> {
            BoundedGitHubResponse.read(
                text.byteInputStream(StandardCharsets.UTF_8), maxBytes = 4)
        }
        assertFailsWith<java.nio.charset.MalformedInputException> {
            BoundedGitHubResponse.read(byteArrayOf(0xc3.toByte(),0x28).inputStream(), 32)
        }
    }

    @Test fun chunkedLocalHttpBodyIsCancelledAsSoonAsLimitIsExceeded() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/large") { exchange ->
            exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(200,0) // chunked: no Content-Length
            try {
                exchange.responseBody.use { body ->
                    repeat(10) {
                        body.write(ByteArray(2048) { 'a'.code.toByte() })
                        body.flush()
                    }
                }
            } catch (_: IOException) {
                // Expected when bounded reader closes the response stream early.
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val url = URI.create("http://127.0.0.1:${server.address.port}/large")
            HttpClient.newHttpClient().use { client ->
                val request = HttpRequest.newBuilder(url).GET().build()
                val response = client.send(request,HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { body ->
                    val error = assertFailsWith<IOException> {
                        BoundedGitHubResponse.read(body,maxBytes = 4096)
                    }
                    assertTrue(error.message.orEmpty().contains("byte limit"))
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
