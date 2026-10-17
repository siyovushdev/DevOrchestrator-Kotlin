package dev.orchestrator.policy

import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.RegisteredProject
import dev.orchestrator.storage.SqliteTaskStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectPolicyPersistenceTest {
    @TempDir lateinit var tmp: Path

    private fun store() = SqliteTaskStore(tmp.resolve("project.db"))
    private val actor = "owner:test"

    @Test fun ownerPolicyDefaultsDenyAndHasVerifiableAuditAfterRestart() {
        val db = store()
        db.insertProject(
            RegisteredProject("personal", 41, ProjectKind.PERSONAL, tmp.toString()), actor
        )
        val initial = assertNotNull(db.findPolicy(41))
        assertFalse(initial.personalAutoExecution)
        assertEquals(1L, initial.version)
        assertEquals(listOf(1L), db.policyVersions(41))

        assertTrue(db.updatePolicyFromTrustedOwner(
            initial.copy(personalAutoExecution = true, version = 2),
            expectedVersion = 1, ownerActorId = actor, reason = "Test repo approved",
        ))
        // Replayed/stale requests and no-op writes must not advance the version/audit.
        assertFalse(db.updatePolicyFromTrustedOwner(
            initial.copy(version = 2), 1, actor, "stale disable",
        ))
        val current = assertNotNull(store().findPolicy(41))
        assertFalse(db.updatePolicyFromTrustedOwner(
            current.copy(version = 3), 2, actor, "no-op",
        ))
        assertFailsWith<IllegalArgumentException> {
            db.updatePolicyFromTrustedOwner(
                current.copy(personalAutoExecution = false, version = 3), 2, "", "missing actor",
            )
        }
        assertEquals(2L, current.version)
        assertTrue(current.personalAutoExecution)
        assertEquals(listOf(1L, 2L), db.policyVersions(41))

        val history = store().policyHistory(41)
        assertEquals(2, history.size)
        assertEquals("REGISTERED", history[0].eventKind)
        assertEquals(actor, history[0].actorId)
        assertEquals(null, history[0].beforePersonalAuto)
        assertFalse(history[0].afterPersonalAuto)
        assertEquals("UPDATED", history[1].eventKind)
        assertEquals("Test repo approved", history[1].reason)
        assertEquals(false, history[1].beforePersonalAuto)
        assertEquals(true, history[1].afterPersonalAuto)
        assertEquals(actor, history[1].actorId)
        assertEquals(current, store().findPolicy(41))
    }

    @Test fun workRequiresTaskScopedOwnerGoRatherThanGlobalProjectApproval() {
        val db = store()
        db.insertProject(RegisteredProject("work", 42, ProjectKind.WORK, tmp.toString()), actor)
        val work = assertNotNull(db.findPolicy(42))
        assertFalse(work.personalAutoExecution)
        assertFalse(db.updatePolicyFromTrustedOwner(work.copy(version = 2), 1, actor, "no-op"))
        assertEquals(listOf(1L), db.policyVersions(42))
        val registry = ProjectRegistry(store())
        assertEquals(PolicyDecision.Allowed,
            registry.evaluate(42, tmp.resolve("repo"), ProjectAction.INTAKE))
        assertEquals(PolicyDecision.Denied(PolicyDenial.WORK_EXECUTION_NEEDS_OWNER),
            registry.evaluate(42, tmp.resolve("repo"), ProjectAction.EXECUTE))
        assertEquals(PolicyDecision.Denied(PolicyDenial.WORK_PUBLICATION_NEEDS_OWNER),
            registry.evaluate(42, tmp.resolve("repo"), ProjectAction.PUBLISH_DRAFT))
    }

    @Test fun policyRegistryReadsOnlyAuthoritativeSqliteSnapshot() {
        val db = store()
        db.insertProject(RegisteredProject("personal", 45, ProjectKind.PERSONAL, tmp.toString()), actor)
        val disabled = assertNotNull(db.findPolicy(45))
        // Fabricating an allowed snapshot cannot authorize a command through registry.
        val forgedSnapshot = disabled.copy(personalAutoExecution = true)
        assertEquals(PolicyDecision.Allowed,
            ProjectPolicyEvaluator().evaluate(listOf(forgedSnapshot), 45,
                tmp.resolve("repo"), ProjectAction.EXECUTE))
        assertEquals(PolicyDecision.Denied(PolicyDenial.PERSONAL_AUTO_DISABLED),
            ProjectRegistry(db).evaluate(45, tmp.resolve("repo"), ProjectAction.EXECUTE))
    }

    @Test fun fullLegacyV1DatabaseMigratesWithoutLostWorkflowAndDefaultsDeny() {
        val path = tmp.resolve("legacy.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { statement ->
                val fixture = requireNotNull(javaClass.getResource("/sqlite-schema-v1.sql"))
                    .readText()
                fixture.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    statement.execute(it)
                }
                statement.execute("PRAGMA user_version = 1")
            }
            conn.prepareStatement(
                "INSERT INTO projects(project_id,repository_id,kind,workspace_root) VALUES (?,?,?,?)"
            ).use {
                it.setString(1, "legacy"); it.setLong(2, 99)
                it.setString(3, "PERSONAL"); it.setString(4, tmp.toString())
                it.executeUpdate()
            }
            conn.createStatement().use {
                it.executeUpdate(
                    """
                    INSERT INTO workflows(workflow_id,repository_id,issue_number,phase,hold,
                                          version,corrections_used,created_at,updated_at)
                    VALUES ('legacy-task',99,17,'QUEUED','NONE',0,0,'2026-01-01','2026-01-01')
                    """.trimIndent()
                )
                it.executeUpdate(
                    """
                    INSERT INTO workflow_events(workflow_id,event_type,detail,recorded_at)
                    VALUES ('legacy-task','WORKFLOW_QUEUED',NULL,'2026-01-01')
                    """.trimIndent()
                )
            }
        }
        val migrated = SqliteTaskStore(path)
        assertEquals(3, migrated.schemaVersion())
        val project = assertNotNull(migrated.findPolicy(99))
        assertEquals("legacy", project.projectId)
        assertFalse(project.personalAutoExecution)
        assertEquals(1L, project.version)
        assertEquals("legacy-task", assertNotNull(migrated.findWorkflow(99,17)).id)
        assertEquals(listOf("WORKFLOW_QUEUED"),
            migrated.listEvents("legacy-task").map { it.type })
        assertEquals(listOf(1L), migrated.policyVersions(99))
        val record = migrated.policyHistory(99).single()
        assertEquals("MIGRATED", record.eventKind)
        assertEquals("system:migration", record.actorId)
        assertEquals(null, record.beforePersonalAuto)
        assertFalse(record.afterPersonalAuto)
        assertEquals(project, SqliteTaskStore(path).findPolicy(99))
    }

    @Test fun corruptProjectWorkspaceDeniesThroughAuthoritativeRegistry() {
        val db = store()
        db.insertProject(RegisteredProject("personal", 55, ProjectKind.PERSONAL, tmp.toString()), actor)
        DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("project.db")}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("UPDATE projects SET workspace_root = 'relative/root' WHERE repository_id = 55")
            }
        }
        assertEquals(PolicyDecision.Denied(PolicyDenial.INVALID_PROJECT_POLICY),
            ProjectRegistry(db).evaluate(55, tmp.resolve("repo"), ProjectAction.EXECUTE))
    }

    @Test fun concurrentOwnerCasRecordsExactlyOnePolicyChange() {
        val db = store()
        db.insertProject(RegisteredProject("personal", 58, ProjectKind.PERSONAL, tmp.toString()), actor)
        val initial = assertNotNull(db.findPolicy(58))
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = (1..2).map {
                pool.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    db.updatePolicyFromTrustedOwner(
                        initial.copy(version = 2, personalAutoExecution = true),
                        1, actor, "concurrent change"
                    )
                }
            }
            start.countDown()
            assertEquals(1, futures.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(listOf(1L, 2L), db.policyVersions(58))
            assertTrue(assertNotNull(store().findPolicy(58)).personalAutoExecution)
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
