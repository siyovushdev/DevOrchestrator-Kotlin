package dev.orchestrator.agent.nativecli

import dev.orchestrator.agent.*
import dev.orchestrator.storage.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Real local Git + real Linux child lifecycle; Codex/model/network are FAKE, in temp only. */
class NativeCodingRunnerTest {
    @TempDir lateinit var temp: Path
    private val repo = 1424152600L
    private val database get() = temp.resolve("state.db")
    private val clone get() = temp.resolve("clone")
    private val artifacts get() = temp.resolve("artifacts")
    private val home get() = temp.resolve("fake-home")
    private val binary get() = temp.resolve("fake-codex")
    private val store get() = SqliteTaskStore(database)
    private val journal get() = CodingAttemptJournal(database)

    private fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("/usr/bin/git","-c","core.hooksPath=/dev/null")+args)
            .directory(clone.toFile()).redirectErrorStream(true)
        p.environment().clear()
        p.environment().putAll(mapOf("HOME" to home.toString(),"PATH" to "/usr/bin:/bin",
            "GIT_CONFIG_NOSYSTEM" to "1","GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_TEMPLATE_DIR" to temp.resolve("template").toString()))
        val child = p.start()
        child.outputStream.close()
        val output = child.inputStream.readNBytes(8192)
        check(child.waitFor(5,TimeUnit.SECONDS) && child.exitValue()==0) { String(output) }
        return String(output).trim()
    }

    private fun setup(body: String = success): CodingIntent {
        Files.createDirectory(clone)
        Files.createDirectory(artifacts)
        Files.createDirectory(home)
        Files.createDirectory(home.resolve(".codex"))
        Files.createDirectory(temp.resolve("template"))
        Files.writeString(clone.resolve("README"),"disposable source\n")
        git("init","-q","--initial-branch=main")
        git("add","README")
        git("-c","user.name=Test","-c","user.email=test@example.invalid","commit","-qm","fixture")
        val base = git("rev-parse","HEAD")
        val tree = git("rev-parse","HEAD^{tree}")
        store.insertProject(RegisteredProject("fixture",repo,ProjectKind.PERSONAL,temp.toString()),"owner:test")
        val policy = assertNotNull(store.findPolicy(repo))
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=2,personalAutoExecution=true),
            1,"owner:test","Fake child tests only"))
        store.acceptGitHubIssue(GitHubIssueRecord(repo,1,1001,Instant.EPOCH,"Tiny sum","Task body",base,true),"workflow")
        val catalog = SqliteAgentCatalog(database)
        catalog.declareManifest(AgentCapabilityManifest("codex-cli","openai",1,setOf(AgentRole.CODING),
            setOf(AgentSandbox.NATIVE_PERSONAL_WRITE),mapOf("fake-model" to setOf(ReasoningEffort.DEFAULT))),null,"owner:test")
        catalog.saveProfile(AgentProfile("coding",1,repo,AgentRole.CODING,"codex-cli",1,"openai","fake-model",
            ReasoningEffort.DEFAULT,AgentSandbox.NATIVE_PERSONAL_WRITE,30,1000),null,"owner:test")
        catalog.bindRole(repo,AgentRole.CODING,"coding",1,null,"owner:test")
        Files.writeString(binary,"""#!/usr/bin/python3
import json, os, signal, sys, time
from pathlib import Path
assert sys.argv[1] == 'exec'
assert sys.argv[sys.argv.index('--sandbox')+1] == 'workspace-write'
assert sys.argv[sys.argv.index('--model')+1] == 'fake-model'
assert 'Task body' in sys.stdin.read()
last = Path(sys.argv[sys.argv.index('--output-last-message')+1])
with Path('launches').open('a') as out: out.write('one\n')
"""+body)
        check(binary.toFile().setExecutable(true))
        return assertNotNull(journal.plan(repo,1,0,"attempt",clone,tree))
    }

    private fun runner(timeout: Duration = Duration.ofSeconds(3), outputLimit: Int = 1_048_576,
                       fixedRepo: Long = repo) = NativeCodingRunner(database,fixedRepo,binary,home,artifacts,
        timeout=timeout,outputLimit=outputLimit)

    private fun claimCount(): Int = DriverManager.getConnection("jdbc:sqlite:$database").use { c ->
        c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM runtime_reservations").use { r -> r.next(); r.getInt(1) } }
    }

    @Test fun capturesRealCandidateAndPinnedProcessReceiptWithExplicitChildProxyAndNoReplay() {
        val intent = setup("""
assert os.environ['HTTP_PROXY'] == 'http://127.0.0.1:10809'
assert os.environ['https_proxy'] == os.environ['HTTP_PROXY']
assert os.environ['ALL_PROXY'] == os.environ['HTTP_PROXY']
assert os.environ['NODE_USE_ENV_PROXY'] == '1'
assert os.environ['HOME'].endswith('fake-home')
assert os.environ['CODEX_HOME'].endswith('fake-home/.codex')
assert not any(k in os.environ for k in ['GITHUB_TOKEN','GH_TOKEN','SSH_AUTH_SOCK','DEVORCH_GITHUB_READ_TOKEN'])
"""+success)
        val hostProxy = System.getenv("HTTP_PROXY")
        val result = runner().run("attempt")
        assertEquals(AttemptState.SUCCEEDED,result.state,result.reason)
        assertEquals(WorkflowPhase.VERIFYING,store.findWorkflow(repo,1)?.phase)
        assertEquals(AttemptState.SUCCEEDED,journal.find("attempt")?.state)
        assertNotEquals(intent.sourceTree,result.candidateTree)
        assertTrue(Files.readString(result.artifacts!!.resolve("candidate.diff")).contains("answer.py"))
        assertEquals(intent.baseSha,git("rev-parse","HEAD"))
        // Candidate uses a separate index; original index has no staged Coding changes.
        assertEquals("",git("diff","--cached","--name-only"))
        assertEquals(0,claimCount())
        assertEquals(hostProxy,System.getenv("HTTP_PROXY"))
        val processEvent = store.listEvents("workflow").single { it.type=="CODING_PROCESS_STARTED" }
        assertTrue(processEvent.detail!!.contains("start_ticks"))
        val completed = store.listEvents("workflow").single { it.type=="CODING_PROCESS_SUCCEEDED" }
        assertTrue(completed.detail!!.contains("cleanup_echild"))
        assertTrue(completed.detail!!.contains("NOT_RUN_BY_CONTROLLER"))
        assertNull(runner().run("attempt").state)
        assertEquals("one\n",Files.readString(clone.resolve("launches")))
    }

    @Test fun timeoutReapsDetachedDoubleForkedDescendantsAndRecordsKnownFailure() {
        setup(detached+"\ntime.sleep(30)\n")
        val result = runner(Duration.ofMillis(500)).run("attempt")
        assertEquals(AttemptState.FAILED,result.state,result.reason)
        assertTrue(result.reason.contains("timeout"),result.reason)
        val descendant = Files.readString(clone.resolve("detached.pid")).trim().toLong()
        assertFalse(ProcessHandle.of(descendant).map { it.isAlive }.orElse(false))
        assertEquals(0,claimCount())
        assertEquals(WorkflowHold.NEEDS_OWNER,store.findWorkflow(repo,1)?.hold)
        val receipt = ObjectMapper().readTree(Files.readString(result.artifacts!!.resolve("supervisor-receipt.json")))
        assertTrue(receipt["cleanup_echild"].asBoolean())
        assertTrue(receipt["reaped"].asInt()>=3)
    }

    @Test fun rootExitWithLiveDetachedChildIsNotSuccessEvenWhenRootExitIsZero() {
        setup(detached+"\nlast.write_text('root exit is not sufficient')\n")
        val result = runner().run("attempt")
        assertEquals(AttemptState.FAILED,result.state,result.reason)
        assertTrue(result.reason.contains("descendants_after_exit"),result.reason)
        val pid = Files.readString(clone.resolve("detached.pid")).trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test fun outputLimitStopsAndReapsChildWhileKeepingLogBounded() {
        setup("\nsys.stdout.write('x'*2000000)\nsys.stdout.flush()\ntime.sleep(30)\n")
        val result = runner(outputLimit=1024).run("attempt")
        assertEquals(AttemptState.FAILED,result.state,result.reason)
        assertEquals("OUTPUT_LIMIT",result.reason)
        assertEquals(1024,Files.size(result.artifacts!!.resolve("events.log")))
        assertEquals(0,claimCount())
    }

    @Test fun supervisorDeathRetainsUnknownClaimsAndNeverRestartsChild() {
        setup("\nos.kill(os.getppid(), signal.SIGKILL)\nos._exit(0)\n")
        val result = runner().run("attempt")
        assertEquals(AttemptState.UNKNOWN,result.state)
        assertEquals(1,claimCount())
        assertEquals(WorkflowHold.NEEDS_OWNER,store.findWorkflow(repo,1)?.hold)
        assertNull(runner().run("attempt").state)
        assertEquals("one\n",Files.readString(clone.resolve("launches")))
    }

    @Test fun metadataFilterInjectedByChildCannotExecuteDuringCandidateCapture() {
        setup("""
with Path('.git/config').open('a') as out: out.write('\n[filter "trap"]\n clean = touch ../trap-marker\n')
Path('.gitattributes').write_text('* filter=trap\n')
"""+success)
        val result = runner().run("attempt")
        assertEquals(AttemptState.UNKNOWN,result.state)
        assertFalse(Files.exists(temp.resolve("trap-marker")))
        assertEquals(1,claimCount())
    }

    @Test fun wrongScopeDirtySourceAndRevokedPolicyDoNotConsumeOrSpawn() {
        setup()
        assertNull(runner(fixedRepo=999).run("attempt").state)
        Files.writeString(clone.resolve("README"),"dirty\n")
        assertNull(runner().run("attempt").state)
        assertFalse(Files.exists(clone.resolve("launches")))
        Files.writeString(clone.resolve("README"),"disposable source\n")
        val policy = assertNotNull(store.findPolicy(repo))
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=3,personalAutoExecution=false),
            2,"owner:test","Revoke before launch"))
        assertNull(runner().run("attempt").state)
        assertFalse(Files.exists(clone.resolve("launches")))
        assertEquals(AttemptState.PLANNED,journal.find("attempt")?.state)
    }

    @Test fun twoConcurrentRunnersLaunchOneChildAndOneIntent() {
        setup()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = workers.invokeAll((1..2).map { Callable { runner().run("attempt") } })
                .map { it.get(15,TimeUnit.SECONDS) }
            assertEquals(1,results.count { it.state==AttemptState.SUCCEEDED },results.toString())
            assertEquals("one\n",Files.readString(clone.resolve("launches")))
            assertEquals(1,store.listEvents("workflow").count { it.type=="CODING_STARTING_INTENT" })
        } finally { workers.shutdownNow() }
    }

    @Test fun completionReceiptFailureRollsBackSuccessAndReleaseThenHoldsUnknown() {
        setup()
        DriverManager.getConnection("jdbc:sqlite:$database").use { c -> c.createStatement().use {
            it.execute("""CREATE TRIGGER inject_fault BEFORE INSERT ON workflow_events
                WHEN NEW.event_type='CODING_PROCESS_SUCCEEDED' BEGIN SELECT RAISE(ABORT,'fault'); END""")
        } }
        val result = runner().run("attempt")
        assertEquals(AttemptState.UNKNOWN,result.state)
        assertEquals(1,claimCount())
        assertEquals(WorkflowPhase.CODING,store.findWorkflow(repo,1)?.phase)
        assertEquals(WorkflowHold.NEEDS_OWNER,store.findWorkflow(repo,1)?.hold)
    }

    @Test fun ambiguousGitProbeBeforeSpawnBecomesUnknownAndCannotProbeOrLaunchAgain() {
        setup()
        val fakeGit = temp.resolve("noisy-git")
        Files.writeString(fakeGit,"#!/usr/bin/python3\nimport sys\nsys.stdout.write('x'*1100000)\n")
        check(fakeGit.toFile().setExecutable(true))
        val runner = NativeCodingRunner(database,repo,binary,home,artifacts,git=fakeGit)
        val result = runner.run("attempt")
        assertEquals(AttemptState.UNKNOWN,result.state)
        assertEquals(AttemptState.UNKNOWN,journal.find("attempt")?.state)
        assertEquals(WorkflowHold.NEEDS_OWNER,store.findWorkflow(repo,1)?.hold)
        assertEquals(1,claimCount())
        assertFalse(Files.exists(clone.resolve("launches")))
        assertNull(runner.run("attempt").state)
    }

    @Test fun nonzeroChildExitIsKnownFailureWithCleanupAndNoVerificationPass() {
        setup("\nsys.exit(7)\n")
        val result = runner().run("attempt")
        assertEquals(AttemptState.FAILED,result.state,result.reason)
        assertTrue(result.reason.endsWith("exit_7"))
        assertEquals(0,claimCount())
        assertEquals(WorkflowPhase.CODING,store.findWorkflow(repo,1)?.phase)
        assertEquals(WorkflowHold.NEEDS_OWNER,store.findWorkflow(repo,1)?.hold)
    }

    @Test fun interruptRequestsCleanupAndPreservesThreadInterruptFlag() {
        setup("\ntime.sleep(30)\n")
        val result = AtomicReference<CodingRunResult>()
        val restored = AtomicBoolean(false)
        val worker = Thread {
            result.set(runner().run("attempt"))
            restored.set(Thread.currentThread().isInterrupted)
        }
        worker.start()
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while (!Files.exists(clone.resolve("launches")) && System.nanoTime()<deadline) Thread.sleep(10)
        assertTrue(Files.exists(clone.resolve("launches")))
        worker.interrupt()
        worker.join(12_000)
        assertFalse(worker.isAlive)
        assertEquals(AttemptState.FAILED,result.get().state,result.get().reason)
        assertEquals("INTERRUPTED",result.get().reason)
        assertTrue(restored.get())
        assertEquals(0,claimCount())
    }

    companion object {
        private val success = """
Path('answer.py').write_text('def add(a,b): return a+b\n')
last.write_text('fake completion; not a real model')
print('{"type":"fake.completed"}')
"""
        private val detached = """
pid = os.fork()
if pid == 0:
    os.setsid()
    grandchild = os.fork()
    if grandchild:
        os._exit(0)
    Path('detached.pid').write_text(str(os.getpid()))
    time.sleep(30)
    os._exit(0)
while not Path('detached.pid').exists(): time.sleep(0.005)
"""
    }
}
