package dev.orchestrator.agent.nativecli

import dev.orchestrator.agent.AgentRole
import dev.orchestrator.agent.SqliteAgentCatalog
import dev.orchestrator.policy.PolicyDecision
import dev.orchestrator.policy.ProjectAction
import dev.orchestrator.policy.ProjectRegistry
import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.SqliteTaskStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

enum class CodexProbeBlocker {
    UNKNOWN_PROJECT,
    WORK_FORBIDDEN,
    PERSONAL_AUTO_DISABLED,
    NO_BOUND_PROFILE,
    WRONG_AGENT_PROFILE,
    UNTRUSTED_WORKSPACE,
    INVALID_BASE_SHA,
    DIRTY_BASE,
    INVALID_CLI_BINARY,
    INVALID_GIT_BINARY,
    EXEC_PROBE_FAILED,
    UNSUPPORTED_CLI_FLAGS,
    PROBE_TIMEOUT_OR_UNKNOWN,
}

sealed interface CodexProbeResult {
    data class Blocked(val reason: CodexProbeBlocker) : CodexProbeResult

    /**
     * CLI help + git state only. NOT runtime-model/auth/network/secret-isolation evidence.
     * Explicitly cannot authorize `codex exec` or publication.
     */
    data class FlagsOnly(
        val cliVersion: String,
        val baseSha: String,
        val executable: Path,
        val assignedProfileId: String,
        val assignedVersion: Long,
        val modelAndCredentialsVerified: Boolean = false,
        val authorizedToLaunch: Boolean = false,
    ) : CodexProbeResult
}

data class CodexNativeProbeRequest(
    val repositoryId: Long,
    val role: AgentRole,
    val checkout: Path,
    val pinnedBaseSha: String,
    val codexBinary: Path,
    val gitBinary: Path,
)

/**
 * Explicit read-only local probe. Never called from startup/scheduler/HTTP, and no
 * model inference or agent execution. Child processes only receive a scrubbed env.
 */
class NativeCodexCliPreflight(
    private val store: SqliteTaskStore,
    private val catalog: SqliteAgentCatalog,
    private val processReader: BoundedNativeProbeReader = BoundedNativeProbeReader(),
) {
    fun inspect(req: CodexNativeProbeRequest): CodexProbeResult {
        if (req.repositoryId <= 0) return CodexProbeResult.Blocked(CodexProbeBlocker.UNKNOWN_PROJECT)
        val policy = try { store.findPolicy(req.repositoryId) } catch (_: Exception) { null }
            ?: return CodexProbeResult.Blocked(CodexProbeBlocker.UNKNOWN_PROJECT)
        if (policy.kind != ProjectKind.PERSONAL) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.WORK_FORBIDDEN)
        }
        if (!policy.personalAutoExecution) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.PERSONAL_AUTO_DISABLED)
        }
        if (req.role != AgentRole.CODING && req.role != AgentRole.REVIEW) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.WRONG_AGENT_PROFILE)
        }
        val assigned = try {
            catalog.findAssignedProfile(req.repositoryId, req.role)
        } catch (_: Exception) { null }
            ?: return CodexProbeResult.Blocked(CodexProbeBlocker.NO_BOUND_PROFILE)
        if (assigned.profile.adapterId != "codex-cli" ||
            assigned.profile.provider != "openai" ||
            assigned.profile.role != req.role ||
            assigned.profile.repositoryId != req.repositoryId) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.WRONG_AGENT_PROFILE)
        }

        val dir = req.checkout
        if (!dir.isAbsolute || dir.normalize() != dir || dir == policy.workspaceRoot ||
            !dir.startsWith(policy.workspaceRoot) ||
            ProjectRegistry(store).evaluate(req.repositoryId, dir, ProjectAction.EXECUTE) !=
                PolicyDecision.Allowed) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE)
        }
        // The first M2 smoke accepts disposable clones only, not linked worktrees.
        // A .git file can point to a privileged checkout elsewhere on the host.
        try {
            if (policy.workspaceRoot.toRealPath() != policy.workspaceRoot ||
                dir.toRealPath() != dir ||
                !Files.isDirectory(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE)
            }
        } catch (_: Exception) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE)
        }
        val expected = req.pinnedBaseSha.lowercase()
        if (!expected.matches(Regex("[0-9a-f]{40}"))) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.INVALID_BASE_SHA)
        }
        val codex = requireExecutable(req.codexBinary)
            ?: return CodexProbeResult.Blocked(CodexProbeBlocker.INVALID_CLI_BINARY)
        val git = requireExecutable(req.gitBinary)
            ?: return CodexProbeResult.Blocked(CodexProbeBlocker.INVALID_GIT_BINARY)

        fun read(args: List<String>, toolBinDirectory: Path? = null) =
            processReader.read(args, dir, toolBinDirectory)
        val head = read(listOf(git.toString(), "-C", dir.toString(),
            "rev-parse", "--verify", "HEAD"))
        if (head !is ProbeProcessResult.Exited || head.exitCode != 0 ||
            head.output.trim().lowercase() != expected) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.INVALID_BASE_SHA)
        }
        val status = read(listOf(git.toString(), "--no-optional-locks", "-c", "core.fsmonitor=false",
            "-C", dir.toString(), "status", "--porcelain=v1", "--untracked-files=all"))
        if (status !is ProbeProcessResult.Exited || status.exitCode != 0 ||
            status.output.isNotBlank()) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.DIRTY_BASE)
        }
        val version = read(listOf(codex.toString(), "--version"), req.codexBinary.parent)
        val help = read(listOf(codex.toString(), "exec", "--help"), req.codexBinary.parent)
        if (version is ProbeProcessResult.TimedOutOrUnknown ||
            help is ProbeProcessResult.TimedOutOrUnknown) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.PROBE_TIMEOUT_OR_UNKNOWN)
        }
        if (version !is ProbeProcessResult.Exited || version.exitCode != 0 ||
            help !is ProbeProcessResult.Exited || help.exitCode != 0) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.EXEC_PROBE_FAILED)
        }
        if (version.output.isBlank() || version.output.length > 256) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.EXEC_PROBE_FAILED)
        }
        val needed = setOf("--sandbox", "--model", "--json",
            "--output-last-message", "--config")
        if (!needed.all { help.output.contains(it) }) {
            return CodexProbeResult.Blocked(CodexProbeBlocker.UNSUPPORTED_CLI_FLAGS)
        }
        return CodexProbeResult.FlagsOnly(
            cliVersion = version.output.trim(),
            baseSha = expected,
            executable = codex,
            assignedProfileId = assigned.profile.profileId,
            assignedVersion = assigned.profile.version,
        )
    }

    private fun requireExecutable(path: Path): Path? = try {
        if (!path.isAbsolute || path.normalize() != path) null
        else path.toRealPath().takeIf {
            Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(it)
        }
    } catch (_: Exception) { null }
}

