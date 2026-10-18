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
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeCodexCliPreflightTest {
    @TempDir lateinit var root: Path
    private val sha = "a".repeat(40)
    private val binaryRoot get() = root.resolve("bin")
    private val worktree get() = root.resolve("scratch").resolve("task-clone")
    private fun db() = SqliteTaskStore(root.resolve("runtime.db"))
    private fun catalog() = SqliteAgentCatalog(root.resolve("runtime.db"))

    private fun fake(name: String, script: String): Path {
        Files.createDirectories(binaryRoot)
        val binary = binaryRoot.resolve(name)
        Files.writeString(binary, "#!/bin/sh\n$script\n")
        check(binary.toFile().setExecutable(true))
        return binary
    }

    private fun fakeCli(validHelp: Boolean = true): Path = fake("codex", """
        if [ "$1" = "--version" ]; then
          printf 'codex-cli 0.999.0\n'
        elif [ "$1" = "exec" ] && [ "$2" = "--help" ]; then
          printf '%s\n' '${if (validHelp) "--sandbox --model --json --output-last-message --config" else "--model --json"}'
        else
          exit 9
        fi
    """.trimIndent())

    private fun fakeGit(dirty: Boolean = false, actualSha: String = sha): Path =
        fake("git", """
            case "$*" in
              *--show-toplevel*) printf '%s\\n' '${worktree}' ;;
              *--absolute-git-dir*|*--git-common-dir*)
                  printf '%s\\n' '${worktree.resolve(".git")}' ;;
              *rev-parse*) printf '%s\\n' '$actualSha' ;;
              *status*) ${if (dirty) "printf '?? .env\\n'" else ":"} ;;
              *) exit 7 ;;
            esac
        """.trimIndent())

    private fun setup(kind: ProjectKind = ProjectKind.PERSONAL,
                      permitted: Boolean = true, role: AgentRole = AgentRole.CODING) {
        val metadata = worktree.resolve(".git")
        Files.createDirectories(metadata.resolve("objects"))
        Files.createDirectories(metadata.resolve("refs"))
        Files.writeString(metadata.resolve("HEAD"), "ref: refs/heads/main\\n")
        Files.writeString(metadata.resolve("config"), """
            [core]
                repositoryformatversion = 0
                filemode = true
                bare = false
                logallrefupdates = true
        """.trimIndent() + "\\n")
        val store = db()
        store.insertProject(
            RegisteredProject("personal-scratch", 101, kind, root.toString()), "owner:test"
        )
        if (permitted && kind == ProjectKind.PERSONAL) {
            val initial = assertNotNull(store.findPolicy(101))
            assertTrue(store.updatePolicyFromTrustedOwner(
                initial.copy(version = 2, personalAutoExecution = true),
                expectedVersion = 1, ownerActorId = "owner:test", reason = "Disposable test only",
            ))
        }
        if (kind == ProjectKind.PERSONAL) {
            val c = catalog()
            val manifest = AgentCapabilityManifest(
                "codex-cli", "openai", 1,
                roles = setOf(AgentRole.CODING, AgentRole.REVIEW),
                sandboxes = setOf(
                    AgentSandbox.NATIVE_PERSONAL_WRITE, AgentSandbox.NATIVE_PERSONAL_READ_ONLY,
                ),
                modelEfforts = mapOf("example-model" to setOf(ReasoningEffort.HIGH)),
            )
            assertTrue(c.declareManifest(manifest, null, "owner:test"))
            val profile = AgentProfile(
                "codex-test", 1, 101, role, "codex-cli", 1, "openai", "example-model",
                ReasoningEffort.HIGH,
                if (role == AgentRole.CODING) AgentSandbox.NATIVE_PERSONAL_WRITE
                else AgentSandbox.NATIVE_PERSONAL_READ_ONLY,
                180, 1_000_000,
            )
            assertTrue(c.saveProfile(profile, null, "owner:test"))
            assertNotNull(c.bindRole(101, role, "codex-test", 1, null, "owner:test"))
        }
    }

    private fun inspect(
        role: AgentRole = AgentRole.CODING,
        checkout: Path = worktree,
        codex: Path = fakeCli(),
        git: Path = fakeGit(),
        expected: String = sha,
    ) = NativeCodexCliPreflight(db(), catalog()).inspect(
        CodexNativeProbeRequest(101, role, checkout, expected, codex, git),
    )

    @Test fun fakeBinaryHelpAndPinnedGitPreflightPassButNeverAuthorizeLiveModel() {
        setup()
        val result = assertIs<CodexProbeResult.FlagsOnly>(inspect())
        assertEquals(sha, result.baseSha)
        assertEquals("codex-cli 0.999.0", result.cliVersion)
        assertEquals("codex-test", result.assignedProfileId)
        assertEquals(1L, result.assignedVersion)
        assertFalse(result.authorizedToLaunch)
        assertFalse(result.modelAndCredentialsVerified)
        assertTrue(Files.exists(worktree.resolve(".git")))
    }

    @Test fun selectedRoleUsesIndependentReadOnlySandboxAndNeverShellOrBypass() {
        setup(role = AgentRole.REVIEW)
        assertIs<CodexProbeResult.FlagsOnly>(inspect(role = AgentRole.REVIEW))
        val assigned = assertNotNull(catalog().findAssignedProfile(101, AgentRole.REVIEW))
        val plan = CodexExecCommandPlanner.plan(
            binaryRoot.resolve("codex"), worktree, root.resolve("report.txt"), assigned,
        )
        assertEquals(AgentSandbox.NATIVE_PERSONAL_READ_ONLY, plan.sandbox)
        assertEquals("read-only",
            plan.argumentVector[plan.argumentVector.indexOf("--sandbox") + 1])
        assertEquals("-", plan.argumentVector.last())
        assertTrue(plan.argumentVector.contains("model_reasoning_effort=high"))
        assertFalse(plan.argumentVector.any {
            it.contains("danger", ignoreCase = true) ||
                it.contains("bypass", ignoreCase = true) ||
                it.contains("skip-git", ignoreCase = true)
        })
    }

    @Test fun cleanCodingPlanUsesWorkspaceWriteButCannotLaunchOnConstruction() {
        setup()
        val assigned = assertNotNull(catalog().findAssignedProfile(101, AgentRole.CODING))
        val plan = CodexExecCommandPlanner.plan(
            binaryRoot.resolve("codex"), worktree, root.resolve("report.txt"), assigned,
        )
        assertEquals(AgentSandbox.NATIVE_PERSONAL_WRITE, plan.sandbox)
        assertEquals("workspace-write",
            plan.argumentVector[plan.argumentVector.indexOf("--sandbox") + 1])
        assertEquals("approval_policy=never",
            plan.argumentVector[plan.argumentVector.indexOf("--config") + 1])
        assertEquals("codex-test", plan.immutableProfileId)
        assertEquals(1L, plan.immutableProfileVersion)
    }

    @Test fun workAndDisabledPersonalAreDeniedBeforeBinaryRuns() {
        setup(kind = ProjectKind.WORK)
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.WORK_FORBIDDEN), inspect())
        // Separate state fixture with no auto enable.
        val second = root.resolve("second")
        Files.createDirectories(second)
        val other = SqliteTaskStore(second.resolve("state.db"))
        other.insertProject(
            RegisteredProject("disabled", 101, ProjectKind.PERSONAL, second.toString()), "owner:test",
        )
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.PERSONAL_AUTO_DISABLED),
            NativeCodexCliPreflight(other, SqliteAgentCatalog(second.resolve("state.db")))
                .inspect(CodexNativeProbeRequest(101, AgentRole.CODING, worktree, sha,
                    fakeCli(), fakeGit())))
    }

    @Test fun dirtyOrMismatchedBaseRefusesToClaimCleanRepo() {
        setup()
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.DIRTY_BASE),
            inspect(git = fakeGit(dirty = true)))
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.INVALID_BASE_SHA),
            inspect(git = fakeGit(actualSha = "b".repeat(40))))
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.INVALID_BASE_SHA),
            inspect(expected = "NOT_SHA"))
    }

    @Test fun workspaceEscapeMissingGitAndUnsupportedHelpFailClosed() {
        setup()
        val outside = root.parent.resolve("outside-repo")
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE),
            inspect(checkout = outside))
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.UNSUPPORTED_CLI_FLAGS),
            inspect(codex = fakeCli(validHelp = false)))
        Files.move(worktree.resolve(".git"), worktree.resolve(".git-old"))
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.UNTRUSTED_WORKSPACE),
            inspect())
    }

    @Test fun tooMuchProbeOutputIsBlockedWithoutFillingTheDisk() {
        Files.createDirectories(worktree)
        val flood = fake("flood-probe", "head -c 100000 /dev/zero")
        val result = BoundedNativeProbeReader(maxOutputBytes = 1024).read(
            listOf(flood.toString(), "--help"), worktree,
        )
        assertIs<ProbeProcessResult.OutputTooLarge>(result)
    }

    @Test fun untrustedOrMissingBinaryCannotPassPreflight() {
        setup()
        val absent = root.resolve("absent-codex")
        assertEquals(CodexProbeResult.Blocked(CodexProbeBlocker.INVALID_CLI_BINARY),
            inspect(codex = absent))
    }

    @Test fun timedOutProbeReturnsUnknownNotAFalseSuccess() {
        Files.createDirectories(worktree)
        val slow = fake("hang-probe", "sleep 3")
        val result = BoundedNativeProbeReader(timeout = Duration.ofMillis(120)).read(
            listOf(slow.toString(), "--help"), worktree,
        )
        assertIs<ProbeProcessResult.TimedOutOrUnknown>(result)
    }
}
