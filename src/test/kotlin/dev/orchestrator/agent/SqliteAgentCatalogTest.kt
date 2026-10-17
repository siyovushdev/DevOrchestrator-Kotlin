package dev.orchestrator.agent

import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.RegisteredProject
import dev.orchestrator.storage.SqliteTaskStore
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

class SqliteAgentCatalogTest {
    @TempDir lateinit var tmp: Path
    private val actor = "owner:test"
    private fun path() = tmp.resolve("catalog.db")
    private fun store() = SqliteTaskStore(path())
    private fun catalog() = SqliteAgentCatalog(path())

    private fun registerProjects() {
        val db = store()
        db.insertProject(RegisteredProject("alpha", 101, ProjectKind.PERSONAL, tmp.toString()), actor)
        db.insertProject(RegisteredProject("beta", 102, ProjectKind.PERSONAL, tmp.toString()), actor)
        db.insertProject(RegisteredProject("work", 103, ProjectKind.WORK, tmp.toString()), actor)
    }

    private fun manifest(version: Long = 1): AgentCapabilityManifest = AgentCapabilityManifest(
        adapterId = "native-cli", provider = "example-provider", version = version,
        roles = setOf(AgentRole.CODING, AgentRole.REVIEW, AgentRole.PLANNER, AgentRole.CHIEF),
        sandboxes = setOf(AgentSandbox.NATIVE_PERSONAL_WRITE,
            AgentSandbox.NATIVE_PERSONAL_READ_ONLY),
        modelEfforts = mapOf(
            "example-model" to setOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            "review-model" to setOf(ReasoningEffort.DEFAULT, ReasoningEffort.HIGH),
        ),
    )
    private fun profile(
        id: String = "coding-alpha",
        version: Long = 1,
        repo: Long = 101,
        role: AgentRole = AgentRole.CODING,
        model: String = "example-model",
        effort: ReasoningEffort = ReasoningEffort.HIGH,
        sandbox: AgentSandbox = AgentSandbox.NATIVE_PERSONAL_WRITE,
        manifestVersion: Long = 1,
        timeout: Int = 180,
    ) = AgentProfile(
        profileId = id, version = version, repositoryId = repo, role = role,
        adapterId = "native-cli", manifestVersion = manifestVersion,
        provider = "example-provider", model = model, effort = effort,
        sandbox = sandbox, timeoutSeconds = timeout, maxBudgetMicroUsd = 2_000_000,
    )

    @Test fun declaredManifestAndProfileSurviveRestartWithImmutableRoleSnapshot() {
        registerProjects()
        val db = catalog()
        assertTrue(db.declareManifest(manifest(), null, actor))
        val coding = profile()
        assertTrue(db.saveProfile(coding, null, actor))
        assertNull(db.findAssignedProfile(101, AgentRole.CODING)) // no implicit binding
        val first = assertNotNull(db.bindRole(101, AgentRole.CODING,
            coding.profileId, coding.version, null, actor))
        assertEquals(1L, first.assignmentVersion)
        val frozen = assertNotNull(db.findAssignedProfile(101, AgentRole.CODING))
        assertEquals(coding, frozen.profile)
        assertEquals(manifest(), frozen.manifest)
        assertEquals(AgentProfileStatus.DECLARED_UNVERIFIED, frozen.status)

        val revised = coding.copy(version = 2, effort = ReasoningEffort.MEDIUM)
        assertTrue(db.saveProfile(revised, expectedPreviousVersion = 1, ownerActorId = actor))
        assertEquals(coding, assertNotNull(catalog()
            .findAssignedProfile(101, AgentRole.CODING)).profile)
        assertEquals(revised, db.findProfile(coding.profileId))
        assertEquals(coding, db.findProfile(coding.profileId, 1))

        // Owner explicitly switches active role pointer; previous revision remains pinned.
        assertEquals(2L, assertNotNull(db.bindRole(
            101, AgentRole.CODING, coding.profileId, 2, 1, actor)).assignmentVersion)
        assertEquals(revised, assertNotNull(catalog()
            .findAssignedProfile(101, AgentRole.CODING)).profile)
        assertEquals(coding, catalog().findProfile(coding.profileId, 1))
        assertEquals(listOf("MANIFEST_DECLARED","PROFILE_CREATED","ROLE_BOUND",
            "PROFILE_REVISED","ROLE_BOUND"), catalog().listConfigAudit().map { it.action })
    }

