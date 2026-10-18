package dev.orchestrator.agent.nativecli

import dev.orchestrator.agent.AgentCapabilityManifest
import dev.orchestrator.agent.AgentProfile
import dev.orchestrator.agent.AgentRole
import dev.orchestrator.agent.AgentSandbox
import dev.orchestrator.agent.ReasoningEffort
import dev.orchestrator.agent.SqliteAgentCatalog
import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.RegisteredProject
import dev.orchestrator.storage.SqliteTaskStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Owner-invoked local WORK smoke: only CLI --version/exec --help, no agent inference.
 * Git commands create a disposable local repository under the JUnit temp directory.
 * Opt-in environment required: DEVORCH_CODEX_FLAGS_PROBE=true and exact CLI binary path.
 */
@EnabledIfEnvironmentVariable(named = "DEVORCH_CODEX_FLAGS_PROBE", matches = "true")
class InstalledCodexCliFlagsProbeTest {
    @TempDir lateinit var temp: Path

    @Test
    fun probesRealInstalledCliAgainstDisposablePersonalGitCloneWithoutRunningModel() {
        val codex = Path.of(requireNotNull(System.getenv("DEVORCH_CODEX_BIN")) {
            "Set DEVORCH_CODEX_BIN to the absolute installed Codex CLI path"
        })
        val git = Path.of(System.getenv("DEVORCH_GIT_BIN") ?: "/usr/bin/git")
        require(codex.isAbsolute && git.isAbsolute)

        val workspace = temp.resolve("disposable-personal-source")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("README.md"), "disposable M2-01 preflight only\n")
        val template = temp.resolve("empty-git-template")
        Files.createDirectories(template)

        fun gitCmd(vararg args: String): String {
            val cmd = listOf(git.toString(), "-c", "core.hooksPath=/dev/null") + args
            val builder = ProcessBuilder(cmd).directory(workspace.toFile()).redirectErrorStream(true)
            val env = builder.environment()
            env.clear()
            env["HOME"] = temp.toString()
            env["PATH"] = "/usr/bin:/bin"
            env["GIT_CONFIG_NOSYSTEM"] = "1"
            env["GIT_CONFIG_GLOBAL"] = "/dev/null"
            env["GIT_TEMPLATE_DIR"] = template.toString()
            env["GIT_TERMINAL_PROMPT"] = "0"
            val outputFile = Files.createTempFile(temp, "fixture-git-", ".log")
            try {
                builder.redirectOutput(outputFile.toFile())
                val process = builder.start()
                process.outputStream.close()
                val ended = process.waitFor(10, TimeUnit.SECONDS)
                if (!ended) process.destroyForcibly()
                check(ended && process.exitValue() == 0) {
                    "Disposable Git fixture command did not succeed"
                }
                val output = Files.readString(outputFile)
                require(output.length < 4096) { "Unexpected Git fixture output size" }
                return output.trim()
            } finally {
                Files.deleteIfExists(outputFile)
            }
        }
        gitCmd("init", "-q", "--initial-branch=main")
        gitCmd("add", "--", "README.md")
        gitCmd("-c", "user.name=DevOrchestrator", "-c", "user.email=local@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "-q", "-m", "disposable preflight")
        val expectedSha = gitCmd("rev-parse", "--verify", "HEAD")
        assertTrue(expectedSha.matches(Regex("[0-9a-f]{40}")))

        val database = temp.resolve("probe.db")
        val store = SqliteTaskStore(database)
        store.insertProject(
            RegisteredProject("disposable-personal", 500001, ProjectKind.PERSONAL,
                temp.toAbsolutePath().normalize().toString()),
            ownerActorId = "owner:local-preflight",
        )
        val policy = requireNotNull(store.findPolicy(500001))
        assertTrue(store.updatePolicyFromTrustedOwner(
            policy.copy(version = 2, personalAutoExecution = true),
            expectedVersion = 1, ownerActorId = "owner:local-preflight",
            reason = "Owner-invoked flags-only probe, not Coding approval",
        ))
        val catalog = SqliteAgentCatalog(database)
        assertTrue(catalog.declareManifest(
            AgentCapabilityManifest(
                adapterId = "codex-cli", provider = "openai", version = 1,
                roles = setOf(AgentRole.CODING),
                sandboxes = setOf(AgentSandbox.NATIVE_PERSONAL_WRITE),
                modelEfforts = mapOf("unverified-model" to setOf(ReasoningEffort.DEFAULT)),
            ), expectedPreviousVersion = null, ownerActorId = "owner:local-preflight",
        ))
        assertTrue(catalog.saveProfile(
            AgentProfile(
                profileId = "disposable-codex", version = 1, repositoryId = 500001,
                role = AgentRole.CODING, adapterId = "codex-cli", manifestVersion = 1,
                provider = "openai", model = "unverified-model",
                effort = ReasoningEffort.DEFAULT,
                sandbox = AgentSandbox.NATIVE_PERSONAL_WRITE,
                timeoutSeconds = 120, maxBudgetMicroUsd = 100_000,
            ), expectedPreviousVersion = null, ownerActorId = "owner:local-preflight",
        ))
        check(catalog.bindRole(500001, AgentRole.CODING, "disposable-codex", 1,
            expectedPreviousBindingVersion = null, ownerActorId = "owner:local-preflight") != null)

        val outcome = NativeCodexCliPreflight(store, catalog).inspect(
            CodexNativeProbeRequest(
                repositoryId = 500001, role = AgentRole.CODING,
                checkout = workspace.toAbsolutePath().normalize(),
                pinnedBaseSha = expectedSha, codexBinary = codex, gitBinary = git,
            )
        )
        val evidence = assertIs<CodexProbeResult.FlagsOnly>(outcome)
        assertTrue(evidence.cliVersion.isNotBlank())
        assertFalse(evidence.authorizedToLaunch)
        assertFalse(evidence.modelAndCredentialsVerified)
        println("REAL_CODEX_CLI_FLAGS_ONLY_PASS; no agent/model invoked; no WORK repo touched")
    }
}
