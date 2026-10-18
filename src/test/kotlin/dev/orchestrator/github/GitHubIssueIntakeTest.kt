package dev.orchestrator.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.storage.GitHubIssueRecord
import dev.orchestrator.storage.IssueIntakeOutcome
import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.RegisteredProject
import dev.orchestrator.storage.SqliteTaskStore
import dev.orchestrator.storage.WorkflowHold
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubIssueIntakeTest {
    @TempDir lateinit var directory: Path
    private val mapper = ObjectMapper()
    private val target = GitHubRepositoryTarget("owner", "disposable", 411)
    private val pinnedSha = "a".repeat(40)
    private val secondSha = "b".repeat(40)
    private val time = Instant.parse("2026-10-17T12:00:00Z")

    private fun db() = SqliteTaskStore(directory.resolve("state.db"))

    private fun allowPersonal(store: SqliteTaskStore) {
        store.insertProject(RegisteredProject(
            "personal-test", 411, ProjectKind.PERSONAL, directory.toString(),
        ), ownerActorId = "owner:test")
        val policy = assertNotNull(store.findPolicy(411))
        assertTrue(store.updatePolicyFromTrustedOwner(
            policy.copy(version = 2, personalAutoExecution = true),
            expectedVersion = 1, ownerActorId = "owner:test", reason = "disposable test",
        ))
    }

    private fun repoJson(id: Long = 411): String = mapper.writeValueAsString(
        mapOf("id" to id, "full_name" to "owner/disposable", "default_branch" to "main")
    )
    private fun commitJson(sha: String = pinnedSha): String =
        mapper.writeValueAsString(mapOf("sha" to sha))

    private fun issue(
        number: Int = 7,
        id: Long = 7007,
        title: String = "Add test feature",
        body: String? = "Implement bounded feature",
        state: String = "open",
        updated: String = "2026-10-17T12:00:00Z",
        url: String = "https://api.github.com/repos/owner/disposable/issues/$number",
    ): JsonNode = mapper.createObjectNode().apply {
        put("number", number)
        put("id", id)
        put("title", title)
        if (body == null) putNull("body") else put("body", body)
        put("state", state)
        put("updated_at", updated)
        put("url", url)
        put("repository_url", "https://api.github.com/repos/owner/disposable")
    }

    private fun reader(
        pages: List<List<JsonNode>>,
        repoId: Long = 411,
        baseSha: String = pinnedSha,
        hook: ((String) -> Unit)? = null,
    ): GitHubIssueReader = GitHubIssueReader(GitHubJsonRead { path ->
        hook?.invoke(path)
        when {
            path == target.apiRoot -> repoJson(repoId)
            path == "${target.apiRoot}/commits/main" -> commitJson(baseSha)
            path.startsWith("${target.apiRoot}/issues?") -> {
                val page = Regex("(?:\\?|&)page=(\\d+)").find(path)
                    ?.groupValues?.get(1)?.toInt()
                    ?: error("Missing page argument")
                mapper.writeValueAsString(pages.getOrElse(page - 1) { emptyList() })
            }
            else -> error("Untrusted GitHub path: $path")
        }
    })

    @Test fun createsOnePinnedWorkflowAndSurvivesRestartWithoutExtraEvents() {
        val store = db()
        allowPersonal(store)
        val first = GitHubIssueIntake(store, reader(listOf(listOf(issue())))).pollOnce(target)
        assertEquals(1, first.created)
        assertEquals(0, first.duplicates)
        val frozen = assertNotNull(store.findPinnedGitHubIssue(411, 7))
        assertEquals(7007L, frozen.githubIssueId)
        assertEquals("Implement bounded feature", frozen.body)
        assertEquals(pinnedSha, frozen.baseSha)
        assertEquals(2, store.listEvents(frozen.workflowId).size)

        // Default branch advances; original pinned base stays fixed for this workflow.
        val repeated = GitHubIssueIntake(db(),
            reader(listOf(listOf(issue())), baseSha = secondSha)).pollOnce(target)
        assertEquals(1, repeated.duplicates)
        assertEquals(frozen, assertNotNull(db().findPinnedGitHubIssue(411, 7)))
        assertEquals(2, db().listEvents(frozen.workflowId).size)
    }

    @Test fun changedAndClosedIssueMoveToNeedsOwnerOnlyOnceWithoutRewritingSource() {
        val store = db()
        allowPersonal(store)
        GitHubIssueIntake(store, reader(listOf(listOf(issue())))).pollOnce(target)
        val frozen = assertNotNull(store.findPinnedGitHubIssue(411, 7))
        val changed = issue(body = "Malicious or changed directions",
            updated = "2026-10-17T13:00:00Z")
        assertEquals(1,
            GitHubIssueIntake(store, reader(listOf(listOf(changed)))).pollOnce(target).needsOwner)
        assertEquals(1,
            GitHubIssueIntake(store, reader(listOf(listOf(changed)))).pollOnce(target).needsOwner)
        assertEquals(frozen, store.findPinnedGitHubIssue(411,7))
        val workflow = assertNotNull(store.findWorkflow(411,7))
        assertEquals(WorkflowHold.NEEDS_OWNER, workflow.hold)
        assertEquals("GITHUB_ISSUE_CHANGED", workflow.holdReason)
        assertEquals(3, store.listEvents(workflow.id).size)

        val closed = issue(state = "closed", updated = "2026-10-17T14:00:00Z")
        assertEquals(1,
            GitHubIssueIntake(store, reader(listOf(listOf(closed)))).pollOnce(target).needsOwner)
        assertEquals(3, store.listEvents(workflow.id).size)
        val originalAgain = GitHubIssueIntake(store, reader(listOf(listOf(issue())))).pollOnce(target)
        assertEquals(1, originalAgain.needsOwner)
        assertEquals(0, originalAgain.duplicates)
        assertEquals("GITHUB_ISSUE_CHANGED",
            assertNotNull(store.findWorkflow(411, 7)).holdReason)
        assertNull(store.findWorkflow(411, 999))
    }

    @Test fun closedUnknownIssueNeverCreatesWorkflow() {
        val store = db()
        allowPersonal(store)
        val receipt = GitHubIssueIntake(store,
            reader(listOf(listOf(issue(number = 9, id = 9009, state = "closed")))))
            .pollOnce(target)
        assertEquals(1, receipt.ignoredClosed)
        assertNull(store.findWorkflow(411,9))
    }

    @Test fun forgedRepositoryResponseOrIssueUrlFailsBeforeIntake() {
        val store = db()
        allowPersonal(store)
        assertFailsWith<IllegalArgumentException> {
            GitHubIssueIntake(store,
                reader(listOf(listOf(issue())), repoId = 999)).pollOnce(target)
        }
        assertFailsWith<IllegalArgumentException> {
            GitHubIssueIntake(store, reader(listOf(listOf(
                issue(url = "https://api.github.com/repos/other/repo/issues/7")
            )))).pollOnce(target)
        }
        assertNull(store.findWorkflow(411,7))
    }

    @Test fun workAndDisabledPersonalDenyBeforeNetwork() {
        val store = db()
        store.insertProject(RegisteredProject(
            "work", 411, ProjectKind.WORK, directory.toString()
        ), ownerActorId = "owner:test")
        val noCalls = GitHubIssueReader(GitHubJsonRead { error("NO_NETWORK_FOR_DENIED_PROJECT") })
        assertFailsWith<IllegalStateException> {
            GitHubIssueIntake(store, noCalls).pollOnce(target)
        }
        assertNull(store.findWorkflow(411,7))
    }

    @Test fun ownerRevocationBetweenReadAndCommitDeniesIntake() {
        val store = db()
        allowPersonal(store)
        var revoked = false
        val source = reader(listOf(listOf(issue())), hook = { path ->
            if (!revoked && path.contains("/issues?")) {
                revoked = true
                val policy = assertNotNull(store.findPolicy(411))
                assertTrue(store.updatePolicyFromTrustedOwner(
                    policy.copy(version = 3, personalAutoExecution = false),
                    expectedVersion = 2, ownerActorId = "owner:test", reason = "revoked",
                ))
            }
        })
        assertFailsWith<IllegalStateException> {
            GitHubIssueIntake(store, source).pollOnce(target)
        }
        assertNull(store.findWorkflow(411,7))
    }

    @Test fun paginationFiltersPullRequestsAndImportsEachIssueOnce() {
        val store = db()
        allowPersonal(store)
        val firstPage = (1..100).map { issue(number = it, id = 10_000L + it) }
        val pr = mapper.createObjectNode().apply { putObject("pull_request") }
        val secondPage = listOf(issue(101, 10101), pr)
        val receipt = GitHubIssueIntake(store,
            reader(listOf(firstPage, secondPage))).pollOnce(target)
        assertEquals(101, receipt.scanned)
        assertEquals(101, receipt.created)
        assertEquals(101, GitHubIssueIntake(store,
            reader(listOf(firstPage, secondPage))).pollOnce(target).duplicates)
        assertNotNull(store.findPinnedGitHubIssue(411,101))
    }

    @Test fun failedSecondPageNeverWritesPartialResults() {
        val store = db()
        allowPersonal(store)
        val firstPage = (1..100).map { issue(number = it, id = 10_000L + it) }
        val source = reader(listOf(firstPage), hook = { path ->
            if (path.contains("page=2")) throw IOException("Injected GitHub failure")
        })
        assertFailsWith<IOException> { GitHubIssueIntake(store, source).pollOnce(target) }
        assertNull(store.findWorkflow(411, 1))
    }

    @Test fun bodyLengthAndInconsistentDuplicateFailClosed() {
        val store = db()
        allowPersonal(store)
        assertFailsWith<IllegalArgumentException> {
            GitHubIssueIntake(store, reader(listOf(listOf(
                issue(body = "x".repeat(65_537))
            )))).pollOnce(target)
        }
        assertFailsWith<IllegalArgumentException> {
            GitHubIssueIntake(store, reader(listOf(listOf(
                issue(), issue(title = "different title")
            )))).pollOnce(target)
        }
        assertNull(store.findWorkflow(411,7))
    }

    @Test fun legacyWorkflowWithoutFrozenIssueIsBlockedNotSilentlyAdopted() {
        val store = db()
        allowPersonal(store)
        val legacy = store.enqueue(411, 7, "legacy-unpinned")
        assertNull(store.findPinnedGitHubIssue(411,7))
        val imported = GitHubIssueIntake(store, reader(listOf(listOf(issue())))).pollOnce(target)
        assertEquals(1, imported.needsOwner)
        assertEquals("GITHUB_PINNED_SNAPSHOT_MISSING",
            assertNotNull(store.findWorkflow(411,7)).holdReason)
        assertNull(store.findPinnedGitHubIssue(411,7))
        assertEquals("legacy-unpinned", legacy.id)
        assertEquals(2, store.listEvents(legacy.id).size)
    }

    @Test fun disabledPersonalAndUnknownReposDenyBeforeGitHubRead() {
        val store = db()
        store.insertProject(RegisteredProject(
            "disabled-personal", 411, ProjectKind.PERSONAL, directory.toString()
        ), ownerActorId = "owner:test")
        val noNetwork = GitHubIssueReader(GitHubJsonRead {
            error("Denied projects must never reach GitHub")
        })
        assertFailsWith<IllegalStateException> {
            GitHubIssueIntake(store, noNetwork).pollOnce(target)
        }
        assertFailsWith<IllegalArgumentException> {
            GitHubIssueIntake(store, noNetwork)
                .pollOnce(GitHubRepositoryTarget("owner","other",999L))
        }
        assertNull(store.findWorkflow(411,7))
    }

    @Test fun malformedGitHubResponseCannotBeCountedAsSuccess() {
        val store = db()
        allowPersonal(store)
        val badJson = GitHubIssueReader(GitHubJsonRead { "not-json" })
        assertFailsWith<Exception> {
            GitHubIssueIntake(store, badJson).pollOnce(target)
        }
        assertNull(store.findWorkflow(411,7))
    }

    @Test fun collidingStableIdsOnSamePageAreRejectedBeforeAnyWorkflowWrite() {
        val store = db()
        allowPersonal(store)
        assertFailsWith<IllegalArgumentException> {
            GitHubIssueIntake(store, reader(listOf(listOf(
                issue(number = 7, id = 7007), issue(number = 8, id = 7007)
            )))).pollOnce(target)
        }
        assertNull(store.findWorkflow(411, 7))
        assertNull(store.findWorkflow(411, 8))
    }

    @Test fun collidingStableIdsAcrossPagesNeverPartiallyImportFirstPage() {
        val store = db()
        allowPersonal(store)
        val firstPage = (1..100).map { issue(number = it, id = 20_000L + it) }
        val conflictingPage = listOf(issue(number = 101, id = 20_001L))
        assertFailsWith<IllegalArgumentException> {
            GitHubIssueIntake(store, reader(listOf(firstPage, conflictingPage)))
                .pollOnce(target)
        }
        assertNull(store.findWorkflow(411, 1))
        assertNull(store.findWorkflow(411, 101))
    }

    @Test fun identityAlreadyPinnedToAnotherIssueNumberIsRejectedExplicitly() {
        val store = db()
        allowPersonal(store)
        assertEquals(1, GitHubIssueIntake(store, reader(listOf(listOf(issue()))))
            .pollOnce(target).created)
        val original = assertNotNull(store.findPinnedGitHubIssue(411, 7))
        val failure = assertFailsWith<IllegalStateException> {
            GitHubIssueIntake(store, reader(listOf(listOf(issue(number = 8, id = 7007)))))
                .pollOnce(target)
        }
        assertTrue(failure.message.orEmpty().contains("GITHUB_STABLE_ISSUE_ID_CONFLICT"))
        assertEquals(original, store.findPinnedGitHubIssue(411, 7))
        assertNull(store.findWorkflow(411, 8))
    }

    @Test fun sixConcurrentPollersCreateOnePinnedRecord() {
        val store = db()
        allowPersonal(store)
        val record = GitHubIssueRecord(
            repositoryId = 411, issueNumber = 87, githubIssueId = 80087L,
            sourceUpdatedAt = time, title = "race test", body = "bounded",
            baseSha = pinnedSha, open = true,
        )
        val threads = Executors.newFixedThreadPool(6)
        val ready = CountDownLatch(6)
        val release = CountDownLatch(1)
        try {
            val futures = (1..6).map { n ->
                threads.submit<IssueIntakeOutcome> {
                    ready.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    store.acceptGitHubIssue(record, "proposal-$n").outcome
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            release.countDown()
            val outcomes = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it == IssueIntakeOutcome.CREATED })
            assertEquals(5, outcomes.count { it == IssueIntakeOutcome.DUPLICATE })
            val pinned = assertNotNull(db().findPinnedGitHubIssue(411,87))
            assertEquals(2, db().listEvents(pinned.workflowId).size)
        } finally {
            release.countDown()
            threads.shutdownNow()
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
