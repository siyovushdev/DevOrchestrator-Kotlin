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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REAL local Git regression fixtures. All repos, configs and marker paths are disposable.
 * The Codex binary remains a fake --version/--help script. Never performs inference.
 */
class NativeGitCheckoutSecurityTest {
    @TempDir lateinit var temp: Path
    private val git = Path.of("/usr/bin/git")
    private val clone get() = temp.resolve("clone")
    private val marker get() = temp.resolve("filter-executed")
    private val database get() = temp.resolve("state.db")

    private fun runGit(cwd: Path, vararg args: String): String {
        val request = listOf(git.toString(), "-c", "core.hooksPath=/dev/null") + args
        val process = ProcessBuilder(request).directory(cwd.toFile())
            .redirectErrorStream(true)
        val builderEnv = process.environment()
        builderEnv.clear()
        builderEnv["HOME"] = temp.toString()
        builderEnv["PATH"] = "/usr/bin:/bin"
        builderEnv["GIT_CONFIG_GLOBAL"] = "/dev/null"
        builderEnv["GIT_CONFIG_NOSYSTEM"] = "1"
        builderEnv["GIT_TEMPLATE_DIR"] = temp.resolve("template").toString()
        val p = process.start()
        p.outputStream.close()
        val bytes = p.inputStream.readNBytes(8192)
        check(p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
            "Disposable Git fixture failed: ${String(bytes).take(120)}"
        }
        return String(bytes).trim()
    }

    private fun setup(): Pair<NativeCodexCliPreflight, String> {
        Files.createDirectories(clone)
        Files.createDirectories(temp.resolve("template"))
        Files.writeString(clone.resolve("README.txt"), "safe tracked content\n")
        runGit(clone, "init", "-q", "--initial-branch=main")
        runGit(clone, "add", "README.txt")
        runGit(clone, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "-q", "-m", "first")
        val sha = runGit(clone, "rev-parse", "--verify", "HEAD")
        assertTrue(sha.matches(Regex("[0-9a-f]{40}")))

        val store = SqliteTaskStore(database)
        store.insertProject(RegisteredProject("disposable", 600001, ProjectKind.PERSONAL,
            temp.toString()), "owner:test")
        val original = assertNotNull(store.findPolicy(600001))
        assertTrue(store.updatePolicyFromTrustedOwner(
            original.copy(version = 2, personalAutoExecution = true),
            1, "owner:test", "Temporary checkout security test",
        ))
        val catalog = SqliteAgentCatalog(database)
        assertTrue(catalog.declareManifest(
            AgentCapabilityManifest("codex-cli","openai",1,setOf(AgentRole.CODING),
                setOf(AgentSandbox.NATIVE_PERSONAL_WRITE),
                mapOf("test-model" to setOf(ReasoningEffort.DEFAULT))),
            null,"owner:test",
        ))
        assertTrue(catalog.saveProfile(
            AgentProfile("coding-test",1,600001,AgentRole.CODING,
                "codex-cli",1,"openai","test-model",ReasoningEffort.DEFAULT,
                AgentSandbox.NATIVE_PERSONAL_WRITE,120,100000),
            null,"owner:test",
        ))
        assertNotNull(catalog.bindRole(600001,AgentRole.CODING,"coding-test",1,null,"owner:test"))
        return NativeCodexCliPreflight(store,catalog) to sha
    }

    private fun fakeCodex(): Path {
        val binary = temp.resolve("codex-fake")
        Files.writeString(binary, "#!/bin/sh\n" +
            "if [ \"$1\" = \"--version\" ]; then printf 'fake-codex 1\\n'; " +
            "elif [ \"$1\" = \"exec\" ] && [ \"$2\" = \"--help\" ]; then " +
            "printf '%s\\n' '--sandbox --model --json --output-last-message --config'; " +
            "else exit 33; fi\n")
        assertTrue(binary.toFile().setExecutable(true))
        return binary
    }

    private fun inspect(probe: NativeCodexCliPreflight, sha: String): CodexProbeResult =
        probe.inspect(CodexNativeProbeRequest(
            600001,AgentRole.CODING,clone,sha,fakeCodex(),git,
        ))

    @Test fun cleanRealGitStandaloneCloneCanReachFlagsOnlyButNeverLaunchPermit() {
        val (probe,sha) = setup()
        val result = assertIs<CodexProbeResult.FlagsOnly>(inspect(probe,sha))
        assertFalse(result.authorizedToLaunch)
        assertFalse(result.modelAndCredentialsVerified)
        assertEquals(sha,result.baseSha)
    }

    @Test fun localCleanFilterAndGitattributesDeniedBeforeExternalCommand() {
        val (probe,sha) = setup()
        val config = clone.resolve(".git/config")
        Files.writeString(config, Files.readString(config) +
            "\n[filter \"trap\"]\n clean = sh -c 'touch ${marker}'\n")
        Files.writeString(clone.resolve(".gitattributes"), "*.txt filter=trap\n")
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE),
            inspect(probe,sha))
        assertFalse(Files.exists(marker),"Filter must never execute during Git preflight")
    }

    @Test fun localProcessFilterDeniedBeforeExternalCommand() {
        val (probe,sha) = setup()
        val config = clone.resolve(".git/config")
        Files.writeString(config, Files.readString(config) +
            "\n[filter \"trap\"]\n process = sh -c 'touch ${marker}'\n")
        Files.writeString(clone.resolve(".gitattributes"), "*.txt filter=trap\n")
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE),
            inspect(probe,sha))
        assertFalse(Files.exists(marker),"Process filter must never execute")
    }

    @Test fun redirectedCoreWorktreeAndExternalCommondirCannotBeTrusted() {
        val (probe,sha) = setup()
        val elsewhere = temp.resolve("other-clean-tree")
        Files.createDirectories(elsewhere)
        val config = clone.resolve(".git/config")
        val original = Files.readString(config)
        Files.writeString(config, original + "\n[core]\n worktree = ${elsewhere}\n")
        Files.writeString(clone.resolve("README.txt"), "dirty A, clean B\n")
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE),
            inspect(probe,sha))
        Files.writeString(config, original)
        Files.writeString(clone.resolve(".git/commondir"), elsewhere.toString())
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE),
            inspect(probe,sha))
    }

    @Test fun linkedGitIndexAndIncludedConfigAreRejected() {
        val (probe,sha) = setup()
        val index = clone.resolve(".git/index")
        val redirect = temp.resolve("foreign-index")
        Files.writeString(redirect,"foreign")
        Files.delete(index)
        Files.createSymbolicLink(index,redirect)
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE),
            inspect(probe,sha))
        Files.delete(index)
        val config = clone.resolve(".git/config")
        Files.writeString(config, Files.readString(config) +
            "\n[include]\npath = /tmp/nonexistent\n")
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE),
            inspect(probe,sha))
    }
}
