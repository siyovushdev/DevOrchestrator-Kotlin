package dev.orchestrator.github

import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.RegisteredProject
import dev.orchestrator.storage.SqliteTaskStore
import dev.orchestrator.storage.WorkflowHold
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LIVE read-only test using the GitHub Actions short-lived read token.
 * A previously pinned hardcoded Issue number becomes invalid when that Issue closes.
 * Read the CURRENT real Issue list and pin one open Issue, or explicitly verify a
 * no-open-issues result. This job must never manufacture or reopen test Issues.
 */
@EnabledIfEnvironmentVariable(named = "DEVORCH_GITHUB_LIVE_READ", matches = "true")
class GitHubLiveReadSmokeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun realGithubReaderHandlesOpenOrAllClosedAndSurvivesStoreRestart() {
        val name = requireNotNull(System.getenv("DEVORCH_GITHUB_LIVE_REPO"))
        val repositoryId = requireNotNull(System.getenv("DEVORCH_GITHUB_LIVE_REPO_ID"))
            .toLong().also { require(it > 0) }
        val token = requireNotNull(System.getenv("DEVORCH_GITHUB_READ_TOKEN")) {
            "Explicitly scoped read-only token is required"
        }
        require(token.isNotBlank())
        val parts = name.split("/")
        require(parts.size == 2)
        val target = GitHubRepositoryTarget(parts[0], parts[1], repositoryId)
        val path = directory.resolve("live-read.db")
        val store = SqliteTaskStore(path)
        store.insertProject(
            RegisteredProject("isolated-live-read-fixture", repositoryId, ProjectKind.PERSONAL,
                directory.toAbsolutePath().normalize().toString()),
            ownerActorId = "ci:trusted-read-only-smoke",
        )
        val policy = assertNotNull(store.findPolicy(repositoryId))
        assertTrue(store.updatePolicyFromTrustedOwner(
            policy.copy(personalAutoExecution = true, version = 2),
            expectedVersion = 1, ownerActorId = "ci:trusted-read-only-smoke",
            reason = "Isolated read-only GitHub integration check",
        ))

        val reader = GitHubIssueReader(HttpGitHubJsonRead(token = token))
        val observed = reader.read(target) // genuine authenticated GitHub API GET
        assertTrue(observed.isNotEmpty()) // tested repo has Issues
        val candidate = observed.firstOrNull { it.open }
        val first = GitHubIssueIntake(store, reader).pollOnce(target)

        if (candidate == null) {
            // All Issues are closed. That is valid: intake MUST NOT resurrect them.
            assertEquals(0, first.created)
            val reopened = SqliteTaskStore(path)
            for (closed in observed) {
                assertNull(reopened.findPinnedGitHubIssue(repositoryId, closed.issueNumber))
            }
            println("LIVE_GITHUB_READ_OK_NO_OPEN_ISSUES: repo=$repositoryId; no GitHub writes")
        } else {
            assertTrue(first.created > 0)
            val pinned = assertNotNull(store.findPinnedGitHubIssue(
                repositoryId, candidate.issueNumber,
            ))
            assertEquals(candidate.githubIssueId, pinned.githubIssueId)
            assertEquals(40, pinned.baseSha.length)
            assertEquals(64, pinned.bodySha256.length)
            val firstEvents = store.listEvents(pinned.workflowId)
            assertEquals(listOf("WORKFLOW_QUEUED", "GITHUB_ISSUE_PINNED"),
                firstEvents.map { it.type })

            val reopened = SqliteTaskStore(path)
            assertEquals(pinned, reopened.findPinnedGitHubIssue(repositoryId, candidate.issueNumber))
            GitHubIssueIntake(reopened, reader).pollOnce(target)
            assertEquals(pinned, reopened.findPinnedGitHubIssue(repositoryId, candidate.issueNumber))
            val workflow = assertNotNull(reopened.findWorkflow(repositoryId, candidate.issueNumber))
            // A concurrently edited/closed Issue may legitimately go to NEEDS_OWNER.
            if (workflow.hold == WorkflowHold.NONE) {
                assertEquals(firstEvents, reopened.listEvents(pinned.workflowId))
            } else {
                assertEquals(WorkflowHold.NEEDS_OWNER, workflow.hold)
            }
            println("LIVE_GITHUB_READ_PINNED_PASS: repo=$repositoryId; issue=${candidate.issueNumber}; no GitHub writes")
        }
    }
}
