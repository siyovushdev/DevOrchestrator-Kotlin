package dev.orchestrator.storage

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant

/**
 * Persistence/receipt journal ONLY; no Git/HTTP sender or execution authority exists here.
 * Consuming SENDING is an intent, not permission. A future trusted Publisher must ALSO
 * enforce source/test/review/process/credential/Owner gates before external writes.
 */
internal class PublicationJournal(database: Path) {
    private val db = RuntimeDatabase(database)
    private val mapper = ObjectMapper()

    fun prepare(plan: PublicationPlan): Boolean = db.write { c ->
        val encoded = encode(plan)
        val prior = c.rows("SELECT plan_json FROM publication_plans WHERE workflow_id=?",plan.workflowId) {
            it.getString(1)
        }.singleOrNull()
        if (prior != null) return@write prior == encoded // Idempotent, never replace a pinned plan.
        if (!policy(c,plan) || !ready(c,plan)) return@write false
        if (c.rows("SELECT 1 FROM publication_plans WHERE repository_id=?",plan.repositoryId) { 1 }.isNotEmpty())
            return@write false // Conservative first-E2E reservation; no unproved release API.
        if (c.rows("""SELECT 1 FROM effects_outbox e JOIN workflows w ON w.workflow_id=e.workflow_id
            WHERE w.repository_id=? AND e.state IN ('SENDING','UNCERTAIN')""",plan.repositoryId) { 1 }.isNotEmpty())
            return@write false
        if (c.rows("SELECT 1 FROM effects_outbox WHERE workflow_id=?",plan.workflowId) { 1 }.isNotEmpty())
            return@write false // Unknown legacy effects cannot be adopted/replaced blindly.
        val now = Instant.now().toString()
        c.mutate("INSERT INTO publication_plans(workflow_id,repository_id,plan_json) VALUES (?,?,?)",
            plan.workflowId,plan.repositoryId,encoded)
        for (kind in EffectKind.entries) {
            val id = effectId(plan.workflowId,kind)
            c.mutate("""INSERT INTO effects_outbox(effect_id,workflow_id,kind,state,idempotency_key,
                intended_target,expected_sha,recorded_at) VALUES (?,?,?,'PREPARED',?,?,?,?)""",
                id,plan.workflowId,kind.name,id,"${plan.owner}/${plan.repository}:${plan.headBranch}",plan.commitSha,now)
        }
        event(c,plan.workflowId,"PUBLICATION_PREPARED",plan.candidateManifestSha256,now)
        true
    }

    fun findPlan(workflowId: String): PublicationPlan? = db.read { readPlan(it,workflowId) }
    fun state(workflowId: String, kind: EffectKind): EffectState? = db.read {
        readState(it,workflowId,kind)
    }

    /** Exactly one durable send intent; previous effect confirmed, current guards repeated. */
    fun markSending(workflowId: String, kind: EffectKind, verifiedManifestSha256: String): Boolean = db.write { c ->
        val plan = readPlan(c,workflowId) ?: return@write false
        if (verifiedManifestSha256 != plan.candidateManifestSha256 || !policy(c,plan)) return@write false
        val publishing = c.rows("""SELECT 1 FROM workflows WHERE workflow_id=? AND repository_id=?
            AND phase='PUBLISHING' AND hold='NONE' AND corrections_used=?""",workflowId,plan.repositoryId,
                plan.generation) { 1 }.singleOrNull() != null
        if (!publishing || !terminalAttempts(c,plan)) return@write false
        val previous = when (kind) { EffectKind.COMMIT -> null; EffectKind.PUSH -> EffectKind.COMMIT
            EffectKind.DRAFT_PR -> EffectKind.PUSH }
        if (previous != null && readState(c,workflowId,previous) != EffectState.CONFIRMED) return@write false
        val now = Instant.now().toString()
        val changed = c.mutate("UPDATE effects_outbox SET state='SENDING' WHERE effect_id=? AND state='PREPARED'",
            effectId(workflowId,kind)) == 1
        if (changed) event(c,workflowId,"PUBLICATION_SENDING",kind.name,now)
        changed
    }

    /** May reconcile UNCERTAIN, but never clears an Owner hold or advances terminal phase. */
    fun confirm(workflowId: String, kind: EffectKind, receipt: PublicationReadBack): Boolean = db.write { c ->
        val plan = readPlan(c,workflowId) ?: return@write false
        if (!PublicationReceiptMatcher.matches(plan,kind,receipt)) return@write false
        val encodedReceipt = mapper.writeValueAsString(receipt)
        val before = readState(c,workflowId,kind)
        if (before == EffectState.CONFIRMED) return@write c.rows(
            "SELECT evidence FROM publication_receipts WHERE effect_id=?",effectId(workflowId,kind),
        ) { it.getString(1) }.singleOrNull() == encodedReceipt
        if (before != EffectState.SENDING && before != EffectState.UNCERTAIN) return@write false
        val now = Instant.now().toString()
        check(c.mutate("UPDATE effects_outbox SET state='CONFIRMED' WHERE effect_id=? AND state IN ('SENDING','UNCERTAIN')",
            effectId(workflowId,kind)) == 1)
        c.mutate("INSERT INTO publication_receipts(effect_id,evidence,recorded_at) VALUES (?,?,?)",
            effectId(workflowId,kind),encodedReceipt,now)
        event(c,workflowId,"PUBLICATION_CONFIRMED",kind.name,now)
        true
    }

