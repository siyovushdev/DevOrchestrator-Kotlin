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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
) {
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
        if (intent.repositoryId != repositoryId || intent.issueNumber != 1 || intent.state != AttemptState.PLANNED)
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
            if (!sourceMatches(intent.workspace,intent.baseSha,intent.sourceTree,true))
                return CodingRunResult(null,"SOURCE_MISMATCH")
        } catch (_: GitOutcomeUnknown) {
            journal.completeObserved(attemptId,AttemptState.UNKNOWN,
                mapper.writeValueAsString(mapOf("attempt_id" to attemptId,"reason" to "GIT_PREFLIGHT_UNKNOWN")))
            return CodingRunResult(AttemptState.UNKNOWN,"GIT_PREFLIGHT_UNKNOWN")
        } catch (_: Exception) { return CodingRunResult(null,"INVALID_LOCAL_INPUT") }
        val artifacts = try { Files.createDirectory(artifactRoot.resolve(attemptId),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } catch (_: Exception) { return CodingRunResult(null,"ARTIFACT_DIRECTORY_EXISTS_OR_INVALID") }
        val lastMessage = artifacts.resolve("last-message.txt")
        val command = CodexExecCommandPlanner.plan(codex.toRealPath(),intent.workspace,lastMessage,assigned)
        val helper = artifacts.resolve("supervisor.py")
        javaClass.getResourceAsStream("/native-codex-supervisor.py")!!.use {
            Files.copy(it,helper)
        }
        val proxy = "http://127.0.0.1:$proxyPort"
        val childEnv = linkedMapOf("HOME" to home.toString(),"CODEX_HOME" to home.resolve(".codex").toString(),
            "PATH" to listOf(codex.parent.toString(),codex.toRealPath().parent.toString(),"/usr/bin","/bin")
                .distinct().joinToString(":"),"LANG" to "C.UTF-8",
            "NODE_USE_ENV_PROXY" to "1","NO_PROXY" to "localhost,127.0.0.1,::1",
            "no_proxy" to "localhost,127.0.0.1,::1")
        for (name in listOf("HTTP_PROXY","HTTPS_PROXY","ALL_PROXY","http_proxy","https_proxy","all_proxy"))
            childEnv[name] = proxy
        val config = artifacts.resolve("launch.json")
        val helperReceipt = artifacts.resolve("supervisor-receipt.json")
        val limitMs = minOf(timeout.toMillis(),assigned.profile.timeoutSeconds*1000L)
        Files.writeString(config,mapper.writeValueAsString(mapOf("argv" to command.argumentVector,
            "cwd" to intent.workspace.toString(),"env" to childEnv,"timeout_ms" to limitMs,
            "receipt" to helperReceipt.toString())),CREATE_NEW,WRITE)
        val prompt = ("Implement only the pinned Issue below in this disposable PERSONAL checkout. " +
            "Do not publish, access credentials or change Git metadata. Run its offline tests.\n" +
            "Issue #${intent.issueNumber}: ${issue.title}\n${issue.body}\n").toByteArray(Charsets.UTF_8)
        if (!journal.markStarting(attemptId)) return CodingRunResult(null,"START_GUARD_DENIED",artifacts)

        var interrupted = false
        var process: Process? = null
        fun complete(state: AttemptState, reason: String, evidence: Any? = null, tree: String? = null): CodingRunResult {
            val receipt = mapper.writeValueAsString(mapOf("attempt_id" to attemptId,"reason" to reason,
                "artifacts" to artifacts.toString(),"candidate_tree" to tree,"observed" to evidence,
                "profile_id" to intent.profileId,"profile_version" to intent.profileVersion,
                "assignment_version" to intent.assignmentVersion,"manifest_version" to intent.manifestVersion,
                "model" to assigned.profile.model,"effort" to assigned.profile.effort.name,
                "timeout_ms" to limitMs,"output_limit_bytes" to outputLimit,
                "estimated_budget_micro_usd" to assigned.profile.maxBudgetMicroUsd,"monetary_cap_enforced" to false,
                "tests" to "NOT_RUN_BY_CONTROLLER","recorded_at" to Instant.now().toString()))
            val written = journal.completeObserved(attemptId,state,receipt)
            return CodingRunResult(if (written) state else AttemptState.UNKNOWN,
                if (written) reason else "JOURNAL_STATE_CHANGED",artifacts,tree)
        }
        try {
            val builder = ProcessBuilder(python.toRealPath().toString(),"-I","-S",helper.toString(),config.toString())
                .directory(artifacts.toFile()).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment().putAll(mapOf("HOME" to artifacts.toString(),"PATH" to "/usr/bin:/bin","LANG" to "C"))
            process = builder.start()
            val proc = process
            val birth = processIdentity(proc.pid())
            if (!journal.markRunning(attemptId,mapper.writeValueAsString(mapOf("attempt_id" to attemptId,
                    "identity" to birth,"cli_binary" to codex.toRealPath().toString(),
                    "profile_id" to intent.profileId,"profile_version" to intent.profileVersion)))) {
                proc.toHandle().destroy()
                proc.waitFor(8,TimeUnit.SECONDS)
                return complete(AttemptState.UNKNOWN,"RUNNING_RECEIPT_REJECTED")
            }
            val oversized = AtomicBoolean(false)
            val readFailed = AtomicBoolean(false)
            val inputFailed = AtomicBoolean(false)
            val drain = Thread({
                try {
                    Files.newOutputStream(artifacts.resolve("events.log"),CREATE_NEW,WRITE).use { output ->
                        proc.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            var captured = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count<0) break
                                val kept = minOf(count,outputLimit-captured)
                                output.write(buffer,0,kept)
                                captured += kept
                                if (kept<count && oversized.compareAndSet(false,true)) proc.toHandle().destroy()
                            }
                        }
                    }
                } catch (_: Exception) { readFailed.set(true); proc.toHandle().destroy() }
            },"devorch-coding-output").apply { isDaemon=true; start() }
            val writer = Thread({
                try { proc.outputStream.use { it.write(prompt) } }
                catch (_: Exception) { inputFailed.set(true) }
            },"devorch-coding-input").apply { isDaemon=true; start() }
            val deadline = System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(limitMs+10_000)
            var stopped = false
            var ended = false
            while (System.nanoTime()<deadline) {
                try { if (proc.waitFor(50,TimeUnit.MILLISECONDS)) { ended=true; break } }
                catch (_: InterruptedException) { interrupted=true; proc.toHandle().destroy(); stopped=true }
                if (interrupted && stopped) break
                if (Files.exists(lastMessage,NOFOLLOW_LINKS) && Files.size(lastMessage)>outputLimit) {
                    oversized.set(true); proc.toHandle().destroy()
                }
            }
            if (!ended) {
                proc.toHandle().destroy()
                ended = proc.waitFor(8,TimeUnit.SECONDS)
                if (!ended) { proc.destroyForcibly(); proc.waitFor(2,TimeUnit.SECONDS) }
            }
            drain.join(2000); writer.join(2000)
            if (!ended || drain.isAlive || writer.isAlive || readFailed.get())
                return complete(AttemptState.UNKNOWN,"PROCESS_OR_STREAM_OUTCOME_UNKNOWN")
            val observed = mapper.readTree(boundedFile(helperReceipt,8192))
            val clean = observed.path("cleanup_echild").asBoolean(false) &&
                observed.path("supervisor").path("pid").asLong()==proc.pid() &&
                observed.path("supervisor").path("start_ticks").asText()==birth["start_ticks"] &&
                observed.path("boot_id").asText()==birth["boot_id"] &&
                observed.path("machine_id").asText()==birth["machine_id"] &&
                observed.path("child").path("pid").asLong()>0 &&
                observed.path("child").path("start_ticks").asText().isNotEmpty() &&
                observed.path("exit_code").isInt && proc.exitValue()==0
            if (!clean) return complete(AttemptState.UNKNOWN,"CLEANUP_NOT_PROVEN",observed)
            if (interrupted || oversized.get() || inputFailed.get() || observed["exit_code"].asInt()!=0 ||
                observed.path("cause").asText()!="exited")
                return complete(AttemptState.FAILED,when {
                    interrupted -> "INTERRUPTED"; oversized.get() -> "OUTPUT_LIMIT"
                    else -> observed.path("cause").asText()+"_exit_"+observed["exit_code"].asInt()
                },observed)
            boundedFile(lastMessage,outputLimit)
            if (!sourceMatches(intent.workspace,intent.baseSha,intent.sourceTree,false))
                return complete(AttemptState.UNKNOWN,"POST_RUN_SOURCE_IDENTITY_CHANGED",observed)
            val index = artifacts.resolve("candidate.index")
            fun indexed(vararg args: String): String = gitCommand(intent.workspace,args.toList(),index)
            indexed("read-tree",intent.baseSha)
            indexed("add","--all","--",".")
            val tree = indexed("write-tree").trim()
            check(tree.matches(Regex("[0-9a-f]{40}")))
            if (tree==intent.sourceTree) return complete(AttemptState.FAILED,"NO_CANDIDATE_DIFF",observed)
            val diff = indexed("diff","--cached","--binary","--no-ext-diff","--no-textconv",intent.baseSha)
            check(diff.isNotBlank())
            Files.writeString(artifacts.resolve("candidate.diff"),diff,CREATE_NEW,WRITE)
            return complete(AttemptState.SUCCEEDED,"CANDIDATE_CAPTURED_TESTS_NOT_VERIFIED",observed,tree)
        } catch (_: Exception) {
            process?.destroy()
            try { process?.waitFor(8,TimeUnit.SECONDS) } catch (_: InterruptedException) { interrupted=true }
            return complete(AttemptState.UNKNOWN,"RUNNER_EVIDENCE_INCOMPLETE")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun sourceMatches(workspace: Path, base: String, tree: String, requireClean: Boolean): Boolean {
        if (!TrustedGitCheckoutGuard.accepts(workspace)) return false
        if (gitCommand(workspace,listOf("rev-parse","--verify","HEAD")).trim()!=base ||
            gitCommand(workspace,listOf("rev-parse","--verify","HEAD^{tree}")).trim()!=tree) return false
        return !requireClean || gitCommand(workspace,listOf("status","--porcelain=v1","--untracked-files=all")).isBlank()
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

    private fun processIdentity(pid: Long): Map<String,String> {
        val fields = Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(')').trim().split(Regex("\\s+"))
        return mapOf("pid" to pid.toString(),"start_ticks" to fields[19],
            "boot_id" to Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim(),
            "machine_id" to Files.readString(Path.of("/etc/machine-id")).trim())
    }

    private class GitOutcomeUnknown : Exception()
}
