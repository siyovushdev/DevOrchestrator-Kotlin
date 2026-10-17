package dev.orchestrator.storage

import java.time.Instant

/** Frozen untrusted GitHub Issue body. Never treat text/labels as authorization. */
data class GitHubIssueRecord(
    val repositoryId: Long,
    val issueNumber: Int,
    val githubIssueId: Long,
    val sourceUpdatedAt: Instant,
    val title: String,
    val body: String,
    val baseSha: String,
    val open: Boolean,
) {
    init {
        require(repositoryId > 0 && githubIssueId > 0 && issueNumber > 0)
        require(title.isNotBlank() && title.length <= 512)
        require(body.length <= 65_536)
        require(baseSha.matches(Regex("[0-9a-fA-F]{40}"))) { "Invalid pinned base SHA" }
    }
}

enum class IssueIntakeOutcome {
    CREATED, DUPLICATE, NEEDS_OWNER, IGNORED_CLOSED, TERMINAL_UNCHANGED,
}

data class IssueIntakeReceipt(
    val outcome: IssueIntakeOutcome,
    val workflowId: String?,
    val reason: String? = null,
)

data class PinnedGitHubIssue(
    val workflowId: String,
    val repositoryId: Long,
    val issueNumber: Int,
    val githubIssueId: Long,
    val sourceUpdatedAt: String,
    val title: String,
    val body: String,
    val bodySha256: String,
    val baseSha: String,
)