    @Test fun undeclaredModelEffortRoleAndSandboxAreRejectedWithoutAudit() {
        registerProjects()
        val db = catalog()
        assertTrue(db.declareManifest(manifest(), null, actor))
        assertFailsWith<IllegalArgumentException> {
            db.saveProfile(profile(model = "not-declared"), null, actor)
        }
        assertFailsWith<IllegalArgumentException> {
            db.saveProfile(profile(effort = ReasoningEffort.XHIGH), null, actor)
        }
        assertFailsWith<IllegalArgumentException> {
            db.saveProfile(profile(sandbox = AgentSandbox.NATIVE_PERSONAL_READ_ONLY), null, actor)
        }
        assertFailsWith<IllegalArgumentException> {
            db.saveProfile(profile(role = AgentRole.REVIEW), null, actor)
        }
        assertFailsWith<IllegalArgumentException> {
            db.saveProfile(profile(repo = 103), null, actor) // WORK always native-denied
        }
        assertFailsWith<IllegalArgumentException> {
            db.saveProfile(profile(timeout = 8), null, actor)
        }
        assertFailsWith<IllegalArgumentException> {
            db.saveProfile(profile().copy(provider = "untrusted-provider"), null, actor)
        }
        assertFailsWith<IllegalArgumentException> {
            db.saveProfile(profile(), null, "")
        }
        assertEquals(listOf("MANIFEST_DECLARED"), db.listConfigAudit().map { it.action })
        assertNull(db.findProfile("coding-alpha"))
    }

    @Test fun independentReviewRoleAndCrossProjectBoundariesCannotBeBypassed() {
        registerProjects()
        val db = catalog()
        db.declareManifest(manifest(), null, actor)
        val coding = profile()
        val review = profile(
            id = "review-alpha", role = AgentRole.REVIEW,
            model = "review-model", effort = ReasoningEffort.DEFAULT,
            sandbox = AgentSandbox.NATIVE_PERSONAL_READ_ONLY,
        )
        db.saveProfile(coding, null, actor)
        db.saveProfile(review, null, actor)
        assertFailsWith<IllegalArgumentException> {
            db.bindRole(102, AgentRole.CODING, coding.profileId, 1, null, actor)
        }
        assertFailsWith<IllegalArgumentException> {
            db.bindRole(101, AgentRole.REVIEW, coding.profileId, 1, null, actor)
        }
        assertFailsWith<IllegalArgumentException> {
            db.bindRole(101, AgentRole.CODING, review.profileId, 1, null, actor)
        }
        assertNull(db.findAssignedProfile(101, AgentRole.CODING))
        assertNull(db.findAssignedProfile(102, AgentRole.CODING))
        assertEquals(review, assertNotNull(db.bindRole(101, AgentRole.REVIEW,
            review.profileId, 1, null, actor)).let {
                db.findAssignedProfile(101, AgentRole.REVIEW)
            }?.profile)
        assertEquals(AgentSandbox.NATIVE_PERSONAL_READ_ONLY,
            assertNotNull(db.findAssignedProfile(101, AgentRole.REVIEW)).profile.sandbox)
    }

    @Test fun manifestsVersionedProviderFrozenAndPreviousProfilesSurviveCatalogEvolution() {
        registerProjects()
        val db = catalog()
        db.declareManifest(manifest(), null, actor)
        db.saveProfile(profile(), null, actor)
        db.bindRole(101, AgentRole.CODING, "coding-alpha", 1, null, actor)

        assertFalse(db.declareManifest(manifest(2), null, actor))
        assertFailsWith<IllegalArgumentException> {
            db.declareManifest(manifest(2).copy(provider = "other-provider"), 1, actor)
        }
        val updated = manifest(2).copy(modelEfforts = mapOf(
            "later-model" to setOf(ReasoningEffort.DEFAULT),
        ))
        assertTrue(db.declareManifest(updated, expectedPreviousVersion = 1, ownerActorId = actor))
        assertEquals(manifest(), db.findManifest("native-cli", 1))
        assertEquals(updated, db.findManifest("native-cli"))
        val pinned = assertNotNull(catalog().findAssignedProfile(101, AgentRole.CODING))
        assertEquals(1L, pinned.manifest.version)
        assertEquals("example-model", pinned.profile.model)
        assertFailsWith<IllegalArgumentException> {
            db.saveProfile(profile(id = "new-alpha", manifestVersion = 2), null, actor)
        }
        assertNull(db.findProfile("new-alpha"))
        assertFalse(db.declareManifest(manifest(2), 1, actor)) // stale expected version
    }

