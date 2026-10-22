package dev.orchestrator.storage

import dev.orchestrator.agent.AgentRole
import dev.orchestrator.agent.AssignedAgentProfile
import dev.orchestrator.agent.SqliteAgentCatalog
import dev.orchestrator.agent.nativecli.TrustedGitCheckoutGuard
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant

internal data class CodingIntent(
    val attemptId: String,
    val workflowId: String,
    val repositoryId: Long,
    val issueNumber: Int,
    val state: AttemptState,
    val sourceTree: String,
    val baseSha: String,
    val issueBodySha256: String,
    val policyVersion: Long,
    val assignmentVersion: Long,
    val profileId: String,
    val profileVersion: Long,
    val adapterId: String,
    val manifestVersion: Long,
    val workspace: Path,
    val plannedWorkflowVersion: Long,
    val generation: Int,
)

/**
 * Coding lifecycle facts only. No spawn, inference, scheduler or security/Owner GO provider.
 * STARTING records a consumed intent, never a launch permit. A future runner must ALSO
 * validate protected source identity, runtime/credential evidence and specific Owner GO.
 */
internal class CodingAttemptJournal(database: Path) {
    private val db = RuntimeDatabase(database)
    private val catalog = SqliteAgentCatalog(database)

    fun plan(repositoryId: Long, issueNumber: Int, expectedWorkflowVersion: Long,
             attemptId: String, workspace: Path, sourceTree: String): CodingIntent? =
        planInternal(repositoryId,issueNumber,expectedWorkflowVersion,attemptId,workspace,sourceTree,false)

    fun planCorrection(repositoryId: Long, issueNumber: Int, expectedWorkflowVersion: Long,
                       attemptId: String, workspace: Path, sourceTree: String): CodingIntent? =
        planInternal(repositoryId,issueNumber,expectedWorkflowVersion,attemptId,workspace,sourceTree,true)

