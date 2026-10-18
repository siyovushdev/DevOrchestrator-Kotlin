package dev.orchestrator.github

import dev.orchestrator.policy.PolicyDecision
import dev.orchestrator.policy.ProjectAction
import dev.orchestrator.policy.ProjectRegistry
import dev.orchestrator.storage.IssueIntakeOutcome
import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.SqliteTaskStore

data class GitHubPollReceipt(
    val repositoryId: Long,
    val scanned: Int,
    val created: Int,
    val duplicates: Int,
    val needsOwner: Int,
    val ignoredClosed: Int,
    val terminalUnchanged: Int,
)

/**
 * Explicit one-shot poll (NO scheduler/startup hook at M1-04).
 * Repository owner/name and stable ID come from trusted configuration.
 * The DB re-checks PERSONAL opt-in inside the same transaction as each insert.
 */
class GitHubIssueIntake(
    private val store: SqliteTaskStore,
    private val reader: GitHubIssueReader,
) {
    fun pollOnce(target: GitHubRepositoryTarget): GitHubPollReceipt {
        val policy = requireNotNull(store.findPolicy(target.repositoryId)) {
            "GitHub intake denied: unknown registered repository ID"
        }
        check(policy.kind == ProjectKind.PERSONAL && policy.personalAutoExecution) {
            "GitHub intake denied: only opted-in PERSONAL test repositories"
        }
        val preflight = ProjectRegistry(store).evaluate(
            target.repositoryId,
            policy.workspaceRoot.resolve("issue-intake-preflight"),
            ProjectAction.INTAKE,
        )
        check(preflight == PolicyDecision.Allowed) {
            "GitHub intake project workspace denied: $preflight"
        }

        // Resolve ALL GitHub pages before writing anything to SQLite.
        val issues = reader.read(target)
        check(issues.all { it.repositoryId == target.repositoryId })
        store.ensureNoStoredGitHubIdentityCollision(issues) // fail before first insert
        var created = 0
        var duplicates = 0
        var needsOwner = 0
        var ignoredClosed = 0
        var terminalUnchanged = 0
        for (issue in issues) {
            // Deterministic proposal only; durable DB unique(repo_id, issue_number) is authority.
            val workflowId = "gh-${issue.repositoryId}-${issue.issueNumber}"
            val receipt = store.acceptGitHubIssue(issue, workflowId)
            when (receipt.outcome) {
                IssueIntakeOutcome.CREATED -> created++
                IssueIntakeOutcome.DUPLICATE -> duplicates++
                IssueIntakeOutcome.NEEDS_OWNER -> needsOwner++
                IssueIntakeOutcome.IGNORED_CLOSED -> ignoredClosed++
                IssueIntakeOutcome.TERMINAL_UNCHANGED -> terminalUnchanged++
            }
        }
        return GitHubPollReceipt(
            repositoryId = target.repositoryId,
            scanned = issues.size,
            created = created,
            duplicates = duplicates,
            needsOwner = needsOwner,
            ignoredClosed = ignoredClosed,
            terminalUnchanged = terminalUnchanged,
        )
    }
}
