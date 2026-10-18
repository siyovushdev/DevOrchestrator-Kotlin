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
)

/**
 * Initial Coding intent ONLY. No spawn, inference, scheduler or security/Owner GO provider.
 * STARTING records a consumed intent, never a launch permit. A future runner must ALSO
 * validate protected source identity, runtime/credential evidence and specific Owner GO.
 */
internal class CodingAttemptJournal(database: Path) {
    private val db = RuntimeDatabase(database)
    private val catalog = SqliteAgentCatalog(database)

    fun plan(repositoryId: Long, issueNumber: Int, expectedWorkflowVersion: Long,
             attemptId: String, workspace: Path, sourceTree: String): CodingIntent? {
        require(attemptId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        require(sourceTree.matches(Regex("[0-9a-f]{40}")))
        return db.write { conn ->
            val workflow = conn.rows("""
                SELECT workflow_id,version FROM workflows WHERE repository_id=? AND issue_number=?
                AND version=? AND phase='QUEUED' AND hold='NONE'
            """.trimIndent(), repositoryId, issueNumber, expectedWorkflowVersion) {
                it.getString(1) to it.getLong(2)
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
            // Initial generation cannot replace any previous attempt, including UNKNOWN.
            if (conn.rows("SELECT 1 FROM agent_attempts WHERE workflow_id=?", workflow.first) { 1 }.isNotEmpty())
                return@write null
            // Legacy rows have no context/cleanup receipt. Absence of a reservation is NOT
            // evidence that their process released this repository.
            if (conn.rows("""SELECT 1 FROM agent_attempts a JOIN workflows w ON w.workflow_id=a.workflow_id
                WHERE w.repository_id=?""", repositoryId) { 1 }.isNotEmpty()) return@write null
            val reservations = conn.rows("SELECT repository_id,workspace FROM runtime_reservations") {
                it.getLong(1) to Path.of(it.getString(2))
            }
            if (reservations.any { (repo, path) -> repo == repositoryId ||
                    workspace.startsWith(path) || path.startsWith(workspace) }) return@write null
            val now = Instant.now().toString()
            conn.mutate("""INSERT INTO agent_attempts(attempt_id,workflow_id,role,generation,state,source_tree,recorded_at)
                VALUES (?,?,'CODING',0,'PLANNED',?,?)""", attemptId, workflow.first, sourceTree, now)
            conn.mutate("""INSERT INTO attempt_contexts(attempt_id,repository_id,issue_number,issue_body_sha256,
                base_sha,policy_version,assignment_version,profile_id,profile_version,adapter_id,manifest_version,
                workspace,planned_workflow_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                attemptId, repositoryId, issueNumber, source.first, source.second, policy.second,
                assigned.assignment.assignmentVersion, assigned.profile.profileId, assigned.profile.version,
                assigned.profile.adapterId, assigned.profile.manifestVersion, workspace.toString(), workflow.second + 1)
            conn.mutate("INSERT INTO runtime_reservations(attempt_id,repository_id,workspace) VALUES (?,?,?)",
                attemptId, repositoryId, workspace.toString())
            check(conn.mutate("""UPDATE workflows SET phase='CODING',version=version+1,updated_at=?
                WHERE workflow_id=? AND version=? AND hold='NONE' AND phase='QUEUED'""",
                now, workflow.first, expectedWorkflowVersion) == 1)
            event(conn, workflow.first, "CODING_PLANNED", attemptId, now)
            read(conn, attemptId)
        }
    }

    fun find(attemptId: String): CodingIntent? = db.read { read(it, attemptId) }

    /** Single successful CAS. Duplicate calls/restarts never produce a second STARTING. */
    fun markStarting(attemptId: String): Boolean = db.write { conn ->
        val intent = read(conn, attemptId) ?: return@write false
        if (intent.state != AttemptState.PLANNED) return@write false
        val current = policy(conn, intent.repositoryId) ?: return@write false
        if (current.second != intent.policyVersion || !safeWorkspace(intent.workspace, current.first))
            return@write false
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
    fun holdUnreconciledStarts(): Int = db.write { conn ->
        val unresolved = conn.rows("""SELECT a.attempt_id,a.workflow_id FROM agent_attempts a
            JOIN attempt_contexts c ON c.attempt_id=a.attempt_id
            WHERE a.state IN ('STARTING','RUNNING')""") { it.getString(1) to it.getString(2) }
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

    private fun safeWorkspace(workspace: Path, root: Path): Boolean = try {
        workspace.isAbsolute && workspace.normalize() == workspace && root.isAbsolute &&
            root.toRealPath() == root && workspace.toRealPath() == workspace && workspace != root &&
            workspace.startsWith(root) && Files.isDirectory(workspace) && TrustedGitCheckoutGuard.accepts(workspace)
    } catch (_: Exception) { false }

    private fun read(conn: Connection, id: String): CodingIntent? = conn.rows("""
        SELECT a.attempt_id,a.workflow_id,a.state,a.source_tree,c.* FROM agent_attempts a
        JOIN attempt_contexts c ON c.attempt_id=a.attempt_id WHERE a.attempt_id=? AND a.role='CODING'
    """.trimIndent(), id) { r -> CodingIntent(r.getString("attempt_id"),r.getString("workflow_id"),
        r.getLong("repository_id"),r.getInt("issue_number"),AttemptState.valueOf(r.getString("state")),
        r.getString("source_tree"),r.getString("base_sha"),r.getString("issue_body_sha256"),
        r.getLong("policy_version"),r.getLong("assignment_version"),r.getString("profile_id"),
        r.getLong("profile_version"),r.getString("adapter_id"),r.getLong("manifest_version"),
        Path.of(r.getString("workspace")),r.getLong("planned_workflow_version")) }.singleOrNull()

    private fun event(conn: Connection, workflow: String, type: String, detail: String, now: String) {
        conn.mutate("INSERT INTO workflow_events(workflow_id,event_type,detail,recorded_at) VALUES (?,?,?,?)",
            workflow, type, detail, now)
    }
}
