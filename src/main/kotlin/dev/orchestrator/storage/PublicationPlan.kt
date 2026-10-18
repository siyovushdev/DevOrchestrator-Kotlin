package dev.orchestrator.storage

import java.util.Base64

/** Trusted controller input, never agent prose; not a security/Owner authorization. */
internal data class PublicationPlan(
    val workflowId: String,
    val repositoryId: Long,
    val issueNumber: Int,
    val owner: String,
    val repository: String,
    val baseBranch: String,
    val baseSha: String,
    val commitSha: String,
    val candidateTree: String,
    val candidateManifestSha256: String,
    val candidateId: String,
    val codingAttemptId: String,
    val reviewerAttemptId: String,
    val generation: Int,
    val policyVersion: Long,
    val workflowVersion: Long,
) {
    // URL-safe encoding is injective and contains no '.' or '/' ref hazards, even
    // for valid workflow IDs ending in '.'/'.lock' or containing '..'. No truncation.
    val headBranch: String get() = "devorch/$repositoryId/$issueNumber/w-" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(workflowId.toByteArray(Charsets.UTF_8))

    init {
        listOf(workflowId,codingAttemptId,reviewerAttemptId,candidateId).forEach {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        }
        require(codingAttemptId != reviewerAttemptId)
        require(repositoryId>0 && issueNumber>0 && generation in 0..2 && policyVersion>0 && workflowVersion>=0)
        require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")))
        require(repository.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")))
        require(baseBranch.length in 1..200 && baseBranch.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")))
        require(baseBranch.split('/').none { it.isEmpty() || it.startsWith('.') || it.endsWith('.') || it.endsWith(".lock") })
        require(!baseBranch.contains(".."))
        listOf(baseSha,commitSha,candidateTree).forEach { require(it.matches(Regex("[0-9a-f]{40}"))) }
        require(candidateManifestSha256.matches(Regex("[0-9a-f]{64}")))
        require(headBranch != baseBranch)
    }
}

internal sealed interface PublicationReadBack {
    data class Commit(val commitSha: String, val tree: String, val parents: List<String>) : PublicationReadBack
    data class Push(val repositoryId: Long, val fullName: String, val branch: String,
                    val commitSha: String) : PublicationReadBack
    data class Drafts(val matches: List<DraftPullRequest>) : PublicationReadBack
}

internal data class DraftPullRequest(
    val number: Int,
    val url: String,
    val repositoryId: Long,
    val fullName: String,
    val headRepositoryId: Long,
    val headBranch: String,
    val headSha: String,
    val baseBranch: String,
    val baseSha: String,
    val draft: Boolean,
)

internal object PublicationReceiptMatcher {
    fun matches(plan: PublicationPlan, kind: EffectKind, receipt: PublicationReadBack): Boolean = when (kind) {
        EffectKind.COMMIT -> receipt is PublicationReadBack.Commit && receipt.commitSha == plan.commitSha &&
            receipt.tree == plan.candidateTree && receipt.parents == listOf(plan.baseSha)
        EffectKind.PUSH -> receipt is PublicationReadBack.Push && receipt.repositoryId == plan.repositoryId &&
            receipt.fullName == "${plan.owner}/${plan.repository}" && receipt.branch == plan.headBranch &&
            receipt.commitSha == plan.commitSha
        EffectKind.DRAFT_PR -> receipt is PublicationReadBack.Drafts && receipt.matches.size == 1 &&
            receipt.matches.single().let { pr ->
                pr.number>0 && pr.url == "https://github.com/${plan.owner}/${plan.repository}/pull/${pr.number}" &&
                    pr.repositoryId == plan.repositoryId && pr.fullName == "${plan.owner}/${plan.repository}" &&
                    pr.headRepositoryId == plan.repositoryId && pr.headBranch == plan.headBranch &&
                    pr.headSha == plan.commitSha && pr.baseBranch == plan.baseBranch &&
                    pr.baseSha == plan.baseSha && pr.draft
            }
    }
}
