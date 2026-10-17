package dev.orchestrator.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.storage.GitHubIssueRecord
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/** Trusted Owner configuration, never derived from an Issue body, label or URL. */
data class GitHubRepositoryTarget(
    val owner: String,
    val name: String,
    val repositoryId: Long,
) {
    init {
        require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")))
        require(name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,99}")))
        require(repositoryId > 0)
    }
    val apiRoot: String get() = "/repos/$owner/$name"
    val fullName: String get() = "$owner/$name"
}

/** GET-only transport. No endpoint to send POST, PATCH, PUT, DELETE or Git commands. */
fun interface GitHubJsonRead {
    fun get(path: String): String
}

class HttpGitHubJsonRead(
    private val token: String? = System.getenv("DEVORCH_GITHUB_READ_TOKEN"),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build(),
) : GitHubJsonRead {
    override fun get(path: String): String {
        require(path.startsWith("/repos/") && !path.contains("..") &&
            !path.contains("://") && !path.contains('#')) {
            "GitHub read must use a safe repository-scoped path"
        }
        val url = URI.create("https://api.github.com$path")
        require(url.scheme == "https" && url.host == "api.github.com" && url.userInfo == null)
        val request = HttpRequest.newBuilder(url)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "DevOrchestrator-Kotlin-M1-04")
            .apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }
            .GET().build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("GitHub read interrupted", e)
        }
        if (response.statusCode() != 200) {
            throw IOException("GitHub GET failed: HTTP ${response.statusCode()}")
        }
        check(response.body().length <= 5_000_000) { "Oversized GitHub API response" }
        return response.body()
    }
}

/**
 * Validates stable repository ID and each GitHub Issue API URL, excludes PRs and captures
 * a pinned default-branch commit SHA. Returns no workflow writes or agents.
 * Inconsistencies abort the FULL fetch, avoiding partial commits from a malformed page.
 */
class GitHubIssueReader(
    private val read: GitHubJsonRead,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    fun read(target: GitHubRepositoryTarget): List<GitHubIssueRecord> {
        val repo = decode(read.get(target.apiRoot))
        require(repo.isObject) { "Invalid GitHub repository response" }
        require(long(repo, "id") == target.repositoryId) { "GitHub repository ID mismatch" }
        require(string(repo, "full_name").equals(target.fullName, ignoreCase = true)) {
            "GitHub repository full_name mismatch"
        }
        val branch = string(repo, "default_branch")
        require(branch.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}")) &&
            !branch.contains("..")) { "Unsafe GitHub default branch" }
        val encodedBranch = URLEncoder.encode(branch, StandardCharsets.UTF_8)
            .replace("+", "%20")
        val commit = decode(read.get("${target.apiRoot}/commits/$encodedBranch"))
        val baseSha = string(commit, "sha").lowercase()
        require(baseSha.matches(Regex("[0-9a-f]{40}"))) { "Missing GitHub base SHA" }

        val all = linkedMapOf<Int, GitHubIssueRecord>()
        var complete = false
        for (page in 1..5) {
            val response = decode(read.get(
                "${target.apiRoot}/issues?state=all&sort=created&direction=asc&per_page=100&page=$page"
            ))
            require(response.isArray && response.size() <= 100) { "Invalid GitHub Issue page" }
            for (item in response) {
                require(item.isObject) { "Invalid GitHub Issue item" }
                if (item.has("pull_request")) continue
                val number = int(item, "number")
                require(number > 0 && long(item, "id") > 0)
                require(string(item, "url").equals(
                    "https://api.github.com${target.apiRoot}/issues/$number",
                    ignoreCase = true,
                )) { "GitHub Issue URL does not match trusted repository" }
                val repositoryUrl = item.get("repository_url")
                if (repositoryUrl != null && !repositoryUrl.isNull) {
                    require(repositoryUrl.isTextual &&
                        repositoryUrl.asText().equals(
                            "https://api.github.com${target.apiRoot}", ignoreCase = true
                        )) { "GitHub Issue claims an unexpected repository" }
                }
                val state = string(item, "state")
                require(state == "open" || state == "closed") { "Unexpected GitHub Issue state" }
                val issue = GitHubIssueRecord(
                    repositoryId = target.repositoryId,
                    issueNumber = number,
                    githubIssueId = long(item, "id"),
                    sourceUpdatedAt = Instant.parse(string(item, "updated_at")),
                    title = string(item, "title"),
                    body = if (item.path("body").isNull) "" else string(item, "body"),
                    baseSha = baseSha,
                    open = state == "open",
                )
                val previous = all.putIfAbsent(issue.issueNumber, issue)
                require(previous == null || previous == issue) {
                    "GitHub Issue changed while paginating; fail closed"
                }
            }
            if (response.size() < 100) {
                complete = true
                break
            }
        }
        check(complete) { "GitHub Issue polling exceeded five-page bound; no intake committed" }
        return all.values.toList()
    }

    private fun decode(json: String): JsonNode {
        require(json.length <= 5_000_000) { "GitHub JSON is too large" }
        return mapper.readTree(json) ?: throw IllegalArgumentException("Empty GitHub response")
    }

    private fun string(node: JsonNode, key: String): String {
        val field = node.get(key)
        require(field != null && field.isTextual) { "Missing GitHub string field: $key" }
        return field.asText()
    }

    private fun long(node: JsonNode, key: String): Long {
        val field = node.get(key)
        require(field != null && field.isIntegralNumber && field.canConvertToLong()) {
            "Invalid GitHub integer field: $key"
        }
        return field.longValue()
    }

    private fun int(node: JsonNode, key: String): Int {
        val field = node.get(key)
        require(field != null && field.isInt) { "Invalid GitHub number field: $key" }
        return field.intValue()
    }
}
