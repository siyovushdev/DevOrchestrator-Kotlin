package dev.orchestrator.workflow

import dev.orchestrator.agent.*
import dev.orchestrator.agent.nativecli.NativeCodingRunner
import dev.orchestrator.review.*
import dev.orchestrator.storage.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real temp Git/Python/processes/SQLite, fake Codex only. Never reads HOME smoke artifacts. */
class WorkflowEngineTest {
    @TempDir lateinit var temp: Path
    private val repo = 1424152600L
    private val database get() = temp.resolve("state.db")
    private val clone get() = temp.resolve("clone")
    private val artifacts get() = temp.resolve("artifacts")
    private val snapshots get() = temp.resolve("snapshots")
    private val home get() = temp.resolve("fake-home")
    private val binary get() = temp.resolve("fake-codex")
    private val store get() = SqliteTaskStore(database)
    private val journal get() = CodingAttemptJournal(database)
    private val db get() = RuntimeDatabase(database)
    private fun engine(minimum: Int = 3, limit: Int = 2, timeoutMs: Long = 3000, ceiling: Int? = null) =
        WorkflowEngine(database,repo,binary,home,artifacts,snapshots,minimum,limit,Duration.ofMillis(timeoutMs),
            ownerCorrectionCeiling={ ceiling ?: limit })
    private fun git(vararg args: String): String {
        val builder = ProcessBuilder(listOf("/usr/bin/git","-c","core.hooksPath=/dev/null")+args)
            .directory(clone.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin","GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to "/dev/null","GIT_TEMPLATE_DIR" to temp.resolve("template").toString()))
        val child = builder.start()
        child.outputStream.close()
        val output = child.inputStream.readNBytes(8192).toString(Charsets.UTF_8)
        check(child.waitFor(5,TimeUnit.SECONDS) && child.exitValue()==0) { output }
        return output.trim()
    }
    private fun setup(review: String = pass, codingExtra: String = "", tests: String = suite) {
        listOf(clone,artifacts,snapshots,home,temp.resolve("template")).forEach { Files.createDirectory(it) }
        Files.createDirectory(home.resolve(".codex"))
        Files.writeString(clone.resolve("README"),"temp project\n")
        git("init","-q","--initial-branch=main")
        git("add","README")
        git("-c","user.name=Test","-c","user.email=test@example.invalid","commit","-qm","fixture")
        val base = git("rev-parse","HEAD")
        store.insertProject(RegisteredProject("fixture",repo,ProjectKind.PERSONAL,temp.toString()),"owner:test")
        val policy = assertNotNull(store.findPolicy(repo))
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=2,personalAutoExecution=true),1,"owner:test","offline only"))
        store.acceptGitHubIssue(GitHubIssueRecord(repo,1,1001,Instant.EPOCH,"Tiny sum","Add tiny function",base,true),"wf")
        val catalog = SqliteAgentCatalog(database)
        catalog.declareManifest(AgentCapabilityManifest("codex-cli","openai",1,setOf(AgentRole.CODING,AgentRole.REVIEW),
            setOf(AgentSandbox.NATIVE_PERSONAL_WRITE,AgentSandbox.NATIVE_PERSONAL_READ_ONLY),
            mapOf("fake-model" to setOf(ReasoningEffort.DEFAULT))),null,"owner:test")
        for (role in listOf(AgentRole.CODING,AgentRole.REVIEW)) {
            val id = role.name.lowercase()
            catalog.saveProfile(AgentProfile(id,1,repo,role,"codex-cli",1,"openai","fake-model",ReasoningEffort.DEFAULT,
                if (role==AgentRole.CODING) AgentSandbox.NATIVE_PERSONAL_WRITE else AgentSandbox.NATIVE_PERSONAL_READ_ONLY,
                30,1000),null,"owner:test")
            catalog.bindRole(repo,role,id,1,null,"owner:test")
        }
        // This executable is the only CLI launched by any test. No actual model adapter.
        Files.writeString(binary,"""#!/usr/bin/python3
import json, os, re, signal, sys, time
from pathlib import Path
assert sys.argv[1] == 'exec'
assert 'resume' not in sys.argv
print('Reading prompt from stdin...',file=sys.stderr)
prompt = sys.stdin.read()
assert 'Add tiny function' in prompt
last = Path(sys.argv[sys.argv.index('--output-last-message')+1])
mode = sys.argv[sys.argv.index('--sandbox')+1]
root = Path(os.environ['CODEX_HOME'])
if mode == 'workspace-write':
    count = root / 'coding-count'
    n = int(count.read_text())+1 if count.exists() else 0
    count.write_text(str(n))
    Path('answer.py').write_text('def add(a,b): return a+b\n# generation '+str(n)+'\n')
    Path('tests').mkdir(exist_ok=True)
    Path('tests/test_answer.py').write_text(${py(tests)})
${codingExtra.prependIndent("    ")}
    session = 'coding-session-'+str(n)
    last.write_text('Coding done')
else:
    assert mode == 'read-only'
    assert '--skip-git-repo-check' in sys.argv
    assert os.environ['HTTP_PROXY'] == 'http://127.0.0.1:10809'
    assert not any(k in os.environ for k in ['GITHUB_TOKEN','GH_TOKEN','SSH_AUTH_SOCK'])
    count = root / 'review-count'
    n = int(count.read_text())+1 if count.exists() else 0
    count.write_text(str(n))
    specification = prompt.split('Return only JSON: ')[1].split('\n')[0]
    result = json.loads(specification)
    session = 'review-session-'+str(n)
${review.prependIndent("    ")}
    last.write_text(json.dumps(result))
print(json.dumps({'type':'thread.started','thread_id':session}))
print(json.dumps({'type':'turn.completed','usage':{}}))
""")
        check(binary.toFile().setExecutable(true))
        assertNotNull(journal.plan(repo,1,0,"coding-0",clone,git("rev-parse","HEAD^{tree}")))
        val result = NativeCodingRunner(database,repo,binary,home,artifacts,timeout=Duration.ofSeconds(3)).run("coding-0")
        assertEquals(AttemptState.SUCCEEDED,result.state,result.reason)
    }
    private fun py(value: String): String = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value)
    private fun workflow() = assertNotNull(store.findWorkflow(repo,1))
    private fun claims(): Int = db.read { c -> c.rows("SELECT COUNT(*) FROM runtime_reservations") { it.getInt(1) }.single() }
    private fun launches(role: String): Int = home.resolve(".codex/$role-count").let { if (Files.exists(it)) Files.readString(it).toInt()+1 else 0 }

    @Test fun hostilePythonAndCodexDescendantsCannotReachControllerCanaryOrDatabase() {
        val secret = temp.resolve("controller.env")
        Files.writeString(secret,"FAKE_CANARY_ONLY")
        val attacks = """
from pathlib import Path
import os, subprocess
for name in [${py(secret.toString())}, ${py(database.toString())},
             '/proc/${ProcessHandle.current().pid()}/root'+${py(secret.toString())},
             '/proc/${ProcessHandle.current().pid()}/environ']:
    for mode in ['r', 'r+']:
        try:
            with open(name, mode) as f:
                if mode == 'r': f.read()
                else: f.write('compromised')
        except OSError: pass
        else: raise AssertionError('controller data reachable: '+name)
"""
        val testAttacks=attacks.replace("for name in [","for name in ["+py(home.resolve(".codex/auth.json").toString())+", ")
        val tests = """
import unittest
class Boundary(unittest.TestCase):
 def test_denied(self):
${testAttacks.prependIndent("  ")}
  import socket
  with self.assertRaises(OSError): socket.create_connection(('127.0.0.1',10809),timeout=0.2)
"""
        setup(codingExtra="subprocess_code = " + py(attacks) +
            "\nimport subprocess\nsubprocess.run(['/usr/bin/python3','-c',subprocess_code],check=True)",
            review="import subprocess\nsubprocess.run(['/usr/bin/python3','-c',"+py(attacks)+"],check=True)\n"+pass,
            tests=tests)
        Files.writeString(home.resolve(".codex/auth.json"),"FAKE_MODEL_AUTH_ONLY")
        assertEquals("PASS",engine(minimum=1).step())
        assertEquals("PASS",engine(minimum=1).step())
        assertEquals("FAKE_CANARY_ONLY",Files.readString(secret))
        assertEquals("FAKE_MODEL_AUTH_ONLY",Files.readString(home.resolve(".codex/auth.json")))
        assertEquals(WorkflowPhase.READY_FOR_PR,workflow().phase)
    }

    @Test fun savedCodingIsVerifiedAndReviewedInFreshSessionThenRestartHasNoEffects() {
        setup()
        val savedTree = git("diff","--name-only")
        assertEquals("PASS",engine().step())
        val receipt = assertNotNull(engine().verification("wf",0))
        assertEquals(3,receipt.testsRun)
        assertEquals(VerificationStatus.PASS,receipt.status)
        assertTrue(receipt.process["cleanup_echild"].asBoolean())
        assertEquals(WorkflowPhase.REVIEWING,workflow().phase)
        assertEquals("PASS",engine().step())
        assertEquals(WorkflowPhase.READY_FOR_PR,workflow().phase)
        assertEquals(savedTree,git("diff","--name-only"))
        assertEquals(0,engine().recover())
        repeat(3) { assertEquals("READY_FOR_PR",engine().step()) }
        assertEquals(1,launches("coding")); assertEquals(1,launches("review")); assertEquals(0,claims())
        assertTrue(FrozenCandidate.reopen(Path.of(receipt.directory),receipt.manifestSha256).isIntact())
        assertEquals(0,db.read { c -> c.rows("SELECT COUNT(*) FROM effects_outbox") { it.getInt(1) }.single() })
    }

    @Test fun twoCorrectionsAreDurableAcrossNewEngineInstancesAndThirdRequestHolds() {
        setup(review=changes)
        for (generation in 0..2) {
            assertEquals(generation,workflow().correctionsUsed)
            assertEquals("PASS",engine().step())
            assertEquals("CHANGES_REQUIRED",engine().step())
            if (generation<2) {
                assertEquals(WorkflowPhase.QUEUED,workflow().phase)
                assertEquals("CORRECTION_PLANNED",engine().step())
                assertEquals(generation+1,workflow().correctionsUsed)
                assertEquals("CANDIDATE_CAPTURED_TESTS_NOT_VERIFIED",engine().step())
            }
        }
        assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold)
        assertEquals(2,workflow().correctionsUsed)
        repeat(3) { assertEquals("NEEDS_OWNER",engine().step()) }
        assertEquals(3,launches("coding")); assertEquals(3,launches("review"))
    }

    @Test fun knownTestFailureCanBeCorrectedWithoutLaunchingReviewerFirst() {
        setup(codingExtra="if n == 0: Path('answer.py').write_text('def add(a,b): return 0\\n')")
        assertEquals("FAIL",engine().step())
        assertEquals(0,launches("review"))
        assertEquals("CORRECTION_PLANNED",engine().step())
        assertEquals("CANDIDATE_CAPTURED_TESTS_NOT_VERIFIED",engine().step())
        assertEquals("PASS",engine().step()); assertEquals("PASS",engine().step())
        assertEquals(WorkflowPhase.READY_FOR_PR,workflow().phase)
        assertEquals(1,workflow().correctionsUsed)
    }

    @Test fun modelStageDeadlineKeepsAFiniteCeilingAboveThePilotCap() {
        assertFails {
            WorkflowEngine(database,repo,binary,home,artifacts,snapshots,timeout=Duration.ofSeconds((StageBudget.MAX_SECONDS+1).toLong()))
        }
        WorkflowEngine(database,repo,binary,home,artifacts,snapshots,timeout=Duration.ofSeconds(StageBudget.MAX_SECONDS.toLong()))
        assertFails { NativeCodingRunner(database,repo,binary,home,artifacts,timeout=Duration.ofSeconds((StageBudget.MAX_SECONDS+1).toLong())) }
    }

    @Test fun ownerCeilingBlocksTheNextCorrectionWithoutRaisingTheStoredCap() {
        setup(review=changes)
        assertEquals("PASS", engine().step())
        assertEquals("CHANGES_REQUIRED", engine(ceiling=0).step())
        assertEquals(WorkflowHold.NEEDS_OWNER, workflow().hold)
        assertEquals(0, workflow().correctionsUsed)
        assertEquals(1, launches("coding"))
        assertEquals(1, launches("review"))
        repeat(2) { assertEquals("NEEDS_OWNER", engine(ceiling=2).step()) }
        assertEquals(1, launches("coding"))
        assertEquals(1, launches("review"))
    }

    @Test fun stricterProjectLimitAndTestContractCannotBeRelaxedAfterRestart() {
        setup(review=changes)
        assertEquals("PASS",engine(minimum=3,limit=1).step())
        assertEquals("CHANGES_REQUIRED",engine(limit=2).step())
        assertEquals("CORRECTION_PLANNED",engine(limit=2).step())
        assertEquals("CANDIDATE_CAPTURED_TESTS_NOT_VERIFIED",engine().step())
        assertEquals("PASS",engine(minimum=1,limit=2).step())
        assertEquals("CHANGES_REQUIRED",engine(limit=2).step())
        assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold)
        assertEquals(1,workflow().correctionsUsed)
    }

    @Test fun noTestsAndAllSkippedNeverPassVerification() {
        setup(tests="import unittest\nclass Empty(unittest.TestCase):\n @unittest.skip('skip')\n def test_none(self): pass\n")
        assertEquals("FAIL",engine().step())
        assertEquals(VerificationStatus.FAIL,engine().verification("wf",0)?.status)
        assertEquals(0,launches("review"))
    }

    @Test fun timeoutIsUnknownAndCannotConsumeCorrectionBudgetOrLaunchAgain() {
        setup(tests="import time\ntime.sleep(10)\n"+suite)
        assertEquals("UNKNOWN",engine(timeoutMs=200).step())
        assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold)
        assertEquals(0,workflow().correctionsUsed); assertEquals(1,claims())
        assertEquals("NEEDS_OWNER",engine().step())
        assertEquals(1,launches("coding")); assertEquals(0,launches("review"))
    }

    @Test fun changedSavedCandidateBeforeVerificationFailsClosed() {
        setup()
        Files.writeString(clone.resolve("answer.py"),"different saved result\n")
        assertEquals("UNKNOWN",engine().step())
        assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold); assertEquals(1,claims())
    }

    @Test fun frozenCandidateTamperingCannotLaunchReviewer() {
        setup(); assertEquals("PASS",engine().step())
        val r = assertNotNull(engine().verification("wf",0))
        Files.writeString(Path.of(r.directory).resolve("files/answer.py"),"changed\n")
        assertEquals("UNKNOWN",engine().step())
        assertEquals(0,launches("review")); assertEquals(1,claims())
    }

    @Test fun reviewerEditsNeverEnterSavedCandidateAndCauseHold() {
        setup(review=pass+"\nPath('answer.py').write_text('reviewer edit\\n')")
        assertEquals("PASS",engine().step())
        val r = assertNotNull(engine().verification("wf",0))
        assertEquals("UNKNOWN",engine().step())
        assertTrue(FrozenCandidate.reopen(Path.of(r.directory),r.manifestSha256).isIntact())
        assertFalse(Files.readString(clone.resolve("answer.py")).contains("reviewer"))
        assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold)
    }

    @Test fun selfReviewSessionCannotPass() {
        setup(review=pass+"\nsession = 'coding-session-0'")
        assertEquals("PASS",engine().step()); assertEquals("UNKNOWN",engine().step())
        assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold); assertEquals(0,workflow().correctionsUsed)
    }

    @Test fun staleGenerationCannotPass() {
        setup(review=pass+"\nresult['generation'] = 2")
        assertEquals("PASS",engine().step()); assertEquals("UNKNOWN",engine().step())
        assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold)
    }

    @Test fun mismatchedTreeCannotPass() {
        setup(review=pass+"\nresult['candidate_tree'] = 'a'*40")
        assertEquals("PASS",engine().step()); assertEquals("UNKNOWN",engine().step())
    }

    @Test fun barePassTextWithoutBoundStructuredVerdictCannotPass() {
        setup(review="last.write_text('PASS'); sys.exit(0)")
        assertEquals("PASS",engine().step()); assertEquals("UNKNOWN",engine().step())
        assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold)
    }

    @Test fun reviewerHelperDeathKeepsClaimsAndNoReplay() {
        setup(review="os.kill(os.getppid(),signal.SIGKILL); os._exit(0)")
        assertEquals("PASS",engine().step()); assertEquals("UNKNOWN",engine().step())
        assertEquals(1,claims()); assertEquals("NEEDS_OWNER",engine().step()); assertEquals(1,launches("review"))
    }

    @Test fun concurrentReviewPollsConsumeExactlyOneIntent() {
        setup(review=pass+"\ntime.sleep(0.2)")
        assertEquals("PASS",engine().step())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll((1..2).map { Callable { engine().step() } }).map { it.get(15,TimeUnit.SECONDS) }
            assertEquals(1,results.count { it=="PASS" },results.toString())
            assertEquals(1,launches("review"))
        } finally { pool.shutdownNow() }
    }

    @Test fun verificationReceiptFailureRollsBackTransitionAndClaimRelease() {
        setup()
        db.write { c -> c.createStatement().use { it.execute("""CREATE TRIGGER fault BEFORE INSERT ON workflow_events
            WHEN NEW.event_type='VERIFICATION_PASS' BEGIN SELECT RAISE(ABORT,'fault'); END""") } }
        assertEquals("UNKNOWN",engine().step())
        assertNull(engine().verification("wf",0)); assertEquals(1,claims())
        assertEquals(WorkflowPhase.VERIFYING,workflow().phase); assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold)
    }

    @Test fun reviewReceiptFailureCannotLeaveReadyOrReleaseClaim() {
        setup(); assertEquals("PASS",engine().step())
        db.write { c -> c.createStatement().use { it.execute("""CREATE TRIGGER fault BEFORE INSERT ON workflow_events
            WHEN NEW.event_type='REVIEW_COMPLETED' BEGIN SELECT RAISE(ABORT,'fault'); END""") } }
        assertEquals("UNKNOWN",engine().step()); assertEquals(1,claims())
        assertEquals(WorkflowPhase.REVIEWING,workflow().phase); assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold)
    }

    @Test fun recoveryHoldsUnfinishedVerificationWithoutRerun() {
        setup()
        db.write { c ->
            c.mutate("""INSERT INTO workflow_verifications VALUES ('wf',0,'coding-0',2,3,2,'STARTING',NULL)""")
            c.mutate("INSERT INTO runtime_reservations VALUES ('coding-0',?,?)",repo,clone.toString())
        }
        assertEquals(1,engine().recover()); assertEquals(0,engine().recover())
        assertEquals("NEEDS_OWNER",engine().step()); assertEquals(1,claims()); assertEquals(1,launches("coding"))
    }

    @Test fun replayedReviewerSessionAcrossCorrectionsIsRejected() {
        setup(review=changes+"\nsession = 'reused-review-session'")
        assertEquals("PASS",engine().step()); assertEquals("CHANGES_REQUIRED",engine().step())
        assertEquals("CORRECTION_PLANNED",engine().step()); assertEquals("CANDIDATE_CAPTURED_TESTS_NOT_VERIFIED",engine().step())
        assertEquals("PASS",engine().step()); assertEquals("UNKNOWN",engine().step())
        assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold)
    }

    @Test fun policyRevocationBeforeReviewDoesNotLaunch() {
        setup(); assertEquals("PASS",engine().step())
        val policy = assertNotNull(store.findPolicy(repo))
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=3,personalAutoExecution=false),2,"owner:test","revoke"))
        assertEquals("POLICY_OR_VERIFICATION_DENIED",engine().step()); assertEquals(0,launches("review"))
    }

    @Test fun testMutationOfSnapshotIsDeniedAndFailsVerification() {
        setup(tests=suite+"\nfrom pathlib import Path\nPath('answer.py').write_text('mutated')\n")
        assertEquals("FAIL",engine().step())
        assertEquals(WorkflowPhase.QUEUED,workflow().phase); assertEquals(0,claims())
        assertFalse(Files.readString(clone.resolve("answer.py")).contains("mutated"))
    }

    @Test fun aBareSucceededRowCannotReplaceObservedCodingCompletion() {
        setup()
        db.write { c -> c.mutate("DELETE FROM workflow_events WHERE event_type='CODING_PROCESS_SUCCEEDED'") }
        assertEquals("UNKNOWN",engine().step())
        assertEquals(0,launches("review")); assertEquals(1,claims())
    }

    @Test fun correctionPlanningFaultRollsBackCounterAndNewAttempt() {
        setup(review=changes)
        assertEquals("PASS",engine().step()); assertEquals("CHANGES_REQUIRED",engine().step())
        db.write { c -> c.createStatement().use { it.execute("""CREATE TRIGGER fault BEFORE INSERT ON workflow_events
            WHEN NEW.event_type='CODING_PLANNED' BEGIN SELECT RAISE(ABORT,'fault'); END""") } }
        assertFails { engine().step() }
        assertEquals(0,workflow().correctionsUsed); assertEquals(WorkflowPhase.QUEUED,workflow().phase)
        assertEquals(0,claims())
        assertEquals(1,db.read { c -> c.rows("SELECT COUNT(*) FROM agent_attempts WHERE role='CODING'") { it.getInt(1) }.single() })
        db.write { c -> c.createStatement().use { it.execute("DROP TRIGGER fault") } }
        assertEquals("CORRECTION_PLANNED",engine().step()); assertEquals(1,workflow().correctionsUsed)
    }

    @Test fun restartAfterReviewIntentRetainsClaimAndDoesNotSpawn() {
        setup(); assertEquals("PASS",engine().step())
        val candidateTree = engine().verification("wf",0)!!.candidateTree
        db.write { c ->
            c.mutate("""INSERT INTO agent_attempts VALUES ('interrupted-review','wf','REVIEW',0,'STARTING',?,'now')""",
                candidateTree)
            c.mutate("""INSERT INTO attempt_contexts SELECT 'interrupted-review',repository_id,issue_number,
                issue_body_sha256,base_sha,policy_version,assignment_version,profile_id,profile_version,
                adapter_id,manifest_version,workspace,planned_workflow_version FROM attempt_contexts WHERE attempt_id='coding-0'""")
            c.mutate("INSERT INTO runtime_reservations VALUES ('interrupted-review',?,?)",repo,clone.toString())
        }
        assertEquals(1,engine().recover()); assertEquals(0,engine().recover())
        assertEquals("NEEDS_OWNER",engine().step()); assertEquals(0,launches("review")); assertEquals(1,claims())
    }

    @Test fun unrelatedUnknownAttemptInOverlappingRootPreventsReview() {
        setup(); assertEquals("PASS",engine().step())
        store.insertProject(RegisteredProject("other",999,ProjectKind.PERSONAL,temp.toString()),"owner:test")
        store.enqueue(999,2,"other-workflow")
        db.write { c -> c.mutate("INSERT INTO agent_attempts VALUES ('other','other-workflow','CODING',0,'UNKNOWN',NULL,'now')") }
        assertEquals("ALREADY_CONSUMED_OR_GUARD_DENIED",engine().step())
        assertEquals(0,launches("review")); assertNotEquals(WorkflowPhase.READY_FOR_PR,workflow().phase)
    }

    @Test fun v6SavedCodingMigratesWithoutRerunningCoding() {
        setup()
        val before = workflow()
        java.sql.DriverManager.getConnection("jdbc:sqlite:$database").use { c -> c.createStatement().use {
            it.execute("DROP TABLE review_inputs")
            it.execute("DROP TABLE correction_requests")
            it.execute("DROP TABLE workflow_verifications")
            it.execute("PRAGMA user_version=6")
        } }
        val migrated = engine()
        assertEquals(7,store.schemaVersion()); assertEquals(before,workflow())
        assertEquals("PASS",migrated.step()); assertEquals("PASS",migrated.step())
        assertEquals(1,launches("coding")); assertEquals(WorkflowPhase.READY_FOR_PR,workflow().phase)
    }

    @Test fun recoveryDoesNotChangeAnotherRepositoryWorkflow() {
        setup()
        store.insertProject(RegisteredProject("other",999,ProjectKind.PERSONAL,temp.toString()),"owner:test")
        store.enqueue(999,2,"other-workflow")
        db.write { c ->
            c.mutate("INSERT INTO agent_attempts VALUES ('other','other-workflow','REVIEW',0,'RUNNING',NULL,'now')")
        }
        val before = store.findWorkflow(999,2)
        assertEquals(0,engine().recover()); assertEquals(before,store.findWorkflow(999,2))
        assertEquals("RUNNING",db.read { c -> c.rows("SELECT state FROM agent_attempts WHERE attempt_id='other'") { it.getString(1) }.single() })
    }

    @Test fun verifierNeverUsesSavedGitDirectoryForObjectOrIndexWrites() {
        setup()
        val guarded = temp.resolve("guarded-git")
        Files.writeString(guarded,"""#!/usr/bin/python3
import os, sys
if ${py("--git-dir=${clone.resolve(".git")}")} in sys.argv:
    assert any(cmd in sys.argv for cmd in ['rev-parse','read-tree','ls-files']), 'saved Git objects are read-only'
    if 'rev-parse' not in sys.argv:
        assert os.environ['GIT_INDEX_FILE'].startswith(${py(artifacts.toString()+"/")})
os.execv('/usr/bin/git',['/usr/bin/git']+sys.argv[1:])
""")
        assertTrue(guarded.toFile().setExecutable(true))
        val guardedEngine = WorkflowEngine(database,repo,binary,home,artifacts,snapshots,3,2,
            Duration.ofSeconds(3),git=guarded)
        assertEquals("PASS",guardedEngine.step())
        assertEquals("PASS",guardedEngine.step())
        assertEquals(WorkflowPhase.READY_FOR_PR,workflow().phase)
    }

    @Test fun reportPathIsNeverGivenToCandidateInterpreterAndEarlyExitCannotPass() {
        // Independent reviewer reproduction: zero TestCases, candidate writes report and exits.
        setup(tests="import json, os, sys\nwith open(sys.argv[2], 'x') as f: json.dump({'tests_run':3,'failures':0,'errors':0,'skipped':0},f)\nos._exit(0)\n")
        assertNotEquals("PASS",engine().step())
        assertNotEquals(WorkflowPhase.REVIEWING,workflow().phase)
        assertNotEquals(WorkflowPhase.READY_FOR_PR,workflow().phase)
        assertEquals(0,launches("review"))
    }

    @Test fun cleanWorkerExitWithoutCompletedInvocationIsUnknownAndNotCorrection() {
        setup(tests="import os\nos._exit(0)\n")
        assertEquals("UNKNOWN",engine().step())
        assertEquals(WorkflowHold.NEEDS_OWNER,workflow().hold)
        assertEquals(0,workflow().correctionsUsed); assertEquals(1,claims())
        assertEquals("NEEDS_OWNER",engine().step())
    }

    @Test fun unexpectedSuccessIsKnownFailureAndAllowsBoundedCorrection() {
        setup(tests="import unittest\nclass Unexpected(unittest.TestCase):\n @unittest.expectedFailure\n def test_fixed(self): self.assertEqual(1,1)\n")
        assertEquals("FAIL",engine(minimum=1).step())
        val receipt = assertNotNull(engine().verification("wf",0))
        assertEquals(1,receipt.unexpectedSuccesses)
        assertEquals(WorkflowPhase.QUEUED,workflow().phase); assertEquals(0,claims())
        assertEquals("CORRECTION_PLANNED",engine().step())
    }

    @Test fun ignoredCodingCacheIsNotPartOfSavedCandidateOrTestSnapshot() {
        setup(codingExtra="Path('.gitignore').write_text('cache.tmp\\n'); Path('cache.tmp').write_text('generated cache')")
        assertEquals("PASS",engine().step())
        val receipt = assertNotNull(engine().verification("wf",0))
        assertEquals(3,receipt.completedInvocations)
        assertFalse(Files.exists(Path.of(receipt.directory).resolve("files/cache.tmp")))
        assertEquals("generated cache",Files.readString(clone.resolve("cache.tmp")))
        assertEquals("PASS",engine().step())
    }

    @Test fun uncertainExistingPublicationEffectPreventsReviewAndReady() {
        setup(); assertEquals("PASS",engine().step())
        db.write { c -> c.mutate("""INSERT INTO effects_outbox(effect_id,workflow_id,kind,state,
            idempotency_key,intended_target,expected_sha,recorded_at)
            VALUES ('uncertain','wf','PUSH','UNCERTAIN','uncertain','fixture',?,'now')""","a".repeat(40)) }
        assertEquals("ALREADY_CONSUMED_OR_GUARD_DENIED",engine().step())
        assertEquals(0,launches("review")); assertEquals(WorkflowPhase.REVIEWING,workflow().phase)
    }

    companion object {
        private val pass = "result['verdict'] = 'PASS'; result['findings'] = []"
        private val changes = "result['verdict'] = 'CHANGES_REQUIRED'; result['findings'] = ['Add a concrete edge case']"
        private val suite = """
import unittest
from answer import add
class SumTest(unittest.TestCase):
    def test_positive(self): self.assertEqual(5,add(2,3))
    def test_negative(self): self.assertEqual(-5,add(-2,-3))
    def test_zero(self): self.assertEqual(0,add(0,0))
""".trimIndent()+"\n"
    }
}
