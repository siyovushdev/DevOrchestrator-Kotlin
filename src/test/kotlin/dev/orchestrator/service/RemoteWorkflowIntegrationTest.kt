package dev.orchestrator.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.agent.nativecli.*
import dev.orchestrator.publish.*
import dev.orchestrator.storage.*
import dev.orchestrator.remote.*
import dev.orchestrator.workflow.*
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Production service + production runtime/engine/publisher. Only remote GitHub/model replaced. */
class RemoteWorkflowIntegrationTest {
    @TempDir lateinit var temp: Path
    private val mapper=ObjectMapper()
    private val root get()=temp.resolve("state")
    private val bare get()=temp.resolve("remote.git")
    private val binary get()=temp.resolve("fake-codex")
    private val home get()=temp.resolve("agent-home")
    private val remotePrs=mutableMapOf<String,Map<String,Any?>>()
    private var posts=0
    private var sends=0
    private var losePr=false
    private var correctOnce=false
    private var loseRole: String?=null
    private var checkpoint: String?=null
    private var allowed=true
    private var extraTests=""
    private var alwaysChanges=false
    private var failTestsOnce=false
    private var forcedReviewSession: String?=null
    private var loseHeldPost=false
    private val statusComments=mutableListOf<StatusEvidence>()
    private var responseEdit: ((String,ByteArray) -> ByteArray)?=null
    private var submissions=0
    private var queries=0
    private lateinit var worker: Path
    private lateinit var workerConfig: Path
    private val crashed=object: Error("simulated controller process loss") {}
    private lateinit var base: String
    private fun git(cwd: Path,vararg args: String): String {
        val b=ProcessBuilder(listOf("/usr/bin/git","-c","core.hooksPath=/dev/null")+args).directory(cwd.toFile()).redirectErrorStream(true)
        b.environment().clear(); b.environment().putAll(mapOf("PATH" to "/usr/bin:/bin","HOME" to temp.toString(),
            "GIT_CONFIG_NOSYSTEM" to "1","GIT_CONFIG_GLOBAL" to "/dev/null"))
        val p=b.start(); p.outputStream.close(); val out=p.inputStream.readNBytes(100000).toString(Charsets.UTF_8)
        check(p.waitFor(10,TimeUnit.SECONDS) && p.exitValue()==0) { out }; return out.trim()
    }
    private fun setup() {
        listOf(root,home,home.resolve(".codex"),temp.resolve("template"),temp.resolve("seed")).forEach { Files.createDirectory(it) }
        val workerRoot=Files.createDirectory(temp.resolve("worker-state"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val install=Files.createDirectory(temp.resolve("worker-install"))
        for(name in listOf("ssh-vm-worker.py","native-codex-supervisor.py","verify-python-unittest.py"))
            javaClass.getResourceAsStream("/$name")!!.use { Files.copy(it,install.resolve(name)) }
        worker=install.resolve("ssh-vm-worker.py")
        Files.setPosixFilePermissions(home,PosixFilePermissions.fromString("rwx------"))
        Files.writeString(home.resolve(".codex/auth.json"),"ARTIFICIAL_CODEX_SECRET")
        workerConfig=Files.writeString(temp.resolve("worker.json"),mapper.writeValueAsString(mapOf("root" to workerRoot.toString(),"modelHome" to home.toString(),"codex" to binary.toString())))
        Files.setPosixFilePermissions(workerConfig,PosixFilePermissions.fromString("rw-------"))
        val seed=temp.resolve("seed")
        Files.writeString(seed.resolve("README"),"Base\n")
        git(seed,"init","-q","--initial-branch=main","--template=${temp.resolve("template")}")
        git(seed,"add","README"); git(seed,"-c","user.name=Test","-c","user.email=test@example.invalid","commit","-qm","base")
        base=git(seed,"rev-parse","HEAD")
        git(temp,"init","--bare","--template=${temp.resolve("template")}",bare.toString())
        git(seed,"push",bare.toString(),"HEAD:refs/heads/main")
        Files.writeString(binary,"""#!/usr/bin/python3
import json, os, sys
from pathlib import Path
if sys.argv[1]=='sandbox': sys.exit(0) # fake native policy only; no real Codex inference
if sys.argv[1:] == ['--version']:
    print('fake-codex test'); sys.exit(0)
if sys.argv[1:] == ['exec','--help']:
    print('--sandbox --model --json --output-last-message --config'); sys.exit(0)
assert sys.argv[1]=='exec'
assert not any(k in os.environ for k in ['DEVORCH_PERSONAL_TOKEN','DEVORCH_FETCH_TOKEN','DEVORCH_PUBLISH_TOKEN','GH_TOKEN','GITHUB_TOKEN','SSH_AUTH_SOCK'])
prompt=sys.stdin.read()
last=Path(sys.argv[sys.argv.index('--output-last-message')+1])
mode='workspace-write' if any(a.startswith('permissions.devorch=') and '"."="write"' in a for a in sys.argv) else 'read-only'
state=Path(os.environ['CODEX_HOME'])
count=state / ('coding-count' if mode=='workspace-write' else 'review-count')
n=int(count.read_text())+1 if count.exists() else 0
count.write_text(str(n))
if mode=='workspace-write':
    Path('answer.py').write_text('def add(a,b): return a+b\n# attempt '+str(n)+'\n')
    Path('tests').mkdir(exist_ok=True)
    Path('tests/test_answer.py').write_text('import unittest\nfrom answer import add\nclass TestSum(unittest.TestCase):\n def test_add(self): self.assertEqual(3,add(1,2))\n')
    Path('tests/test_boundary.py').write_text(${mapper.writeValueAsString(extraTests)})
    if ${if(failTestsOnce) "True" else "False"} and n==0:
        Path('answer.py').write_text('def add(a,b): return 0\n')
    session='coding-'+str(n)
    last.write_text('Done')
else:
    assert mode=='read-only'
    result=json.loads(prompt.split('Return only JSON: ')[1].split('\n')[0])
    result['verdict']='CHANGES_REQUIRED' if (${if(correctOnce) "True" else "False"} and n==0 or ${if(alwaysChanges) "True" else "False"}) else 'PASS'
    result['findings']=['Add a precise boundary assertion'] if result['verdict']=='CHANGES_REQUIRED' else []
    last.write_text(json.dumps(result)); session=${forcedReviewSession?.let { mapper.writeValueAsString(it) } ?: "'review-'+str(n)"}
print(json.dumps({'type':'thread.started','thread_id':session}))
print(json.dumps({'type':'turn.completed','usage':{}}))
""")
        check(binary.toFile().setExecutable(true))
    }
    private fun issue(number: Int)=GitHubIssueRecord(1424152600,number,1000L+number,Instant.EPOCH,"Sum $number","Add tiny sum",base,true)
    private val http=PublicationHttp { method,path,body ->
        val api=SMOKE_TARGET.apiRoot
        val decoded=URLDecoder.decode(path,Charsets.UTF_8)
        fun json(value: Any,status: Int=200)=PublicationResponse(status,mapper.writeValueAsString(value))
        when {
            path==api -> json(mapOf("id" to 1424152600,"full_name" to SMOKE_TARGET.fullName,"default_branch" to "main","archived" to false))
            decoded.startsWith("$api/git/ref/heads/") -> {
                val branch=decoded.substringAfter("$api/git/ref/heads/")
                val sha=try { git(temp,"--git-dir=$bare","rev-parse","--verify","refs/heads/$branch") } catch(_: Exception) { null }
                if(sha==null) json(emptyMap<String,String>(),404) else json(mapOf("ref" to "refs/heads/$branch","object" to mapOf("type" to "commit","sha" to sha)))
            }
            path.startsWith("$api/git/commits/") -> {
                val sha=path.substringAfterLast('/'); val info=git(temp,"--git-dir=$bare","show","-s","--format=%T %P",sha).split(' ')
                json(mapOf("sha" to sha,"tree" to mapOf("sha" to info[0]),"parents" to info.drop(1).map { mapOf("sha" to it) }))
            }
            path.startsWith("$api/pulls?") -> {
                val branch=decoded.substringAfter("head=siyovushdev:").substringBefore('&')
                json(listOfNotNull(remotePrs[branch]))
            }
            method=="POST" && path=="$api/pulls" -> {
                posts++; val n=mapper.readTree(body); val branch=n.path("head").asText()
                val sha=git(temp,"--git-dir=$bare","rev-parse","refs/heads/$branch")
                val repo=mapOf("id" to 1424152600,"full_name" to SMOKE_TARGET.fullName)
                val pr=mapOf("number" to posts,"html_url" to "https://github.com/${SMOKE_TARGET.fullName}/pull/$posts",
                    "state" to "open","merged_at" to null,"draft" to true,
                    "head" to mapOf("repo" to repo,"ref" to branch,"sha" to sha),"base" to mapOf("repo" to repo,"ref" to "main","sha" to base))
                remotePrs[branch]=pr
                if(losePr) error("Lost POST response")
                json(pr,201)
            }
            path.startsWith("$api/pulls/") -> json(remotePrs.values.single { it["number"]==path.substringAfterLast('/').toInt() })
            else -> error("Unexpected $method $path")
        }
    }
    private fun runtime(): PersonalRuntime {
        val config=PersonalConfig(true,root,home,binary,"fake-model",1,10809,90,"ssh-vm")
        return PersonalRuntime(config,"FAKE_SECRET",{allowed},http,PublicationPush { plan,stage,artifacts ->
            sends++
            val args=GitHubBranchPush("FAKE").arguments(plan,stage).drop(1).map {
                if(it=="https://github.com/${SMOKE_TARGET.fullName}.git") bare.toString() else it
            }
            Files.writeString(artifacts.resolve("events.log"),git(temp,*args.toTypedArray()))
            ProcessObservation(AttemptState.SUCCEEDED,"EXITED_WITH_CLEANUP",null)
        },{ i,checkout,_ -> git(checkout,"-c","protocol.file.allow=always","fetch","--no-tags",bare.toString(),i.baseSha); Unit },RemoteExecutionSettings("offline-fixture-peer",WorkerTransport { wire,timeout ->
            val op=RemoteProtocol.mapper.readTree(wire)
            val role=if(op["op"].asText()=="submit") {
                submissions++
                RemoteRequest(java.util.Base64.getDecoder().decode(op["requestBase64"].asText())).json["identity"]["role"].asText()
            } else { queries++; "QUERY" }
            val result=WorkerWireProcess.exchange(listOf("/usr/bin/python3","-I","-S",worker.toString(),"serve",workerConfig.toString()),wire,timeout)
            if(role==loseRole) { loseRole=null; error("fixture lost response after guest completion") }
            responseEdit?.invoke(role,result) ?: result
        }, { at -> if(at==checkpoint) { checkpoint=null; throw crashed } }))
    }
    private fun service(records: List<GitHubIssueRecord>)=PersonalService(root.resolve("service.db"),object: ServiceGitHub {
        override fun issues()=records
        override fun authorized(issue: GitHubIssueRecord)=true
        override fun postStatus(number: Int,body: String) {
            statusComments+=StatusEvidence(statusComments.size+1L,"https://github.com/fixture#issuecomment-${statusComments.size+1}",body)
            if(loseHeldPost && body.contains("Остановлено")) { loseHeldPost=false; throw GitHubUnavailable("FIXTURE_LOST_HELD_POST") }
        }
        override fun findStatus(issue: GitHubIssueRecord,body: String)=statusComments.singleOrNull { it.body==body }
    },runtime(),{true})
    private fun db(i: GitHubIssueRecord)=root.resolve("task-${i.githubIssueId}/run.db")
    private fun actionId(i: GitHubIssueRecord,role: String)="issue-${i.githubIssueId}-${role.lowercase()}-0"
    @Test fun fullRemoteProductionChainWithCorrectionAndRestartPublishesOnce() {
        correctOnce=true; setup(); val i=issue(17)
        var s=service(listOf(i))
        repeat(13) { s.tick(); s=service(listOf(i)); s.recover() }
        assertEquals("IDLE",s.tick()); assertEquals(1,posts); assertEquals(1,sends)
        assertEquals(6,submissions) // Coding, Tests, Review, then one correction of each.
        assertEquals("1",Files.readString(home.resolve(".codex/coding-count")))
        assertEquals("1",Files.readString(home.resolve(".codex/review-count")))
        val w=SqliteTaskStore(db(i)).findWorkflow(1424152600,i.issueNumber)!!
        assertEquals(WorkflowPhase.DRAFT_PR_CREATED,w.phase); assertEquals(1,w.correctionsUsed)
        val events=SqliteTaskStore(db(i)).listEvents(w.id)
        assertEquals(6,events.count { it.type=="REMOTE_RESULT_IMPORTED" })
        assertFalse(events.any { it.type in setOf("CODING_PROCESS_STARTED","VERIFICATION_PROCESS_STARTED","REVIEW_PROCESS_STARTED") })
        assertFalse(Files.readString(root.resolve("task-${i.githubIssueId}/artifacts/remote-${actionId(i,"coding")}/result.json")).contains("ARTIFICIAL_CODEX_SECRET"))
    }
    @Test fun lostCodingResponseRequiresExactOwnerReconciliationWithoutReplay() = lostResponse("CODING",1)
    @Test fun lostTestsResponseRequiresExactOwnerReconciliationWithoutReplay() = lostResponse("TESTS",2)
    @Test fun lostReviewResponseRequiresExactOwnerReconciliationWithoutReplay() = lostResponse("REVIEW",3)
    private fun lostResponse(role: String,steps: Int) {
        setup(); val i=issue(17); var s=service(listOf(i)); s.tick()
        repeat(steps-1) { s.tick() }; loseRole=role; assertEquals("HELD",s.tick())
        val count=submissions; val id=actionId(i,role)
        s=service(listOf(i)); s.recover(); repeat(2) { assertEquals("NEEDS_OWNER",s.tick()) }; assertEquals(count,submissions)
        val observed=s.checkRemote(i.issueNumber,id); assertTrue(observed.startsWith("EVIDENCE_STORED:")); assertEquals(1,queries)
        assertFalse(s.resumeRemote(i.issueNumber,id,"a".repeat(64))); assertEquals(count,submissions)
        allowed=false; assertFalse(s.resumeRemote(i.issueNumber,id,observed.substringAfter(':'))); allowed=true
        assertTrue(s.resumeRemote(i.issueNumber,id,observed.substringAfter(':')))
        repeat(6) { s.tick() }; assertEquals(1,posts); assertEquals(1,sends); assertEquals(3,submissions)
        assertEquals("0",Files.readString(home.resolve(".codex/coding-count"))); assertEquals("0",Files.readString(home.resolve(".codex/review-count")))
    }
    @Test fun crashBeforeAtomicRenameCanImportSavedResultWithoutAnotherExecution() = interruptedImport("BEFORE_RENAME")
    @Test fun crashAfterAtomicRenameCanImportSavedResultWithoutAnotherExecution() = interruptedImport("AFTER_RENAME")
    @Test fun crashBeforeSqlCommitCanImportSavedResultWithoutAnotherExecution() = interruptedImport("BEFORE_COMMIT")
    @Test fun crashAfterSqlCommitDoesNotRepeatCoding() {
        setup(); val i=issue(17); var s=service(listOf(i)); s.tick(); checkpoint="AFTER_COMMIT"
        assertFailsWith<Error> { s.tick() }; s=service(listOf(i)); s.recover(); repeat(6) { s.tick() }
        assertEquals(3,submissions); assertEquals(1,posts); assertEquals(1,sends)
    }
    private fun interruptedImport(at: String) {
        setup(); val i=issue(17); var s=service(listOf(i)); s.tick(); checkpoint=at
        assertFailsWith<Error> { s.tick() }; assertEquals(1,submissions)
        s=service(listOf(i)); s.recover()
        val id=actionId(i,"CODING"); val result=s.checkRemote(i.issueNumber,id)
        assertTrue(result.startsWith("EVIDENCE_STORED:")); assertEquals(0,queries) // controller already has immutable receipt
        assertTrue(s.resumeRemote(i.issueNumber,id,result.substringAfter(':')))
        repeat(6) { s.tick() }; assertEquals(3,submissions); assertEquals(1,posts); assertEquals(1,sends)
    }
    @Test fun untrustedPythonCannotReadOrAlterControllerStateOrGuestCodexCredentials() {
        val secret=root.resolve("controller-canary")
        extraTests="""import unittest
from pathlib import Path
class Boundary(unittest.TestCase):
 def test_private_files(self):
  for name in [${mapper.writeValueAsString(secret.toString())}, ${mapper.writeValueAsString(home.resolve(".codex/auth.json").toString())}, ${mapper.writeValueAsString(root.resolve("task-1017/run.db").toString())}]:
   with self.assertRaises(OSError): Path(name).read_bytes()
   with self.assertRaises(OSError): Path(name).write_bytes(b'changed')
"""
        setup(); Files.writeString(secret,"ARTIFICIAL_CONTROLLER_SECRET")
        val i=issue(17); val s=service(listOf(i)); repeat(7) { s.tick() }
        assertEquals(1,posts); assertEquals("ARTIFICIAL_CONTROLLER_SECRET",Files.readString(secret))
        assertEquals("ARTIFICIAL_CODEX_SECRET",Files.readString(home.resolve(".codex/auth.json")))
        val report=RuntimeDatabase(db(i)).read { c -> c.rows("SELECT evidence FROM workflow_verifications") { mapper.readTree(it.getString(1)) }.single() }
        assertEquals(2,report["testsRun"].asInt()); assertEquals("PASS",report["status"].asText())
    }
    @Test fun failingTrustedTestsRequestOneCorrectionAndUseRealPreviousImage() {
        failTestsOnce=true; setup(); val i=issue(17); var s=service(listOf(i))
        repeat(12) { s.tick(); s=service(listOf(i)); s.recover() }
        assertEquals(1,posts); assertEquals(5,submissions) // failed Tests skip Review in generation zero
        val j=RemoteAttemptJournal(db(i)); val first=mapper.readTree(j.find(actionId(i,"CODING"))!!.evidence)
        val second=j.find("issue-1017-coding-1")!!.request.json
        assertEquals(first["outputTree"],second["identity"]["sourceTree"])
        assertEquals(first["files"].associateBy { it["path"].asText() },second["files"].associateBy { it["path"].asText() })
    }
    @Test fun correctionLimitRemainsTwoWithoutFourthCodingOrDraftPr() {
        alwaysChanges=true; setup(); val i=issue(17); val s=service(listOf(i)); repeat(17) { s.tick() }
        assertEquals(9,submissions); assertEquals(0,posts); assertEquals(0,sends)
        val w=SqliteTaskStore(db(i)).findWorkflow(1424152600,17)!!
        assertEquals(2,w.correctionsUsed); assertEquals(WorkflowHold.NEEDS_OWNER,w.hold); assertEquals("CORRECTION_LIMIT_OR_REVIEW_BINDING",w.holdReason)
    }
    @Test fun wrongRemoteIdentityNeverCompletesPhaseButExactReadOnlyQueryCanReconcile() {
        setup(); val i=issue(17); val s=service(listOf(i)); s.tick()
        responseEdit={ role,bytes -> if(role!="CODING") bytes else {
            val n=mapper.readTree(bytes) as com.fasterxml.jackson.databind.node.ObjectNode
            (n["identity"] as com.fasterxml.jackson.databind.node.ObjectNode).put("issueNumber",999)
            mapper.writeValueAsBytes(n)
        } }
        assertEquals("HELD",s.tick()); assertEquals(1,submissions)
        val w=SqliteTaskStore(db(i)).findWorkflow(1424152600,17)!!
        assertEquals(WorkflowPhase.CODING,w.phase)
        assertFalse(Files.exists(root.resolve("task-1017/artifacts/remote-${actionId(i,"CODING")}")))
        responseEdit=null; val checked=s.checkRemote(17,actionId(i,"CODING"))
        assertEquals(WorkflowPhase.CODING,SqliteTaskStore(db(i)).findWorkflow(1424152600,17)!!.phase)
        assertTrue(s.resumeRemote(17,actionId(i,"CODING"),checked.substringAfter(':')))
        repeat(6) { s.tick() }; assertEquals(1,posts); assertEquals(3,submissions)
    }
    @Test fun reviewCannotReuseCodingSession() {
        forcedReviewSession="coding-0"; setup(); val i=issue(17); val s=service(listOf(i)); repeat(4) { s.tick() }
        assertEquals(3,submissions); assertEquals(0,posts)
        assertEquals(WorkflowHold.NEEDS_OWNER,SqliteTaskStore(db(i)).findWorkflow(1424152600,17)!!.hold)
    }
    @Test fun publisherRejectsAlteredImportedReceiptBeforeAnyPushOrPost() {
        setup(); val i=issue(17); val s=service(listOf(i)); repeat(4) { s.tick() }
        val path=root.resolve("task-1017/artifacts/remote-${actionId(i,"CODING")}/result.json")
        Files.writeString(path,"{}")
        assertEquals("HELD",s.tick()); assertEquals(0,posts); assertEquals(0,sends)
    }
    @Test fun changedSavedImportCannotBeAcceptedOnOwnerResume() {
        setup(); val i=issue(17); var s=service(listOf(i)); s.tick(); checkpoint="AFTER_RENAME"
        assertFailsWith<Error> { s.tick() }; s=service(listOf(i)); s.recover()
        val id=actionId(i,"CODING"); val checked=s.checkRemote(17,id)
        Files.writeString(root.resolve("task-1017/artifacts/remote-$id/image/answer.py"),"changed")
        assertFails { s.resumeRemote(17,id,checked.substringAfter(':')) }
        assertEquals(1,submissions); assertEquals(WorkflowHold.NEEDS_OWNER,SqliteTaskStore(db(i)).findWorkflow(1424152600,17)!!.hold)
    }
    @Test fun completedInnerOwnerImportCanFinishOuterQueueResumeAfterCrash() = completedOwnerImport("CODING",1)
    @Test fun completedTestsOwnerImportCanFinishOuterQueueResumeAfterCrash() = completedOwnerImport("TESTS",2)
    @Test fun completedReviewOwnerImportCanFinishOuterQueueResumeAfterCrash() = completedOwnerImport("REVIEW",3)
    private fun completedOwnerImport(role: String,steps: Int) {
        setup(); val i=issue(17); var s=service(listOf(i)); s.tick(); repeat(steps-1) { s.tick() }; loseRole=role; s.tick()
        val id=actionId(i,role); val checked=s.checkRemote(17,id); checkpoint="AFTER_COMMIT"
        assertFailsWith<Error> { s.resumeRemote(17,id,checked.substringAfter(':')) }
        s=service(listOf(i)); s.recover()
        assertTrue(s.resumeRemote(17,id,checked.substringAfter(':')))
        repeat(6) { s.tick() }; assertEquals(3,submissions); assertEquals(1,posts)
    }

    @Test fun ownerCommandsRecoverExactCompletedResultDirectlyAfterCrashBeforeHold() {
        setup(); val i=issue(17); var s=service(listOf(i)); s.tick(); checkpoint="BEFORE_RENAME"
        assertFailsWith<Error> { s.tick() }; s=service(listOf(i)) // no service/recover call before Owner commands
        val id=actionId(i,"CODING"); val checked=s.checkRemote(17,id)
        assertEquals(WorkflowHold.NONE,SqliteTaskStore(db(i)).findWorkflow(1424152600,17)!!.hold)
        assertTrue(s.resumeRemote(17,id,checked.substringAfter(':')))
        repeat(6) { s.tick() }; assertEquals(3,submissions); assertEquals(1,posts)
    }
    @Test fun localEntrypointsCannotExecuteRemoteWorkflowAndCompletedRemoteStepsAreIdempotent() {
        setup(); val i=issue(17); val s=service(listOf(i)); repeat(3) { s.tick() }
        fun engine(remote: Boolean)=WorkflowEngine(db(i),1424152600,binary,home,root.resolve("task-1017/artifacts"),root.resolve("task-1017/snapshots"),
            remote=if(remote) RemoteExecutionSettings("offline-fixture-peer",WorkerTransport { _,_ -> error("must not execute") }) else null)
        assertFails { engine(false).launchReview(17) }
        assertFails { engine(false).verifyCompletedCoding(17) }
        assertFails { engine(false).recover(17) }
        assertEquals("GUARD_DENIED",engine(true).verifyCompletedCoding(17))
        s.tick(); assertEquals("GUARD_DENIED",engine(true).launchReview(17))
        assertEquals(WorkflowHold.NONE,SqliteTaskStore(db(i)).findWorkflow(1424152600,17)!!.hold)
        s.tick(); assertEquals(1,posts); assertEquals(3,submissions)
    }
    @Test fun lostHeldCommentRestoresUnderlyingRemoteHoldBeforeSeparateOwnerResume() {
        setup(); val i=issue(17); var s=service(listOf(i)); s.tick(); loseRole="CODING"; loseHeldPost=true
        assertEquals("NEEDS_OWNER",s.tick()); val count=statusComments.size
        s=service(listOf(i)); s.recover()
        assertFails { s.checkRemote(17,actionId(i,"CODING")) }
        val comment=s.checkStatus(17,"held")!!
        assertTrue(s.resumeStatus(17,"held",comment.id)); assertEquals(count,statusComments.size)
        assertTrue(s.localStatus().contains("REMOTE_ACTION_UNKNOWN:${actionId(i,"CODING")}"))
        assertEquals("NEEDS_OWNER",s.tick()); assertEquals(1,submissions)
        val checked=s.checkRemote(17,actionId(i,"CODING"))
        assertTrue(s.resumeRemote(17,actionId(i,"CODING"),checked.substringAfter(':')))
        repeat(6) { s.tick() }; assertEquals(1,posts); assertEquals(3,submissions)
        assertEquals(1,statusComments.count { it.body==comment.body })
    }

    @Test fun uncertainControllerDraftPostNeverRepeatsModelsPushOrPostAfterRestart() {
        losePr=true; setup(); val i=issue(17); var s=service(listOf(i)); repeat(5) { s.tick() }
        assertEquals(1,posts); assertEquals(1,sends); assertEquals(3,submissions)
        s=service(listOf(i)); s.recover(); repeat(4) { s.tick() }
        assertEquals(1,posts); assertEquals(1,sends); assertEquals(3,submissions)
        assertEquals(WorkflowHold.NEEDS_OWNER,SqliteTaskStore(db(i)).findWorkflow(1424152600,17)!!.hold)
    }
    @Test fun incompleteFailedTestsEvidenceDoesNotCreateCorrectionPermission() {
        failTestsOnce=true; setup(); val i=issue(17); val s=service(listOf(i)); s.tick(); s.tick()
        responseEdit={ role,bytes -> if(role!="TESTS") bytes else {
            val n=mapper.readTree(bytes) as com.fasterxml.jackson.databind.node.ObjectNode
            (n["artifacts"] as com.fasterxml.jackson.databind.node.ObjectNode).remove("tests.json")
            mapper.writeValueAsBytes(n)
        } }
        assertFails { s.tick() }; assertEquals(2,submissions); assertEquals(0,posts)
        RuntimeDatabase(db(i)).read { c ->
            assertTrue(c.rows("SELECT 1 FROM correction_requests") { 1 }.isEmpty())
            assertEquals("STARTING",c.rows("SELECT state FROM workflow_verifications") { it.getString(1) }.single())
        }
    }

    @Test fun changedCodingManifestBindingStopsBeforeTestsAreSubmitted() {
        setup(); val i=issue(17); val s=service(listOf(i)); s.tick(); s.tick()
        val manifest=root.resolve("task-1017/artifacts/remote-${actionId(i,"CODING")}/candidates/${actionId(i,"CODING")}-untested/manifest")
        val original=Files.readString(manifest)
        Files.writeString(manifest,original.replace(base,"a".repeat(40)))
        assertFails { s.tick() }; assertEquals(1,submissions); assertEquals(0,posts)
        assertTrue(RuntimeDatabase(db(i)).read { c -> c.rows("SELECT 1 FROM workflow_verifications") { 1 }.isEmpty() })
    }

    @Test fun idempotentOuterResumeStillHonorsRevokedPolicyAfterInnerCommit() {
        setup(); val i=issue(17); var s=service(listOf(i)); s.tick(); loseRole="CODING"; s.tick()
        val id=actionId(i,"CODING"); val checked=s.checkRemote(17,id); checkpoint="AFTER_COMMIT"
        assertFailsWith<Error> { s.resumeRemote(17,id,checked.substringAfter(':')) }
        val store=SqliteTaskStore(db(i)); val policy=store.findPolicy(1424152600)!!
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=policy.version+1,personalAutoExecution=false),policy.version,"owner:fixture","fixture policy revoked"))
        s=service(listOf(i)); assertFalse(s.resumeRemote(17,id,checked.substringAfter(':')))
        assertTrue(s.localStatus().contains("REMOTE_ACTION_UNKNOWN:$id")); assertEquals(1,submissions); assertEquals(0,posts)
    }

}
