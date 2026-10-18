package dev.orchestrator.service

import dev.orchestrator.agent.*
import dev.orchestrator.agent.nativecli.*
import dev.orchestrator.publish.*
import dev.orchestrator.storage.*
import dev.orchestrator.workflow.WorkflowEngine
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal data class TaskProgress(val stage: String, val url: String? = null)
internal interface TaskRuntime {
    fun prepare(issue: GitHubIssueRecord)
    fun recover(issue: GitHubIssueRecord)
    fun step(issue: GitHubIssueRecord): TaskProgress
}

/** Reuses production journals, supervised Coding, controller tests, fresh Review and guarded Publisher. */
internal class PersonalRuntime(private val config: PersonalConfig, private val token: String,
    private val permitted: (GitHubIssueRecord) -> Boolean,
    private val publicationHttp: PublicationHttp = GitHubPublicationHttp(token),
    private val publicationPush: PublicationPush = GitHubBranchPush(token),
    private val fetch: (GitHubIssueRecord,Path,Path) -> Unit = { i,c,a -> fetchPersonalCheckout(token,i,c,a,permitted) },
) : TaskRuntime {
    private val repo=SMOKE_TARGET.repositoryId
    private fun root(i: GitHubIssueRecord)=config.root.resolve("task-${i.githubIssueId}")
    private fun database(i: GitHubIssueRecord)=root(i).resolve("run.db")
    private fun engine(i: GitHubIssueRecord)=WorkflowEngine(database(i),repo,config.codex,config.home,
        root(i).resolve("artifacts"),root(i).resolve("snapshots"),config.minimumTests,2,
        Duration.ofSeconds(120),proxyPort=config.proxyPort,ownerGo={ permitted(i) })
    private fun publisher(i: GitHubIssueRecord)=DraftPrPublisher(database(i),root(i).resolve("publisher"),i.baseSha,
        GitHubPublicationApi(publicationHttp),publicationPush,{ plan,_ ->
            permitted(i) && plan.repositoryId==repo && plan.issueNumber==i.issueNumber
        },issueNumber=i.issueNumber)
    override fun prepare(issue: GitHubIssueRecord) {
        check(permitted(issue))
        val dir=Files.createDirectory(root(issue))
        val checkout=Files.createDirectory(dir.resolve("checkout"))
        listOf("artifacts","snapshots","publisher","empty-template").forEach { Files.createDirectory(dir.resolve(it)) }
        // Empty local repository + fixed HTTPS fetch. No inherited credentials, helpers, hooks or templates.
        fun git(vararg args: String): String {
            val r=BoundedNativeProbeReader(timeout=Duration.ofSeconds(30)).read(listOf("/usr/bin/git")+args,checkout)
            check(r is ProbeProcessResult.Exited && r.exitCode==0)
            return r.output.trim()
        }
        git("init","--template=${dir.resolve("empty-template")}","--initial-branch=main")
        check(permitted(issue))
        val fetchArtifacts=Files.createDirectory(dir.resolve("fetch"))
        fetch(issue,checkout,fetchArtifacts)
        // Inspect the tree before materializing files: reject submodules and symlinks.
        val inventory=git("ls-tree","-r",issue.baseSha)
        check(inventory.lineSequence().filter { it.isNotBlank() }.all { it.startsWith("100644 blob ") || it.startsWith("100755 blob ") })
        git("-c","core.hooksPath=/dev/null","checkout","--detach",issue.baseSha)
        check(TrustedGitCheckoutGuard.accepts(checkout))
        val store=SqliteTaskStore(database(issue))
        store.insertProject(RegisteredProject("personal",repo,ProjectKind.PERSONAL,dir.toString()),"owner:personal-service")
        val policy=store.findPolicy(repo)!!
        check(permitted(issue))
        check(store.updatePolicyFromTrustedOwner(policy.copy(version=2,personalAutoExecution=true),1,
            "owner:personal-service","Local PERSONAL configuration; fixed repository only"))
        store.acceptGitHubIssue(issue,"issue-${issue.githubIssueId}")
        val catalog=SqliteAgentCatalog(database(issue))
        catalog.declareManifest(AgentCapabilityManifest("codex-cli","openai",1,setOf(AgentRole.CODING,AgentRole.REVIEW),
            setOf(AgentSandbox.NATIVE_PERSONAL_WRITE,AgentSandbox.NATIVE_PERSONAL_READ_ONLY),
            mapOf(config.model to setOf(ReasoningEffort.DEFAULT))),null,"owner:personal-service")
        for (role in listOf(AgentRole.CODING,AgentRole.REVIEW)) {
            val id=role.name.lowercase()
            catalog.saveProfile(AgentProfile(id,1,repo,role,"codex-cli",1,"openai",config.model,ReasoningEffort.DEFAULT,
                if (role==AgentRole.CODING) AgentSandbox.NATIVE_PERSONAL_WRITE else AgentSandbox.NATIVE_PERSONAL_READ_ONLY,
                120,100_000),null,"owner:personal-service")
            catalog.bindRole(repo,role,id,1,null,"owner:personal-service")
        }
        for (role in listOf(AgentRole.CODING,AgentRole.REVIEW)) {
            check(NativeCodexCliPreflight(store,catalog).inspect(CodexNativeProbeRequest(repo,role,checkout,
                issue.baseSha,config.codex,Path.of("/usr/bin/git"))) is CodexProbeResult.FlagsOnly)
        }
        check(CodingAttemptJournal(database(issue)).plan(repo,issue.issueNumber,0,"issue-${issue.githubIssueId}-coding-0",
            checkout,git("rev-parse","HEAD^{tree}"))!=null)
    }
    override fun recover(issue: GitHubIssueRecord) {
        check(Files.isRegularFile(database(issue)))
        engine(issue).recover(issue.issueNumber)
        publisher(issue).recover()
    }
    override fun step(issue: GitHubIssueRecord): TaskProgress {
        check(permitted(issue))
        val store=SqliteTaskStore(database(issue))
        var w=store.findWorkflow(repo,issue.issueNumber)!!
        if (w.hold!=WorkflowHold.NONE) return TaskProgress("held")
        if (w.phase==WorkflowPhase.DRAFT_PR_CREATED) return TaskProgress("done",publisher(issue).publish().url)
        if (w.phase in setOf(WorkflowPhase.READY_FOR_PR,WorkflowPhase.PUBLISHING)) {
            val result=publisher(issue).publish()
            return if (result.status in setOf("DRAFT_PR_CREATED","ALREADY_CONFIRMED")) TaskProgress("done",result.url)
                else TaskProgress("held")
        }
        val before=w.version
        check(permitted(issue))
        engine(issue).step(issue.issueNumber)
        w=store.findWorkflow(repo,issue.issueNumber)!!
        if (w.hold!=WorkflowHold.NONE || w.version==before) return TaskProgress("held")
        return TaskProgress(when(w.phase) {
            WorkflowPhase.VERIFYING -> "tests"
            WorkflowPhase.REVIEWING,WorkflowPhase.READY_FOR_PR -> "review"
            else -> "coding"
        })
    }
}

