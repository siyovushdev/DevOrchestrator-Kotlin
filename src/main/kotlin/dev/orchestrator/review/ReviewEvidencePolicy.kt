package dev.orchestrator.review

internal enum class ReviewVerdict { PASS, CHANGES_REQUIRED, NOT_RUN }

/** Receipt supplied by a trusted independent runner; this class cannot authenticate sessions. */
internal data class ReviewReceipt(
    val reviewerAttemptId: String,
    val reviewerSessionId: String,
    val candidateManifestSha256: String,
    val candidateTree: String,
    val codingGeneration: Int,
    val verdict: ReviewVerdict,
)

internal enum class ReviewDisposition {
    ELIGIBLE_FOR_CONTROLLER_CHECKS, CORRECTION_REQUIRED, NEEDS_OWNER,
}

/**
 * Pure artifact/receipt consistency check. NEVER a publish/launch grant. The controller
 * must separately establish fresh process/session provenance and terminal/cleanup proof.
 */
internal object ReviewEvidencePolicy {
    fun evaluate(candidate: FrozenCandidate, receipt: ReviewReceipt,
                 currentManifestSha256: String, correctionsUsed: Int, projectLimit: Int): ReviewDisposition {
        require(correctionsUsed in 0..2 && projectLimit in 0..2)
        val binding = candidate.binding
        if (!candidate.isIntact() || binding.verification != VerificationStatus.PASS ||
            candidate.manifestSha256 != currentManifestSha256 ||
            receipt.candidateManifestSha256 != candidate.manifestSha256 ||
            receipt.candidateTree != binding.candidateTree || receipt.codingGeneration != binding.generation ||
            binding.generation != correctionsUsed || receipt.reviewerAttemptId == binding.codingAttemptId ||
            receipt.reviewerSessionId == binding.codingSessionId ||
            !receipt.reviewerAttemptId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) ||
            !receipt.reviewerSessionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            return ReviewDisposition.NEEDS_OWNER
        return when (receipt.verdict) {
            ReviewVerdict.PASS -> ReviewDisposition.ELIGIBLE_FOR_CONTROLLER_CHECKS
            ReviewVerdict.CHANGES_REQUIRED -> if (correctionsUsed < projectLimit)
                ReviewDisposition.CORRECTION_REQUIRED else ReviewDisposition.NEEDS_OWNER
            ReviewVerdict.NOT_RUN -> ReviewDisposition.NEEDS_OWNER
        }
    }
}
