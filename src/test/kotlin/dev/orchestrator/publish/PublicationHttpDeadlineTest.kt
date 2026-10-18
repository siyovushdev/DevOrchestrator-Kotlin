package dev.orchestrator.publish

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Loopback dummy HTTP only; exercises the production send/subscriber path, never GitHub/auth. */
class PublicationHttpDeadlineTest {
    private fun stalledBody(action: (HttpClient,HttpRequest,CountDownLatch)->Unit) {
        val headers=CountDownLatch(1)
        val release=CountDownLatch(1)
        val worker=Executors.newSingleThreadExecutor()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.executor=worker
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200,100)
                exchange.responseBody.write('{'.code); exchange.responseBody.flush()
                headers.countDown()
                release.await(5,TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        try {
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { client ->
                val request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}/"))
                    .timeout(Duration.ofSeconds(4)).GET().build()
                action(client,request,headers)
            }
        } finally { release.countDown(); server.stop(0); worker.shutdownNow() }
    }

    @Test fun fullResponseDeadlineStopsPartialBodyAfterHeaders() = stalledBody { client,request,headers ->
        val started=System.nanoTime()
        assertFailsWith<HttpTimeoutException> { sendPublicationRequest(client,request,500) }
        assertEquals(0,headers.count)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<3000)
    }

    @Test fun interruptedWaitCancelsRequestAndRestoresInterruptFlag() = stalledBody { client,request,headers ->
        val restored=AtomicBoolean(false)
        val interrupted=AtomicBoolean(false)
        val thread=Thread {
            try { sendPublicationRequest(client,request,4000) }
            catch (_: InterruptedException) { interrupted.set(true); restored.set(Thread.currentThread().isInterrupted) }
        }
        thread.start()
        try {
            assertTrue(headers.await(2,TimeUnit.SECONDS))
            thread.interrupt(); thread.join(2000)
            assertFalse(thread.isAlive); assertTrue(interrupted.get()); assertTrue(restored.get())
        } finally { thread.interrupt(); thread.join(5000) }
    }
}
