package dev.orchestrator.storage

/** Storage vocabulary fixed by ADR-002/R-06. The workflow engine will own transitions. */
enum class ProjectKind { PERSONAL, WORK }
enum class WorkflowPhase {
    QUEUED, CODING, VERIFYING, REVIEWING, READY_FOR_PR,
    PUBLISHING, DRAFT_PR_CREATED, FAILED, CANCELLED
}
enum class WorkflowHold { NONE, PAUSED, NEEDS_OWNER }
enum class AttemptRole { CODING, REVIEW }
enum class AttemptState { PLANNED, STARTING, RUNNING, SUCCEEDED, FAILED, UNKNOWN, CANCELLED }
enum class EffectKind { COMMIT, PUSH, DRAFT_PR }
enum class EffectState { PREPARED, SENDING, CONFIRMED, REJECTED, UNCERTAIN }

data class RegisteredProject(
    val id: String,
    val repositoryId: Long,
    val kind: ProjectKind,
    val workspaceRoot: String,
)

data class WorkflowSnapshot(
    val id: String,
    val repositoryId: Long,
    val issueNumber: Int,
    val phase: WorkflowPhase,
    val hold: WorkflowHold,
    val holdReason: String?,
    val version: Long,
    val correctionsUsed: Int,
)

data class WorkflowEvent(
    val sequence: Long,
    val workflowId: String,
    val type: String,
    val detail: String?,
    val recordedAt: String,
)
