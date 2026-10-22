package dev.orchestrator.cursor

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration

/** Transport failure. The message is a code, never a response body or credential. */
internal class CursorTransportException(val kind: String) : RuntimeException(kind)

internal data class CursorHttpResult(val status: Int, val body: String)

/**
 * Official Cloud Agents API. Cursor runs the agent. This client only creates,
 * reads and cancels. It does not implement an agent loop.
 */
internal interface CursorCloudApi {
    fun createAgent(body: String): CursorHttpResult
    fun getAgent(id: String): CursorHttpResult
    fun getRun(agentId: String, runId: String): CursorHttpResult
    fun cancelRun(agentId: String, runId: String): CursorHttpResult
}

internal class CursorCloudHttpApi(
    private val baseUrl: String = "https://api.cursor.com",
    private val key: () -> String? = { System.getenv("CURSOR_API_KEY") },
) : CursorCloudApi {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    override fun createAgent(body: String) = call("POST", "/v1/agents", body)
    override fun getAgent(id: String) = call("GET", "/v1/agents/${agent(id)}", null)
    override fun getRun(agentId: String, runId: String) =
        call("GET", "/v1/agents/${agent(agentId)}/runs/${run(runId)}", null)
    override fun cancelRun(agentId: String, runId: String) =
        call("POST", "/v1/agents/${agent(agentId)}/runs/${run(runId)}/cancel", null)

    private fun call(method: String, path: String, body: String?): CursorHttpResult {
        require(baseUrl == "https://api.cursor.com")
        require(path.startsWith("/v1/") && !path.contains("..") && !path.contains("?"))
        val secret = credential()
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $secret")
            .header("Accept", "application/json")
            .apply { if (body != null) header("Content-Type", "application/json") }
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else {
                require(body.length <= 200_000)
                HttpRequest.BodyPublishers.ofString(body)
            }).build()
        try {
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            val text = response.body().replace(secret, "[redacted]").take(1_000_000)
            return CursorHttpResult(response.statusCode(), text)
        } catch (_: HttpTimeoutException) {
            throw CursorTransportException("CURSOR_TRANSPORT")
        } catch (_: java.io.IOException) {
            throw CursorTransportException("CURSOR_TRANSPORT")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CursorTransportException("CURSOR_TRANSPORT")
        }
    }

    private fun credential(): String {
        val value = key()?.trim().orEmpty()
        if (value.length !in 8..512 || value.any { it.isISOControl() || it.isWhitespace() })
            throw CursorTransportException("CURSOR_AUTH_MISSING")
        return value
    }

    private fun agent(id: String): String {
        require(id.matches(AGENT_ID))
        return id
    }

    private fun run(id: String): String {
        require(id.matches(RUN_ID) && !id.contains(".."))
        return id
    }

    companion object {
        val AGENT_ID: Regex = Regex("bc-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val RUN_ID: Regex = Regex("run-[A-Za-z0-9._-]{1,80}")
    }
}
