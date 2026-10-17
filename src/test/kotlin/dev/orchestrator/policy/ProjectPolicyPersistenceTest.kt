package dev.orchestrator.policy

import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.RegisteredProject
import dev.orchestrator.storage.SqliteTaskStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectPolicyPersistenceTest {
    @TempDir lateinit var tmp: Path
    private fun store() = SqliteTaskStore(tmp.resolve("project.db"))
    @Test fun ownerPolicyDefaultsToDenyAndPersistsAfterRestart() {
        val db = store()
        db.insertProject(RegisteredProject("personal", 41, ProjectKind.PERSONAL, tmp.toString()))
        val initial = assertNotNull(db.findPolicy(41))
        assertFalse(initial.personalAutoExecution)
        assertEquals(1L, initial.version)
        assertTrue(db.updatePolicyFromTrustedOwner(initial.copy(personalAutoExecution = true, version = 2), 1))
        assertFailsWith<IllegalArgumentException> { db.updatePolicyFromTrustedOwner(initial, 1) }
        assertFalse(db.updatePolicyFromTrustedOwner(initial.copy(version = 2), 1))
        val next = assertNotNull(store().findPolicy(41))
        assertEquals(2L, next.version)
        assertTrue(next.personalAutoExecution)
        assertEquals(listOf(2L), store().policyVersions(41))
    }

    @Test fun workNeverInheritsPersonalPermissions() {
        val db = store()
        db.insertProject(RegisteredProject("work", 42, ProjectKind.WORK, tmp.toString()))
        val work = assertNotNull(db.findPolicy(42))
        assertFalse(work.workOwnerExecutionApproved)
        assertFalse(work.workOwnerPublicationApproved)
        assertTrue(db.updatePolicyFromTrustedOwner(work.copy(workOwnerExecutionApproved = true, version = 2), 1))
        val updated = assertNotNull(store().findPolicy(42))
        assertTrue(updated.workOwnerExecutionApproved)
        assertFalse(updated.workOwnerPublicationApproved)
        assertEquals(PolicyDecision.Denied(PolicyDenial.WORK_PUBLICATION_NEEDS_OWNER),
            ProjectPolicyEvaluator().evaluate(listOf(updated), 42, tmp.resolve("repo"), ProjectAction.PUBLISH_DRAFT))
    }
    @Test fun legacyV1RegistryMigratesToDenyWithPreservedIdentity() {
        val path = tmp.resolve("legacy.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { sql ->
                sql.execute(
                    """
                    CREATE TABLE projects(
                        project_id TEXT PRIMARY KEY NOT NULL,
                        repository_id INTEGER UNIQUE NOT NULL,
                        kind TEXT NOT NULL,
                        workspace_root TEXT NOT NULL,
                        policy_version INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                sql.executeUpdate(
                    "INSERT INTO projects(project_id,repository_id,kind,workspace_root) " +
                    "VALUES ('legacy', 99, 'PERSONAL', '${tmp.toAbsolutePath()}')"
                )
                sql.execute("PRAGMA user_version = 1")
            }
        }
        val migrated = SqliteTaskStore(path)
        assertEquals(2, migrated.schemaVersion())
        val policy = assertNotNull(migrated.findPolicy(99))
        assertEquals("legacy", policy.projectId)
        assertFalse(policy.personalAutoExecution)
        assertEquals(1L, policy.version)
        assertEquals(emptyList(), migrated.policyVersions(99))
        assertEquals(policy, SqliteTaskStore(path).findPolicy(99))
    }

}
