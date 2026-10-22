package dev.orchestrator.workflow

import com.fasterxml.jackson.databind.JsonNode
import dev.orchestrator.agent.AgentRole
import dev.orchestrator.agent.SqliteAgentCatalog
import dev.orchestrator.remote.*
import dev.orchestrator.review.FrozenCandidate
import dev.orchestrator.review.VerificationStatus
import dev.orchestrator.storage.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Publisher rechecks the genuine guest chain; no invented local process-start events. */
internal object RemotePublicationEvidence {
    fun validate(database: Path,root: Path,w: WorkflowSnapshot,candidate: FrozenCandidate,
                 verified: JsonNode,coding: CodingIntent,completion: JsonNode,review: JsonNode) {
        val db=RuntimeDatabase(database); val journal=RemoteAttemptJournal(database); val mapper=RemoteProtocol.mapper
        check(RemoteWorkflowBridge.site(database,w.id)!=null)
        val scratch=Files.createTempDirectory(root,".remote-publication-check-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        try {
            val rows=db.read { c -> c.rows("""SELECT a.attempt_id,a.context FROM remote_workflow_actions a JOIN remote_attempts r ON r.attempt_id=a.attempt_id
                WHERE r.workflow_id=? AND r.generation=? AND a.state='IMPORTED'""",w.id,w.correctionsUsed) { it.getString(1) to mapper.readTree(it.getString(2)) } }
            check(rows.size==3)
            val records=rows.associate { (id,context) ->
                val saved=journal.find(id)!!; check(saved.state=="EVIDENCE_STORED")
                RemoteEvidence(scratch).check(saved.request,saved.evidence!!)
                val request=saved.request.json; val i=request["identity"]; val role=i["role"].asText(); val n=mapper.readTree(saved.evidence)
                check(i["workflowId"].asText()==w.id && i["issueNumber"].asInt()==w.issueNumber && i["baseSha"].asText()==coding.baseSha && i["generation"].asInt()==w.correctionsUsed)
                check(context["codingAttemptId"].asText()==coding.attemptId && context["policyVersion"].asLong()==coding.policyVersion && context["issueBodySha256"].asText()==coding.issueBodySha256)
                val assigned=SqliteAgentCatalog(database).findAssignedProfile(w.repositoryId,if(role=="REVIEW") AgentRole.REVIEW else AgentRole.CODING)!!
                check(mapper.readTree(mapper.writeValueAsBytes(assigned))==context["assigned"] && request["model"].asText()==assigned.profile.model && request["effort"].asText()==assigned.profile.effort.name.lowercase())
                val event=SqliteTaskStore(database).listEvents(w.id).filter { it.type=="REMOTE_RESULT_IMPORTED" }.map { mapper.readTree(it.detail) }.single { it["attemptId"].asText()==id }
                check(event["requestSha256"].asText()==saved.request.sha256 && event["evidenceSha256"].asText()==RemoteProtocol.digest(saved.evidence))
                val dir=Path.of(event["directory"].asText()); check(dir.toRealPath()==dir && dir.startsWith(root) && dir.fileName.toString()=="remote-$id")
                check(RemoteProtocol.file(dir.resolve("request.json"),RemoteProtocol.LIMIT).contentEquals(saved.request.bytes) && RemoteProtocol.file(dir.resolve("result.json"),RemoteProtocol.LIMIT).contentEquals(saved.evidence))
                check(mapper.readTree(RemoteProtocol.file(dir.resolve("context.json"),65536))==context)
                check(RemoteProtocol.tree(dir.resolve("image"),Files.createTempDirectory(scratch,"tree-"))==n["outputTree"].asText())
                for((name,value) in n["artifacts"].fields().asSequence()) check(RemoteProtocol.digest(RemoteProtocol.file(dir.resolve("output").resolve(name),1_048_576))==value["sha256"].asText())
                if(role!="CODING") {
                    val input=FrozenCandidate.reopen(Path.of(context["inputDirectory"].asText()),i["manifestSha256"].asText())
                    check(input.directory.startsWith(root) && input.candidateId==i["candidateId"].asText() && input.binding.workflowId==w.id && input.binding.codingAttemptId==coding.attemptId && input.binding.generation==w.correctionsUsed && input.binding.baseSha==coding.baseSha && input.binding.candidateTree==i["sourceTree"].asText() && input.binding.codingSessionId==i["codingSessionId"].asText())
                    check(input.binding.verification==if(role=="TESTS") VerificationStatus.NOT_RUN else VerificationStatus.PASS)
                    check(RemoteProtocol.tree(input.filesDirectory,Files.createTempDirectory(scratch,"input-tree-"))==i["sourceTree"].asText())
                }
                role to Triple(saved,dir,n)
            }
            check(records.keys==setOf("CODING","TESTS","REVIEW"))
            val c=records.getValue("CODING"); val t=records.getValue("TESTS"); val r=records.getValue("REVIEW")
            val b=candidate.binding
            check(c.first.request.attemptId==coding.attemptId && c.first.request.json["identity"]["sourceTree"].asText()==coding.sourceTree && c.third["outputTree"].asText()==b.candidateTree && RemoteWorkflowBridge.remoteSession(c.third)==b.codingSessionId)
            check(completion["observed"]==c.third["receipt"] && completion["artifacts"].asText()==c.second.resolve("output").toString())
            check(t.first.request.json["identity"]["sourceTree"].asText()==b.candidateTree && t.first.request.json["identity"]["codingSessionId"].asText()==b.codingSessionId)
            val tests=RemoteWorkflowBridge.testResult(t.first.request,t.third)
            check(tests["pass"].asBoolean() && verified["process"]==t.third["receipt"] && verified["artifacts"].asText()==t.second.resolve("output").toString() && verified["harnessSha256"].asText()==RemoteWorkflowBridge.harnessDigest() && verified["minimumTests"].asInt()==t.first.request.json["minimumTests"].asInt())
            for((local,remote) in mapOf("testsRun" to "tests_run","failures" to "failures","errors" to "errors","skipped" to "skipped","unexpectedSuccesses" to "unexpected_successes","completedInvocations" to "completed_invocations","testIdsSha256" to "test_ids_sha256")) check(verified[local]==tests[remote])
            check(RemoteProtocol.tree(candidate.filesDirectory,Files.createTempDirectory(scratch,"candidate-tree-"))==b.candidateTree)
            val i=r.first.request.json["identity"]
            check(i["candidateId"].asText()==candidate.candidateId && i["manifestSha256"].asText()==candidate.manifestSha256 && i["sourceTree"].asText()==b.candidateTree && i["codingSessionId"].asText()==b.codingSessionId)
            val last=mapper.readTree(RemoteWorkflowBridge.decoded(r.third,"last-message.txt")); val receipt=review["receipt"]
            check(last.isObject && last.fieldNames().asSequence().toSet()==setOf("verdict","candidate_id","manifest_sha256","candidate_tree","generation","findings") && last["verdict"].asText()=="PASS" && last["findings"].isArray && last["findings"].isEmpty && last["candidate_id"].asText()==candidate.candidateId && last["manifest_sha256"].asText()==candidate.manifestSha256 && last["candidate_tree"].asText()==b.candidateTree && last["generation"].isInt && last["generation"].asInt()==w.correctionsUsed)
            check(receipt["reviewerAttemptId"].asText()==r.first.request.attemptId && receipt["reviewerSessionId"].asText()==RemoteWorkflowBridge.remoteSession(r.third) && review["process"]==r.third["receipt"] && review["artifacts"].asText()==r.second.resolve("output").toString())
            val all=db.read { connection -> connection.rows("SELECT evidence FROM remote_attempts WHERE workflow_id=? AND role IN ('CODING','REVIEW') AND state='EVIDENCE_STORED'",w.id) { mapper.readTree(it.getBytes(1)) } }
            val session=RemoteWorkflowBridge.remoteSession(r.third)
            check(all.count { RemoteWorkflowBridge.remoteSession(it)==session }==1 && session!=b.codingSessionId)
        } finally { Files.walk(scratch).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
