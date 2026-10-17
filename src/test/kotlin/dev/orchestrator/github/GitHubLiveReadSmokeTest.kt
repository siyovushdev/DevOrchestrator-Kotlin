package dev.orchestrator.github

import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.RegisteredProject
import dev.orchestrator.storage.SqliteTaskStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in LIVE test: real authenticated GETs to the configured GitHub repository.
 * The token is ephemeral, read-only and never logged. Only a temporary local SQLite
 * database is written. No agents, checkout modification, push or GitHub mutation.
 */
@EnabledIfEnvironmentVariable(named = "DEVORCH_GITHUB_LIVE_READ", matches = "true")
class GitHubLiveReadSmokeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun realGithubIssueIsPinnedOnceAndSurvivesStoreRestart() {
        val name = requireNotNull(System.getenv("DEVORCH_GITHUB_LIVE_REPO"))
        val repositoryId = requireNotNull(System.getenv("DEVORCH_GITHUB_LIVE_REPO_ID"))
            .toLong().also { require(it > 0) }
        val expectedIssue = requireNotNull(System.getenv("DEVORCH_GITHUB_LIVE_ISSUE"))
            .toInt().also { require(it > 0) }
        val token = requireNotNull(System.getenv("DEVORCH_GITHUB_READ_TOKEN")) {
            "An explicitly scoped read-only GitHub token is required"
        }
        require(token.isNotBlank())
        val parts = name.split("/")
        require(parts.size == 2)
        // Real project identity is supplied by trusted CI config, never GitHub Issue text.
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
        val first = GitHubIssueIntake(store, reader).pollOnce(target)
        assertTrue(first.created > 0)
        val pinned = assertNotNull(store.findPinnedGitHubIssue(repositoryId, expectedIssue))
        assertEquals(expectedIssue, pinned.issueNumber)
        assertTrue(pinned.githubIssueId > 0)
        assertEquals(40, pinned.baseSha.length)
        assertEquals(64, pinned.bodySha256.length)
        val firstEvents = store.listEvents(pinned.workflowId)
        assertEquals(listOf("WORKFLOW_QUEUED", "GITHUB_ISSUE_PINNED"),
            firstEvents.map { it.type })

        val restarted = SqliteTaskStore(path)
        assertEquals(pinned, restarted.findPinnedGitHubIssue(repositoryId, expectedIssue))
        val second = GitHubIssueIntake(restarted, reader).pollOnce(target)
        assertEquals(0, second.created)
        assertEquals(pinned, restarted.findPinnedGitHubIssue(repositoryId, expectedIssue))
        assertEquals(firstEvents, restarted.listEvents(pinned.workflowId))
        println("LIVE_GITHUB_READ: PASS; exact_repo_id=$repositoryId; issue=$expectedIssue; no writes to GitHub")
    }
}
