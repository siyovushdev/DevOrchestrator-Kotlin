package dev.orchestrator.remote

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.orchestrator.agent.StageBudget
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** No network, SSH daemon, real CLI/login, GitHub PAT or model inference. */
class RemoteWorkerTest {
    @TempDir lateinit var temp: Path
    private fun privateDir(name: String)=Files.createDirectory(temp.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    private fun privateFile(path: Path,text: String): Path=Files.writeString(path,text).also { Files.setPosixFilePermissions(it,PosixFilePermissions.fromString("rw-------")) }
    private lateinit var root: Path
    private lateinit var home: Path
    private lateinit var checkout: Path
    private lateinit var worker: Path
    private lateinit var config: Path
    private lateinit var scratch: Path
    private lateinit var calls: Path
    private fun setup(code: String="") {
        root=privateDir("worker-state"); home=privateDir("model-home"); checkout=privateDir("checkout"); scratch=privateDir("evidence")
        val install=privateDir("install")
        for(name in listOf("ssh-vm-worker.py","native-codex-supervisor.py","verify-python-unittest.py"))
            javaClass.getResourceAsStream("/$name")!!.use { Files.copy(it,install.resolve(name)) }
        worker=install.resolve("ssh-vm-worker.py")
        val auth=Files.createDirectory(home.resolve(".codex"))
        privateFile(auth.resolve("auth.json"),"ARTIFICIAL_MODEL_SECRET_DO_NOT_EXPORT")
        calls=temp.resolve("model-calls")
        val binary=temp.resolve("fake-codex")
        Files.writeString(binary,"""#!/usr/bin/python3
import json, sys
from pathlib import Path
args=sys.argv[1:]
if args[0]=='sandbox': sys.exit(0)  # fixture only: real native policy canary is a separate test
with Path(${RemoteProtocol.mapper.writeValueAsString(calls.toString())}).open('a') as out: out.write(json.dumps(args)+'\n')
sys.stdin.read()
$code
Path(args[args.index('--output-last-message')+1]).write_text('{"verdict":"PASS"}')
print(json.dumps({'type':'thread.started','thread_id':'fixture-'+Path.cwd().parent.name}))
print('{"type":"turn.completed"}')
""")
        binary.toFile().setExecutable(true,true)
        config=privateFile(temp.resolve("worker.json"),RemoteProtocol.mapper.writeValueAsString(mapOf("root" to root.toString(),"modelHome" to home.toString(),"codex" to binary.toString())))
        RemoteProtocol.git(checkout,"init","--template=")
        Files.writeString(checkout.resolve("hello.py"),"print('fixture')\n")
        Files.writeString(checkout.resolve("a.pending"),"ordinary source file")
        Files.writeString(checkout.resolve("a"),"ordinary neighboring file")
        RemoteProtocol.git(checkout,"add",".")
        RemoteProtocol.git(checkout,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-m","fixture")
    }
    private fun request(role: String="CODING",attempt: String="a1",workflow: String="w1",timeout: Long=3000): RemoteRequest {
        val tree=RemoteProtocol.git(checkout,"rev-parse","HEAD^{tree}")
        val identity=RemoteTaskIdentity(attempt,workflow,4,RemoteProtocol.git(checkout,"rev-parse","HEAD"),tree,0,role,
            if(role=="CODING") "" else "candidate-1",if(role=="CODING") "" else "a".repeat(64),if(role=="CODING") "" else "coding-session")
        return RemoteRequest.capture(identity,checkout,Files.createTempDirectory(temp,"capture-"),"fixture prompt",timeoutMs=timeout)
    }
    private fun transport()=WorkerTransport { wire,timeout -> WorkerWireProcess.exchange(listOf("/usr/bin/python3","-I","-S",worker.toString(),"serve",config.toString()),wire,timeout) }
    private fun wire(r: RemoteRequest)=RemoteProtocol.mapper.writeValueAsBytes(mapOf("op" to "submit","requestBase64" to Base64.getEncoder().encodeToString(r.bytes)))
    private fun query(r: RemoteRequest)=RemoteProtocol.mapper.writeValueAsBytes(mapOf("op" to "query","attemptId" to r.attemptId,"requestSha256" to r.sha256))
    private fun journal()=RemoteAttemptJournal(temp.resolve("controller.db"))
    private fun client(j: RemoteAttemptJournal=journal(),t: WorkerTransport=transport())=RemoteWorkerClient(j,t,RemoteEvidence(scratch))

    @Test fun cancelStopsGuestProcessAndDoesNotWriteFinishedResult() {
        setup()
        val sleep=ProcessBuilder("/bin/sleep","30").start()
        val stat=java.nio.file.Files.readString(java.nio.file.Path.of("/proc/${sleep.pid()}/stat"))
        val ticks=stat.substringAfterLast(')').trim().split(Regex("\\s+"))[19]
        val boot=java.nio.file.Files.readString(java.nio.file.Path.of("/proc/sys/kernel/random/boot_id")).trim()
        val attempt=root.resolve("a1"); java.nio.file.Files.createDirectory(attempt)
        val body="cancel-request".toByteArray()
        java.nio.file.Files.write(attempt.resolve("request.json"), body)
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        java.nio.file.Files.writeString(attempt.resolve("started.json"), RemoteProtocol.mapper.writeValueAsString(mapOf(
            "boot_id" to boot, "supervisor" to mapOf("pid" to sleep.pid(), "start_ticks" to ticks))))
        val wire=RemoteProtocol.mapper.writeValueAsBytes(mapOf("op" to "cancel","attemptId" to "a1","requestSha256" to digest))
        val proc=ProcessBuilder("/usr/bin/python3","-I","-S",worker.toString(),"serve",config.toString()).start()
        proc.outputStream.use { it.write(wire); it.write('\n'.code) }
        val out=proc.inputStream.readNBytes(8000).toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue()==0, out)
        assertFalse(sleep.isAlive)
        assertTrue(java.nio.file.Files.exists(attempt.resolve("cancel.request")))
        assertFalse(java.nio.file.Files.exists(attempt.resolve("result.json")))
        assertTrue(out.contains("CANCEL_REQUESTED"))
    }
    @Test fun remoteStageDeadlineMatchesTheFiniteOperatorCeiling() {
        setup()
        assertEquals(StageBudget.MAX_MILLIS, request(timeout=StageBudget.MAX_MILLIS).json["timeoutMs"].asLong())
        assertFails { request(timeout=StageBudget.MAX_MILLIS+1) }
        assertTrue(Files.readString(worker).contains("<= ${StageBudget.MAX_MILLIS}"))
    }
    @Test fun workflowPreparationAndTransportClaimRollbackTogetherBeforeAnyExchange() {
        setup(); val j=journal(); val r=request(); var exchanges=0
        val c=client(j,WorkerTransport { _,_ -> exchanges++; error("must not send") })
        assertFails { c.submitPrepared(r,{ true }) { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE fake_workflow_transition(id TEXT)") }
            error("fixture invariant failure")
        } }
        assertNull(j.find(r.attemptId)); assertEquals(0,exchanges)
        dev.orchestrator.storage.RuntimeDatabase(temp.resolve("controller.db")).read { connection ->
            connection.createStatement().use { statement -> statement.executeQuery("SELECT 1 FROM sqlite_master WHERE name='fake_workflow_transition'").use { assertFalse(it.next()) } }
        }
    }
    @Test fun consumedTransportClaimDoesNotRepeatPreparationOrSubmit() {
        setup(); val j=journal(); val r=request(); var exchanges=0; var plans=0
        val c=client(j,WorkerTransport { _,_ -> exchanges++; error("fixture lost connection") })
        assertFailsWith<RemoteExchangeUnknown> { c.submitPrepared(r,{ true }) { plans++ } }
        assertEquals("UNKNOWN",c.submitPrepared(r,{ true }) { plans++ })
        assertEquals(1,plans); assertEquals(1,exchanges)
    }

    @Test fun realWorkerReceiptAndExportAreBoundToRequestAndNativeCommand() {
        setup("Path('hello.py').write_text(\"print('changed')\\n\")")
        val r=request(); val j=journal()
        assertEquals("EVIDENCE_STORED",client(j).submit(r) { true })
        val evidence=RemoteProtocol.mapper.readTree(j.find(r.attemptId)!!.evidence)
        assertEquals(r.sha256,evidence["requestSha256"].asText())
        assertTrue(evidence["receipt"]["cleanup_echild"].asBoolean())
        assertNotEquals(ProcessHandle.current().pid(),evidence["receipt"]["supervisor"]["pid"].asLong())
        assertNotEquals(r.json["identity"]["sourceTree"].asText(),evidence["outputTree"].asText())
        val args=RemoteProtocol.mapper.readTree(Files.readAllLines(calls).single()).map { it.asText() }
        assertFalse("--sandbox" in args); assertTrue(args.any { it.startsWith("permissions.devorch=") && it.contains("\".\"=\"write\"") }); assertFalse(args.contains("resume")); assertFalse(args.any { it.contains("danger-full-access") })
        assertFalse(Files.readString(root.resolve("a1/result.json")).contains("ARTIFICIAL_MODEL_SECRET"))
        assertEquals("print('fixture')\n",Files.readString(checkout.resolve("hello.py")))
    }
    @Test fun separateReviewSessionUsesReadOnlyAndCannotMasqueradeAsCodingSession() {
        setup(); val j=journal()
        assertEquals("EVIDENCE_STORED",client(j).submit(request("CODING","coding")) { true })
        assertEquals("EVIDENCE_STORED",client(j).submit(request("REVIEW","review")) { true })
        val args=Files.readAllLines(calls).map { RemoteProtocol.mapper.readTree(it).map { v -> v.asText() } }
        assertEquals(2,args.size); assertTrue(args[1].any { it.startsWith("permissions.devorch=") && it.contains("\".\"=\"read\"") })
        val saved=j.find("review")!!
        val forged=RemoteProtocol.mapper.readTree(saved.evidence) as ObjectNode
        val events="""{"type":"thread.started","thread_id":"coding-session"}
{"type":"turn.completed"}
""".toByteArray()
        (forged["artifacts"] as ObjectNode).set<ObjectNode>("events.log",RemoteProtocol.mapper.valueToTree(mapOf("data" to Base64.getEncoder().encodeToString(events),"sha256" to RemoteProtocol.digest(events))))
        assertFails { RemoteEvidence(scratch).check(saved.request,RemoteProtocol.mapper.writeValueAsBytes(forged)) }
    }
    @Test fun lostResponseAndControllerRestartQuerySavedResultWithoutSecondExecution() {
        setup(); val r=request(); val j=journal(); var submits=0; var queries=0
        val actual=transport()
        val lost=WorkerTransport { w,t ->
            if(RemoteProtocol.mapper.readTree(w)["op"].asText()=="submit") { submits++; actual.exchange(w,t); error("lost response") }
            else { queries++; actual.exchange(w,t) }
        }
        assertFailsWith<RemoteExchangeUnknown> { client(j,lost).submit(r) { true } }
        assertEquals("UNKNOWN",journal().find("a1")!!.state)
        assertEquals("UNKNOWN",client(journal(),lost).submit(r) { true })
        assertEquals(1,submits)
        assertEquals("EVIDENCE_STORED",client(journal(),lost).reconcile("a1"))
        assertEquals(1,queries); assertEquals(1,Files.readAllLines(calls).size)
        assertEquals("EVIDENCE_STORED",client(journal(),lost).reconcile("a1")); assertEquals(1,queries)
        // Guest's own dedup also persists, independently of controller journal.
        val result=actual.exchange(wire(r),18000)
        assertEquals("FINISHED",RemoteProtocol.mapper.readTree(result)["state"].asText())
        assertEquals(1,Files.readAllLines(calls).size)
    }
    @Test fun lostSshKillsTheGuestInsteadOfLeavingItRunning() {
        setup("import time; time.sleep(30)")
        val r=request(timeout=4000); val j=journal()
        val cut=WorkerTransport { w,_ -> WorkerWireProcess.exchange(listOf("/usr/bin/python3","-I","-S",worker.toString(),"serve",config.toString()),w,350) }
        assertFailsWith<RemoteExchangeUnknown> { client(j,cut).submit(r) { true } }
        Thread.sleep(400)
        val observed=RemoteProtocol.mapper.readTree(transport().exchange(query(r),3000))
        assertNotEquals("RUNNING", observed["state"].asText())
        assertFalse(Files.exists(root.resolve("a1/result.json")))
        val before=if (Files.exists(calls)) Files.readAllLines(calls).size else 0
        assertEquals("UNKNOWN", client(journal()).reconcile("a1"))
        val after=if (Files.exists(calls)) Files.readAllLines(calls).size else 0
        assertEquals(before, after)
    }
    @Test fun sshStageTimeoutIsTimeoutNotAHeldSuccess() {
        setup("import time; time.sleep(5)")
        val state=client().submit(request(timeout=300)) { true }
        assertEquals("TIMEOUT", state)
        assertFalse(Files.exists(root.resolve("a1/result.json")))
        assertTrue(Files.readString(root.resolve("a1/failure.json")).contains("STAGE_TIMEOUT"))
    }
    @Test fun forgedUsageJsonIsNotCodexAttestation() {
        val usageOnly=RemoteProtocol.mapper.readTree("""{"usage":{"input_tokens":1,"output_tokens":1}}""")
        assertFalse(dev.orchestrator.agent.nativecli.CodexAttestation.guestBinary(usageOnly))
        val script=RemoteProtocol.mapper.readTree("""{"codexBinary":{"elf":false,"size":40,"sha256":"${"ab".repeat(32)}"}}""")
        assertFalse(dev.orchestrator.agent.nativecli.CodexAttestation.guestBinary(script))
        val elf=RemoteProtocol.mapper.readTree("""{"codexBinary":{"elf":true,"size":2000000,"sha256":"${"cd".repeat(32)}"}}""")
        assertTrue(dev.orchestrator.agent.nativecli.CodexAttestation.guestBinary(elf))
    }
    @Test fun neverSentUnknownAndOwnerDenialDoNotAuthorizeReplayOrOtherSlot() {
        setup(); val j=journal(); val r=request(); var calls=0
        val disconnected=WorkerTransport { _,_ -> calls++; error("ssh auth/key/host failure") }
        assertFailsWith<IllegalStateException> { client(j,disconnected).submit(r) { false } }
        assertNull(j.find("a1")); assertEquals(0,calls)
        assertFailsWith<RemoteExchangeUnknown> { client(j,disconnected).submit(r) { true } }
        assertEquals("UNKNOWN",client(j).reconcile("a1")) // NOT_FOUND is no retry grant
        assertEquals("UNKNOWN",client(j,disconnected).submit(r) { true }); assertEquals(1,calls)
        assertEquals("DUPLICATE_SLOT",client(j,disconnected).submit(request(attempt="renamed")) { true })
        assertFailsWith<IllegalStateException> { client(j,disconnected).submit(request(attempt="other",workflow="other-workflow")) { true } }
        assertEquals(1,calls)
    }
    @Test fun malformedOrPartialEvidenceCannotBecomeSuccessAndIsNotRetried() {
        setup(); val r=request(); val j=journal(); var calls=0
        val truncated=WorkerTransport { _,_ -> calls++; "{\"state\":\"FINISHED\"".toByteArray() }
        assertFailsWith<RemoteExchangeUnknown> { client(j,truncated).submit(r) { true } }
        assertEquals("UNKNOWN",client(j,truncated).submit(r) { true }); assertEquals(1,calls)
    }
    @Test fun bindsSourceIdentityInstallationDigestAndArtifactBytes() {
        setup(); val r=request(); val bytes=transport().exchange(wire(r),18000)
        val valid=RemoteProtocol.mapper.readTree(bytes) as ObjectNode
        for(change in listOf<(ObjectNode)->Unit>(
            { it.put("requestSha256","b".repeat(64)) },
            { (it["identity"] as ObjectNode).put("issueNumber",99) },
            { (it["receipt"] as ObjectNode).put("cleanup_echild",false) },
            { (it["receipt"]["supervisor"] as ObjectNode).put("start_ticks","99999") },
            { it.put("outputTree","c".repeat(40)) },
            { (it["artifacts"]["events.log"] as ObjectNode).put("sha256","d".repeat(64)) }
        )) {
            val changed=valid.deepCopy(); change(changed)
            assertFails { RemoteEvidence(scratch).check(r,RemoteProtocol.mapper.writeValueAsBytes(changed)) }
        }
        RemoteEvidence(scratch).check(r,bytes)
        val foreign=r.json.deepCopy<ObjectNode>().put("repositoryId",1)
        assertFails { RemoteRequest(RemoteProtocol.mapper.writeValueAsBytes(foreign)) }
        val mismatched=r.json.deepCopy<ObjectNode>().put("workerSha256","e".repeat(64))
        assertFails { RemoteRequest(RemoteProtocol.mapper.writeValueAsBytes(mismatched)) }
    }
    @Test fun traversalSymlinksHardlinksAndServicePathsAreRejected() {
        setup()
        for(name in listOf("../secret","/etc/passwd","a/.git/config",".codex/auth.json",".ssh/id_rsa")) assertFails { RemoteProtocol.path(name) }
        Files.createSymbolicLink(checkout.resolve("link"),home.resolve(".codex/auth.json"))
        assertFails { request() }; Files.delete(checkout.resolve("link"))
        Files.createLink(checkout.resolve("hard"),home.resolve(".codex/auth.json"))
        assertFails { request() }
    }
    @Test fun missingGuestAuthDoesNotLaunchModelAndRemainsHeld() {
        setup(); Files.delete(home.resolve(".codex/auth.json")); val r=request(); val j=journal()
        assertEquals("UNKNOWN",client(j).submit(r) { true })
        assertFalse(Files.exists(calls))
        assertEquals("UNKNOWN",client(j).reconcile("a1"))
    }
    @Test fun pythonChildrenCannotReadOrModifyModelAuthControllerCanaryOrWorkerState() {
        setup()
        val secret=privateFile(temp.resolve("controller-canary"),"FAKE_CONTROLLER_PAT")
        val auth=home.resolve(".codex/auth.json")
        val tests=Files.createDirectory(checkout.resolve("tests"))
        Files.writeString(tests.resolve("test_boundaries.py"),"""import pathlib, unittest
class Boundary(unittest.TestCase):
    def test_hidden(self):
        for text in ${RemoteProtocol.mapper.writeValueAsString(listOf(secret.toString(),auth.toString(),root.resolve("a1/request.json").toString()))}:
            p=pathlib.Path(text)
            with self.assertRaises(OSError): p.read_bytes()
            with self.assertRaises(OSError): p.open('r+b')
""")
        RemoteProtocol.git(checkout,"add",".")
        RemoteProtocol.git(checkout,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-m","boundary fixture")
        val j=journal()
        assertEquals("EVIDENCE_STORED",client(j).submit(request("TESTS")) { true })
        val e=RemoteProtocol.mapper.readTree(j.find("a1")!!.evidence)
        assertEquals(0,e["receipt"]["exit_code"].asInt(), e.toString().take(1500))
        assertEquals("FAKE_CONTROLLER_PAT",Files.readString(secret)); assertEquals("ARTIFICIAL_MODEL_SECRET_DO_NOT_EXPORT",Files.readString(auth))
        assertFalse(Files.exists(calls))
        assertEquals(j.find("a1")!!.request.sha256,RemoteProtocol.digest(Files.readAllBytes(root.resolve("a1/request.json"))))
    }
    @Test fun installedNativePolicyProtectsSecretsForCodingAndReadOnlyReviewWithoutInference() {
        val binary=System.getenv("DEVORCH_REMOTE_NATIVE_CANARY_BINARY")
        org.junit.jupiter.api.Assumptions.assumeTrue(!binary.isNullOrBlank(),"Pinned native Codex canary runs in CI; no model or real auth")
        setup()
        val driver=temp.resolve("policy-canary.py")
        Files.writeString(driver,"""import importlib.util, json, os
from pathlib import Path
spec=importlib.util.spec_from_file_location('worker',${RemoteProtocol.mapper.writeValueAsString(worker.toString())})
w=importlib.util.module_from_spec(spec); spec.loader.exec_module(w)
c=w.config(Path(${RemoteProtocol.mapper.writeValueAsString(config.toString())}))
c['codex']=${RemoteProtocol.mapper.writeValueAsString(binary)}
# Match the production installation/state topology, all outside denied tmp.
# This temporary HOME subdirectory contains public helpers and fake auth only.
import tempfile, shutil, atexit
os.umask(0o077)
fixture=Path(tempfile.mkdtemp(prefix='.devorch-native-canary-',dir=Path(${RemoteProtocol.mapper.writeValueAsString(System.getProperty("user.home"))})))
atexit.register(shutil.rmtree,fixture)
installed=fixture/'install'; installed.mkdir()
for name in ('ssh-vm-worker.py','native-codex-supervisor.py','verify-python-unittest.py'):
    shutil.copyfile(w.INSTALL/name,installed/name)
w.INSTALL=installed
original_root=Path(c['root'])
state=fixture/'state'; state.mkdir(mode=0o700)
home=fixture/'model-home'; home.mkdir(mode=0o700); (home/'.codex').mkdir()
w.write(home/'.codex/auth.json',b'ARTIFICIAL_AUTH_ONLY')
c.update(root=str(state),modelHome=str(home))
configuration=fixture/'config.json'; w.write(configuration,w.encoded(c)); c=w.config(configuration)
stdout,stderr=os.dup(1),os.dup(2)
for role in ('CODING','REVIEW'):
    a=Path(c['root'])/('native-canary-'+role); a.mkdir(mode=0o700)
    w.write(a/'request.json',b'ARTIFICIAL_WORKER_STATE')
    source=a/'source'; source.mkdir(); w.git(source,'init','--template=')
    try:
        w.native_policy(c,source,a,role,w.supervisor_module())
    finally:
        os.dup2(stdout,1); os.dup2(stderr,2)
        observed=original_root/('native-canary-'+role); observed.mkdir()
        for name in ('policy.log','policy-receipt.json'):
            if (a/name).exists(): shutil.copyfile(a/name,observed/name)
print('NATIVE_PERMISSION_CANARY_PASS_NO_INFERENCE')
""")
        val output=try { WorkerWireProcess.exchange(listOf("/usr/bin/python3","-I","-S",driver.toString()),"{}".toByteArray(),30000) }
        catch(e: Exception) {
            val diagnostic=listOf("CODING","REVIEW").joinToString("\n") { role ->
                val log=root.resolve("native-canary-$role/policy.log")
                if(Files.exists(log)) Files.readString(log).take(8192) else "no $role policy log"
            }
            throw AssertionError("No-inference native canary: $diagnostic",e)
        }
        assertEquals("NATIVE_PERMISSION_CANARY_PASS_NO_INFERENCE",output.toString(Charsets.UTF_8).trim())
        for(role in listOf("CODING","REVIEW")) {
            val receipt=RemoteProtocol.mapper.readTree(Files.readAllBytes(root.resolve("native-canary-$role/policy-receipt.json")))
            assertEquals(0,receipt["exit_code"].asInt()); assertTrue(receipt["cleanup_echild"].asBoolean())
        }
        assertFalse(Files.exists(calls))
    }
    @Test fun changedAttemptCannotReusePreviouslyStoredEvidence() {
        setup(); val j=journal(); val r=request()
        assertEquals("EVIDENCE_STORED",client(j).submit(r) { true })
        val changed=r.json.deepCopy<ObjectNode>().put("prompt","different task")
        assertFailsWith<IllegalStateException> { client(j).submit(RemoteRequest(RemoteProtocol.mapper.writeValueAsBytes(changed))) { true } }
        assertEquals(1,Files.readAllLines(calls).size)
    }
    @Test fun sqliteFailureRemainsInternalAndCannotBeReclassifiedAsSshUnknown() {
        setup(); val j=journal(); val real=transport()
        val broken=WorkerTransport { wire,timeout ->
            val response=real.exchange(wire,timeout)
            java.sql.DriverManager.getConnection("jdbc:sqlite:${temp.resolve("controller.db")}").use { c ->
                c.createStatement().use { it.execute("CREATE TRIGGER fail_remote_record BEFORE UPDATE ON remote_attempts BEGIN SELECT RAISE(ABORT,'fixture sqlite failure'); END") }
            }
            response
        }
        val failure=assertFails { client(j,broken).submit(request()) { true } }
        assertFalse(failure is RemoteExchangeUnknown)
        assertTrue(failure is java.sql.SQLException)
        assertEquals("SENDING",journal().find("a1")!!.state) // immutable intent, no fabricated UNKNOWN write
    }
    @Test fun guestRejectsUnauthorizedIdentityAndChangedBaseBeforeModelExecution() {
        setup(); val r=request()
        val unauthorized=r.json.deepCopy<ObjectNode>().put("repositoryId",999)
        val invalid=RemoteProtocol.mapper.writeValueAsBytes(mapOf("op" to "submit","requestBase64" to Base64.getEncoder().encodeToString(RemoteProtocol.mapper.writeValueAsBytes(unauthorized))))
        assertFails { transport().exchange(invalid,3000) }
        assertFalse(Files.exists(root.resolve("a1"))); assertFalse(Files.exists(calls))
        val wrongBase=r.json.deepCopy<ObjectNode>()
        (wrongBase["identity"] as ObjectNode).put("baseSha","f".repeat(40))
        val changed=RemoteRequest(RemoteProtocol.mapper.writeValueAsBytes(wrongBase))
        assertEquals("UNKNOWN",client().submit(changed) { true })
        assertFalse(Files.exists(calls))
    }
    @Test fun escapedDescendantsCannotBecomeAcceptedEvidence() {
        setup("import subprocess; subprocess.Popen(['/usr/bin/python3','-c','import time; time.sleep(30)'],start_new_session=True)")
        val j=journal(); val r=request()
        assertFailsWith<RemoteExchangeUnknown> { client(j).submit(r) { true } }
        assertEquals("UNKNOWN",j.find("a1")!!.state)
        val raw=RemoteProtocol.mapper.readTree(Files.readAllBytes(root.resolve("a1/supervisor.json")))
        assertTrue(raw["cleanup_echild"].asBoolean()); assertEquals("descendants_after_exit",raw["cause"].asText())
        assertEquals("UNKNOWN",client(journal()).submit(r) { true }); assertEquals(1,Files.readAllLines(calls).size)
    }
    @Test fun unsupportedNativePolicyCannotFallBackToAnUnrestrictedModel() {
        setup()
        val binary=Path.of(RemoteProtocol.mapper.readTree(Files.readAllBytes(config))["codex"].asText())
        Files.writeString(binary,Files.readString(binary).replace("if args[0]=='sandbox': sys.exit(0)","if args[0]=='sandbox': sys.exit(91)"))
        assertEquals("UNKNOWN",client().submit(request()) { true })
        assertFalse(Files.exists(calls))
        assertTrue(Files.exists(root.resolve("a1/policy-receipt.json")))
    }
    @Test fun inheritedLegacySandboxConfigurationCannotOverrideTheNativeProfile() {
        setup(); privateFile(home.resolve(".codex/config.toml"),"sandbox_mode = \"danger-full-access\"\n")
        assertEquals("UNKNOWN",client().submit(request()) { true })
        assertFalse(Files.exists(calls))
        assertFalse(Files.exists(root.resolve("a1/policy-receipt.json")))
    }
    @Test fun modelCredentialAliasesAreRejectedBeforeAnyRoleCanStart() {
        setup()
        val original=home.resolve(".codex")
        val external=temp.resolve("external-model-config")
        Files.move(original,external); Files.createSymbolicLink(original,external)
        val j=journal()
        assertFailsWith<RemoteExchangeUnknown> { client(j).submit(request("TESTS")) { true } }
        assertFalse(Files.exists(root.resolve("a1"))); assertFalse(Files.exists(calls))
        Files.delete(original); Files.move(external,original)
        val auth=original.resolve("auth.json"); Files.move(auth,temp.resolve("external-auth"))
        Files.createSymbolicLink(auth,temp.resolve("external-auth"))
        assertFails { transport().exchange(query(request("TESTS",attempt="other")),3000) }
        assertFalse(Files.exists(root.resolve("other")))
    }
    @Test fun sshOptionsPinHostAndKeyAndDoNotDelegateAgentOrIssueCommands() {
        val key=privateFile(temp.resolve("fake-key"),"not a real key")
        val hosts=privateFile(temp.resolve("known-hosts"),"artificial host pin")
        val args=SshWorkerTransport(SshWorkerConfig(key,hosts)).arguments()
        assertEquals(SshWorkerTransport.COMMAND,args.last())
        for(option in listOf("BatchMode=yes","StrictHostKeyChecking=yes","IdentitiesOnly=yes","IdentityAgent=none","ForwardAgent=no","ClearAllForwardings=yes","ProxyCommand=none")) assertTrue(option in args)
        assertTrue("UserKnownHostsFile=$hosts" in args); assertTrue(key.toString() in args)
        Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-r--r--"))
        assertFails { SshWorkerConfig(key,hosts) }
    }
    @Test fun wireProcessHasNoInheritedControllerSecretsAndBoundsOutputAndDeadline() {
        val script=temp.resolve("wire.py")
        Files.writeString(script,"import os,sys; sys.stdin.buffer.readline(); print(sorted(os.environ))")
        val out=WorkerWireProcess.exchange(listOf("/usr/bin/python3","-I",script.toString()),"{}".toByteArray(),3000).toString(Charsets.UTF_8)
        assertFalse(out.contains("DEVORCH_PERSONAL_TOKEN")); assertFalse(out.contains("SSH_AUTH_SOCK"))
        Files.writeString(script,"import time; time.sleep(10)")
        assertFails { WorkerWireProcess.exchange(listOf("/usr/bin/python3","-I",script.toString()),"{}".toByteArray(),100) }
        Files.writeString(script,"import sys; sys.stdin.buffer.readline(); sys.stdout.write('x'*(24*1024*1024+1))")
        assertFails { WorkerWireProcess.exchange(listOf("/usr/bin/python3","-I",script.toString()),"{}".toByteArray(),3000) }
    }
}
