package dev.orchestrator.service

import dev.orchestrator.profile.ReviewResume
import dev.orchestrator.storage.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class PersonalServiceTest {
    @TempDir lateinit var temp: Path
    private val database get()=temp.resolve("service.db")
    private fun issue(n: Int=17)=GitHubIssueRecord(1424152600,n,1000L+n,Instant.EPOCH,"Task $n","Add a function","a".repeat(40),true)
    private var enabled=true
    private var authorized=true
    private var posts=mutableListOf<String>()
    private var loseStatus=false
    private var prepares=0
    private var steps=0
    private var recoveries=0
    private var crashPrepare=false
    private var crashStep=false
    private var progress=TaskProgress("tests")
    private var resume=ReviewResume.NOT_APPLICABLE
    private var resumes=0
    private var records=listOf(issue())
    private val github=object: ServiceGitHub {
        override fun issues()=records
        override fun authorized(issue: GitHubIssueRecord)=authorized
        override fun findStatus(issue: GitHubIssueRecord,body: String): StatusEvidence? =
            if (posts.count { it=="${issue.issueNumber}:$body" }==1) StatusEvidence(99,"https://github.com/example#issuecomment-99",body) else null
        override fun postStatus(number: Int,body: String) { posts.add("$number:$body"); if(loseStatus) throw GitHubUnavailable("FAKE_LOST_RESPONSE") }
    }
    private val runtime=object: TaskRuntime {
        override fun prepare(issue: GitHubIssueRecord) { prepares++; if(crashPrepare) throw SimulatedCrash() }
        override fun recover(issue: GitHubIssueRecord) { recoveries++ }
        override fun step(issue: GitHubIssueRecord): TaskProgress { steps++; if(crashStep) throw SimulatedCrash(); return progress }
        override fun resumeSavedReview(issue: GitHubIssueRecord): ReviewResume { resumes++; return resume }
    }
    private class SimulatedCrash: Error()
    private fun service()=PersonalService(database,github,runtime,{enabled})
    @Test fun `an internal review parse hold is imported on the next tick without release`() {
        val s=service(); assertEquals("ACTIVE", s.tick())
        RuntimeDatabase(database).write { c ->
            c.mutate("UPDATE personal_tasks SET state='held', stage='held', reason='INTERNAL_ACTION_ERROR'")
            c.mutate("INSERT INTO personal_failures(recorded_at, diagnostic) VALUES ('t', 'parseVerdict')")
        }
        resume=ReviewResume.IMPORTED
        assertEquals("ACTIVE", s.tick())
        assertEquals(1, resumes)
        assertEquals(1, steps)
        assertEquals(0, RuntimeDatabase(database).read { c -> c.rows("SELECT diagnostic FROM personal_failures") { it.getString(1) }.size })
        assertEquals("active", RuntimeDatabase(database).read { c -> c.rows("SELECT state FROM personal_tasks") { it.getString(1) }.single() })
    }
    @Test fun confirmedLostPostIsReadOnlyCheckedAndExplicitlyResumedWithoutDuplicate() {
        loseStatus=true
        val s=service(); assertEquals("NEEDS_OWNER",s.tick())
        loseStatus=false
        val restarted=service(); restarted.recover()
        val before=java.nio.file.Files.readAllBytes(database)
        assertEquals(99,checkSavedStatus(database,github,17,"accepted")?.id)
        assertContentEquals(before,java.nio.file.Files.readAllBytes(database))
        assertFalse(restarted.resumeStatus(17,"accepted",100))
        assertEquals(0,prepares); assertEquals(1,posts.size)
        assertTrue(restarted.resumeStatus(17,"accepted",99))
        assertFails { restarted.resumeStatus(17,"accepted",99) }
        assertEquals(1,posts.size)
        assertEquals("ACTIVE",service().tick()); assertEquals(1,prepares)
        val evidence=RuntimeDatabase(database).read { it.rows("SELECT evidence FROM personal_reconciliations") { it.getString(1) } }
        assertEquals(1,evidence.size); assertTrue(evidence.single().contains("issuecomment-99"))
    }
    private fun reconcileAfterCompletedAction(stage: String) {
        val s=service(); assertEquals("ACTIVE",s.tick())
        progress=if(stage=="done") TaskProgress("done","https://github.com/siyovushdev/devorch-kotlin-e2e-smoke/pull/7") else TaskProgress(stage)
        loseStatus=true; assertEquals("NEEDS_OWNER",s.tick()); loseStatus=false
        val beforePosts=posts.size
        val restarted=service(); restarted.recover()
        assertEquals(99,checkSavedStatus(database,github,17,stage)?.id)
        assertTrue(restarted.resumeStatus(17,stage,99))
        val final=service(); final.recover()
        if(stage=="done") repeat(3) { assertEquals("IDLE",final.tick()) }
        else { progress=TaskProgress("done","https://github.com/siyovushdev/devorch-kotlin-e2e-smoke/pull/7"); final.tick() }
        assertEquals(1,prepares)
        assertEquals(if(stage=="done") 1 else 2,steps)
        assertEquals(if(stage=="done") beforePosts else beforePosts+1,posts.size)
    }
    @Test fun reconcileLostTestsStatusDoesNotRepeatPriorRuntimeAction() = reconcileAfterCompletedAction("tests")
    @Test fun reconcileLostDoneStatusDoesNotRepeatPublisher() = reconcileAfterCompletedAction("done")
    @Test fun sendingCrashCanBeInspectedReadOnlyBeforeRecovery() {
        val s=service(); loseStatus=true; s.tick(); loseStatus=false
        RuntimeDatabase(database).write {
            it.mutate("UPDATE personal_status SET state='sending'")
            it.mutate("UPDATE personal_tasks SET state='accepted',reason=NULL,resume_state=NULL")
        }
        assertEquals(99,checkSavedStatus(database,github,17,"accepted")?.id)
        s.recover(); assertTrue(s.resumeStatus(17,"accepted",99)); assertEquals(0,prepares)
    }
    @Test fun absentOrAmbiguousResultAndLegacyIntentStayHeld() {
        loseStatus=true; val s=service(); s.tick(); loseStatus=false
        posts.clear()
        assertNull(s.checkStatus(17,"accepted")); assertFalse(s.resumeStatus(17,"accepted",99))
        assertEquals("NEEDS_OWNER",s.tick()); assertEquals(0,prepares); assertEquals(0,steps)
        RuntimeDatabase(database).write { it.mutate("UPDATE personal_status SET body=NULL") }
        assertFails { s.resumeStatus(17,"accepted",99) }
    }
    @Test fun sqliteFailureIsRecordedAndStopsRestartWithoutMoreEffectsOrSecretText() {
        val s=service()
        RuntimeDatabase(database).write { it.mutate("""CREATE TRIGGER fail_state BEFORE UPDATE ON personal_tasks
            BEGIN SELECT RAISE(ABORT,'FAKE_SECRET_IN_EXCEPTION'); END""") }
        val failure=assertFailsWith<IllegalStateException> { guardedServiceCall(s) { s.tick() } }
        assertTrue(failure.message!!.contains("INTERNAL_ERROR_STOPPED"))
        assertFalse(failure.message!!.contains("FAKE_SECRET_IN_EXCEPTION"))
        val saved=RuntimeDatabase(database).read { it.rows("SELECT diagnostic FROM personal_failures") { it.getString(1) }.single() }
        assertTrue(saved.contains("SQLiteException")); assertFalse(saved.contains("FAKE_SECRET_IN_EXCEPTION"))
        assertFails { service().recover() }; assertFails { service().tick() }
        assertEquals(0,prepares); assertEquals(0,steps); assertEquals(1,posts.size)
    }
    @Test fun githubAccessRejectionRetainsNumericCauseAndStopsInsteadOfPolling() {
        val s=service()
        val failure=assertFailsWith<ServiceStopped> { guardedServiceCall(s) { throw GitHubRejected(403) } }
        assertTrue(failure.message!!.startsWith("GITHUB_ACCESS_STOPPED_HTTP_403"))
        assertTrue(readLocalServiceStatus(database).contains("GITHUB_REJECTED_HTTP_403"))
        assertFails { service().tick() }; assertEquals(0,prepares)
    }
    @Test fun onlyTypedAvailabilityErrorIsRetryableBeforeEffects() {
        val unavailable=object: ServiceGitHub {
            override fun issues(): List<GitHubIssueRecord> = throw GitHubUnavailable("GITHUB_HTTP_503")
            override fun authorized(issue: GitHubIssueRecord)=true
            override fun postStatus(number: Int,body: String)=error("not called")
        }
        val s=PersonalService(database,unavailable,runtime,{true})
        assertTrue(guardedServiceCall(s) { s.tick() }.startsWith("GITHUB_UNAVAILABLE_NO_ACTION"))
        assertTrue(posts.isEmpty()); assertEquals(0,prepares)
        val failure=assertFailsWith<IllegalStateException> { guardedServiceCall(s) { error("FAKE_SECRET") } }
        assertFalse(failure.message!!.contains("FAKE_SECRET")); assertFails { service().tick() }
    }

    @Test fun offAndUnauthorizedCannotCreateWork() {
        enabled=false; assertEquals("OFF",service().tick()); assertEquals(0,prepares)
        enabled=true; authorized=false; assertEquals("IDLE",service().tick()); assertTrue(posts.isEmpty())
    }
    @Test fun duplicatePollRestartAndTerminalNeverRepeatEffects() {
        val first=service(); assertEquals("ACTIVE",first.tick()); assertEquals(1,prepares)
        first.tick(); assertEquals(1,steps)
        val next=service(); next.recover(); assertEquals(1,recoveries)
        progress=TaskProgress("review"); next.tick()
        progress=TaskProgress("done","https://github.com/siyovushdev/devorch-kotlin-e2e-smoke/pull/7"); next.tick()
        val final=service(); final.recover(); repeat(3) { assertEquals("IDLE",final.tick()) }
        assertEquals(1,prepares); assertEquals(3,steps); assertEquals(5,posts.size)
        assertTrue(readLocalServiceStatus(database).contains("pull/7"))
    }
    @Test fun nextTaskWaitsForFirstThenUsesItsOwnIdentity() {
        records=listOf(issue(18),issue(17)); val s=service(); s.tick()
        assertEquals(1,prepares)
        progress=TaskProgress("done","https://github.com/siyovushdev/devorch-kotlin-e2e-smoke/pull/7"); s.tick()
        s.tick(); assertEquals(2,prepares); assertTrue(posts.last().startsWith("18:"))
    }
    @Test fun lostStatusBlocksModelsAndQueueWithoutDuplicatePost() {
        records=listOf(issue(17),issue(18)); loseStatus=true
        val s=service(); assertEquals("NEEDS_OWNER",s.tick()); assertEquals(0,prepares)
        loseStatus=false
        val restarted=service(); restarted.recover(); repeat(3) { assertEquals("NEEDS_OWNER",restarted.tick()) }
        assertEquals(0,steps); assertEquals(0,prepares)
        assertEquals(1,posts.size) // No further POST while outcome is uncertain.
        assertTrue(readLocalServiceStatus(database).contains("STATUS_OUTCOME_UNKNOWN"))
    }
    @Test fun crashAfterPreparationIntentNeverReprepares() {
        crashPrepare=true; assertFailsWith<SimulatedCrash> { service().tick() }
        crashPrepare=false; val s=service(); s.recover(); repeat(2) { assertEquals("NEEDS_OWNER",s.tick()) }
        assertEquals(1,prepares); assertEquals(0,steps)
    }
    @Test fun crashAfterRuntimeEffectUsesRecoveryAndNoNewTask() {
        val s=service(); s.tick(); crashStep=true; assertFailsWith<SimulatedCrash> { s.tick() }
        crashStep=false; progress=TaskProgress("held")
        val restarted=service(); restarted.recover(); assertEquals(1,recoveries)
        restarted.tick(); repeat(2) { assertEquals("NEEDS_OWNER",restarted.tick()) }
        assertEquals(1,prepares); assertEquals(2,steps)
    }
    @Test fun revokedOwnerLabelStopsBeforeNextEffect() {
        val s=service(); s.tick(); authorized=false; assertEquals("NEEDS_OWNER",s.tick()); assertEquals(0,steps)
    }
    @Test fun statusPostCrashIntentIsRecoveredWithoutSendingAgain() {
        val s=service(); s.tick()
        RuntimeDatabase(database).write { it.mutate("INSERT INTO personal_status(issue_id,stage,state) VALUES (1017,'tests','sending')") }
        val restarted=service(); restarted.recover(); repeat(2) { restarted.tick() }
        assertEquals(0,steps); assertEquals(1,prepares)
        assertEquals(0,posts.count { it.contains("тестами") })
    }
    @Test fun malformedIdentityIsRejectedBeforeAcceptance() {
        records=listOf(issue(),issue().copy(issueNumber=18)); assertFails { service().tick() }
        assertEquals(0,prepares)
        records=listOf(issue().copy(repositoryId=10)); assertFails { service().tick() }
    }
    @Test fun cancelBeforeStartDoesNotPrepareAndLetsTheNextIssueRun() {
        records=listOf(issue(17),issue(18))
        requestOwnerCancel(database, database.parent, 17)
        val s=service()
        assertEquals("CANCELLED", s.tick())
        assertEquals(0, prepares); assertEquals(0, steps)
        assertTrue(posts.single().startsWith("17:")); assertTrue(posts.single().contains("отменена"))
        assertEquals("ACTIVE", s.tick())
        assertEquals(1, prepares); assertEquals(0, steps)
        assertTrue(posts.any { it.startsWith("18:") && it.contains("принята") })
        assertEquals(1, posts.count { it.startsWith("17:") })
    }
    @Test fun cancelOfAcceptedTaskDoesNotStartTheNextStage() {
        val s=service(); assertEquals("ACTIVE", s.tick()); assertEquals(1, prepares)
        requestOwnerCancel(database, database.parent, 17)
        assertEquals("CANCELLED", s.tick())
        assertEquals(0, steps)
        assertEquals("IDLE", s.tick())
        assertEquals(0, steps); assertEquals(1, prepares)
    }
    @Test fun cancelDoesNotReplayAPublishedDraft() {
        val s=service(); assertEquals("ACTIVE", s.tick())
        progress=TaskProgress("done","https://github.com/siyovushdev/devorch-kotlin-e2e-smoke/pull/7")
        assertEquals("DONE", s.tick()); assertEquals(1, steps)
        val runtimeCancel=object: TaskRuntime by runtime {
            override fun cancel(issue: GitHubIssueRecord)=TaskProgress("done", progress.url, "ALREADY_PUBLISHED")
        }
        val cancelling=PersonalService(database, github, runtimeCancel, {enabled})
        requestOwnerCancel(database, database.parent, 17)
        val before=posts.size
        assertEquals("ALREADY_PUBLISHED", cancelling.tick())
        assertEquals(before+1, posts.size)
        assertTrue(posts.last().contains("не закрывает"))
        assertEquals("IDLE", cancelling.tick())
        assertEquals(before+1, posts.size); assertEquals(1, steps)
    }
    @Test fun unconfirmedEffectIsNotReportedAsCancelledAndIsNotRetried() {
        val s=service(); assertEquals("ACTIVE", s.tick())
        val runtimeCancel=object: TaskRuntime by runtime {
            override fun cancel(issue: GitHubIssueRecord)=TaskProgress("held", reason="EFFECT_OR_PROCESS_UNCONFIRMED")
        }
        val cancelling=PersonalService(database, github, runtimeCancel, {enabled})
        requestOwnerCancel(database, database.parent, 17)
        assertEquals("NEEDS_OWNER", cancelling.tick())
        assertEquals("NEEDS_OWNER", cancelling.tick())
        assertEquals(0, steps); assertEquals(1, prepares)
        assertTrue(posts.any { it.contains("не успешная отмена") })
    }
    @Test fun publishedRefusalStaysTerminalOnTheNextTick() {
        val s=service(); assertEquals("ACTIVE", s.tick())
        val refusing=object: TaskRuntime by runtime {
            override fun cancel(issue: GitHubIssueRecord)=TaskProgress("done","https://github.com/siyovushdev/devorch-kotlin-e2e-smoke/pull/7","ALREADY_PUBLISHED")
            override fun step(issue: GitHubIssueRecord): TaskProgress { steps++; return progress }
        }
        requestOwnerCancel(database, database.parent, 17)
        val cancelling=PersonalService(database, github, refusing, {enabled})
        assertEquals("ALREADY_PUBLISHED", cancelling.tick())
        assertEquals("IDLE", cancelling.tick())
        val stage=RuntimeDatabase(database).read { it.rows("SELECT state,stage FROM personal_tasks") { it.getString(1)+"/"+it.getString(2) }.single() }
        assertEquals("done/cancel-refused", stage)
        assertEquals(0, steps)
        assertTrue(posts.any { it.contains("не закрывает") })
        assertFalse(posts.any { it.contains("Готов черновик") })
    }
    @Test fun releaseLetsTheQueueContinueWithoutDeletingTheDatabaseOrStepping() {
        records=listOf(issue(17), issue(18))
        val s=service(); assertEquals("ACTIVE", s.tick())
        RuntimeDatabase(database).write { it.mutate("UPDATE personal_tasks SET state='held',reason='PREPARATION_INTERRUPTED'") }
        assertEquals("RELEASED", s.releaseHeld(17))
        assertTrue(java.nio.file.Files.isRegularFile(database))
        assertEquals("ACTIVE", s.tick())
        assertEquals(2, prepares); assertEquals(0, steps)
        val released=RuntimeDatabase(database).read { it.rows("SELECT stage FROM personal_tasks WHERE number=17") { it.getString(1) }.single() }
        assertEquals("released", released)
    }
    @Test fun cancelFlagDoesNotPullReleasedOrTimedOutBackToHeld() {
        records=listOf(issue(17), issue(18))
        val s=service(); assertEquals("ACTIVE", s.tick())
        RuntimeDatabase(database).write { it.mutate("UPDATE personal_tasks SET state='held',reason='PREPARATION_INTERRUPTED'") }
        requestOwnerCancel(database, database.parent, 17)
        assertEquals("RELEASED", s.releaseHeld(17))
        assertEquals("ACTIVE", s.tick())
        val stage=RuntimeDatabase(database).read { it.rows("SELECT state,stage FROM personal_tasks WHERE number=17") { it.getString(1)+"/"+it.getString(2) }.single() }
        assertEquals("done/released", stage)
        assertEquals(2, prepares)
        val flagged=object: TaskRuntime by runtime {
            override fun cancel(issue: GitHubIssueRecord)=TaskProgress("timed_out", reason="STAGE_TIMEOUT")
        }
        requestOwnerCancel(database, database.parent, 18)
        val cancelling=PersonalService(database, github, flagged, {enabled})
        assertEquals("TIMED_OUT", cancelling.tick())
        assertEquals("IDLE", cancelling.tick())
        val timed=RuntimeDatabase(database).read { it.rows("SELECT state,stage FROM personal_tasks WHERE number=18") { it.getString(1)+"/"+it.getString(2) }.single() }
        assertEquals("done/timed_out", timed)
    }
    @Test fun timeoutEndsThatTaskAndStartsTheNext() {
        records=listOf(issue(17), issue(18))
        val s=service(); assertEquals("ACTIVE", s.tick())
        progress=TaskProgress("timed_out", reason="STAGE_TIMEOUT")
        assertEquals("TIMED_OUT", s.tick())
        assertEquals("ACTIVE", s.tick())
        assertEquals(2, prepares)
        val stage=RuntimeDatabase(database).read { it.rows("SELECT stage FROM personal_tasks WHERE number=17") { it.getString(1) }.single() }
        assertEquals("timed_out", stage)
    }
    @Test fun releaseStopsALiveProcessWithoutDeletingTheDatabase() {
        val s=service(); assertEquals("ACTIVE", s.tick())
        RuntimeDatabase(database).write { it.mutate("UPDATE personal_tasks SET state='held',reason='UNRECONCILED_CODING_START'") }
        val sleep=ProcessBuilder("/bin/sleep","30").start()
        val stat=java.nio.file.Files.readString(java.nio.file.Path.of("/proc/${sleep.pid()}/stat"))
        val ticks=stat.substringAfterLast(')').trim().split(Regex("\\s+"))[19]
        val boot=java.nio.file.Files.readString(java.nio.file.Path.of("/proc/sys/kernel/random/boot_id")).trim()
        val runDb=temp.resolve("task-1017/run.db")
        java.nio.file.Files.createDirectories(runDb.parent)
        val taskDb=dev.orchestrator.storage.RuntimeDatabase(runDb)
        taskDb.write { c ->
            c.mutate("INSERT INTO projects(project_id,repository_id,kind,workspace_root) VALUES('p',1,'PERSONAL',?)", temp.toString())
            c.mutate("INSERT INTO workflows(workflow_id,repository_id,issue_number,phase,hold,version,corrections_used,created_at,updated_at) VALUES('w',1,1,'CODING','NONE',0,0,'t','t')")
            c.mutate("INSERT INTO workflow_events(workflow_id,event_type,detail,recorded_at) VALUES('w','CODING_PROCESS_STARTED',?,'t')",
                """{"pid":"${sleep.pid()}","start_ticks":"$ticks","boot_id":"$boot"}""")
        }
        assertEquals("RELEASED", s.releaseHeld(17))
        assertTrue(sleep.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(sleep.isAlive)
        assertTrue(java.nio.file.Files.isRegularFile(database))
        assertTrue(java.nio.file.Files.isRegularFile(runDb))
    }
    @Test fun singleWorkerLockAndReadOnlyStatusWorkTogether() {
        service()
        ServiceLock(temp).use { assertFails { ServiceLock(temp) }; assertEquals("No tasks",readLocalServiceStatus(database)) }
        ServiceLock(temp).close()
    }
}
