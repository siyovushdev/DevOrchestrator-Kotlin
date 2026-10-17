package dev.orchestrator.policy

import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.SqliteTaskStore
import java.nio.file.Path

/**
 * Trusted project configuration. WORK authorizations are deliberately NOT project-wide flags:
 * Owner GO and publication approval must be scoped to a specific workflow/effect (future gate).
 */
data class ProjectPolicy(
    val projectId: String,
    val repositoryId: Long,
    val kind: ProjectKind,
    val workspaceRoot: Path,
    val personalAutoExecution: Boolean = false,
    val version: Long = 1,
) {
    init {
        require(projectId.isNotBlank())
        require(repositoryId > 0)
        require(version > 0)
        require(workspaceRoot.isAbsolute) { "Workspace root must be absolute" }
        require(workspaceRoot.normalize() == workspaceRoot) { "Workspace root must be normalized" }
        require(workspaceRoot.parent != null) { "Filesystem root is not a project workspace" }
        require(!personalAutoExecution || kind == ProjectKind.PERSONAL)
    }
}

enum class ProjectAction { INTAKE, EXECUTE, PUBLISH_DRAFT }
enum class PolicyDenial {
    UNKNOWN_REPOSITORY, INVALID_PROJECT_POLICY, INVALID_WORKSPACE,
    PERSONAL_AUTO_DISABLED, WORK_EXECUTION_NEEDS_OWNER, WORK_PUBLICATION_NEEDS_OWNER,
}
sealed interface PolicyDecision {
    data object Allowed : PolicyDecision
    data class Denied(val reason: PolicyDenial) : PolicyDecision
}

/**
 * Pure snapshot evaluator; never accept a snapshot created from Issue/agent/API request data.
 * The authoritative entry point for clients is ProjectRegistry, which reads SQLite.
 *
 * Lexical containment is not an OS security boundary. Future native runner must revalidate
 * resolved paths, symlinks and workspace identity immediately at process launch.
 */
internal class ProjectPolicyEvaluator {
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
        if (!workspace.isAbsolute || workspace.normalize() != workspace ||
            !workspace.startsWith(policy.workspaceRoot) || workspace == policy.workspaceRoot
        ) {
            return PolicyDecision.Denied(PolicyDenial.INVALID_WORKSPACE)
        }
        return when (policy.kind) {
            ProjectKind.PERSONAL -> {
                if (action == ProjectAction.INTAKE || policy.personalAutoExecution) {
                    PolicyDecision.Allowed
                } else {
                    PolicyDecision.Denied(PolicyDenial.PERSONAL_AUTO_DISABLED)
                }
            }
            // Owner GO must be for this exact task/attempt, not a permanent project toggle.
            // Until workflow-scoped approval records exist, WORK execution/publishing is DENIED.
            ProjectKind.WORK -> when (action) {
                ProjectAction.INTAKE -> PolicyDecision.Allowed
                ProjectAction.EXECUTE ->
                    PolicyDecision.Denied(PolicyDenial.WORK_EXECUTION_NEEDS_OWNER)
                ProjectAction.PUBLISH_DRAFT ->
                    PolicyDecision.Denied(PolicyDenial.WORK_PUBLICATION_NEEDS_OWNER)
            }
        }
    }
}

/**
 * Authoritative policy lookup: caller cannot pass fabricated permissions.
 * This is a preflight only; the future effect boundary MUST check policy/version again.
 */
class ProjectRegistry(
    private val store: SqliteTaskStore,
) {
    private val evaluator = ProjectPolicyEvaluator()
    fun evaluate(repositoryId: Long, workspace: Path, action: ProjectAction): PolicyDecision {
        val snapshot = try {
            store.findPolicy(repositoryId)
        } catch (_: IllegalArgumentException) {
            return PolicyDecision.Denied(PolicyDenial.INVALID_PROJECT_POLICY)
        }
        if (snapshot == null) return PolicyDecision.Denied(PolicyDenial.UNKNOWN_REPOSITORY)
        return evaluator.evaluate(listOf(snapshot), repositoryId, workspace, action)
    }
}