    private fun planInternal(repositoryId: Long, issueNumber: Int, expectedWorkflowVersion: Long,
             attemptId: String, workspace: Path, sourceTree: String, correction: Boolean): CodingIntent? {
        require(attemptId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        require(sourceTree.matches(Regex("[0-9a-f]{40}")))
        return db.write { conn ->
            val workflow = conn.rows("""
                SELECT workflow_id,version,corrections_used FROM workflows WHERE repository_id=? AND issue_number=?
                AND version=? AND phase='QUEUED' AND hold='NONE'
            """.trimIndent(), repositoryId, issueNumber, expectedWorkflowVersion) {
                Triple(it.getString(1),it.getLong(2),it.getInt(3))
            }.singleOrNull() ?: return@write null
            val policy = policy(conn, repositoryId) ?: return@write null
            if (!safeWorkspace(workspace, policy.first)) return@write null
            val assigned = catalog.findAssignedProfile(conn, repositoryId, AgentRole.CODING)
                ?: return@write null
            if (!nativeCoding(assigned)) return@write null
            val source = conn.rows("""
                SELECT issue_body_sha256,base_sha FROM github_issue_snapshots
                WHERE workflow_id=? AND repository_id=? AND issue_number=?
            """.trimIndent(), workflow.first, repositoryId, issueNumber) {
                it.getString(1) to it.getString(2)
            }.singleOrNull() ?: return@write null
            if (!source.first.matches(Regex("[0-9a-f]{64}")) ||
                !source.second.matches(Regex("[0-9a-f]{40}"))) return@write null
            val generation = if (correction) workflow.third+1 else 0
            if (correction) {
                val request = conn.rows("""SELECT 1 FROM correction_requests r JOIN workflow_verifications v
                    ON v.workflow_id=r.workflow_id AND v.generation=r.generation
                    WHERE r.workflow_id=? AND r.generation=? AND r.source_tree=?
                    AND v.correction_limit>? AND v.state IN ('SUCCEEDED','FAILED')""",
                    workflow.first,workflow.third,sourceTree,workflow.third) { 1 }.singleOrNull()
                if (generation !in 1..2 || request == null) return@write null
                if (conn.rows("""SELECT 1 FROM agent_attempts WHERE workflow_id=?
                    AND state NOT IN ('SUCCEEDED','FAILED')""",workflow.first) { 1 }.isNotEmpty()) return@write null
            } else if (workflow.third != 0 ||
                conn.rows("SELECT 1 FROM agent_attempts WHERE workflow_id=?",workflow.first) { 1 }.isNotEmpty())
                return@write null
            if (conflictingAttempt(conn,repositoryId,workspace,completedWorkflow=if (correction) workflow.first else null))
                return@write null
            val reservations = conn.rows("SELECT repository_id,workspace FROM runtime_reservations") {
                it.getLong(1) to Path.of(it.getString(2))
            }
            if (reservations.any { (repo, path) -> repo == repositoryId ||
                    workspace.startsWith(path) || path.startsWith(workspace) }) return@write null
            val now = Instant.now().toString()
            conn.mutate("""INSERT INTO agent_attempts(attempt_id,workflow_id,role,generation,state,source_tree,recorded_at)
                VALUES (?,?,'CODING',?,'PLANNED',?,?)""", attemptId, workflow.first, generation, sourceTree, now)
            conn.mutate("""INSERT INTO attempt_contexts(attempt_id,repository_id,issue_number,issue_body_sha256,
                base_sha,policy_version,assignment_version,profile_id,profile_version,adapter_id,manifest_version,
                workspace,planned_workflow_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                attemptId, repositoryId, issueNumber, source.first, source.second, policy.second,
                assigned.assignment.assignmentVersion, assigned.profile.profileId, assigned.profile.version,
                assigned.profile.adapterId, assigned.profile.manifestVersion, workspace.toString(), workflow.second + 1)
            conn.mutate("INSERT INTO runtime_reservations(attempt_id,repository_id,workspace) VALUES (?,?,?)",
                attemptId, repositoryId, workspace.toString())
            check(conn.mutate("""UPDATE workflows SET phase='CODING',corrections_used=?,version=version+1,updated_at=?
                WHERE workflow_id=? AND version=? AND hold='NONE' AND phase='QUEUED'""",
                generation, now, workflow.first, expectedWorkflowVersion) == 1)
            event(conn, workflow.first, "CODING_PLANNED", attemptId, now)
            read(conn, attemptId)
        }
    }

    fun find(attemptId: String): CodingIntent? = db.read { read(it, attemptId) }

    /** Trusted runner facts only; neither method is exposed to Issue/agent/HTTP input. */
    fun markRunning(attemptId: String, processReceipt: String): Boolean = db.write { c ->
        require(processReceipt.length in 1..16_384)
        val intent = read(c,attemptId) ?: return@write false
        val live = c.rows("SELECT 1 FROM workflows WHERE workflow_id=? AND phase='CODING' AND hold='NONE'",
            intent.workflowId) { 1 }.isNotEmpty()
        if (!live || c.mutate("UPDATE agent_attempts SET state='RUNNING' WHERE attempt_id=? AND state='STARTING'",
                attemptId) != 1) return@write false
        event(c,intent.workflowId,"CODING_PROCESS_STARTED",processReceipt,Instant.now().toString())
        true
    }

    /** Terminal facts + phase/hold + claim release are one transaction. UNKNOWN retains claims. */
    fun completeObserved(attemptId: String, state: AttemptState, receipt: String): Boolean = db.write { c ->
        require(state in setOf(AttemptState.SUCCEEDED,AttemptState.FAILED,AttemptState.UNKNOWN,AttemptState.CANCELLED))
        require(receipt.length in 1..16_384)
        val intent = read(c,attemptId) ?: return@write false
        val priorName = c.rows("SELECT state FROM agent_attempts WHERE attempt_id=?",attemptId) { it.getString(1) }.single()
        val prior = if (state==AttemptState.UNKNOWN || state==AttemptState.CANCELLED)
            "'PLANNED','STARTING','RUNNING'" else "'STARTING','RUNNING'"
        if (c.mutate("UPDATE agent_attempts SET state=? WHERE attempt_id=? AND state IN ($prior)",
                state.name,attemptId) != 1) return@write false
        val now = Instant.now().toString()
        if (state == AttemptState.SUCCEEDED) {
            c.mutate("""UPDATE workflows SET phase='VERIFYING',version=version+1,updated_at=?
                WHERE workflow_id=? AND phase='CODING' AND hold='NONE'""",now,intent.workflowId)
        } else if (state == AttemptState.CANCELLED) {
            if (c.mutate("""UPDATE workflows SET phase='CANCELLED',hold='NONE',hold_reason='OWNER_CANCELLED',version=version+1,updated_at=?
                WHERE workflow_id=? AND phase='CODING' AND hold='NONE'""",now,intent.workflowId) != 1) {
                c.mutate("UPDATE agent_attempts SET state=? WHERE attempt_id=? AND state='CANCELLED'",priorName,attemptId)
                return@write false
            }
        } else {
            val holdReason = when {
                state==AttemptState.FAILED && receipt.contains("\"reason\":\"timeout") -> "STAGE_TIMEOUT"
                state==AttemptState.FAILED && receipt.contains("\"reason\":\"CODEX_NOT_ATTESTED\"") -> "CODEX_NOT_ATTESTED"
                state==AttemptState.FAILED && receipt.contains("\"reason\":\"CODEX_CANARY_FAILED\"") -> "CODEX_CANARY_FAILED"
                else -> "CODING_RUNNER_${state.name}"
            }
            c.mutate("""UPDATE workflows SET hold='NEEDS_OWNER',hold_reason=?,version=version+1,updated_at=?
                WHERE workflow_id=? AND hold!='NEEDS_OWNER'""", holdReason,now,intent.workflowId)
        }
        if (state != AttemptState.UNKNOWN)
            c.mutate("DELETE FROM runtime_reservations WHERE attempt_id=?",attemptId)
        event(c,intent.workflowId,"CODING_PROCESS_${state.name}",receipt,now)
        true
    }

    /** Single successful CAS. Duplicate calls/restarts never produce a second STARTING. */
    fun markStarting(attemptId: String): Boolean = db.write { conn ->
        val intent = read(conn, attemptId) ?: return@write false
        if (intent.state != AttemptState.PLANNED) return@write false
        val current = policy(conn, intent.repositoryId) ?: return@write false
        if (current.second != intent.policyVersion || !safeWorkspace(intent.workspace, current.first))
            return@write false
        if (conflictingAttempt(conn,intent.repositoryId,intent.workspace,intent.attemptId,
                completedWorkflow=if (intent.generation>0) intent.workflowId else null)) return@write false
        val assigned = catalog.findAssignedProfile(conn, intent.repositoryId, AgentRole.CODING)
            ?: return@write false
        if (!nativeCoding(assigned) || assigned.assignment.assignmentVersion != intent.assignmentVersion ||
            assigned.profile.profileId != intent.profileId || assigned.profile.version != intent.profileVersion ||
            assigned.profile.adapterId != intent.adapterId || assigned.manifest.version != intent.manifestVersion)
            return@write false
        val reserved = conn.rows("""SELECT 1 FROM runtime_reservations
            WHERE attempt_id=? AND repository_id=? AND workspace=?""",
            attemptId, intent.repositoryId, intent.workspace.toString()) { 1 }.singleOrNull() != null
        if (!reserved) return@write false
        val now = Instant.now().toString()
        if (conn.mutate("""UPDATE workflows SET version=version+1,updated_at=? WHERE workflow_id=?
            AND phase='CODING' AND hold='NONE' AND version=?""", now, intent.workflowId,
                intent.plannedWorkflowVersion) != 1) return@write false
        check(conn.mutate("UPDATE agent_attempts SET state='STARTING' WHERE attempt_id=? AND state='PLANNED'",
            attemptId) == 1)
        event(conn, intent.workflowId, "CODING_STARTING_INTENT", attemptId, now)
        true
    }

    /** Conservative restart path: hold all unresolved starts, retain reservations; never retry. */
    fun holdUnreconciledStarts(workflowId: String? = null): Int = db.write { conn ->
        val unresolved = conn.rows("""SELECT a.attempt_id,a.workflow_id FROM agent_attempts a
            JOIN attempt_contexts c ON c.attempt_id=a.attempt_id
            WHERE a.role='CODING' AND a.state IN ('STARTING','RUNNING')
            AND (? IS NULL OR a.workflow_id=?)""",workflowId,workflowId) { it.getString(1) to it.getString(2) }
        val now = Instant.now().toString()
        for ((id, workflow) in unresolved) {
            conn.mutate("UPDATE agent_attempts SET state='UNKNOWN' WHERE attempt_id=?", id)
            conn.mutate("""UPDATE workflows SET hold='NEEDS_OWNER',hold_reason='UNRECONCILED_CODING_START',
                version=version+1,updated_at=? WHERE workflow_id=? AND hold!='NEEDS_OWNER'""", now, workflow)
            event(conn, workflow, "CODING_OUTCOME_UNKNOWN", id, now)
        }
        unresolved.size
    }

    private fun policy(conn: Connection, repo: Long): Pair<Path, Long>? = conn.rows("""
        SELECT p.workspace_root,p.policy_version FROM projects p JOIN project_policies pp
        ON pp.repository_id=p.repository_id WHERE p.repository_id=? AND p.kind='PERSONAL'
        AND pp.personal_auto_execution=1
    """.trimIndent(), repo) { Path.of(it.getString(1)) to it.getLong(2) }.singleOrNull()

    private fun nativeCoding(assigned: AssignedAgentProfile): Boolean =
        assigned.profile.adapterId == "codex-cli" && assigned.profile.provider == "openai"

    private fun conflictingAttempt(conn: Connection, repo: Long, workspace: Path, own: String? = null,
                                   completedWorkflow: String? = null): Boolean {
        // Legacy attempts have no precise workspace/cleanup receipt. Their registered
        // root is the minimum conservative affected scope, even across repository IDs.
        return conn.rows("""SELECT a.attempt_id,w.repository_id,p.workspace_root,c.workspace,a.workflow_id,a.state
            FROM agent_attempts a JOIN workflows w ON w.workflow_id=a.workflow_id
            JOIN projects p ON p.repository_id=w.repository_id
            LEFT JOIN attempt_contexts c ON c.attempt_id=a.attempt_id""") { r ->
            if (r.getString(1) == own || (r.getString(5)==completedWorkflow &&
                r.getString(6) in setOf("SUCCEEDED","FAILED"))) false
            else if (r.getLong(2) == repo) true
            else {
                val scope = Path.of(r.getString(4) ?: r.getString(3))
                try {
                    val real = scope.toRealPath()
                    workspace.startsWith(real) || real.startsWith(workspace)
                } catch (_: Exception) { true } // Unknown filesystem identity cannot release ownership.
            }
        }.any { it }
    }

    private fun safeWorkspace(workspace: Path, root: Path): Boolean = try {
        workspace.isAbsolute && workspace.normalize() == workspace && root.isAbsolute &&
            root.toRealPath() == root && workspace.toRealPath() == workspace && workspace != root &&
            workspace.startsWith(root) && Files.isDirectory(workspace) && TrustedGitCheckoutGuard.accepts(workspace)
    } catch (_: Exception) { false }

    private fun read(conn: Connection, id: String): CodingIntent? = conn.rows("""
        SELECT a.attempt_id,a.workflow_id,a.state,a.source_tree,a.generation,c.* FROM agent_attempts a
        JOIN attempt_contexts c ON c.attempt_id=a.attempt_id WHERE a.attempt_id=? AND a.role='CODING'
    """.trimIndent(), id) { r -> CodingIntent(r.getString("attempt_id"),r.getString("workflow_id"),
        r.getLong("repository_id"),r.getInt("issue_number"),AttemptState.valueOf(r.getString("state")),
        r.getString("source_tree"),r.getString("base_sha"),r.getString("issue_body_sha256"),
        r.getLong("policy_version"),r.getLong("assignment_version"),r.getString("profile_id"),
        r.getLong("profile_version"),r.getString("adapter_id"),r.getLong("manifest_version"),
        Path.of(r.getString("workspace")),r.getLong("planned_workflow_version"),r.getInt("generation")) }.singleOrNull()

    private fun event(conn: Connection, workflow: String, type: String, detail: String, now: String) {
        conn.mutate("INSERT INTO workflow_events(workflow_id,event_type,detail,recorded_at) VALUES (?,?,?,?)",
            workflow, type, detail, now)
    }
}
