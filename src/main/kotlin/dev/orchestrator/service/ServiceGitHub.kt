package dev.orchestrator.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.github.*
import dev.orchestrator.publish.*
import dev.orchestrator.storage.GitHubIssueRecord
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

internal interface ServiceGitHub {
    fun issues(): List<GitHubIssueRecord>
    fun authorized(issue: GitHubIssueRecord): Boolean
    fun postStatus(number: Int, body: String)
}

/** Fixed PERSONAL repo, bounded full HTTP deadline; only new status comments may be written. */
internal class PersonalGitHub(private val token: String, private val transport: PublicationHttp? = null) : ServiceGitHub {
    private val mapper=ObjectMapper()
    private val root=SMOKE_TARGET.apiRoot
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    init { require(token.isNotBlank() && token.none { it.isISOControl() }) }
    private fun request(method: String,path: String,body: String?=null): PublicationResponse {
        require(path==root || path.startsWith("$root/"))
        require(!path.contains("..") && !path.contains(':') && !path.contains('#'))
        require(method=="GET" || method=="POST" && path.matches(Regex("$root/issues/[1-9][0-9]*/comments")))
        transport?.let { return it.request(method,path,body) }
        val req=HttpRequest.newBuilder(URI.create("https://api.github.com$path"))
            .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer $token")
            .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28")
            .header("Content-Type","application/json").header("User-Agent","DevOrchestrator-Personal")
            .method(method,if (body==null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build()
        val response=sendPublicationRequest(client,req)
        return PublicationResponse(response.statusCode(),response.body().toString(Charsets.UTF_8))
    }
    private fun get(path: String): String = request("GET",path).let { check(it.status==200); it.body }
    override fun issues(): List<GitHubIssueRecord> {
        val repo=mapper.readTree(get(root))
        require(repo.path("id").asLong()==SMOKE_TARGET.repositoryId && repo.path("full_name").asText()==SMOKE_TARGET.fullName &&
            repo.path("default_branch").asText()=="main" && !repo.path("archived").asBoolean() && !repo.path("disabled").asBoolean())
        val records=GitHubIssueReader(GitHubJsonRead(::get)).read(SMOKE_TARGET)
        require(records.groupBy { it.githubIssueId }.values.all { it.size==1 })
        return records
    }
    override fun authorized(issue: GitHubIssueRecord): Boolean {
        // Check current source and current label, then its latest actor: Issue prose grants nothing.
        val n=mapper.readTree(get("$root/issues/${issue.issueNumber}"))
        if (n.has("pull_request") || n.path("id").asLong()!=issue.githubIssueId || n.path("state").asText()!="open" ||
            n.path("user").path("login").asText()!="siyovushdev" || n.path("title").asText()!=issue.title ||
            n.path("body").asText("")!=issue.body || n.path("labels").none { it.path("name").asText()=="devorch:run" }) return false
        var actor: String?=null
        for (page in 1..10) {
            val events=mapper.readTree(get("$root/issues/${issue.issueNumber}/events?per_page=100&page=$page"))
            require(events.isArray && events.size()<=100)
            for (event in events) if (event.path("label").path("name").asText()=="devorch:run") {
                when(event.path("event").asText()) {
                    "labeled" -> actor=event.path("actor").path("login").asText()
                    "unlabeled" -> actor=null
                }
            }
            if (events.size()<100) return actor=="siyovushdev"
        }
        return false
    }
    override fun postStatus(number: Int,body: String) {
        require(number>0 && body.length<=2000)
        val r=request("POST","$root/issues/$number/comments",mapper.writeValueAsString(mapOf("body" to body)))
        check(r.status==201)
        val n=mapper.readTree(r.body)
        val id=n.path("id").asLong(); check(id>0 && n.path("body").asText()==body)
        val saved=mapper.readTree(get("$root/issues/comments/$id"))
        check(saved.path("id").asLong()==id && saved.path("body").asText()==body &&
            saved.path("issue_url").asText()=="https://api.github.com$root/issues/$number")
    }
}
