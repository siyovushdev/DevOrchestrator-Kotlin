package dev.orchestrator.publish

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.agent.nativecli.NativeProcessRunner
import dev.orchestrator.agent.nativecli.ProcessObservation
import dev.orchestrator.github.GitHubRepositoryTarget
import dev.orchestrator.storage.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.net.http.HttpTimeoutException
import java.io.ByteArrayOutputStream

internal val SMOKE_TARGET = GitHubRepositoryTarget("siyovushdev","devorch-kotlin-e2e-smoke",1424152600)
internal data class PublicationResponse(val status: Int, val body: String)
internal fun interface PublicationHttp {
    fun request(method: String, path: String, body: String?): PublicationResponse
}

/** No redirects, arbitrary URLs, application retries, token logging or merge endpoint. */
internal class GitHubPublicationHttp(private val token: String) : PublicationHttp {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build()
    init { require(token.isNotBlank() && token.none { it.isISOControl() }) }
    override fun request(method: String, path: String, body: String?): PublicationResponse {
        require(path==SMOKE_TARGET.apiRoot || path.startsWith(SMOKE_TARGET.apiRoot+"/"))
        require(!path.contains("..") && !path.contains(":") && !path.contains('#'))
        require(method=="GET" || method=="POST" && path==SMOKE_TARGET.apiRoot+"/pulls")
        val request = HttpRequest.newBuilder(URI.create("https://api.github.com$path"))
            .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer $token")
            .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28")
            .header("User-Agent","DevOrchestrator-Kotlin-Publisher")
            .apply { if (body!=null) header("Content-Type","application/json") }
            .method(method,if (body==null) HttpRequest.BodyPublishers.noBody() else {
                require(body.length<=65536)
                HttpRequest.BodyPublishers.ofString(body)
            }).build()
        val response = sendPublicationRequest(client,request)
        return PublicationResponse(response.statusCode(),response.body().toString(Charsets.UTF_8))
    }
}

/** JDK21 request timeout ends at headers. Bound full response completion and cancel I/O. */
internal fun sendPublicationRequest(client: HttpClient,request: HttpRequest,timeoutMillis: Long = 20000): HttpResponse<ByteArray> {
    require(timeoutMillis in 1..20000)
    val pending=client.sendAsync(request) { LimitedPublicationBody() }
    try { return pending.get(timeoutMillis,TimeUnit.MILLISECONDS) }
    catch (e: Exception) {
        pending.cancel(true) // Best effort only: a timed-out POST remains an uncertain effect.
        if (e is InterruptedException) Thread.currentThread().interrupt()
        if (e is TimeoutException) throw HttpTimeoutException("Publication HTTP deadline exceeded")
        throw e
    }
}

/** Bound bytes while receiving, independently of the full-response deadline above. */
internal class LimitedPublicationBody(private val limit: Int = 1_000_000) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private lateinit var subscription: Flow.Subscription
    override fun getBody(): CompletionStage<ByteArray> = result
    override fun onSubscribe(s: Flow.Subscription) { subscription=s; s.request(1) }
    override fun onNext(items: List<ByteBuffer>) {
        if (items.sumOf { it.remaining().toLong() }+bytes.size()>limit) {
            subscription.cancel(); result.completeExceptionally(IllegalStateException("Publication response too large")); return
        }
        for (item in items) { val block=ByteArray(item.remaining()); item.get(block); bytes.write(block) }
        subscription.request(1)
    }
    override fun onError(t: Throwable) { result.completeExceptionally(t) }
    override fun onComplete() { result.complete(bytes.toByteArray()) }
}