    @Test fun duplicateAndStaleCasAreNoOpsWithoutHistoryAmplification() {
        registerProjects()
        val db = catalog()
        db.declareManifest(manifest(), null, actor)
        val p = profile()
        assertTrue(db.saveProfile(p, null, actor))
        assertFalse(db.saveProfile(p, null, actor))
        assertFalse(db.saveProfile(p.copy(version = 2), 1, actor))
        assertNull(db.findProfile(p.profileId, 2))
        assertNotNull(db.bindRole(101, AgentRole.CODING, p.profileId, 1, null, actor))
        assertNull(db.bindRole(101, AgentRole.CODING, p.profileId, 1, 999, actor))
        assertNotNull(db.bindRole(101, AgentRole.CODING, p.profileId, 1, 1, actor))
        assertEquals(1L, assertNotNull(db.findAssignedProfile(
            101, AgentRole.CODING)).assignment.assignmentVersion)
        assertEquals(3, db.listConfigAudit().size)
    }

    @Test fun concurrentRoleBindingCasOnlyOneWinner() {
        registerProjects()
        val db = catalog()
        db.declareManifest(manifest(), null, actor)
        for (id in listOf("coding-a", "coding-b", "coding-c")) {
            db.saveProfile(profile(id = id), null, actor)
        }
        assertNotNull(db.bindRole(101, AgentRole.CODING, "coding-a", 1, null, actor))
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            val results = listOf("coding-b","coding-c").map { id ->
                pool.submit<AgentRoleAssignment?> {
                    ready.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    db.bindRole(101, AgentRole.CODING, id, 1, 1, actor)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(1, results.count { it.get(15, TimeUnit.SECONDS) != null })
            assertEquals(2L, assertNotNull(db.findAssignedProfile(101,
                AgentRole.CODING)).assignment.assignmentVersion)
            assertEquals(2, db.listConfigAudit().count { it.action == "ROLE_BOUND" })
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun sqlitePreventsMutationOfHistoricalManifestAndProfileRows() {
        registerProjects()
        val db = catalog()
        db.declareManifest(manifest(), null, actor)
        db.saveProfile(profile(), null, actor)
        DriverManager.getConnection("jdbc:sqlite:${path()}").use { conn ->
            assertFailsWith<SQLException> {
                conn.createStatement().use {
                    it.executeUpdate("UPDATE agent_manifest_versions SET capabilities_json='{}'")
                }
            }
            assertFailsWith<SQLException> {
                conn.createStatement().use {
                    it.executeUpdate("DELETE FROM agent_profile_versions")
                }
            }
        }
        assertEquals(manifest(), catalog().findManifest("native-cli", 1))
        assertEquals(profile(), catalog().findProfile("coding-alpha", 1))
    }

    @Test fun completeV3MigrationKeepsPinnedIssueAndPolicyIntact() {
        val path = tmp.resolve("old-v3.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { stmt ->
                val sql = requireNotNull(javaClass.getResource("/sqlite-schema-v3.sql")).readText()
                sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    stmt.execute(it)
                }
                stmt.execute("PRAGMA user_version=3")
                stmt.executeUpdate(
                    "INSERT INTO projects(project_id,repository_id,kind,workspace_root) " +
                        "VALUES ('old',101,'PERSONAL','${tmp.toAbsolutePath()}')",
                )
                stmt.executeUpdate(
                    "INSERT INTO project_policies(repository_id,personal_auto_execution) VALUES (101,1)",
                )
                stmt.executeUpdate(
                    """
                    INSERT INTO workflows(workflow_id,repository_id,issue_number,phase,hold,
                        version,corrections_used,created_at,updated_at)
                    VALUES ('old-workflow',101,7,'QUEUED','NONE',0,0,'t','t')
                    """.trimIndent(),
                )
                stmt.executeUpdate(
                    """
                    INSERT INTO github_issue_snapshots(workflow_id,repository_id,issue_number,
                        github_issue_id,source_updated_at,issue_title,issue_body,
                        issue_body_sha256,base_sha,pinned_at)
                    VALUES ('old-workflow',101,7,7007,'t','frozen','source','hash','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','t')
                    """.trimIndent(),
                )
            }
        }
        val migrated = SqliteTaskStore(path)
        assertEquals(4, migrated.schemaVersion())
        assertEquals("source", assertNotNull(migrated.findPinnedGitHubIssue(101,7)).body)
        assertTrue(assertNotNull(migrated.findPolicy(101)).personalAutoExecution)
        val agent = SqliteAgentCatalog(path)
        assertNull(agent.findManifest("native-cli"))
        assertEquals(emptyList(), agent.listConfigAudit())
        // Migrations are idempotent and do not clear previously pinned source.
        assertEquals("source", assertNotNull(SqliteTaskStore(path)
            .findPinnedGitHubIssue(101,7)).body)
    }
}