sealed interface ProbeProcessResult {
    data class Exited(val exitCode: Int, val output: String) : ProbeProcessResult
    data object TimedOutOrUnknown : ProbeProcessResult
    data object StartFailed : ProbeProcessResult
    data object OutputTooLarge : ProbeProcessResult
}

/**
 * Bounded, read-only command execution for --help, --version and git HEAD/status.
 * No user prompt, shell, agent session, credentials or automation loop.
 * Timeout does NOT prove all descendants ended; never automatically retry UNKNOWN.
 */
class BoundedNativeProbeReader(
    private val timeout: Duration = Duration.ofSeconds(8),
    private val maxOutputBytes: Long = 65_536,
) {
    init {
        require(timeout.toMillis() in 1..30_000)
        require(maxOutputBytes in 1024..1_000_000)
    }

    fun read(args: List<String>, cwd: Path, extraTrustedBinaryDirectory: Path? = null): ProbeProcessResult {
        require(args.isNotEmpty() && args.size <= 20)
        val home = Files.createTempDirectory("devorch-probe-home-")
        try {
            val builder = ProcessBuilder(args)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
            val env = builder.environment()
            env.clear()
            // Include only trusted binary directory, never arbitrary user/Issue PATH.
            val binaryParent = Path.of(args.first()).parent?.toString() ?: ""
            // NVM installs a node entrypoint and codex symlink in the same bin dir;
            // once owner-validated, that directory may supply node to the shebang.
            val trustedDir = extraTrustedBinaryDirectory?.let {
                if (!it.isAbsolute || it.normalize() != it || !Files.isDirectory(it)) {
                    return ProbeProcessResult.StartFailed
                }
                it.toRealPath().toString()
            }
            env["PATH"] = listOfNotNull(trustedDir, binaryParent, "/usr/bin", "/bin")
                .distinct().joinToString(":")
            env["HOME"] = home.toString()
            env["LANG"] = "C"
            env["GIT_CONFIG_NOSYSTEM"] = "1"
            env["GIT_CONFIG_GLOBAL"] = "/dev/null"
            val proc = try { builder.start() } catch (_: Exception) {
                return ProbeProcessResult.StartFailed
            }
            proc.outputStream.close()
            val captured = java.io.ByteArrayOutputStream()
            val oversized = java.util.concurrent.atomic.AtomicBoolean(false)
            val drain = Thread({
                try {
                    proc.inputStream.use { stream ->
                        val chunk = ByteArray(4096)
                        while (true) {
                            val size = stream.read(chunk)
                            if (size < 0) break
                            if (captured.size().toLong() + size > maxOutputBytes) {
                                oversized.set(true)
                                proc.destroyForcibly()
                                break
                            }
                            captured.write(chunk, 0, size)
                        }
                    }
                } catch (_: Exception) {
                    // Interrupted/broken pipe is not proof of process completion.
                }
            }, "devorch-bounded-probe-reader")
            drain.isDaemon = true
            drain.start()
            val finished = try {
                proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!finished || oversized.get()) {
                try { proc.toHandle().descendants().forEach { it.destroyForcibly() } }
                catch (_: Exception) { }
                proc.destroyForcibly()
                try { proc.waitFor(2, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            try { drain.join(2_000) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            if (!finished || drain.isAlive) return ProbeProcessResult.TimedOutOrUnknown
            if (oversized.get()) return ProbeProcessResult.OutputTooLarge
            return ProbeProcessResult.Exited(
                proc.exitValue(), captured.toString(java.nio.charset.StandardCharsets.UTF_8),
            )
        } finally {
            // Probe output is never written to a persistent log or Git workspace.
            // A malicious CLI might populate HOME; do not recursively delete unknown files.
            try { Files.deleteIfExists(home) } catch (_: Exception) { }
        }
    }
}
