package dev.orchestrator.policy

import dev.orchestrator.storage.ProjectKind
import java.nio.file.Path

/** Owner-controlled snapshot. Issue contents and agent outputs must never populate this model. */
data class ProjectPolicy(
    val projectId: String,
    val repositoryId: Long,
    val kind: ProjectKind,
    val workspaceRoot: Path,
    val personalAutoExecution: Boolean = false,
    val workOwnerExecutionApproved: Boolean = false,
    val workOwnerPublicationApproved: Boolean = false,
    val version: Long = 1,
) {
    init {
        require(projectId.isNotBlank())
        require(repositoryId > 0)
        require(version > 0)
        require(workspaceRoot.isAbsolute) { "Workspace root must be absolute" }
        require(workspaceRoot.normalize() == workspaceRoot) { "Workspace root must be normalized" }
        require(!workOwnerExecutionApproved || kind == ProjectKind.WORK)
        require(!workOwnerPublicationApproved || kind == ProjectKind.WORK)
        require(!personalAutoExecution || kind == ProjectKind.PERSONAL)
    }
}

enum class ProjectAction { INTAKE, EXECUTE, PUBLISH_DRAFT }
enum class PolicyDenial {
    UNKNOWN_REPOSITORY, INVALID_WORKSPACE, PERSONAL_AUTO_DISABLED,
    WORK_EXECUTION_NEEDS_OWNER, WORK_PUBLICATION_NEEDS_OWNER,
}
sealed interface PolicyDecision {
    data object Allowed : PolicyDecision
    data class Denied(val reason: PolicyDenial) : PolicyDecision
}

/**
 * Pure fail-closed policy evaluator. Approval fields are already-authorized durable owner state,
 * NOT an instruction to mint approvals from requests, issues or agent output.
 *
 * Lexical containment is only one precondition: the future native runner must also enforce
 * symlink/realpath, filesystem race and process isolation boundaries immediately before effects.
 */
class ProjectPolicyEvaluator {
    fun evaluate(
        policies: Collection<ProjectPolicy>,
        repositoryId: Long,
        workspace: Path,
        action: ProjectAction,
    ): PolicyDecision {
        val matches = policies.filter { it.repositoryId == repositoryId }
        if (repositoryId <= 0 || matches.size != 1) {
            return PolicyDecision.Denied(PolicyDenial.UNKNOWN_REPOSITORY)
        }
        val policy = matches.single()
        if (!workspace.isAbsolute || !workspace.normalize().startsWith(policy.workspaceRoot) ||
            workspace.normalize() == policy.workspaceRoot
        ) {
            return PolicyDecision.Denied(PolicyDenial.INVALID_WORKSPACE)
        }
        return when (policy.kind) {
            ProjectKind.PERSONAL ->
                if (action == ProjectAction.INTAKE || policy.personalAutoExecution)
                    PolicyDecision.Allowed
                else PolicyDecision.Denied(PolicyDenial.PERSONAL_AUTO_DISABLED)
            ProjectKind.WORK -> when (action) {
                ProjectAction.INTAKE -> PolicyDecision.Allowed
                ProjectAction.EXECUTE ->
                    if (policy.workOwnerExecutionApproved) PolicyDecision.Allowed
                    else PolicyDecision.Denied(PolicyDenial.WORK_EXECUTION_NEEDS_OWNER)
                ProjectAction.PUBLISH_DRAFT ->
                    if (policy.workOwnerExecutionApproved && policy.workOwnerPublicationApproved)
                        PolicyDecision.Allowed
                    else PolicyDecision.Denied(PolicyDenial.WORK_PUBLICATION_NEEDS_OWNER)
            }
        }
    }
}
