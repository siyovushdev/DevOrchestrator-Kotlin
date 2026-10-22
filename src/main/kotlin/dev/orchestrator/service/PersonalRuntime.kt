package dev.orchestrator.service

import dev.orchestrator.agent.*
import dev.orchestrator.agent.nativecli.*
import dev.orchestrator.publish.*
import dev.orchestrator.storage.*
import dev.orchestrator.workflow.*
import dev.orchestrator.remote.*
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal data class TaskProgress(val stage: String, val url: String? = null,val reason: String? = null)
internal interface TaskRuntime {
    fun prepare(issue: GitHubIssueRecord)
    fun recover(issue: GitHubIssueRecord)
    fun step(issue: GitHubIssueRecord): TaskProgress
    fun cancel(issue: GitHubIssueRecord): TaskProgress = TaskProgress("cancelled", reason="NOT_STARTED")
    fun summary(issue: GitHubIssueRecord): String = ""
    fun checkRemote(issue: GitHubIssueRecord,id: String): String = error("REMOTE_DISABLED")
    fun resumeRemote(issue: GitHubIssueRecord,id: String,digest: String): TaskProgress? = null
}

/** Reuses production journals, supervised Coding, controller tests, fresh Review and guarded Publisher. */
internal class PersonalRuntime(private val config: PersonalConfig, private val token: String,
    private val permitted: (GitHubIssueRecord) -> Boolean,
    private val publicationHttp: PublicationHttp = GitHubPublicationHttp(token),
    private val publicationPush: PublicationPush = GitHubBranchPush(token),
    private val fetch: (GitHubIssueRecord,Path,Path) -> Unit = { i,c,a -> fetchPersonalCheckout(token,i,c,a,permitted) },
    private val remoteOverride: RemoteExecutionSettings? = null,
    private val fixtureCli: Boolean = false,
) : TaskRuntime {
    private val remote by lazy { if(config.runtimeMode=="ssh-vm") remoteOverride ?: requireNotNull(config.ssh).let { RemoteExecutionSettings(it.peer,SshWorkerTransport(it)) } else null }
    private val repo=SMOKE_TARGET.repositoryId
    private val mapper=ObjectMapper()
    private fun root(i: GitHubIssueRecord)=config.root.resolve("task-${i.githubIssueId}")
    private fun database(i: GitHubIssueRecord)=root(i).resolve("run.db")
    private fun serviceDb()=config.root.resolve("service.db")
    private fun limits(i: GitHubIssueRecord)=readExecutionLimits(serviceDb(), i.issueNumber)
    private fun engine(i: GitHubIssueRecord): WorkflowEngine {
        val phase=if (Files.isRegularFile(database(i))) SqliteTaskStore(database(i)).findWorkflow(repo,i.issueNumber)?.phase else null
        val seconds=if (phase==WorkflowPhase.REVIEWING) limits(i).reviewTimeoutSeconds else limits(i).codingTimeoutSeconds
        return WorkflowEngine(database(i),repo,config.codex,config.home,
            root(i).resolve("artifacts"),root(i).resolve("snapshots"),config.minimumTests,2,
            Duration.ofSeconds(seconds.toLong()),proxyPort=config.proxyPort,ownerGo={ permitted(i) },remote=remote,
            ownerCorrectionCeiling={ limits(i).maxCorrections },
            stopRequested={ ownerCancelRequested(config.root, i.issueNumber) },
            fixtureCli=fixtureCli)
    }
    private fun terminalOrHeld(w: WorkflowSnapshot): TaskProgress = when (w.holdReason) {
        "STAGE_TIMEOUT" -> TaskProgress("timed_out", reason=w.holdReason)
        "CODEX_NOT_ATTESTED", "CODEX_CANARY_FAILED" -> TaskProgress("unattested", reason=w.holdReason)
        else -> TaskProgress("held", reason=w.holdReason)
    }
    private fun requireSite(i: GitHubIssueRecord) {
        val site=RemoteWorkflowBridge.site(database(i),"issue-${i.githubIssueId}")
        check(if(remote==null) site==null else site==remote!!.peer) { "EXECUTION_SITE_CHANGED" }
    }
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
                StageBudget.MAX_SECONDS,100_000),null,"owner:personal-service")
            catalog.bindRole(repo,role,id,1,null,"owner:personal-service")
        }
        if(remote==null) for (role in listOf(AgentRole.CODING,AgentRole.REVIEW)) {
            check(NativeCodexCliPreflight(store,catalog).inspect(CodexNativeProbeRequest(repo,role,checkout,
                issue.baseSha,config.codex,Path.of("/usr/bin/git"))) is CodexProbeResult.FlagsOnly)
        }
        remote?.let { RemoteWorkflowBridge.pin(database(issue),"issue-${issue.githubIssueId}",it.peer) }
        check(CodingAttemptJournal(database(issue)).plan(repo,issue.issueNumber,0,"issue-${issue.githubIssueId}-coding-0",
            checkout,git("rev-parse","HEAD^{tree}"))!=null)
    }
    override fun recover(issue: GitHubIssueRecord) {
        check(Files.isRegularFile(database(issue)))
        requireSite(issue)
        engine(issue).recover(issue.issueNumber)
        publisher(issue).recover()
    }
    override fun cancel(issue: GitHubIssueRecord): TaskProgress {
        val dbFile=database(issue)
        if (Files.isRegularFile(dbFile)) {
            LocalProcessControl.stopOrphans(dbFile)
            if (config.ssh!=null) LocalProcessControl.cancelGuests(dbFile, config.ssh)
        }
        if (!Files.isRegularFile(dbFile)) return TaskProgress("cancelled", reason="NOT_STARTED")
        val store=SqliteTaskStore(dbFile)
        val w=store.findWorkflow(repo,issue.issueNumber) ?: return TaskProgress("cancelled", reason="NOT_STARTED")
        if (w.phase==WorkflowPhase.CANCELLED) return TaskProgress("cancelled", reason="OWNER_CANCELLED")
        if (w.phase==WorkflowPhase.DRAFT_PR_CREATED) return TaskProgress("done", publisher(issue).publish().url, "ALREADY_PUBLISHED")
        if (w.hold!=WorkflowHold.NONE) return TaskProgress("held", reason=w.holdReason ?: "CANCEL_UNKNOWN")
        val taskDb=RuntimeDatabase(dbFile)
        val blocked=w.phase==WorkflowPhase.PUBLISHING || taskDb.read { c ->
            c.rows("SELECT 1 FROM effects_outbox WHERE workflow_id=? AND state IN ('SENDING','UNCERTAIN')",w.id) { 1 }.isNotEmpty() ||
                c.rows("SELECT 1 FROM agent_attempts WHERE workflow_id=? AND state IN ('STARTING','RUNNING')",w.id) { 1 }.isNotEmpty() ||
                c.rows("SELECT 1 FROM workflow_verifications WHERE workflow_id=? AND state='STARTING'",w.id) { 1 }.isNotEmpty()
        }
        if (blocked) return TaskProgress("held", reason="EFFECT_OR_PROCESS_UNCONFIRMED")
        val changed=taskDb.write { c ->
            val planned=c.rows("SELECT attempt_id FROM agent_attempts WHERE workflow_id=? AND state='PLANNED'",w.id) { it.getString(1) }
            for (id in planned) {
                if (c.mutate("UPDATE agent_attempts SET state='CANCELLED' WHERE attempt_id=? AND state='PLANNED'",id)==1)
                    c.mutate("DELETE FROM runtime_reservations WHERE attempt_id=?",id)
            }
            c.mutate("""UPDATE workflows SET phase='CANCELLED',hold='NONE',hold_reason='OWNER_CANCELLED',version=version+1,updated_at=?
                WHERE workflow_id=? AND version=? AND hold='NONE' AND phase NOT IN ('DRAFT_PR_CREATED','PUBLISHING','CANCELLED')""",
                java.time.Instant.now().toString(),w.id,w.version)==1
        }
        return if (changed) TaskProgress("cancelled", reason="OWNER_CANCELLED") else TaskProgress("held", reason="CANCEL_UNKNOWN")
    }
    override fun summary(issue: GitHubIssueRecord): String = try {
        val dbFile=database(issue)
        if (!Files.isRegularFile(dbFile)) "" else {
            val w=SqliteTaskStore(dbFile).findWorkflow(repo,issue.issueNumber)
            if (w==null) "" else {
                val taskDb=RuntimeDatabase(dbFile)
                val evidence=taskDb.read { c -> c.rows("""SELECT evidence FROM workflow_verifications
                    WHERE workflow_id=? AND generation=? AND evidence IS NOT NULL""",w.id,w.correctionsUsed) { it.getString(1) }.singleOrNull() }
                val node=evidence?.let { mapper.readTree(it) }
                val review=taskDb.read { c -> c.rows("""SELECT detail FROM workflow_events WHERE workflow_id=? AND event_type='REVIEW_COMPLETED'
                    ORDER BY event_id DESC LIMIT 1""",w.id) { it.getString(1) }.singleOrNull() }?.let { mapper.readTree(it) }
                formatOwnerReport(node?.path("testsRun")?.takeIf { !it.isMissingNode }?.asInt(),
                    node?.path("failures")?.takeIf { !it.isMissingNode }?.asInt(),
                    node?.path("errors")?.takeIf { !it.isMissingNode }?.asInt(),
                    review?.path("receipt")?.path("verdict")?.asText()?.takeIf { it.isNotBlank() },
                    review?.path("findings")?.mapNotNull { it.takeIf { n -> n.isTextual }?.asText() } ?: emptyList())
            }
        }
    } catch (_: Exception) { "Отчёт: сохранённые итоги не прочитаны. Сырые логи не публикуются." }
    override fun step(issue: GitHubIssueRecord): TaskProgress {
        check(permitted(issue))
        if (ownerCancelRequested(config.root, issue.issueNumber)) return cancel(issue)
        requireSite(issue)
        val store=SqliteTaskStore(database(issue))
        var w=store.findWorkflow(repo,issue.issueNumber)!!
        if (w.hold!=WorkflowHold.NONE) return terminalOrHeld(w)
        if (w.phase==WorkflowPhase.CANCELLED) return TaskProgress("cancelled", reason="OWNER_CANCELLED")
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
        if (w.phase==WorkflowPhase.CANCELLED) return TaskProgress("cancelled", reason="OWNER_CANCELLED")
        if (w.hold!=WorkflowHold.NONE) return terminalOrHeld(w)
        if (w.version==before) return TaskProgress("held",reason=w.holdReason)
        return TaskProgress(when(w.phase) {
            WorkflowPhase.VERIFYING -> "tests"
            WorkflowPhase.REVIEWING,WorkflowPhase.READY_FOR_PR -> "review"
            else -> "coding"
        })
    }
    override fun checkRemote(issue: GitHubIssueRecord,id: String): String = engine(issue).checkRemote(id)
    override fun resumeRemote(issue: GitHubIssueRecord,id: String,digest: String): TaskProgress? {
        requireSite(issue)
        if(!permitted(issue) || !engine(issue).resumeRemote(id,digest)) return null
        val w=SqliteTaskStore(database(issue)).findWorkflow(repo,issue.issueNumber)!!
        return TaskProgress(if(w.hold!=WorkflowHold.NONE) "held" else when(w.phase) {
            WorkflowPhase.VERIFYING -> "tests"
            WorkflowPhase.REVIEWING,WorkflowPhase.READY_FOR_PR -> "review"
            else -> "coding"
        },reason=w.holdReason)
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