    /** Lost response/restart: retain all intents and hold workflow; no retry transition exists. */
    fun holdUnreconciledSends(): Int = db.write { c ->
        val sending = c.rows("""SELECT e.effect_id,e.workflow_id FROM effects_outbox e
            JOIN publication_plans p ON p.workflow_id=e.workflow_id WHERE e.state='SENDING'""") {
            it.getString(1) to it.getString(2)
        }
        val now = Instant.now().toString()
        for ((id,workflow) in sending) {
            c.mutate("UPDATE effects_outbox SET state='UNCERTAIN' WHERE effect_id=?",id)
            c.mutate("""UPDATE workflows SET hold='NEEDS_OWNER',hold_reason='UNRECONCILED_PUBLICATION',
                version=version+1,updated_at=? WHERE workflow_id=? AND hold!='NEEDS_OWNER'""",now,workflow)
            event(c,workflow,"PUBLICATION_UNCERTAIN",id,now)
        }
        sending.size
    }

    private fun policy(c: Connection,p: PublicationPlan): Boolean = c.rows("""SELECT 1 FROM projects p
        JOIN project_policies pp ON pp.repository_id=p.repository_id WHERE p.repository_id=?
        AND p.kind='PERSONAL' AND p.policy_version=? AND pp.personal_auto_execution=1""",
        p.repositoryId,p.policyVersion) { 1 }.singleOrNull() != null

    private fun ready(c: Connection,p: PublicationPlan): Boolean = c.rows("""SELECT 1 FROM workflows w
        JOIN github_issue_snapshots s ON s.workflow_id=w.workflow_id WHERE w.workflow_id=?
        AND w.repository_id=? AND w.issue_number=? AND w.phase='READY_FOR_PR' AND w.hold='NONE'
        AND w.version=? AND w.corrections_used=? AND s.base_sha=?""",p.workflowId,p.repositoryId,
        p.issueNumber,p.workflowVersion,p.generation,p.baseSha) { 1 }.singleOrNull() != null && terminalAttempts(c,p)

    private fun terminalAttempts(c: Connection,p: PublicationPlan): Boolean {
        val valid = c.rows("""SELECT attempt_id,role,source_tree FROM agent_attempts
            WHERE workflow_id=? AND generation=? AND state='SUCCEEDED' AND attempt_id IN (?,?)""",
            p.workflowId,p.generation,p.codingAttemptId,p.reviewerAttemptId) {
            Triple(it.getString(1),it.getString(2),it.getString(3))
        }
        return valid.any { it.first==p.codingAttemptId && it.second=="CODING" } &&
            valid.any { it.first==p.reviewerAttemptId && it.second=="REVIEW" && it.third==p.candidateTree } &&
            c.rows("""SELECT 1 FROM agent_attempts a JOIN workflows w ON w.workflow_id=a.workflow_id
                WHERE w.repository_id=? AND a.state IN ('PLANNED','STARTING','RUNNING','UNKNOWN')""",
                p.repositoryId) { 1 }.isEmpty()
    }

    private fun readState(c: Connection,w: String,k: EffectKind): EffectState? =
        c.rows("SELECT state FROM effects_outbox WHERE effect_id=?",effectId(w,k)) {
            EffectState.valueOf(it.getString(1))
        }.singleOrNull()

    private fun readPlan(c: Connection,w: String): PublicationPlan? =
        c.rows("SELECT plan_json FROM publication_plans WHERE workflow_id=?",w) { r ->
            val n = mapper.readTree(r.getString(1))
            PublicationPlan(n["workflowId"].asText(),n["repositoryId"].asLong(),n["issueNumber"].asInt(),
                n["owner"].asText(),n["repository"].asText(),n["baseBranch"].asText(),n["baseSha"].asText(),
                n["commitSha"].asText(),n["candidateTree"].asText(),n["candidateManifestSha256"].asText(),n["candidateId"].asText(),
                n["codingAttemptId"].asText(),n["reviewerAttemptId"].asText(),n["generation"].asInt(),
                n["policyVersion"].asLong(),n["workflowVersion"].asLong())
        }.singleOrNull()

    private fun encode(p: PublicationPlan): String = mapper.writeValueAsString(linkedMapOf(
        "workflowId" to p.workflowId,"repositoryId" to p.repositoryId,"issueNumber" to p.issueNumber,
        "owner" to p.owner,"repository" to p.repository,"baseBranch" to p.baseBranch,"baseSha" to p.baseSha,
        "commitSha" to p.commitSha,"candidateTree" to p.candidateTree,"candidateManifestSha256" to p.candidateManifestSha256,
        "candidateId" to p.candidateId,
        "codingAttemptId" to p.codingAttemptId,"reviewerAttemptId" to p.reviewerAttemptId,"generation" to p.generation,
        "policyVersion" to p.policyVersion,"workflowVersion" to p.workflowVersion))

    private fun event(c: Connection,w: String,type: String,detail: String,now: String) {
        c.mutate("INSERT INTO workflow_events(workflow_id,event_type,detail,recorded_at) VALUES (?,?,?,?)",w,type,detail,now)
    }

    private fun effectId(workflow: String,kind: EffectKind) = "$workflow:${kind.name}"
}
