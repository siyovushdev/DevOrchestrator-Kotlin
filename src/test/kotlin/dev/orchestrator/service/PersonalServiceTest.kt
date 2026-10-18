package dev.orchestrator.service

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
    private var records=listOf(issue())
    private val github=object: ServiceGitHub {
        override fun issues()=records
        override fun authorized(issue: GitHubIssueRecord)=authorized
        override fun postStatus(number: Int,body: String) { posts.add("$number:$body"); if(loseStatus) error("lost response") }
    }
    private val runtime=object: TaskRuntime {
        override fun prepare(issue: GitHubIssueRecord) { prepares++; if(crashPrepare) throw SimulatedCrash() }
        override fun recover(issue: GitHubIssueRecord) { recoveries++ }
        override fun step(issue: GitHubIssueRecord): TaskProgress { steps++; if(crashStep) throw SimulatedCrash(); return progress }
    }
    private class SimulatedCrash: Error()
    private fun service()=PersonalService(database,github,runtime,{enabled})
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
        assertEquals(2,posts.size) // Original uncertain accepted + one held explanation.
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
        RuntimeDatabase(database).write { it.mutate("INSERT INTO personal_status VALUES (1017,'tests','sending')") }
        val restarted=service(); restarted.recover(); repeat(2) { restarted.tick() }
        assertEquals(0,steps); assertEquals(1,prepares)
        assertEquals(0,posts.count { it.contains("тестами") })
    }
    @Test fun malformedIdentityIsRejectedBeforeAcceptance() {
        records=listOf(issue(),issue().copy(issueNumber=18)); assertFails { service().tick() }
        assertEquals(0,prepares)
        records=listOf(issue().copy(repositoryId=10)); assertFails { service().tick() }
    }
    @Test fun singleWorkerLockAndReadOnlyStatusWorkTogether() {
        service()
        ServiceLock(temp).use { assertFails { ServiceLock(temp) }; assertEquals("No tasks",readLocalServiceStatus(database)) }
        ServiceLock(temp).close()
    }
}