internal class GitHubPublicationApi(private val http: PublicationHttp) {
    private val mapper = ObjectMapper()
    private val root = SMOKE_TARGET.apiRoot
    private fun get(path: String): JsonNode {
        val r=http.request("GET",path,null); check(r.status==200); return mapper.readTree(r.body)
    }
    fun checkBase(plan: PublicationPlan) {
        val repo=get(root)
        check(repo.path("id").asLong()==plan.repositoryId && repo.path("full_name").asText()==SMOKE_TARGET.fullName &&
            repo.path("default_branch").asText()==plan.baseBranch && !repo.path("archived").asBoolean())
        check(ref(plan.baseBranch)==plan.baseSha)
    }
    fun ref(branch: String): String? {
        val r=http.request("GET","$root/git/ref/heads/${encode(branch)}",null)
        if (r.status==404) return null
        check(r.status==200)
        val n=mapper.readTree(r.body)
        check(n.path("ref").asText()=="refs/heads/$branch" && n.path("object").path("type").asText()=="commit")
        return n.path("object").path("sha").asText().also { check(it.matches(Regex("[0-9a-f]{40}"))) }
    }
    fun commit(plan: PublicationPlan): PublicationReadBack.Commit {
        val n=get("$root/git/commits/${plan.commitSha}")
        return PublicationReadBack.Commit(n.path("sha").asText(),n.path("tree").path("sha").asText(),
            n.path("parents").map { it.path("sha").asText() })
    }
    /** state=all and no base filter: a closed/wrong-base PR must prevent a second PR. */
    fun drafts(plan: PublicationPlan): PublicationReadBack.Drafts {
        val n=get("$root/pulls?state=all&head=${encode(plan.owner+":"+plan.headBranch)}&per_page=100&page=1")
        check(n.isArray && n.size()<100)
        return PublicationReadBack.Drafts(n.map { decodePr(it,plan) })
    }
    fun createDraft(plan: PublicationPlan): Int {
        val body=mapper.writeValueAsString(mapOf("title" to "Implement Issue #${plan.issueNumber}",
            "head" to plan.headBranch,"base" to plan.baseBranch,"draft" to true,
            "maintainer_can_modify" to false,"body" to "Implements #${plan.issueNumber}.\n\n"+
                "DevOrchestrator workflow ${plan.workflowId}; candidate ${plan.candidateId}.\n"+
                "Tree ${plan.commitTree}; manifest ${plan.candidateManifestSha256}.\n"+
                "Saved controller tests PASS; independent Review PASS. Merge is Owner-only."))
        val r=http.request("POST","$root/pulls",body)
        check(r.status==201)
        val pr=decodePr(mapper.readTree(r.body),plan)
        check(PublicationReceiptMatcher.matches(plan,EffectKind.DRAFT_PR,PublicationReadBack.Drafts(listOf(pr))))
        return pr.number
    }
    fun singleDraft(number: Int, plan: PublicationPlan): DraftPullRequest = decodePr(get("$root/pulls/$number"),plan)
    private fun decodePr(n: JsonNode, plan: PublicationPlan): DraftPullRequest {
        check(n.path("state").asText()=="open" && n.path("merged_at").isNull)
        val base=n.path("base"); val head=n.path("head"); val repo=base.path("repo")
        check(head.path("repo").path("full_name").asText()==SMOKE_TARGET.fullName)
        return DraftPullRequest(n.path("number").asInt(),n.path("html_url").asText(),repo.path("id").asLong(),
            repo.path("full_name").asText(),head.path("repo").path("id").asLong(),head.path("ref").asText(),
            head.path("sha").asText(),base.path("ref").asText(),base.path("sha").asText(),
            n.path("draft").isBoolean && n.path("draft").asBoolean()).also {
            check(it.headBranch==plan.headBranch) // Do not trust list filters alone.
        }
    }
    private fun encode(s: String)=URLEncoder.encode(s,Charsets.UTF_8).replace("+","%20")
}

internal fun interface PublicationPush {
    fun send(plan: PublicationPlan, staging: Path, artifacts: Path): ProcessObservation
}

/** Fixed HTTPS target; no remote/helper/SSH/user auth configuration from the saved checkout. */
internal class GitHubBranchPush(private val token: String,
    private val git: Path = Path.of("/usr/bin/git"), private val python: Path = Path.of("/usr/bin/python3")) : PublicationPush {
    init { require(token.isNotBlank() && token.none { it.isISOControl() }) }
    override fun send(plan: PublicationPlan, staging: Path, artifacts: Path): ProcessObservation {
        val askpass=artifacts.resolve("askpass.sh")
        Files.writeString(askpass,"""#!/bin/sh
case "${'$'}1" in
  *Username*) printf '%s\n' x-access-token ;;
  *Password*) printf '%s\n' "${'$'}DEVORCH_PUBLISH_TOKEN" ;;
  *) exit 1 ;;
esac
""",CREATE_NEW)
        check(askpass.toFile().setExecutable(true,true))
        val env=mapOf("HOME" to artifacts.toString(),"PATH" to "/usr/bin:/bin","LANG" to "C",
            "GIT_CONFIG_NOSYSTEM" to "1","GIT_CONFIG_GLOBAL" to "/dev/null","GIT_TERMINAL_PROMPT" to "0",
            "GIT_ASKPASS" to askpass.toString())
        return NativeProcessRunner(python).run(arguments(plan,staging),staging,env,artifacts,byteArrayOf(),120000,
            privateEnvironment=mapOf("DEVORCH_PUBLISH_TOKEN" to token)) { true }
    }
    internal fun arguments(plan: PublicationPlan, staging: Path): List<String> = listOf(git.toRealPath().toString(),
        "--git-dir=$staging","-c","credential.helper=","-c","http.followRedirects=false","-c","core.hooksPath=/dev/null",
        "push","--porcelain","--no-verify",
        // Empty lease never updates an existing ref. Git may report an identical
        // ref as up-to-date: Publisher additionally requires '*' new-branch evidence.
        "--force-with-lease=refs/heads/${plan.headBranch}:",
        "https://github.com/${plan.owner}/${plan.repository}.git","${plan.commitSha}:refs/heads/${plan.headBranch}")
}