/** Supervised fixed-target fetch; secret is passed privately and is absent from saved launch JSON. */
internal fun fetchPersonalCheckout(token: String,issue: GitHubIssueRecord,checkout: Path,fetchArtifacts: Path,
    permitted: (GitHubIssueRecord) -> Boolean, git: Path = Path.of("/usr/bin/git")) {
    check(permitted(issue))
    val askpass=fetchArtifacts.resolve("askpass.sh")
    Files.writeString(askpass,"#!/bin/sh\ncase \"${'$'}1\" in\n *Username*) echo x-access-token ;;\n *Password*) printf '%s\\n' \"${'$'}DEVORCH_PUBLISH_TOKEN\" ;;\n *) exit 1 ;;\nesac\n")
    check(askpass.toFile().setExecutable(true,true))
    val fetched=NativeProcessRunner(Path.of("/usr/bin/python3")).run(
        listOf(git.toRealPath().toString(),"-c","credential.helper=","-c","http.followRedirects=false","fetch","--no-tags",
        "--no-auto-maintenance","https://github.com/${SMOKE_TARGET.fullName}.git",issue.baseSha),checkout,
        mapOf("HOME" to fetchArtifacts.toString(),"PATH" to "/usr/bin:/bin","LANG" to "C",
        "GIT_CONFIG_NOSYSTEM" to "1","GIT_CONFIG_GLOBAL" to "/dev/null","GIT_TERMINAL_PROMPT" to "0",
        "GIT_ASKPASS" to askpass.toString()),fetchArtifacts,byteArrayOf(),120000,
        privateEnvironment=mapOf("DEVORCH_PUBLISH_TOKEN" to token)) { permitted(issue) }
    check(fetched.state==AttemptState.SUCCEEDED)

}
