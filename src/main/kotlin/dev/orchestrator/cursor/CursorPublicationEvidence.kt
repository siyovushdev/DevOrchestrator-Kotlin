package dev.orchestrator.cursor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.publish.SMOKE_TARGET
import dev.orchestrator.review.FrozenCandidate
import dev.orchestrator.storage.CodingIntent
import dev.orchestrator.storage.RuntimeDatabase
import dev.orchestrator.storage.WorkflowSnapshot
import dev.orchestrator.storage.rows
import java.nio.file.Files
import java.nio.file.Path

/** Publisher rechecks the two Cursor sessions. It does not invent a local Codex process. */
internal object CursorPublicationEvidence {
    private val mapper = ObjectMapper()

    fun validate(
        database: Path, root: Path, workflow: WorkflowSnapshot, candidate: FrozenCandidate,
        completion: JsonNode, review: JsonNode,
    ) {
        check(CursorCloudWorkflowBridge.site(database, workflow.id) == "api.cursor.com")
        val db = RuntimeDatabase(database)
        val rows = db.read { c ->
            c.rows(
                """SELECT role,agent_id,state,request_json,branch,head_sha,tree_sha FROM cursor_sessions
                   WHERE workflow_id=? AND generation=? AND state='FINISHED' ORDER BY role""",
                workflow.id, workflow.correctionsUsed,
            ) {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7))
            }
        }
        check(rows.map { it[0] }.toSet() == setOf("CODING", "REVIEW"))
        val coding = rows.single { it[0] == "CODING" }
        val reviewed = rows.single { it[0] == "REVIEW" }
        check(coding[1] != reviewed[1])
        check(coding[1] == candidate.binding.codingSessionId)
        check(reviewed[1] == review.path("receipt").path("reviewerSessionId").asText())
        check(coding[6] == candidate.binding.candidateTree)
        check(completion.path("candidate_tree").asText() == candidate.binding.candidateTree)
        check(completion.path("observed").path("agent_id").asText() == coding[1])
        check(completion.path("observed").path("monetary_cap_enforced").asBoolean(true) == false)
        check(review.path("process").path("monetary_cap_enforced").asBoolean(true) == false)
        check(review.path("monetary_cap_enforced").asBoolean(true) == false)
        for (row in listOf(coding, reviewed)) {
            val body = mapper.readTree(row[3])
            check(body.path("autoCreatePR").isBoolean && !body.path("autoCreatePR").asBoolean())
            check(!body.has("envVars"))
            check(body.path("workOnCurrentBranch").asBoolean(true) == false)
            check(body.path("agentId").asText() == row[1])
            val repo = body.path("repos")
            check(repo.size() == 1)
            check(repo[0].path("url").asText() == "https://github.com/${SMOKE_TARGET.fullName}")
        }
        check(coding[4]?.startsWith("devorch/") != true)
        val log = Path.of(completion.path("artifacts").asText()).resolve("events.log")
        check(log.startsWith(root) && Files.isRegularFile(log))
    }
}
