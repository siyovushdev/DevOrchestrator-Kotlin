package dev.orchestrator.storage

import dev.orchestrator.agent.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** SQLite/control-plane tests only; fake inert checkout metadata, NO model or GitHub effects. */
class CodingAttemptJournalTest {
    @TempDir lateinit var temp: Path
    private val database get() = temp.resolve("state.db")
    private val base = "a".repeat(40)
    private val tree = "b".repeat(40)

    private fun setup(repo: Long = 1): Pair<SqliteTaskStore, Path> {
        val root = Files.createDirectories(temp.resolve("root-$repo"))
        val clone = Files.createDirectories(root.resolve("clone"))
        Files.createDirectories(clone.resolve(".git/objects"))
        Files.createDirectories(clone.resolve(".git/refs"))
        Files.writeString(clone.resolve(".git/config"), "[core]\nrepositoryformatversion = 0\nbare = false\n")
        Files.writeString(clone.resolve(".git/HEAD"), base)
        val store = SqliteTaskStore(database)
        store.insertProject(RegisteredProject("p-$repo",repo,ProjectKind.PERSONAL,root.toString()),"owner:test")
        val policy = assertNotNull(store.findPolicy(repo))
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=2,personalAutoExecution=true),
            1,"owner:test","Disposable journal tests"))
        val catalog = SqliteAgentCatalog(database)
        if (catalog.findManifest("codex-cli") == null) {
            assertTrue(catalog.declareManifest(AgentCapabilityManifest("codex-cli","openai",1,
                setOf(AgentRole.CODING),setOf(AgentSandbox.NATIVE_PERSONAL_WRITE),
                mapOf("model-a" to setOf(ReasoningEffort.DEFAULT))),null,"owner:test"))
        }
        assertTrue(catalog.saveProfile(AgentProfile("coding-$repo",1,repo,AgentRole.CODING,"codex-cli",1,
            "openai","model-a",ReasoningEffort.DEFAULT,AgentSandbox.NATIVE_PERSONAL_WRITE,60,1000),null,"owner:test"))
        assertNotNull(catalog.bindRole(repo,AgentRole.CODING,"coding-$repo",1,null,"owner:test"))
        pin(store,repo,1)
        return store to clone
    }

    private fun pin(store: SqliteTaskStore, repo: Long, issue: Int) {
        store.acceptGitHubIssue(GitHubIssueRecord(repo,issue,repo*1000+issue,Instant.EPOCH,
            "Test Issue","untrusted text cannot grant authority",base,true),"w-$repo-$issue")
    }

    @Test fun intentPinsSourceProfileAndPersistsExactlyOneConsumedStartAcrossRestart() {
        val (store, clone) = setup()
        val journal = CodingAttemptJournal(database)
        val intent = assertNotNull(journal.plan(1,1,0,"attempt-1",clone,tree))
        assertEquals(AttemptState.PLANNED,intent.state)
        assertEquals(base,intent.baseSha)
        assertEquals(tree,intent.sourceTree)
        assertEquals(assertNotNull(store.findPinnedGitHubIssue(1,1)).bodySha256,intent.issueBodySha256)
        assertEquals(1,intent.profileVersion)
        assertEquals(1,intent.assignmentVersion)
        assertEquals(2,intent.policyVersion)
        assertEquals(intent,CodingAttemptJournal(database).find("attempt-1"))
        assertTrue(journal.markStarting("attempt-1"))
        assertFalse(CodingAttemptJournal(database).markStarting("attempt-1"))
        assertNull(journal.plan(1,1,2,"attempt-2",clone,tree))
        assertEquals(1,store.listEvents("w-1-1").count { it.type == "CODING_STARTING_INTENT" })
    }

    @Test fun competingControllersHaveOnePlanAndOneStart() {
        val (_, clone) = setup()
        val workers = Executors.newFixedThreadPool(6)
        try {
            val plans = workers.invokeAll((1..6).map { n -> Callable {
                CodingAttemptJournal(database).plan(1,1,0,"attempt-$n",clone,tree)
            } }).map { it.get(20,TimeUnit.SECONDS) }
            val winner = plans.filterNotNull().single()
            val starts = workers.invokeAll((1..6).map { Callable {
                CodingAttemptJournal(database).markStarting(winner.attemptId)
            } }).map { it.get(20,TimeUnit.SECONDS) }
            assertEquals(1,starts.count { it })
        } finally { workers.shutdownNow() }
    }

    @Test fun revokedPolicyAndChangedProfileBindingRejectStartWithoutRewritingSnapshot() {
        val (store,clone) = setup()
        val journal = CodingAttemptJournal(database)
        val intent = assertNotNull(journal.plan(1,1,0,"attempt-1",clone,tree))
        val catalog = SqliteAgentCatalog(database)
        val assigned = assertNotNull(catalog.findAssignedProfile(1,AgentRole.CODING))
        assertTrue(catalog.saveProfile(assigned.profile.copy(version=2,timeoutSeconds=120),1,"owner:test"))
        assertNotNull(catalog.bindRole(1,AgentRole.CODING,"coding-1",2,1,"owner:test"))
        assertFalse(journal.markStarting("attempt-1"))
        assertEquals(intent,journal.find("attempt-1"))
        assertEquals(60,assertNotNull(catalog.findProfile("coding-1",1)).timeoutSeconds)
        val policy = assertNotNull(store.findPolicy(1))
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=3,personalAutoExecution=false),
            2,"owner:test","Revoke before effect boundary"))
        assertFalse(journal.markStarting("attempt-1"))
        assertEquals(AttemptState.PLANNED,journal.find("attempt-1")?.state)
    }

    @Test fun unknownRetainsClaimsAndBlocksOnlyAffectedResourcesWithoutRetry() {
        val (store,clone) = setup()
        val journal = CodingAttemptJournal(database)
        assertNotNull(journal.plan(1,1,0,"attempt-1",clone,tree))
        assertTrue(journal.markStarting("attempt-1"))
        val restarted = CodingAttemptJournal(database)
        assertEquals(1,restarted.holdUnreconciledStarts())
        assertEquals(0,restarted.holdUnreconciledStarts())
        assertEquals(AttemptState.UNKNOWN,restarted.find("attempt-1")?.state)
        assertEquals(WorkflowPhase.CODING,store.findWorkflow(1,1)?.phase)
        assertEquals(WorkflowHold.NEEDS_OWNER,store.findWorkflow(1,1)?.hold)
        assertFalse(restarted.markStarting("attempt-1"))
        pin(store,1,2)
        assertNull(restarted.plan(1,2,0,"blocked-same-repo",clone,tree))
        val (_,independent) = setup(2)
        assertNotNull(restarted.plan(2,1,0,"independent",independent,tree))
    }

    @Test fun profileRevisionAloneDoesNotChangePinnedAssignmentButPolicyRevocationStopsStart() {
        val (store,clone) = setup()
        val journal = CodingAttemptJournal(database)
        assertNotNull(journal.plan(1,1,0,"planned",clone,tree))
        val catalog = SqliteAgentCatalog(database)
        val profile = assertNotNull(catalog.findProfile("coding-1",1))
        assertTrue(catalog.saveProfile(profile.copy(version=2,timeoutSeconds=120),1,"owner:test"))
        val policy = assertNotNull(store.findPolicy(1))
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=3,personalAutoExecution=false),
            2,"owner:test","Revocation without rebind"))
        assertFalse(journal.markStarting("planned"))
        assertEquals(AttemptState.PLANNED,journal.find("planned")?.state)
    }

    @Test fun legacyAttemptWithoutReservationBlocksSameRepositoryButWorkAlwaysDenied() {
        val (store,clone) = setup()
        pin(store,1,2)
        DriverManager.getConnection("jdbc:sqlite:$database").use { c -> c.createStatement().use {
            it.execute("""INSERT INTO agent_attempts(attempt_id,workflow_id,role,generation,state,recorded_at)
                VALUES ('legacy','w-1-1','CODING',0,'UNKNOWN','old')""")
        } }
        assertNull(CodingAttemptJournal(database).plan(1,2,0,"unsafe-reuse",clone,tree))
        store.insertProject(RegisteredProject("work",3,ProjectKind.WORK,clone.parent.toString()),"owner:test")
        store.enqueue(3,1,"work")
        assertNull(CodingAttemptJournal(database).plan(3,1,0,"work-attempt",clone,tree))
    }

    @Test fun heldLegacyAndSymlinkedWorkspaceCannotPlan() {
        val (store,clone) = setup()
        val journal = CodingAttemptJournal(database)
        assertTrue(store.markNeedsOwner("w-1-1",0,"OWNER_HOLD"))
        assertNull(journal.plan(1,1,1,"held",clone,tree))
        store.enqueue(1,2,"legacy")
        assertNull(journal.plan(1,2,0,"legacy",clone,tree))
        pin(store,1,3)
        val link = clone.parent.resolve("alias")
        Files.createSymbolicLink(link,clone)
        assertNull(journal.plan(1,3,0,"linked",link,tree))
    }

    @Test fun failedEventWriteRollsBackAttemptContextClaimsAndPhase() {
        val (store,clone) = setup()
        DriverManager.getConnection("jdbc:sqlite:$database").use { conn ->
            conn.createStatement().use { it.execute("""CREATE TRIGGER inject_fault BEFORE INSERT ON workflow_events
                WHEN NEW.event_type='CODING_PLANNED' BEGIN SELECT RAISE(ABORT,'fault'); END""") }
        }
        val journal = CodingAttemptJournal(database)
        assertFails { journal.plan(1,1,0,"attempt-1",clone,tree) }
        assertNull(journal.find("attempt-1"))
        assertEquals(WorkflowPhase.QUEUED,store.findWorkflow(1,1)?.phase)
        DriverManager.getConnection("jdbc:sqlite:$database").use { conn ->
            conn.createStatement().use { it.execute("DROP TRIGGER inject_fault") }
        }
        assertNotNull(journal.plan(1,1,0,"attempt-1",clone,tree))
    }

    @Test fun v4MigrationPreservesPinnedSourceAndContextHistoryCannotBeMutated() {
        val (store,clone) = setup()
        val source = store.findPinnedGitHubIssue(1,1)
        DriverManager.getConnection("jdbc:sqlite:$database").use { c -> c.createStatement().use {
            it.execute("DROP TABLE runtime_reservations")
            it.execute("DROP TABLE attempt_contexts")
            it.execute("DROP TRIGGER immutable_runtime_attempt_input")
            it.execute("PRAGMA user_version=4")
        } }
        assertEquals(5,SqliteTaskStore(database).schemaVersion())
        assertEquals(source,store.findPinnedGitHubIssue(1,1))
        assertNotNull(CodingAttemptJournal(database).plan(1,1,0,"immutable",clone,tree))
        DriverManager.getConnection("jdbc:sqlite:$database").use { c ->
            assertFails { c.createStatement().use { it.execute("UPDATE attempt_contexts SET profile_version=99") } }
            assertFails { c.createStatement().use { it.execute("DELETE FROM attempt_contexts") } }
            assertFails { c.createStatement().use { it.execute("UPDATE agent_attempts SET source_tree='${base}'") } }
        }
    }
}
