package dev.orchestrator.publish

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.agent.*
import dev.orchestrator.agent.nativecli.*
import dev.orchestrator.review.*
import dev.orchestrator.storage.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Flow
import kotlin.test.*

/** MOCK saved runtime receipts; real disposable Git/SQLite/frozen files; no model or GitHub traffic. */
class DraftPrPublisherTest {
    @TempDir lateinit var temp: Path
    private val database get()=temp.resolve("state.db")
    private val source get()=temp.resolve("source")
    private val root get()=temp.resolve("publisher")
    private val bare get()=temp.resolve("remote.git")
    private val db get()=RuntimeDatabase(database)
    private val store get()=SqliteTaskStore(database)
    private val journal get()=PublicationJournal(database)
    private val mapper=ObjectMapper()
    private lateinit var base: String
    private lateinit var tree: String
    private lateinit var candidate: FrozenCandidate
    private var sends=0
    private var posts=0
    private var lostPost=false
    private var lostPush=false
    private var wrongTree=false
    private var repoId=SMOKE_TARGET.repositoryId
    private var remoteBase: String?=null
    private var draft=true
    private var headOverride: String?=null
    private var copies=1
    private var pr: Map<String,Any?>?=null
    private var beforePost: ()->Unit={}
    private var beforePush: ()->Unit={}
    private var permit: (PublicationPlan,EffectKind)->Boolean={_,_->true}
    private fun git(cwd: Path,vararg args: String): String {
        val b=ProcessBuilder(listOf("/usr/bin/git")+args).directory(cwd.toFile()).redirectErrorStream(true)
        b.environment().clear()
        b.environment().putAll(mapOf("PATH" to "/usr/bin:/bin","HOME" to temp.toString(),
            "GIT_CONFIG_NOSYSTEM" to "1","GIT_CONFIG_GLOBAL" to "/dev/null","GIT_TERMINAL_PROMPT" to "0"))
        val p=b.start(); p.outputStream.close()
        val output=p.inputStream.readNBytes(100000).toString(Charsets.UTF_8)
        check(p.waitFor(8,java.util.concurrent.TimeUnit.SECONDS) && p.exitValue()==0) { output }
        return output.trim()
    }
    private fun clean(): Map<String,Any> = mapOf("cleanup_echild" to true,"exit_code" to 0,"cause" to "exited",
        "boot_id" to "mock-boot","machine_id" to "mock-machine","supervisor" to mapOf("pid" to 100,"start_ticks" to "10"),
        "child" to mapOf("pid" to 101,"start_ticks" to "11"))
    private fun event(c: java.sql.Connection,type: String,value: Any) {
        c.mutate("INSERT INTO workflow_events(workflow_id,event_type,detail,recorded_at) VALUES ('wf',?,?,?)",
            type,mapper.writeValueAsString(value),Instant.EPOCH.toString())
    }
    private fun fixture(badVerification: Boolean=false,badReview: Boolean=false) {
        listOf(source,root,temp.resolve("snapshots"),temp.resolve("verify"),temp.resolve("coding-artifacts"),temp.resolve("review-artifacts"),temp.resolve("template")).forEach { Files.createDirectory(it) }
        Files.writeString(source.resolve("README"),"base\n")
        git(source,"init","-q","--initial-branch=main","--template=${temp.resolve("template")}")
        git(source,"add","README")
        git(source,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-qm","base")
        base=git(source,"rev-parse","HEAD")
        git(temp,"init","--bare","--template=${temp.resolve("template")}",bare.toString())
        git(source,"push",bare.toString(),"HEAD:refs/heads/main")
        store.insertProject(RegisteredProject("fixture",SMOKE_TARGET.repositoryId,ProjectKind.PERSONAL,temp.toString()),"owner:fixture")
        val policy=store.findPolicy(SMOKE_TARGET.repositoryId)!!
        assertTrue(store.updatePolicyFromTrustedOwner(policy.copy(version=2,personalAutoExecution=true),1,"owner:fixture","offline"))
        store.acceptGitHubIssue(GitHubIssueRecord(SMOKE_TARGET.repositoryId,1,1001,Instant.EPOCH,"Sum","Tiny sum",base,true),"wf")
        val catalog=SqliteAgentCatalog(database)
        catalog.declareManifest(AgentCapabilityManifest("codex-cli","openai",1,setOf(AgentRole.CODING,AgentRole.REVIEW),
            setOf(AgentSandbox.NATIVE_PERSONAL_WRITE,AgentSandbox.NATIVE_PERSONAL_READ_ONLY),mapOf("fake-model" to setOf(ReasoningEffort.DEFAULT))),null,"owner:fixture")
        for (role in listOf(AgentRole.CODING,AgentRole.REVIEW)) {
            catalog.saveProfile(AgentProfile(role.name,1,SMOKE_TARGET.repositoryId,role,"codex-cli",1,"openai","fake-model",
                ReasoningEffort.DEFAULT,if (role==AgentRole.CODING) AgentSandbox.NATIVE_PERSONAL_WRITE else AgentSandbox.NATIVE_PERSONAL_READ_ONLY,
                30,1000),null,"owner:fixture")
            catalog.bindRole(SMOKE_TARGET.repositoryId,role,role.name,1,null,"owner:fixture")
        }
        val j=CodingAttemptJournal(database)
        val intent=j.plan(SMOKE_TARGET.repositoryId,1,0,"coding",source,git(source,"rev-parse","HEAD^{tree}"))!!
        assertTrue(j.markStarting("coding"))
        assertTrue(j.markRunning("coding",mapper.writeValueAsString(mapOf("attempt_id" to "coding","identity" to
            mapOf("pid" to "100","start_ticks" to "10","boot_id" to "mock-boot","machine_id" to "mock-machine")))))
        Files.writeString(source.resolve("README"),"reviewed result\n")
        Files.createDirectory(source.resolve("src")); Files.writeString(source.resolve("src/add.py"),"def add(a,b): return a+b\n")
        git(source,"add","--all"); tree=git(source,"write-tree")
        Files.writeString(temp.resolve("coding-artifacts/events.log"),"{\"type\":\"thread.started\",\"thread_id\":\"coding-session\"}\n{\"type\":\"turn.completed\"}\n")
        Files.writeString(temp.resolve("review-artifacts/events.log"),"{\"type\":\"thread.started\",\"thread_id\":\"review-session\"}\n{\"type\":\"turn.completed\"}\n")
        val completion=mapOf("attempt_id" to "coding","observed" to clean(),"candidate_tree" to tree,"base_sha" to base,
            "input_tree" to intent.sourceTree,"issue_body_sha256" to intent.issueBodySha256,"profile_id" to intent.profileId,
            "profile_version" to intent.profileVersion,"assignment_version" to intent.assignmentVersion,"manifest_version" to intent.manifestVersion,
            "artifacts" to temp.resolve("coding-artifacts").toString())
        assertTrue(j.completeObserved("coding",AttemptState.SUCCEEDED,mapper.writeValueAsString(completion)))
        candidate=FrozenCandidate.capture(source,temp.resolve("snapshots"),"candidate",
            CandidateBinding("wf","coding","coding-session",0,base,tree,VerificationStatus.PASS,"coding-tests"))
        val verified=mapOf("status" to if (badVerification) "FAIL" else "PASS","generation" to 0,"minimumTests" to 3,
            "testsRun" to 3,"skipped" to 0,"completedInvocations" to 3,"testIdsSha256" to "d".repeat(64),
            "failures" to 0,"errors" to 0,"unexpectedSuccesses" to 0,"process" to clean(),"codingAttemptId" to "coding",
            "directory" to candidate.directory.toString(),"manifestSha256" to candidate.manifestSha256,"candidateId" to candidate.candidateId,
            "codingSessionId" to "coding-session","candidateTree" to tree,"artifacts" to temp.resolve("verify").toString())
        db.write { c ->
            c.mutate("INSERT INTO workflow_verifications VALUES ('wf',0,'coding',2,3,2,'SUCCEEDED',?)",mapper.writeValueAsString(verified))
            c.mutate("INSERT INTO agent_attempts VALUES ('review','wf','REVIEW',0,'SUCCEEDED',?,?)",tree,Instant.EPOCH.toString())
            c.mutate("""INSERT INTO attempt_contexts SELECT 'review',repository_id,issue_number,issue_body_sha256,base_sha,
                policy_version,assignment_version,'REVIEW',profile_version,adapter_id,manifest_version,workspace,planned_workflow_version
                FROM attempt_contexts WHERE attempt_id='coding'""")
            c.mutate("INSERT INTO review_inputs VALUES ('review','wf',0,?,?,?,?,?)",candidate.candidateId,candidate.manifestSha256,tree,
                candidate.directory.toString(),"coding-session")
            event(c,"VERIFICATION_PROCESS_STARTED",mapOf("pid" to "100","start_ticks" to "10","boot_id" to "mock-boot","machine_id" to "mock-machine"))
            event(c,"REVIEW_PROCESS_STARTED",mapOf("pid" to "100","start_ticks" to "10","boot_id" to "mock-boot","machine_id" to "mock-machine"))
            event(c,"REVIEW_COMPLETED",mapOf("receipt" to ReviewReceipt(candidate.candidateId,"review","review-session",
                candidate.manifestSha256,tree,0,if (badReview) ReviewVerdict.CHANGES_REQUIRED else ReviewVerdict.PASS),
                "artifacts" to temp.resolve("review-artifacts").toString(),"process" to clean(),"findings" to emptyList<String>(),"profile_id" to "REVIEW","profile_version" to 1,
                "assignment_version" to 1,"manifest_version" to 1))
            c.mutate("UPDATE workflows SET phase='READY_FOR_PR',version=5 WHERE workflow_id='wf'")
        }
    }
    private val http=PublicationHttp { method,path,body ->
        val api=SMOKE_TARGET.apiRoot
        val decoded=java.net.URLDecoder.decode(path,Charsets.UTF_8)
        fun response(value: Any,status: Int=200)=PublicationResponse(status,mapper.writeValueAsString(value))
        when {
            method=="GET" && path==api -> response(mapOf("id" to repoId,"full_name" to SMOKE_TARGET.fullName,"default_branch" to "main","archived" to false))
            method=="GET" && decoded.startsWith("$api/git/ref/heads/") -> {
                val branch=decoded.substringAfter("$api/git/ref/heads/")
                val sha=if (branch=="main") remoteBase ?: base else try { git(temp,"--git-dir=$bare","rev-parse","refs/heads/$branch") } catch (_: Exception) { null }
                if (sha==null) response(mapOf("message" to "Not Found"),404) else response(mapOf("ref" to "refs/heads/$branch","object" to mapOf("type" to "commit","sha" to sha)))
            }
            method=="GET" && path.startsWith("$api/git/commits/") -> {
                val sha=path.substringAfterLast('/')
                val actual=git(temp,"--git-dir=$bare","show","-s","--format=%T %P",sha).split(' ')
                response(mapOf("sha" to sha,"tree" to mapOf("sha" to if (wrongTree) "a".repeat(40) else actual[0]),"parents" to actual.drop(1).map { mapOf("sha" to it) }))
            }
            method=="GET" && path.startsWith("$api/pulls?") -> response(if (pr==null) emptyList<Any>() else List(copies) { pr!! })
            method=="GET" && path.startsWith("$api/pulls/") -> response(pr!!)
            method=="POST" && path=="$api/pulls" -> {
                beforePost(); posts++
                val n=mapper.readTree(body); assertTrue(n["draft"].asBoolean()); assertFalse(n["maintainer_can_modify"].asBoolean())
                val p=journal.findPlan("wf")!!
                pr=mapOf("number" to 7,"html_url" to "https://github.com/${SMOKE_TARGET.fullName}/pull/7","state" to "open","merged_at" to null,
                    "draft" to draft,"head" to mapOf("ref" to p.headBranch,"sha" to (headOverride ?: p.commitSha),"repo" to mapOf("id" to repoId,"full_name" to SMOKE_TARGET.fullName)),
                    "base" to mapOf("ref" to "main","sha" to base,"repo" to mapOf("id" to repoId,"full_name" to SMOKE_TARGET.fullName)))
                if (lostPost) throw java.io.IOException("MOCK lost response after create")
                response(pr!!,201)
            }
            else -> error("Unexpected request $method $path")
        }
    }
    private val sender=PublicationPush { p,staging,_ ->
        assertEquals(EffectState.SENDING,journal.state("wf",EffectKind.PUSH)); beforePush(); sends++
        val arguments=GitHubBranchPush("MOCK_TOKEN").arguments(p,staging).drop(1).map {
            if (it=="https://github.com/${SMOKE_TARGET.fullName}.git") bare.toString() else it
        }
        git(temp,*arguments.toTypedArray())
        if (lostPush) throw java.io.IOException("MOCK lost push result")
        ProcessObservation(AttemptState.SUCCEEDED,"EXITED_WITH_CLEANUP",mapper.valueToTree(clean()))
    }
    private fun publisher()=DraftPrPublisher(database,root,base,GitHubPublicationApi(http),sender,{p,k->permit(p,k)})
    private fun phase()=store.findWorkflow(SMOKE_TARGET.repositoryId,1)!!

    @Test fun publishesExactSavedTreeAndOneDraftThenRestartHasNoWrites() {
        fixture(); val originalIndex=Files.readAllBytes(source.resolve(".git/index"))
        val result=publisher().publish()
        assertEquals("DRAFT_PR_CREATED",result.status)
        assertEquals("https://github.com/${SMOKE_TARGET.fullName}/pull/7",result.url)
        val plan=journal.findPlan("wf")!!
        assertEquals(tree,git(temp,"--git-dir=$bare","show","-s","--format=%T",plan.commitSha))
        assertEquals(base,git(temp,"--git-dir=$bare","show","-s","--format=%P",plan.commitSha))
        assertEquals(base,git(source,"rev-parse","HEAD")); assertContentEquals(originalIndex,Files.readAllBytes(source.resolve(".git/index")))
        assertTrue(candidate.isIntact()); assertEquals(WorkflowPhase.DRAFT_PR_CREATED,phase().phase)
        for (kind in EffectKind.entries) assertEquals(EffectState.CONFIRMED,journal.state("wf",kind))
        repeat(3) { assertEquals("ALREADY_CONFIRMED",publisher().publish().status) }
        assertEquals(1,sends); assertEquals(1,posts)
    }
    @Test fun lostCreateResponseNeverPostsAgainAndReadOnlyReconciliationRetainsHold() {
        fixture(); lostPost=true
        assertEquals("UNCERTAIN_NEEDS_OWNER",publisher().publish().status)
        assertEquals(EffectState.UNCERTAIN,journal.state("wf",EffectKind.DRAFT_PR))
        assertEquals(WorkflowHold.NEEDS_OWNER,phase().hold)
        repeat(3) { assertEquals("NEEDS_OWNER",publisher().publish().status) }
        assertTrue(publisher().reconcile(EffectKind.DRAFT_PR))
        assertEquals(EffectState.CONFIRMED,journal.state("wf",EffectKind.DRAFT_PR))
        assertEquals(WorkflowHold.NEEDS_OWNER,phase().hold); assertEquals(1,posts)
    }
    @Test fun lostPushResultNeverPushesAgainAndCanBeReadBackOnly() {
        fixture(); lostPush=true
        assertEquals("UNCERTAIN_NEEDS_OWNER",publisher().publish().status)
        assertEquals(EffectState.UNCERTAIN,journal.state("wf",EffectKind.PUSH))
        assertTrue(publisher().reconcile(EffectKind.PUSH))
        assertEquals("NEEDS_OWNER",publisher().publish().status)
        assertEquals(1,sends); assertEquals(0,posts)
    }
    @Test fun wrongRemoteTreeBlocksPrCreation() {
        fixture(); wrongTree=true
        assertEquals("UNCERTAIN_NEEDS_OWNER",publisher().publish().status)
        assertEquals(0,posts); assertEquals(EffectState.UNCERTAIN,journal.state("wf",EffectKind.PUSH))
    }
    @Test fun nonDraftResponseIsNotConfirmed() {
        fixture(); draft=false
        assertEquals("UNCERTAIN_NEEDS_OWNER",publisher().publish().status)
        assertEquals(EffectState.UNCERTAIN,journal.state("wf",EffectKind.DRAFT_PR))
        assertFalse(publisher().reconcile(EffectKind.DRAFT_PR)); assertEquals(1,posts)
    }
    @Test fun duplicateListIsNotSingletonSuccess() {
        fixture(); copies=2
        assertEquals("UNCERTAIN_NEEDS_OWNER",publisher().publish().status)
        assertEquals(1,posts); assertFalse(publisher().reconcile(EffectKind.DRAFT_PR))
    }
    @Test fun currentOwnerDenialStopsBeforePush() {
        fixture(); permit={_,k->k!=EffectKind.PUSH}
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status)
        assertEquals(EffectState.CONFIRMED,journal.state("wf",EffectKind.COMMIT))
        assertEquals(EffectState.PREPARED,journal.state("wf",EffectKind.PUSH)); assertEquals(0,sends)
    }
    @Test fun changedRepoIdentityDeniesAnySend() {
        fixture(); repoId++
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status)
        assertEquals(0,sends); assertEquals(0,posts)
    }
    @Test fun changedBaseDeniesAnySend() {
        fixture(); remoteBase="a".repeat(40)
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status)
        assertEquals(0,sends); assertEquals(0,posts)
    }
    @Test fun changedFrozenBytesDenyAnyIntent() {
        fixture(); Files.writeString(candidate.filesDirectory.resolve("README"),"mutated")
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status)
        assertNull(journal.findPlan("wf")); assertEquals(0,sends)
    }
    @Test fun enumReadyWithoutPassingVerificationIsDenied() {
        fixture(badVerification=true)
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status); assertNull(journal.findPlan("wf"))
    }
    @Test fun enumReadyWithoutIndependentPassIsDenied() {
        fixture(badReview=true)
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status); assertNull(journal.findPlan("wf"))
    }
    @Test fun ambiguousRuntimeClaimDeniesPublication() {
        fixture(); db.write { it.mutate("INSERT INTO runtime_reservations VALUES ('coding',?,?)",SMOKE_TARGET.repositoryId,source.toString()) }
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status); assertEquals(0,sends)
    }
    @Test fun policyRevocationAfterCommitStopsPush() {
        fixture(); permit={_,k->
            if (k==EffectKind.PUSH) {
                val p=store.findPolicy(SMOKE_TARGET.repositoryId)!!
                store.updatePolicyFromTrustedOwner(p.copy(version=p.version+1,personalAutoExecution=false),p.version,"owner:fixture","revoke")
            }
            true
        }
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status); assertEquals(0,sends)
    }
    @Test fun createOnlyLeaseRejectsRacingExistingBranchEvenIfFastForwardWouldWork() {
        fixture(); beforePush={
            val p=journal.findPlan("wf")!!
            git(temp,"--git-dir=$bare","update-ref","refs/heads/${p.headBranch}",base)
        }
        assertEquals("UNCERTAIN_NEEDS_OWNER",publisher().publish().status)
        val p=journal.findPlan("wf")!!
        assertEquals(base,git(temp,"--git-dir=$bare","rev-parse","refs/heads/${p.headBranch}")); assertEquals(0,posts)
    }
    @Test fun readBackMismatchOfHeadNeverConfirmsDraft() {
        fixture(); headOverride="b".repeat(40)
        assertEquals("UNCERTAIN_NEEDS_OWNER",publisher().publish().status)
        assertEquals(EffectState.UNCERTAIN,journal.state("wf",EffectKind.DRAFT_PR))
    }
    @Test fun crashAfterSendingIsHeldWithoutResendOnRecovery() {
        fixture(); permit={_,_->false}; publisher().publish()
        val p=journal.findPlan("wf")!!
        assertTrue(journal.markSending("wf",EffectKind.COMMIT,p.candidateManifestSha256))
        assertEquals(1,publisher().recover()); assertEquals("NEEDS_OWNER",publisher().publish().status)
        assertEquals(EffectState.UNCERTAIN,journal.state("wf",EffectKind.COMMIT)); assertEquals(0,sends)
    }
    @Test fun privatePushTokenDoesNotEnterSupervisorConfigOrLogs() {
        val dir=Files.createDirectory(temp.resolve("private-env"))
        val token="MOCK_PRIVATE_TOKEN_not_a_credential"
        val command=listOf("/usr/bin/python3","-I","-c","import os; assert os.environ['DEVORCH_PUBLISH_TOKEN']; print('ok')")
        val result=NativeProcessRunner(Path.of("/usr/bin/python3")).run(command,dir,mapOf("PATH" to "/usr/bin:/bin"),dir,
            byteArrayOf(),3000,privateEnvironment=mapOf("DEVORCH_PUBLISH_TOKEN" to token)) { true }
        assertEquals(AttemptState.SUCCEEDED,result.state,result.reason)
        Files.list(dir).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { assertFalse(Files.readString(it).contains(token),it.toString()) } }
    }
    @Test fun knownConfirmedCommitResumesWithoutRepeatingItOrModels() {
        fixture(); permit={_,k->k!=EffectKind.PUSH}
        publisher().publish()
        assertEquals(EffectState.CONFIRMED,journal.state("wf",EffectKind.COMMIT))
        permit={_,_->true}
        assertEquals("DRAFT_PR_CREATED",publisher().publish().status)
        assertEquals(1,sends); assertEquals(1,posts)
    }
    @Test fun existingDraftAfterPushPreventsAnySecondPost() {
        fixture(); lostPost=true
        publisher().publish()
        assertEquals(1,posts)
        assertEquals("NEEDS_OWNER",publisher().publish().status)
        assertTrue(publisher().reconcile(EffectKind.DRAFT_PR))
        assertEquals(1,posts)
    }
    @Test fun changedReviewSessionLogDeniesSending() {
        fixture()
        Files.writeString(temp.resolve("review-artifacts/events.log"),"{\"type\":\"thread.started\",\"thread_id\":\"coding-session\"}\n{\"type\":\"turn.completed\"}\n")
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status); assertNull(journal.findPlan("wf"))
    }
    @Test fun changedStagingConfigIsRejectedBeforeAnyResumeCommand() {
        fixture(); permit={_,k->k!=EffectKind.PUSH}; publisher().publish()
        Files.writeString(root.resolve("wf/objects.git/config"),"[include]\n path=/no/such/file\n")
        permit={_,_->true}
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status); assertEquals(0,sends)
    }
    @Test fun realPushWrapperUsesAskpassWithoutPersistingCredential() {
        fixture(); permit={_,k->k!=EffectKind.PUSH}; publisher().publish()
        val p=journal.findPlan("wf")!!
        val fake=Files.writeString(temp.resolve("fake-git"),"""#!/usr/bin/python3
import os, subprocess, sys
assert 'https://github.com/siyovushdev/devorch-kotlin-e2e-smoke.git' in sys.argv
assert not any(k in os.environ for k in ['GH_TOKEN','GITHUB_TOKEN','SSH_AUTH_SOCK'])
assert subprocess.check_output([os.environ['GIT_ASKPASS'],'Username']).strip()==b'x-access-token'
assert subprocess.check_output([os.environ['GIT_ASKPASS'],'Password']).strip().decode()==os.environ['DEVORCH_PUBLISH_TOKEN']
print('MOCK push accepted')
""")
        assertTrue(fake.toFile().setExecutable(true))
        val art=Files.createDirectory(temp.resolve("push-wrapper"))
        val token="MOCK_WRAPPER_TOKEN"
        val result=GitHubBranchPush(token,fake).send(p,root.resolve("wf/objects.git"),art)
        assertEquals(AttemptState.SUCCEEDED,result.state,result.reason)
        Files.list(art).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { assertFalse(Files.readString(it).contains(token),it.toString()) } }
    }
    @Test fun concurrentPublishersDoNotSendOrCreateTwice() {
        fixture()
        val workers=java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val results=workers.invokeAll((1..3).map { java.util.concurrent.Callable { publisher().publish().status } })
                .map { it.get(20,java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(results.contains("DRAFT_PR_CREATED"),results.toString())
            assertEquals(1,sends); assertEquals(1,posts)
        } finally { workers.shutdownNow() }
    }
    @Test fun existingClosedPrWithSameHeadBlocksRatherThanCreatingAnother() {
        fixture()
        permit={ p,_ ->
            pr=mapOf("state" to "closed","merged_at" to null,"head" to mapOf("ref" to p.headBranch))
            true
        }
        assertEquals("GUARD_OR_INPUT_DENIED",publisher().publish().status)
        assertEquals(0,sends); assertEquals(0,posts)
    }
    @Test fun responseSubscriberCancelsWhileReceivingOversizedBody() {
        var cancelled=false
        val body=LimitedPublicationBody(8)
        body.onSubscribe(object: Flow.Subscription { override fun request(n: Long) {}; override fun cancel() { cancelled=true } })
        body.onNext(listOf(ByteBuffer.wrap(ByteArray(9))))
        assertTrue(cancelled); assertTrue(body.body.toCompletableFuture().isCompletedExceptionally)
    }
}
