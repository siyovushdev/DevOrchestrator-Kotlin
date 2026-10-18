package dev.orchestrator.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Direct SQL arranges MOCK completed runtime; journal cannot create such evidence itself. */
class PublicationJournalTest {
    @TempDir lateinit var temp: Path
    private val database get() = temp.resolve("state.db")
    private val plan get() = PublicationPlan("workflow",1,1,"owner","disposable","main",
        "a".repeat(40),"c".repeat(40),"b".repeat(40),"d".repeat(64),"candidate",
        "coding","review",0,2,5)

    private fun sql(vararg statements: String) = DriverManager.getConnection("jdbc:sqlite:$database").use { c ->
        c.createStatement().use { s -> statements.forEach { s.execute(it) } }
    }

    private fun count(table: String): Int = DriverManager.getConnection("jdbc:sqlite:$database").use { c ->
        c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $table").use { r -> r.next(); r.getInt(1) } }
    }

    private fun setup(): Pair<SqliteTaskStore,PublicationJournal> {
        val store = SqliteTaskStore(database)
        store.insertProject(RegisteredProject("personal",1,ProjectKind.PERSONAL,temp.toString()),"owner:test")
        val policy = assertNotNull(store.findPolicy(1))
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=2,personalAutoExecution=true),
            1,"owner:test","Disposable offline fixture"))
        store.acceptGitHubIssue(GitHubIssueRecord(1,1,1001,Instant.EPOCH,"Test","Task",plan.baseSha,true),"workflow")
        sql("UPDATE workflows SET phase='READY_FOR_PR',version=5 WHERE workflow_id='workflow'",
            """INSERT INTO agent_attempts(attempt_id,workflow_id,role,generation,state,source_tree,recorded_at)
                VALUES ('coding','workflow','CODING',0,'SUCCEEDED','${plan.baseSha}','mock')""",
            """INSERT INTO agent_attempts(attempt_id,workflow_id,role,generation,state,source_tree,recorded_at)
                VALUES ('review','workflow','REVIEW',0,'SUCCEEDED','${plan.candidateTree}','mock')""")
        return store to PublicationJournal(database)
    }

    private fun publishing() = sql("UPDATE workflows SET phase='PUBLISHING',version=6 WHERE workflow_id='workflow'")
    private fun commit() = PublicationReadBack.Commit(plan.commitSha,plan.candidateTree,listOf(plan.baseSha))
    private fun push() = PublicationReadBack.Push(1,"owner/disposable",plan.headBranch,plan.commitSha)
    private fun pr() = DraftPullRequest(42,"https://github.com/owner/disposable/pull/42",1,
        "owner/disposable",1,plan.headBranch,plan.commitSha,"main",plan.baseSha,true)
    private fun confirmedThroughPush(j: PublicationJournal) {
        assertTrue(j.prepare(plan)); publishing()
        assertTrue(j.markSending("workflow",EffectKind.COMMIT,plan.candidateManifestSha256))
        assertTrue(j.confirm("workflow",EffectKind.COMMIT,commit()))
        assertTrue(j.markSending("workflow",EffectKind.PUSH,plan.candidateManifestSha256))
        assertTrue(j.confirm("workflow",EffectKind.PUSH,push()))
    }

    @Test fun durableSequenceConfirmsExactReceiptsOnceButDoesNotClaimWorkflowSuccess() {
        val (store,j) = setup()
        assertTrue(j.prepare(plan))
        assertTrue(j.prepare(plan))
        assertFalse(j.prepare(plan.copy(commitSha="f".repeat(40))))
        assertEquals(3,count("effects_outbox"))
        assertFalse(j.markSending("workflow",EffectKind.COMMIT,plan.candidateManifestSha256))
        publishing()
        assertFalse(j.markSending("workflow",EffectKind.PUSH,plan.candidateManifestSha256))
        assertTrue(j.markSending("workflow",EffectKind.COMMIT,plan.candidateManifestSha256))
        assertFalse(j.markSending("workflow",EffectKind.COMMIT,plan.candidateManifestSha256))
        assertTrue(j.confirm("workflow",EffectKind.COMMIT,commit()))
        assertTrue(j.confirm("workflow",EffectKind.COMMIT,commit()))
        assertTrue(j.markSending("workflow",EffectKind.PUSH,plan.candidateManifestSha256))
        assertTrue(j.confirm("workflow",EffectKind.PUSH,push()))
        assertTrue(j.markSending("workflow",EffectKind.DRAFT_PR,plan.candidateManifestSha256))
        assertTrue(j.confirm("workflow",EffectKind.DRAFT_PR,PublicationReadBack.Drafts(listOf(pr()))))
        assertEquals(3,count("publication_receipts"))
        assertEquals(plan,PublicationJournal(database).findPlan("workflow"))
        assertEquals(WorkflowPhase.PUBLISHING,store.findWorkflow(1,1)?.phase)
        assertFalse(j.markSending("workflow",EffectKind.DRAFT_PR,plan.candidateManifestSha256))
    }

    @Test fun concurrentPrepareIsIdempotentAndOnlyOneControllerConsumesSendIntent() {
        setup()
        val workers = Executors.newFixedThreadPool(6)
        try {
            val prepared = workers.invokeAll((1..6).map { Callable { PublicationJournal(database).prepare(plan) } })
                .map { it.get(20,TimeUnit.SECONDS) }
            assertTrue(prepared.all { it })
            assertEquals(3,count("effects_outbox"))
            publishing()
            val starts = workers.invokeAll((1..6).map { Callable {
                PublicationJournal(database).markSending("workflow",EffectKind.COMMIT,plan.candidateManifestSha256)
            } }).map { it.get(20,TimeUnit.SECONDS) }
            assertEquals(1,starts.count { it })
        } finally { workers.shutdownNow() }
    }

    @Test fun revokeOrManifestMismatchPreventsConsumingSendIntent() {
        val (store,j) = setup()
        assertTrue(j.prepare(plan)); publishing()
        assertFalse(j.markSending("workflow",EffectKind.COMMIT,"0".repeat(64)))
        val policy = assertNotNull(store.findPolicy(1))
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=3,personalAutoExecution=false),
            2,"owner:test","Revoke publish authority"))
        assertFalse(j.markSending("workflow",EffectKind.COMMIT,plan.candidateManifestSha256))
        assertEquals(EffectState.PREPARED,j.state("workflow",EffectKind.COMMIT))
    }

    @Test fun wrongCommitPushAndAmbiguousOrNonDraftPrReceiptsCannotConfirm() {
        val (_,j) = setup()
        assertTrue(j.prepare(plan)); publishing()
        assertFalse(j.confirm("workflow",EffectKind.COMMIT,commit()))
        assertTrue(j.markSending("workflow",EffectKind.COMMIT,plan.candidateManifestSha256))
        assertFalse(j.confirm("workflow",EffectKind.COMMIT,commit().copy(tree="e".repeat(40))))
        assertFalse(j.confirm("workflow",EffectKind.COMMIT,commit().copy(parents=listOf("e".repeat(40)))))
        assertTrue(j.confirm("workflow",EffectKind.COMMIT,commit()))
        assertTrue(j.markSending("workflow",EffectKind.PUSH,plan.candidateManifestSha256))
        assertFalse(j.confirm("workflow",EffectKind.PUSH,push().copy(repositoryId=2)))
        assertFalse(j.confirm("workflow",EffectKind.PUSH,push().copy(branch="other")))
        assertTrue(j.confirm("workflow",EffectKind.PUSH,push()))
        assertTrue(j.markSending("workflow",EffectKind.DRAFT_PR,plan.candidateManifestSha256))
        for (bad in listOf(pr().copy(draft=false),pr().copy(baseSha="e".repeat(40)),
            pr().copy(headRepositoryId=2),pr().copy(headSha="e".repeat(40)),
            pr().copy(url="https://example.invalid/pull/42"))) {
            assertFalse(j.confirm("workflow",EffectKind.DRAFT_PR,PublicationReadBack.Drafts(listOf(bad))))
        }
        assertFalse(j.confirm("workflow",EffectKind.DRAFT_PR,PublicationReadBack.Drafts(listOf(pr(),pr().copy(number=43)))))
        assertFalse(j.confirm("workflow",EffectKind.DRAFT_PR,PublicationReadBack.Drafts(emptyList())))
        assertEquals(EffectState.SENDING,j.state("workflow",EffectKind.DRAFT_PR))
    }

    @Test fun restartUnknownDoesNotResendAndReadOnlyConfirmationPreservesOwnerHold() {
        val (store,j) = setup()
        assertTrue(j.prepare(plan)); publishing()
        assertTrue(j.markSending("workflow",EffectKind.COMMIT,plan.candidateManifestSha256))
        val restarted = PublicationJournal(database)
        assertEquals(1,restarted.holdUnreconciledSends())
        assertEquals(0,restarted.holdUnreconciledSends())
        assertEquals(EffectState.UNCERTAIN,restarted.state("workflow",EffectKind.COMMIT))
        assertFalse(restarted.markSending("workflow",EffectKind.COMMIT,plan.candidateManifestSha256))
        assertTrue(restarted.confirm("workflow",EffectKind.COMMIT,commit()))
        assertEquals(WorkflowHold.NEEDS_OWNER,store.findWorkflow(1,1)?.hold)
        assertFalse(restarted.markSending("workflow",EffectKind.PUSH,plan.candidateManifestSha256))
        assertEquals(3,count("effects_outbox"))
    }

    @Test fun unprovenReviewWorkAndLegacyOutboxAreNotReadyToPrepare() {
        val (_,j) = setup()
        sql("UPDATE agent_attempts SET state='UNKNOWN' WHERE attempt_id='review'")
        assertFalse(j.prepare(plan))
        sql("UPDATE agent_attempts SET state='SUCCEEDED' WHERE attempt_id='review'",
            """INSERT INTO effects_outbox(effect_id,workflow_id,kind,state,idempotency_key,intended_target,recorded_at)
                VALUES ('legacy','workflow','PUSH','UNCERTAIN','legacy','old','old')""")
        assertFalse(j.prepare(plan))
        sql("DELETE FROM effects_outbox", "UPDATE projects SET kind='WORK' WHERE repository_id=1")
        assertFalse(j.prepare(plan))
    }

    @Test fun faultRollsBackAllIntentsAndImmutableReceiptsKeepExactReadBackEvidence() {
        val (_,j) = setup()
        sql("""CREATE TRIGGER inject_fault BEFORE INSERT ON workflow_events
            WHEN NEW.event_type='PUBLICATION_PREPARED' BEGIN SELECT RAISE(ABORT,'fault'); END""")
        assertFails { j.prepare(plan) }
        assertEquals(0,count("publication_plans"))
        assertEquals(0,count("effects_outbox"))
        sql("DROP TRIGGER inject_fault")
        confirmedThroughPush(j)
        assertFails { sql("UPDATE publication_plans SET plan_json='{}'") }
        assertFails { sql("DELETE FROM publication_plans") }
        assertFails { sql("UPDATE effects_outbox SET expected_sha='drift'") }
        assertFails { sql("DELETE FROM publication_receipts") }
        DriverManager.getConnection("jdbc:sqlite:$database").use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT evidence FROM publication_receipts WHERE effect_id='workflow:COMMIT'").use {
                assertTrue(it.next()); assertTrue(it.getString(1).contains(plan.candidateTree))
                assertTrue(it.getString(1).contains(plan.baseSha))
            }
        } }
    }

    @Test fun v5MigrationPreservesWorkflowAndDoesNotAdoptExistingOutbox() {
        val (store,_) = setup()
        val before = store.findWorkflow(1,1)
        sql("DROP TABLE publication_receipts","DROP TABLE publication_plans",
            "DROP TRIGGER immutable_publication_effect_identity","PRAGMA user_version=5")
        assertEquals(6,SqliteTaskStore(database).schemaVersion())
        assertEquals(before,store.findWorkflow(1,1))
        assertTrue(PublicationJournal(database).prepare(plan))
    }

    @Test fun confirmedPrIdentityIsImmutableAcrossRestartAndDifferentSingletonReadBack() {
        val (_,j) = setup()
        confirmedThroughPush(j)
        assertTrue(j.markSending("workflow",EffectKind.DRAFT_PR,plan.candidateManifestSha256))
        val original = PublicationReadBack.Drafts(listOf(pr()))
        assertTrue(j.confirm("workflow",EffectKind.DRAFT_PR,original))
        val restarted = PublicationJournal(database)
        assertTrue(restarted.confirm("workflow",EffectKind.DRAFT_PR,original))
        val another = pr().copy(number=43,url="https://github.com/owner/disposable/pull/43")
        assertFalse(restarted.confirm("workflow",EffectKind.DRAFT_PR,PublicationReadBack.Drafts(listOf(another))))
        assertEquals(3,count("publication_receipts"))
    }

    @Test fun everyAllowedWorkflowIdBoundaryProducesDistinctValidGitBranch() {
        val ids = listOf("workflow.","workflow.lock","workflow..x","a","a".repeat(128),"a_b-c.d")
        val branches = ids.map { plan.copy(workflowId=it).headBranch }
        assertEquals(ids.size,branches.toSet().size)
        for (branch in branches) {
            val process = ProcessBuilder("/usr/bin/git","check-ref-format","--branch",branch)
                .redirectErrorStream(true)
            process.environment().clear()
            process.environment()["GIT_CONFIG_NOSYSTEM"]="1"
            process.environment()["GIT_CONFIG_GLOBAL"]="/dev/null"
            process.directory(temp.toFile())
            val child = process.start()
            child.outputStream.close()
            val output = child.inputStream.readNBytes(1024)
            assertTrue(child.waitFor(5,TimeUnit.SECONDS))
            assertEquals(0,child.exitValue(),String(output))
            assertTrue(branch.length < 255)
        }
    }
}
