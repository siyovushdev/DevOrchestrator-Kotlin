package dev.orchestrator.publish

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.agent.nativecli.*
import dev.orchestrator.review.*
import dev.orchestrator.storage.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal data class PublicationResult(val status: String, val url: String? = null)

/** One explicit trusted call, no startup/step/model hook. Real sends require separate Owner GO. */
internal class DraftPrPublisher(
    private val database: Path,
    private val publisherRoot: Path,
    private val expectedBase: String,
    private val api: GitHubPublicationApi,
    private val push: PublicationPush,
    private val ownerGo: (PublicationPlan,EffectKind) -> Boolean,
    private val git: Path = Path.of("/usr/bin/git"),
    private val issueNumber: Int = 1,
) {
    private val store=SqliteTaskStore(database)
    private val db=RuntimeDatabase(database)
    private val journal=PublicationJournal(database)
    private val coding=CodingAttemptJournal(database)
    private val mapper=ObjectMapper()
    private val probe=BoundedNativeProbeReader(maxOutputBytes=1_000_000)
    private val target=SMOKE_TARGET
    private data class Evidence(val w: WorkflowSnapshot, val candidate: FrozenCandidate,
        val coding: CodingIntent, val reviewer: String)
    init { require(issueNumber>0); require(expectedBase.matches(Regex("[0-9a-f]{40}"))) }

    fun publish(): PublicationResult {
        val w=store.findWorkflow(target.repositoryId,issueNumber) ?: return PublicationResult("NO_WORKFLOW")
        if (w.hold!=WorkflowHold.NONE) return PublicationResult("NEEDS_OWNER")
        if (w.phase==WorkflowPhase.DRAFT_PR_CREATED) return PublicationResult("ALREADY_CONFIRMED",confirmedUrl(w.id))
        var active: EffectKind?=null
        try {
            val evidence=loadEvidence()
            var plan=journal.findPlan(w.id)
            if (plan==null) {
                check(w.phase==WorkflowPhase.READY_FOR_PR)
                val dir=privateDirectory(publisherRoot.resolve(w.id))
                stage(evidence,dir)
                val candidate=evidence.candidate
                val content=commitBytes(candidate.binding.candidateTree,w.id,candidate.candidateId,candidate.manifestSha256)
                plan=PublicationPlan(w.id,target.repositoryId,issueNumber,target.owner,target.name,"main",expectedBase,
                    objectId(content),candidate.binding.candidateTree,candidate.manifestSha256,candidate.candidateId,
                    evidence.coding.attemptId,evidence.reviewer,w.correctionsUsed,evidence.coding.policyVersion,w.version)
                Files.write(dir.resolve("commit.txt"),content,CREATE_NEW)
                check(journal.prepare(plan))
            }
            assertPlan(plan,loadEvidence())
            if (store.findWorkflow(target.repositoryId,issueNumber)!!.phase==WorkflowPhase.READY_FOR_PR)
                check(journal.beginPublishing(plan))
            for (kind in EffectKind.entries) {
                when (journal.state(plan.workflowId,kind)) {
                    EffectState.CONFIRMED -> continue
                    EffectState.SENDING,EffectState.UNCERTAIN -> return PublicationResult("PENDING_OR_UNCERTAIN_NO_RETRY")
                    EffectState.PREPARED -> {}
                    else -> error("Invalid effect state")
                }
                boundary(plan,kind)
                if (!journal.markSending(plan.workflowId,kind,plan.candidateManifestSha256))
                    return PublicationResult("ALREADY_CONSUMED_OR_GUARD_DENIED")
                active=kind
                // Repeat protected facts, current policy/resources, Owner decision and remote
                // identity immediately before each write; PREPARED/SENDING is not permission.
                boundary(plan,kind)
                val dir=publisherRoot.resolve(plan.workflowId)
                stagingGuard(dir.resolve("objects.git"))
                val receipt: PublicationReadBack=when (kind) {
                    EffectKind.COMMIT -> {
                        check(Files.readAllBytes(dir.resolve("commit.txt")).contentEquals(commitBytes(
                            plan.candidateTree,plan.workflowId,plan.candidateId,plan.candidateManifestSha256)))
                        check(local(dir,"hash-object","-t","commit","-w",dir.resolve("commit.txt").toString())==plan.commitSha)
                        localCommit(plan)
                    }
                    EffectKind.PUSH -> {
                        val artifacts=privateDirectory(dir.resolve("push"))
                        val result=push.send(plan,dir.resolve("objects.git"),artifacts)
                        check(result.state==AttemptState.SUCCEEDED)
                        checkNewBranch(plan,artifacts.resolve("events.log"))
                        remotePush(plan)
                    }
                    EffectKind.DRAFT_PR -> {
                        val number=api.createDraft(plan)
                        val observed=api.drafts(plan)
                        check(observed.matches.single()==api.singleDraft(number,plan))
                        check(remotePush(plan).commitSha==plan.commitSha)
                        observed
                    }
                }
                check(journal.confirm(plan.workflowId,kind,receipt))
                active=null
            }
            // A resumed terminal sequence is also re-read, never merely trusted from enum rows.
            assertPlan(plan,loadEvidence()); api.checkBase(plan); remotePush(plan)
            check(journal.confirm(plan.workflowId,EffectKind.DRAFT_PR,api.drafts(plan)))
            check(journal.finish(plan))
            return PublicationResult("DRAFT_PR_CREATED",confirmedUrl(plan.workflowId))
        } catch (_: Exception) {
            if (active!=null) journal.uncertain(w.id,active,"PUBLICATION_OUTCOME_UNCERTAIN")
            return PublicationResult(if (active!=null) "UNCERTAIN_NEEDS_OWNER" else "GUARD_OR_INPUT_DENIED")
        }
    }

    /** Exclusive restart only. Never resends, clears a hold, or launches Coding/Review. */
    fun recover(): Int = journal.holdUnreconciledSends()

    /** Read-only effect reconciliation; even a matching receipt leaves NEEDS_OWNER intact. */
    fun reconcile(kind: EffectKind): Boolean = try {
        val w=store.findWorkflow(target.repositoryId,issueNumber)!!
        val plan=journal.findPlan(w.id)!!
        check(journal.state(w.id,kind)==EffectState.UNCERTAIN)
        assertPlan(plan,loadEvidence(allowHold=true)); api.checkBase(plan)
        val receipt=when (kind) {
            EffectKind.COMMIT -> localCommit(plan)
            EffectKind.PUSH -> remotePush(plan)
            EffectKind.DRAFT_PR -> {
                remotePush(plan)
                val observed=api.drafts(plan)
                check(observed.matches.single()==api.singleDraft(observed.matches.single().number,plan))
                observed
            }
        }
        journal.confirm(w.id,kind,receipt)
    } catch (_: Exception) { false }

    private fun boundary(plan: PublicationPlan,kind: EffectKind) {
        assertPlan(plan,loadEvidence())
        check(ownerGo(plan,kind))
        api.checkBase(plan)
        if (kind==EffectKind.DRAFT_PR) remotePush(plan) else check(api.ref(plan.headBranch)==null)
        check(api.drafts(plan).matches.isEmpty())
        if (kind!=EffectKind.COMMIT) localCommit(plan)
        // The network checks above can take time: repeat the local guards and GO last.
        assertPlan(plan,loadEvidence()); check(ownerGo(plan,kind))
    }

    private fun assertPlan(plan: PublicationPlan,e: Evidence) {
        val b=e.candidate.binding
        check(plan.repositoryId==target.repositoryId && plan.owner==target.owner && plan.repository==target.name &&
            plan.issueNumber==issueNumber && plan.baseBranch=="main" && plan.baseSha==expectedBase && plan.workflowId==e.w.id &&
            plan.candidateTree==b.candidateTree && plan.candidateManifestSha256==e.candidate.manifestSha256 &&
            plan.candidateId==e.candidate.candidateId && plan.codingAttemptId==e.coding.attemptId &&
            plan.reviewerAttemptId==e.reviewer && plan.generation==e.w.correctionsUsed &&
            plan.policyVersion==e.coding.policyVersion &&
            plan.commitSha==objectId(commitBytes(plan.candidateTree,plan.workflowId,plan.candidateId,plan.candidateManifestSha256)))
        check(e.w.version==plan.workflowVersion+(if (e.w.phase==WorkflowPhase.READY_FOR_PR) 0 else 1) || e.w.hold!=WorkflowHold.NONE)
    }

    private fun loadEvidence(allowHold: Boolean=false): Evidence {
        val w=store.findWorkflow(target.repositoryId,issueNumber)!!
        check(w.phase in setOf(WorkflowPhase.READY_FOR_PR,WorkflowPhase.PUBLISHING) && (allowHold || w.hold==WorkflowHold.NONE))
        val policy=store.findPolicy(target.repositoryId)!!
        check(policy.kind==ProjectKind.PERSONAL && policy.personalAutoExecution)
        canonical(policy.workspaceRoot); canonical(publisherRoot)
        check(publisherRoot.startsWith(policy.workspaceRoot) && publisherRoot!=policy.workspaceRoot)
        val issue=store.findPinnedGitHubIssue(target.repositoryId,issueNumber)!!
        check(issue.workflowId==w.id && issue.baseSha==expectedBase)
        val raw=db.read { c -> c.rows("SELECT evidence FROM workflow_verifications WHERE workflow_id=? AND generation=? AND state='SUCCEEDED'",
            w.id,w.correctionsUsed) { mapper.readTree(it.getString(1)) }.single() }
        check(raw.path("status").asText()=="PASS" && raw.path("generation").asInt(-1)==w.correctionsUsed &&
            raw.path("minimumTests").asInt()>0 && raw.path("testsRun").asInt()-raw.path("skipped").asInt()>=raw.path("minimumTests").asInt() &&
            raw.path("completedInvocations").asInt()==raw.path("testsRun").asInt() && raw.path("testIdsSha256").asText().matches(Regex("[0-9a-f]{64}")) &&
            listOf("failures","errors","unexpectedSuccesses").all { raw.path(it).isInt && raw.path(it).asInt()==0 })
        clean(raw.path("process"))
        val intent=coding.find(raw.path("codingAttemptId").asText())!!
        check(intent.workflowId==w.id && intent.repositoryId==target.repositoryId && intent.issueNumber==issueNumber &&
            intent.state==AttemptState.SUCCEEDED && intent.generation==w.correctionsUsed && intent.baseSha==expectedBase &&
            intent.issueBodySha256==issue.bodySha256 && intent.policyVersion==policy.version)
        db.read { c -> check(c.rows("""SELECT 1 FROM workflow_verifications WHERE workflow_id=? AND generation=?
            AND coding_attempt_id=? AND policy_version=? AND minimum_tests=? AND state='SUCCEEDED'""",
            w.id,w.correctionsUsed,intent.attemptId,policy.version,raw.path("minimumTests").asInt()) { 1 }.size==1) }
        val candidate=FrozenCandidate.reopen(Path.of(raw.path("directory").asText()),raw.path("manifestSha256").asText())
        val b=candidate.binding
        check(candidate.directory.startsWith(policy.workspaceRoot) && candidate.candidateId==raw.path("candidateId").asText() &&
            b.workflowId==w.id && b.codingAttemptId==intent.attemptId && b.codingSessionId==raw.path("codingSessionId").asText() &&
            b.generation==w.correctionsUsed && b.baseSha==expectedBase && b.candidateTree==raw.path("candidateTree").asText() &&
            b.verification==VerificationStatus.PASS && b.verificationEvidenceId=="${intent.attemptId}-tests")
        listOf(intent.workspace,candidate.directory,Path.of(raw.path("artifacts").asText())).forEach {
            canonical(it); check(it.startsWith(policy.workspaceRoot) && !it.startsWith(publisherRoot) && !publisherRoot.startsWith(it))
        }
        val events=store.listEvents(w.id)
        fun started(type: String,n: JsonNode) = events.filter { it.type==type }.map { mapper.readTree(it.detail) }
            .any { birthMatches(n,it) || birthMatches(n,it.path("identity")) }
        val remote=dev.orchestrator.workflow.RemoteWorkflowBridge.site(database,w.id)!=null
        val cursor=dev.orchestrator.cursor.CursorCloudWorkflowBridge.site(database,w.id)!=null
        check(!(remote && cursor))
        if(!remote && !cursor) check(started("VERIFICATION_PROCESS_STARTED",raw.path("process")))
        val completionType=when {
            cursor -> "CURSOR_CODING_COMPLETED"
            remote -> "REMOTE_CODING_COMPLETED"
            else -> "CODING_PROCESS_SUCCEEDED"
        }
        val completion=events.filter { it.type==completionType }.map { mapper.readTree(it.detail) }
            .single { it.path("attempt_id").asText()==intent.attemptId }
        if(!cursor) clean(completion.path("observed"))
        if(!remote && !cursor) {
        val codingBirth=events.filter { it.type=="CODING_PROCESS_STARTED" }.map { mapper.readTree(it.detail) }
            .single { it.path("attempt_id").asText()==intent.attemptId }.path("identity")
        check(birthMatches(completion.path("observed"),codingBirth))
        }
        val codingArtifacts=Path.of(completion.path("artifacts").asText())
        canonical(codingArtifacts); check(codingArtifacts.startsWith(policy.workspaceRoot))
        check(session(codingArtifacts.resolve("events.log"))==b.codingSessionId)
        check(completion.path("candidate_tree").asText()==b.candidateTree && completion.path("base_sha").asText()==expectedBase &&
            completion.path("input_tree").asText()==intent.sourceTree && completion.path("issue_body_sha256").asText()==intent.issueBodySha256 &&
            completion.path("profile_id").asText()==intent.profileId && completion.path("profile_version").asLong()==intent.profileVersion &&
            completion.path("assignment_version").asLong()==intent.assignmentVersion && completion.path("manifest_version").asLong()==intent.manifestVersion)
        val reviews=events.filter { it.type=="REVIEW_COMPLETED" }.map { mapper.readTree(it.detail) }
        val review=reviews.single { it.path("receipt").path("codingGeneration").asInt(-1)==w.correctionsUsed }
        if(!cursor) clean(review.path("process"))
        if(!remote && !cursor) check(started("REVIEW_PROCESS_STARTED",review.path("process")))
        val r=review.path("receipt")
        val receipt=ReviewReceipt(r.path("candidateId").asText(),r.path("reviewerAttemptId").asText(),r.path("reviewerSessionId").asText(),
            r.path("candidateManifestSha256").asText(),r.path("candidateTree").asText(),r.path("codingGeneration").asInt(-1),
            ReviewVerdict.valueOf(r.path("verdict").asText()))
        val reviewArtifacts=Path.of(review.path("artifacts").asText())
        canonical(reviewArtifacts); check(reviewArtifacts.startsWith(policy.workspaceRoot))
        check(session(reviewArtifacts.resolve("events.log"))==receipt.reviewerSessionId)
        check(receipt.verdict==ReviewVerdict.PASS && review.path("findings").isArray && review.path("findings").isEmpty &&
            ReviewEvidencePolicy.evaluate(candidate,receipt,candidate.manifestSha256,w.correctionsUsed,2)==ReviewDisposition.ELIGIBLE_FOR_CONTROLLER_CHECKS &&
            reviews.none { it!==review && it.path("receipt").path("reviewerSessionId").asText()==receipt.reviewerSessionId })
        if(remote) dev.orchestrator.workflow.RemotePublicationEvidence.validate(database,policy.workspaceRoot,w,candidate,raw,intent,completion,review)
        if(cursor) dev.orchestrator.cursor.CursorPublicationEvidence.validate(database,policy.workspaceRoot,w,candidate,completion,review)
        db.read { c ->
            check(c.rows("""SELECT 1 FROM review_inputs r JOIN agent_attempts a ON a.attempt_id=r.attempt_id
                JOIN attempt_contexts x ON x.attempt_id=a.attempt_id WHERE r.attempt_id=? AND r.workflow_id=? AND r.generation=?
                AND r.candidate_id=? AND r.manifest_sha256=? AND r.candidate_tree=? AND r.candidate_directory=? AND r.coding_session_id=?
                AND a.role='REVIEW' AND a.state='SUCCEEDED' AND a.source_tree=? AND a.workflow_id=r.workflow_id AND a.generation=r.generation
                AND x.base_sha=? AND x.issue_body_sha256=? AND x.issue_number=? AND x.policy_version=? AND x.repository_id=?
                AND x.profile_id=? AND x.profile_version=? AND x.assignment_version=? AND x.manifest_version=?""",
                receipt.reviewerAttemptId,w.id,w.correctionsUsed,candidate.candidateId,candidate.manifestSha256,b.candidateTree,
                candidate.directory.toString(),b.codingSessionId,b.candidateTree,expectedBase,intent.issueBodySha256,issueNumber,policy.version,target.repositoryId,
                review.path("profile_id").asText(),review.path("profile_version").asLong(),review.path("assignment_version").asLong(),
                review.path("manifest_version").asLong()) { 1 }.size==1)
            // Same minimal protected-controller resource contract as the engine; own
            // SENDING effect is allowed while checking its boundary, all other ambiguity blocks.
            fun overlaps(id: Long,path: String): Boolean {
                val p=Path.of(path); canonical(p)
                return id==target.repositoryId || p.startsWith(policy.workspaceRoot) || policy.workspaceRoot.startsWith(p)
            }
            check(c.rows("SELECT repository_id,workspace FROM runtime_reservations") { overlaps(it.getLong(1),it.getString(2)) }.none { it })
            check(c.rows("""SELECT w.workflow_id,w.repository_id,p.workspace_root,a.state FROM agent_attempts a
                JOIN workflows w ON w.workflow_id=a.workflow_id JOIN projects p ON p.repository_id=w.repository_id""") {
                overlaps(it.getLong(2),it.getString(3)) && (it.getString(1)!=w.id || it.getString(4) !in setOf("SUCCEEDED","FAILED"))
            }.none { it })
            check(c.rows("""SELECT w.workflow_id,w.repository_id,p.workspace_root,e.state FROM effects_outbox e
                JOIN workflows w ON w.workflow_id=e.workflow_id JOIN projects p ON p.repository_id=w.repository_id
                WHERE e.state IN ('SENDING','UNCERTAIN')""") {
                overlaps(it.getLong(2),it.getString(3)) && (it.getString(1)!=w.id || !allowHold && it.getString(4)=="UNCERTAIN")
            }.none { it })
        }
        return Evidence(w,candidate,intent,receipt.reviewerAttemptId)
    }

    private fun clean(n: JsonNode) {
        check(n.path("cleanup_echild").isBoolean && n.path("cleanup_echild").asBoolean() &&
            n.path("exit_code").isInt && n.path("exit_code").asInt()==0 && n.path("cause").asText()=="exited" &&
            listOf("child","supervisor").all { n.path(it).path("pid").asLong()>0 && n.path(it).path("start_ticks").asText().isNotBlank() } &&
            n.path("boot_id").asText().isNotBlank() && n.path("machine_id").asText().isNotBlank())
    }
    private fun birthMatches(n: JsonNode,birth: JsonNode): Boolean =
        n.path("supervisor").path("pid").asText()==birth.path("pid").asText() &&
        n.path("supervisor").path("start_ticks").asText()==birth.path("start_ticks").asText() &&
        n.path("boot_id").asText()==birth.path("boot_id").asText() && n.path("machine_id").asText()==birth.path("machine_id").asText()
    private fun session(path: Path): String {
        check(Files.isRegularFile(path,NOFOLLOW_LINKS) && (Files.getAttribute(path,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt()==1)
        val bytes=Files.newInputStream(path,NOFOLLOW_LINKS).use { it.readNBytes(1_048_577) }
        check(bytes.size<=1_048_576)
        val events=bytes.toString(Charsets.UTF_8).lineSequence().map { it.trim() }.filter { it.startsWith("{") }
            .map { mapper.readTree(it) }.toList()
        check(events.count { it.path("type").asText()=="turn.completed" }==1 &&
            events.none { it.path("type").asText() in setOf("error","turn.failed") })
        return events.single { it.path("type").asText()=="thread.started" }.path("thread_id").asText()
    }
    private fun stage(e: Evidence, dir: Path) {
        check(TrustedGitCheckoutGuard.accepts(e.coding.workspace))
        val head=probe.read(listOf(git.toString(),"--git-dir=${e.coding.workspace.resolve(".git")}","rev-parse","HEAD"),e.coding.workspace)
        check(head is ProbeProcessResult.Exited && head.exitCode==0 && head.output.trim()==expectedBase)
        val empty=Files.createDirectory(dir.resolve("empty-template"))
        command(dir,listOf("init","--bare","--template=$empty",dir.resolve("objects.git").toString()))
        // Import trusted local base objects only; saved source/index/objects stay untouched.
        local(dir,"-c","protocol.file.allow=always","fetch","--no-tags","--no-write-fetch-head","--no-auto-maintenance",
            e.coding.workspace.toString(),expectedBase)
        local(dir,"--work-tree=${e.candidate.filesDirectory}","read-tree","--empty")
        command(e.candidate.filesDirectory,listOf("--git-dir=${dir.resolve("objects.git")}",
            "--work-tree=${e.candidate.filesDirectory}","-c","core.hooksPath=/dev/null","add","--force","--all","--","."))
        check(local(dir,"write-tree")==e.candidate.binding.candidateTree && e.candidate.isIntact())
    }
    private fun localCommit(plan: PublicationPlan): PublicationReadBack.Commit {
        val dir=publisherRoot.resolve(plan.workflowId)
        check(local(dir,"cat-file","commit",plan.commitSha)==commitBytes(plan.candidateTree,plan.workflowId,
            plan.candidateId,plan.candidateManifestSha256).toString(Charsets.UTF_8).trim())
        return PublicationReadBack.Commit(plan.commitSha,plan.candidateTree,listOf(expectedBase))
    }
    private fun remotePush(plan: PublicationPlan): PublicationReadBack.Push {
        api.checkBase(plan)
        check(api.ref(plan.headBranch)==plan.commitSha && PublicationReceiptMatcher.matches(plan,EffectKind.COMMIT,api.commit(plan)))
        return PublicationReadBack.Push(target.repositoryId,target.fullName,plan.headBranch,plan.commitSha)
    }
    private fun checkNewBranch(plan: PublicationPlan,log: Path) {
        check(Files.isRegularFile(log,NOFOLLOW_LINKS) && (Files.getAttribute(log,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt()==1)
        val bytes=Files.newInputStream(log,NOFOLLOW_LINKS).use { it.readNBytes(1_048_577) }
        check(bytes.size<=1_048_576)
        val records=bytes.toString(Charsets.UTF_8).lineSequence().map { it.split('\t') }
            .filter { it.size==3 && it[0] in listOf(" ","+","-","*","=","!") }.toList()
        check(records.single()==listOf("*","${plan.commitSha}:refs/heads/${plan.headBranch}","[new branch]"))
    }
    private fun confirmedUrl(workflow: String): String? = journal.receipt(workflow,EffectKind.DRAFT_PR)?.let {
        mapper.readTree(it).path("matches").single().path("url").asText()
    }
    private fun local(dir: Path,vararg args: String): String {
        stagingGuard(dir.resolve("objects.git"))
        return command(dir,listOf("--git-dir=${dir.resolve("objects.git")}",
            "-c","core.hooksPath=/dev/null","-c","core.fsmonitor=false")+args)
    }
    private fun stagingGuard(stage: Path) {
        canonical(stage)
        check(Files.isRegularFile(stage.resolve("config"),NOFOLLOW_LINKS) &&
            Files.readString(stage.resolve("config"))=="[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = true\n")
        check(!Files.exists(stage.resolve("commondir"),NOFOLLOW_LINKS) &&
            !Files.exists(stage.resolve("objects/info/alternates"),NOFOLLOW_LINKS))
    }
    private fun command(cwd: Path,args: List<String>): String {
        val result=probe.read(listOf(git.toRealPath().toString())+args,cwd)
        check(result is ProbeProcessResult.Exited && result.exitCode==0)
        return result.output.trim()
    }
    private fun commitBytes(tree: String,workflow: String,candidate: String,manifest: String): ByteArray =
        ("tree $tree\nparent $expectedBase\nauthor DevOrchestrator <devorchestrator@users.noreply.github.com> 0 +0000\n"+
            "committer DevOrchestrator <devorchestrator@users.noreply.github.com> 0 +0000\n\n"+
            "Implement Issue #$issueNumber\n\nWorkflow: $workflow\nCandidate: $candidate\nManifest: $manifest\n").toByteArray(Charsets.UTF_8)
    private fun objectId(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest("commit ${bytes.size}\u0000".toByteArray()+bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun canonical(p: Path) { check(p.isAbsolute && p.normalize()==p && p.toRealPath()==p && Files.isDirectory(p,NOFOLLOW_LINKS)) }
    private fun privateDirectory(p: Path): Path = Files.createDirectory(p,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
}
