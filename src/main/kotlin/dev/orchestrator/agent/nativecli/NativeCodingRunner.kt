package dev.orchestrator.agent.nativecli

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.agent.AgentRole
import dev.orchestrator.agent.SqliteAgentCatalog
import dev.orchestrator.storage.AttemptState
import dev.orchestrator.storage.CodingAttemptJournal
import dev.orchestrator.storage.SqliteTaskStore
import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.WorkflowHold
import dev.orchestrator.storage.WorkflowPhase
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant

internal data class CodingRunResult(
    val state: AttemptState?, // null: blocked before consuming a launch intent
    val reason: String,
    val artifacts: Path? = null,
    val candidateTree: String? = null,
)

/**
 * Explicit trusted call only. Not connected to startup/HTTP/polling. A caller must have
 * the separate Owner GO for real inference in the isolated HOME context (#31/#35).
 * Tests substitute a fake CLI. No permission framework, publisher or automatic retry.
 */
internal class NativeCodingRunner(
    database: Path,
    private val repositoryId: Long,
    private val codex: Path,
    private val home: Path,
    private val artifactRoot: Path,
    private val git: Path = Path.of("/usr/bin/git"),
    private val python: Path = Path.of("/usr/bin/python3"),
    private val timeout: Duration = Duration.ofSeconds(120),
    private val outputLimit: Int = 1_048_576,
    private val proxyPort: Int = 10809,
    private val ownerGo: () -> Boolean = { true },
) {
    private val databasePath=database.toAbsolutePath().normalize()
    private val store = SqliteTaskStore(database)
    private val journal = CodingAttemptJournal(database)
    private val catalog = SqliteAgentCatalog(database)
    private val mapper = ObjectMapper()
    private val gitReader = BoundedNativeProbeReader(maxOutputBytes=1_000_000)

    init {
        require(repositoryId>0 && timeout.toMillis() in 50..120_000)
        require(outputLimit in 1024..1_048_576 && proxyPort in 1..65535)
    }

    fun run(attemptId: String): CodingRunResult {
        val intent = journal.find(attemptId) ?: return CodingRunResult(null,"NO_INTENT")
        if (intent.repositoryId != repositoryId || intent.state != AttemptState.PLANNED)
            return CodingRunResult(null,"WRONG_SCOPE_OR_ALREADY_CONSUMED")
        val policy = store.findPolicy(repositoryId) ?: return CodingRunResult(null,"NO_POLICY")
        val workflow = store.findWorkflow(repositoryId,intent.issueNumber) ?: return CodingRunResult(null,"NO_WORKFLOW")
        if (policy.kind!=ProjectKind.PERSONAL || !policy.personalAutoExecution || policy.version!=intent.policyVersion ||
            workflow.hold!=WorkflowHold.NONE || workflow.phase!=WorkflowPhase.CODING)
            return CodingRunResult(null,"POLICY_OR_WORKFLOW_DENIED")
        val assigned = catalog.findAssignedProfile(repositoryId,AgentRole.CODING)
            ?: return CodingRunResult(null,"NO_PROFILE")
        val issue = store.findPinnedGitHubIssue(repositoryId,intent.issueNumber)
            ?: return CodingRunResult(null,"NO_PINNED_ISSUE")
        if (assigned.profile.profileId != intent.profileId || assigned.profile.version != intent.profileVersion ||
            issue.workflowId != intent.workflowId || issue.bodySha256 != intent.issueBodySha256 ||
            issue.baseSha != intent.baseSha) return CodingRunResult(null,"INPUT_CHANGED")
        try {
            LinuxIsolation.requireModelHome(home,databasePath,artifactRoot,intent.workspace)
            LinuxIsolation.requireAvailable()
            canonicalDirectory(home)
            canonicalDirectory(home.resolve(".codex"))
            canonicalDirectory(artifactRoot)
            canonicalDirectory(policy.workspaceRoot)
            canonicalDirectory(intent.workspace)
            require(intent.workspace.startsWith(policy.workspaceRoot) && intent.workspace!=policy.workspaceRoot)
            require(artifactRoot.startsWith(policy.workspaceRoot))
            require(!artifactRoot.startsWith(intent.workspace) && !intent.workspace.startsWith(artifactRoot))
            listOf(codex,git,python).forEach {
                require(it.isAbsolute && it.normalize()==it && Files.isRegularFile(it.toRealPath()) && Files.isExecutable(it))
            }
            if (!sourceMatches(intent.workspace,intent.baseSha,intent.generation==0))
                return CodingRunResult(null,"SOURCE_MISMATCH")
        } catch (_: GitOutcomeUnknown) {
            return preflightUnknown(attemptId)
        } catch (_: Exception) { return CodingRunResult(null,"INVALID_LOCAL_INPUT") }
        val artifacts = try { Files.createDirectory(artifactRoot.resolve(attemptId),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } catch (_: Exception) { return CodingRunResult(null,"ARTIFACT_DIRECTORY_EXISTS_OR_INVALID") }
        // Status can trust forged stat data in a caller's index. Independently hash
        // current files via a fresh index before any Coding spawn, then compare the
        // actual tree to the pinned input. Never attribute old dirt to the agent.
        try {
            if (stageTree(intent.workspace,intent.baseSha,artifacts.resolve("baseline.index"))!=intent.sourceTree)
                return CodingRunResult(null,"WORKTREE_CONTENT_CHANGED",artifacts)
        } catch (_: GitOutcomeUnknown) { return preflightUnknown(attemptId,artifacts) }
        catch (_: Exception) { return CodingRunResult(null,"BASELINE_CAPTURE_FAILED",artifacts) }
        val output = Files.createDirectory(artifacts.resolve("output"))
        val lastMessage = output.resolve("last-message.txt")
        val command = CodexExecCommandPlanner.plan(codex.toRealPath(),intent.workspace,lastMessage,assigned)
        val proxy = "http://127.0.0.1:$proxyPort"
        val childEnv = linkedMapOf("HOME" to home.toString(),"CODEX_HOME" to home.resolve(".codex").toString(),
            "PATH" to listOf(codex.parent.toString(),codex.toRealPath().parent.toString(),"/usr/bin","/bin")
                .distinct().joinToString(":"),"LANG" to "C.UTF-8",
            "NODE_USE_ENV_PROXY" to "1","NO_PROXY" to "localhost,127.0.0.1,::1",
            "no_proxy" to "localhost,127.0.0.1,::1")
        for (name in listOf("HTTP_PROXY","HTTPS_PROXY","ALL_PROXY","http_proxy","https_proxy","all_proxy"))
            childEnv[name] = proxy
        val limitMs = minOf(timeout.toMillis(),assigned.profile.timeoutSeconds*1000L)
        val feedback = store.listEvents(intent.workflowId).lastOrNull { it.type=="CORRECTION_REQUESTED" }?.detail.orEmpty()
        val prompt = ("Implement only the pinned Issue below in this disposable PERSONAL checkout. " +
            "Do not publish, access credentials or change Git metadata. Run its offline tests.\n" +
            "Issue #${intent.issueNumber}: ${issue.title}\n${issue.body}\nCorrection feedback: $feedback\n").toByteArray(Charsets.UTF_8)
        if (!ownerGo() || !journal.markStarting(attemptId)) return CodingRunResult(null,"START_GUARD_DENIED",artifacts)

        fun complete(state: AttemptState, reason: String, evidence: Any? = null, tree: String? = null): CodingRunResult {
            val receipt = mapper.writeValueAsString(mapOf("attempt_id" to attemptId,"reason" to reason,
                "artifacts" to artifacts.toString(),"candidate_tree" to tree,"observed" to evidence,
                "profile_id" to intent.profileId,"profile_version" to intent.profileVersion,
                "base_sha" to intent.baseSha,"input_tree" to intent.sourceTree,"issue_body_sha256" to intent.issueBodySha256,
                "assignment_version" to intent.assignmentVersion,"manifest_version" to intent.manifestVersion,
                "model" to assigned.profile.model,"effort" to assigned.profile.effort.name,
                "timeout_ms" to limitMs,"output_limit_bytes" to outputLimit,
                "estimated_budget_micro_usd" to assigned.profile.maxBudgetMicroUsd,"monetary_cap_enforced" to false,
                "tests" to "NOT_RUN_BY_CONTROLLER","recorded_at" to Instant.now().toString()))
            val written = journal.completeObserved(attemptId,state,receipt)
            return CodingRunResult(if (written) state else AttemptState.UNKNOWN,
                if (written) reason else "JOURNAL_STATE_CHANGED",artifacts,tree)
        }
        val observation = NativeProcessRunner(python,outputLimit).run(
            LinuxIsolation.prefix(intent.workspace,true,home,codex,output)+command.argumentVector,intent.workspace,
            childEnv,artifacts,prompt,limitMs,lastMessage) { birth ->
            ownerGo() && journal.markRunning(attemptId,mapper.writeValueAsString(mapOf("attempt_id" to attemptId,
                "identity" to birth,"cli_binary" to codex.toRealPath().toString(),
                "profile_id" to intent.profileId,"profile_version" to intent.profileVersion)))
        }
        val observed = observation.evidence
        if (observation.state != AttemptState.SUCCEEDED)
            return complete(observation.state,observation.reason,observed)
        try {
            boundedFile(lastMessage,outputLimit)
            if (!sourceMatches(intent.workspace,intent.baseSha,false))
                return complete(AttemptState.UNKNOWN,"POST_RUN_SOURCE_IDENTITY_CHANGED",observed)
            val index = artifacts.resolve("candidate.index")
            fun indexed(vararg args: String): String = gitCommand(intent.workspace,args.toList(),index)
            val tree = stageTree(intent.workspace,intent.baseSha,index)
            if (tree==intent.sourceTree) return complete(AttemptState.FAILED,"NO_CANDIDATE_DIFF",observed)
            val diff = indexed("diff","--cached","--binary","--no-ext-diff","--no-textconv",intent.baseSha)
            check(diff.isNotBlank())
            Files.writeString(artifacts.resolve("candidate.diff"),diff,CREATE_NEW,WRITE)
            return complete(AttemptState.SUCCEEDED,"CANDIDATE_CAPTURED_TESTS_NOT_VERIFIED",observed,tree)
        } catch (_: Exception) {
            return complete(AttemptState.UNKNOWN,"RUNNER_EVIDENCE_INCOMPLETE")
        }
    }

    private fun sourceMatches(workspace: Path, base: String, requireClean: Boolean): Boolean {
        if (!TrustedGitCheckoutGuard.accepts(workspace)) return false
        if (gitCommand(workspace,listOf("rev-parse","--verify","HEAD")).trim()!=base) return false
        return !requireClean || gitCommand(workspace,listOf("status","--porcelain=v1","--untracked-files=all")).isBlank()
    }

    private fun stageTree(workspace: Path, base: String, index: Path): String {
        gitCommand(workspace,listOf("read-tree",base),index)
        gitCommand(workspace,listOf("add","--all","--","."),index)
        return gitCommand(workspace,listOf("write-tree"),index).trim().also {
            check(it.matches(Regex("[0-9a-f]{40}")))
        }
    }

    private fun preflightUnknown(attemptId: String, artifacts: Path? = null): CodingRunResult {
        journal.completeObserved(attemptId,AttemptState.UNKNOWN,
            mapper.writeValueAsString(mapOf("attempt_id" to attemptId,"reason" to "GIT_PREFLIGHT_UNKNOWN")))
        return CodingRunResult(AttemptState.UNKNOWN,"GIT_PREFLIGHT_UNKNOWN",artifacts)
    }

    private fun gitCommand(workspace: Path, suffix: List<String>, index: Path? = null): String {
        val result = gitReader.read(listOf(git.toRealPath().toString(),"--no-optional-locks",
            "--git-dir=${workspace.resolve(".git")}","--work-tree=$workspace",
            "-c","core.fsmonitor=false","-c","core.hooksPath=/dev/null")+suffix,workspace,gitIndex=index)
        if (result !is ProbeProcessResult.Exited) throw GitOutcomeUnknown()
        check(result.exitCode==0) { "Git command failed" }
        return result.output
    }

    private fun canonicalDirectory(path: Path) {
        require(path.isAbsolute && path.normalize()==path && path.toRealPath()==path && Files.isDirectory(path,NOFOLLOW_LINKS))
    }

    private fun boundedFile(path: Path, limit: Int): ByteArray {
        require(Files.isRegularFile(path,NOFOLLOW_LINKS) &&
            (Files.getAttribute(path,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt()==1 && Files.size(path)<=limit)
        return Files.newInputStream(path,NOFOLLOW_LINKS).use { it.readNBytes(limit+1) }.also { check(it.size<=limit) }
    }

    private class GitOutcomeUnknown : Exception()
}
