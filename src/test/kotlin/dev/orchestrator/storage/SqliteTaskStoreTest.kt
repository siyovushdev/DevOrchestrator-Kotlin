package dev.orchestrator.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteTaskStoreTest {
    @TempDir
    lateinit var directory: Path

    private fun createStore() = SqliteTaskStore(directory.resolve("state.db"))

    private fun register(store: SqliteTaskStore) {
        store.insertProject(
            RegisteredProject(
                id = "personal-demo",
                repositoryId = 411,
                kind = ProjectKind.PERSONAL,
                workspaceRoot = directory.toString(),
            ),
            ownerActorId = "owner:test",
        )
    }

    @Test
    fun migrationsAreIdempotentAndPreserveDataOnRestart() {
        val first = createStore()
        assertEquals(4, first.schemaVersion())
        register(first)

        val created = first.enqueue(411, 21, "workflow-1")
        assertEquals(WorkflowPhase.QUEUED, created.phase)
        assertEquals(WorkflowHold.NONE, created.hold)
        assertEquals(0L, created.version)

        val reopened = createStore()
        assertEquals(4, reopened.schemaVersion())
        assertEquals(created, reopened.findWorkflow(411, 21))
        assertEquals(listOf("WORKFLOW_QUEUED"), reopened.listEvents(created.id).map { it.type })
    }

    @Test
    fun duplicateGithubIssueReturnsSameWorkflowWithoutExtraEvents() {
        val store = createStore()
        register(store)

        val first = store.enqueue(411, 33, "original-id")
        val second = store.enqueue(411, 33, "duplicate-should-not-be-stored")
        assertEquals(first, second)
        assertEquals("original-id", second.id)
        assertEquals(1, store.listEvents(second.id).size)
    }

    @Test
    fun concurrentPollersSerializeBeforeReadingAndCreateOnlyOneIssue() {
        val store = createStore()
        register(store)

        val workers = 6
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val release = CountDownLatch(1)

        try {
            val results = (1..workers).map { index ->
                executor.submit<WorkflowSnapshot> {
                    ready.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    store.enqueue(411, 55, "candidate-$index")
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            release.countDown()

            val snapshots = results.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, snapshots.map { it.id }.toSet().size)
            assertEquals(1, store.listEvents(snapshots.first().id).size)
            assertEquals(1, snapshots.map { it.version }.toSet().size)

            // Reopen to confirm transaction integrity survives process recreation.
            assertEquals(snapshots.first(), createStore().findWorkflow(411, 55))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun unknownRepositoryIsDeniedBeforeAnyWorkflowWrite() {
        val store = createStore()
        assertFailsWith<IllegalArgumentException> {
            store.enqueue(999, 12, "unregistered-issue")
        }
        assertNull(store.findWorkflow(999, 12))
    }

    @Test
    fun needsOwnerHoldUsesCasAndSurvivesRestart() {
        val store = createStore()
        register(store)
        val task = store.enqueue(411, 8, "owner-hold")

        assertTrue(store.markNeedsOwner(task.id, expectedVersion = 0, reason = "unconfirmed push"))
        assertFalse(store.markNeedsOwner(task.id, expectedVersion = 0, reason = "stale caller"))
        assertFalse(store.markNeedsOwner(task.id, expectedVersion = 1, reason = "duplicate hold"))

        val reopened = createStore()
        val persisted = assertNotNull(reopened.findWorkflow(411, 8))
        assertEquals(WorkflowPhase.QUEUED, persisted.phase)
        assertEquals(WorkflowHold.NEEDS_OWNER, persisted.hold)
        assertEquals("unconfirmed push", persisted.holdReason)
        assertEquals(1L, persisted.version)
        assertEquals(listOf("WORKFLOW_QUEUED", "NEEDS_OWNER"), reopened.listEvents(task.id).map { it.type })
    }

    @Test
    fun sqliteConstraintsRejectBadStateAndOrphanAttempt() {
        val store = createStore()
        register(store)
        store.enqueue(411, 77, "persisted")
        val db = directory.resolve("state.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }

            assertFailsWith<SQLException> {
                connection.createStatement().use {
                    it.executeUpdate("UPDATE workflows SET phase = 'BYPASS' WHERE workflow_id = 'persisted'")
                }
            }
            assertFailsWith<SQLException> {
                connection.createStatement().use {
                    it.executeUpdate(
                        """
                        INSERT INTO agent_attempts(
                            attempt_id, workflow_id, role, generation, state, recorded_at
                        ) VALUES ('orphan', 'unknown-workflow', 'CODING', 0, 'PLANNED', 'now')
                        """.trimIndent(),
                    )
                }
            }
            assertFailsWith<SQLException> {
                connection.createStatement().use {
                    it.executeUpdate(
                        """
                        INSERT INTO effects_outbox(
                            effect_id, workflow_id, kind, state, idempotency_key,
                            intended_target, recorded_at
                        ) VALUES ('orphan-effect', 'missing-workflow', 'PUSH',
                                  'PREPARED', 'unique', 'branch', 'now')
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    @Test
    fun futureSchemaVersionIsRefusedWithoutDowngrading() {
        createStore()
        val path = directory.resolve("state.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { it.execute("PRAGMA user_version = 200") }
        }
        val failure = assertFailsWith<IllegalStateException> { createStore() }
        assertTrue(failure.message.orEmpty().contains("newer"))
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use {
                it.executeQuery("PRAGMA user_version").use { result ->
                    assertTrue(result.next())
                    assertEquals(200, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun projectIdentityCannotBeReassignedSilently() {
        val store = createStore()
        register(store)
        assertFailsWith<SQLException> {
            store.insertProject(
                RegisteredProject("other-name", 411, ProjectKind.WORK, directory.toString()),
                ownerActorId = "owner:test",
            )
        }
        assertNotNull(store.enqueue(411, 1, "one-workflow"))
    }
}
