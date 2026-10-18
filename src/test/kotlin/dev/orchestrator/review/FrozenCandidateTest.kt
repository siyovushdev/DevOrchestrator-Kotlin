package dev.orchestrator.review

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

/** Offline artifact integrity tests only. No agent/reviewer process or GitHub publication. */
class FrozenCandidateTest {
    @TempDir lateinit var temp: Path
    private val binding = CandidateBinding("workflow","coding-attempt","coding-session",0,
        "a".repeat(40),"b".repeat(40),VerificationStatus.PASS,"tests-receipt")

    private fun source(): Path {
        val source = Files.createDirectory(temp.resolve("source"))
        Files.createDirectory(source.resolve("src"))
        Files.writeString(source.resolve("src/main.kt"),"fun main() = println(1)\n")
        Files.writeString(source.resolve("run.sh"),"#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(source.resolve("run.sh"),PosixFilePermissions.fromString("rwxr-xr-x"))
        return source
    }

    private fun capture(source: Path = source(), bind: CandidateBinding = binding): FrozenCandidate =
        FrozenCandidate.capture(source,Files.createDirectories(temp.resolve("snapshots")),"candidate",bind)

    private fun receipt(candidate: FrozenCandidate, verdict: ReviewVerdict = ReviewVerdict.PASS) =
        ReviewReceipt(candidate.candidateId,"review-attempt","review-session",candidate.manifestSha256,
            candidate.binding.candidateTree,candidate.binding.generation,verdict)

    @Test fun captureReopenPreservesContentExecutableModeAndTrustedBinding() {
        val source = source()
        Files.createDirectory(source.resolve(".git"))
        Files.writeString(source.resolve(".git/config"),"never copied")
        val candidate = capture(source)
        assertTrue(candidate.isIntact())
        assertFalse(Files.exists(candidate.filesDirectory.resolve(".git")))
        assertTrue(Files.isExecutable(candidate.filesDirectory.resolve("run.sh")))
        Files.writeString(source.resolve("src/main.kt"),"Coding changes after freeze do not enter snapshot\n")
        val reopened = FrozenCandidate.reopen(candidate.directory,candidate.manifestSha256)
        assertEquals(binding,reopened.binding)
        assertTrue(reopened.isIntact())
        assertEquals("fun main() = println(1)\n",Files.readString(reopened.filesDirectory.resolve("src/main.kt")))
        assertEquals(ReviewDisposition.ELIGIBLE_FOR_CONTROLLER_CHECKS,ReviewEvidencePolicy.evaluate(
            reopened,receipt(reopened),reopened.manifestSha256,0,2))
    }

    @Test fun modifiedMissingExtraFilesAndModeChangesInvalidateReview() {
        val candidate = capture()
        val file = candidate.filesDirectory.resolve("src/main.kt")
        val original = Files.readString(file)
        Files.writeString(file,"tampered")
        assertFalse(candidate.isIntact())
        assertEquals(ReviewDisposition.NEEDS_OWNER,ReviewEvidencePolicy.evaluate(
            candidate,receipt(candidate),candidate.manifestSha256,0,2))
        Files.writeString(file,original)
        assertTrue(candidate.isIntact())
        Files.delete(file)
        assertFalse(candidate.isIntact())
        Files.writeString(file,original)
        Files.writeString(candidate.filesDirectory.resolve("extra"),"unexpected")
        assertFalse(candidate.isIntact())
        Files.delete(candidate.filesDirectory.resolve("extra"))
        Files.setPosixFilePermissions(candidate.filesDirectory.resolve("run.sh"),PosixFilePermissions.fromString("rw-r--r--"))
        assertFalse(candidate.isIntact())
    }

    @Test fun symlinkHardlinkAndNestedMetadataDeniedWithoutReadingOutsideFixture() {
        val source = source()
        val elsewhere = temp.resolve("test-owned-file")
        Files.writeString(elsewhere,"test-owned content")
        val entry = source.resolve("linked")
        Files.createSymbolicLink(entry,elsewhere)
        assertFails { capture(source) }
        Files.delete(entry)
        Files.createLink(entry,elsewhere)
        assertFails { capture(source) }
        Files.delete(entry)
        Files.createDirectory(source.resolve("src/.git"))
        assertFails { capture(source) }
        assertEquals("test-owned content",Files.readString(elsewhere))
    }

    @Test fun traversalOverlappingStorageAndExistingDestinationCannotBeCaptured() {
        val source = source()
        val snapshots = Files.createDirectory(temp.resolve("snapshots"))
        assertFails { FrozenCandidate.capture(source,snapshots,"../escape",binding) }
        val nested = Files.createDirectory(source.resolve("snapshots"))
        assertFails { FrozenCandidate.capture(source,nested,"candidate",binding) }
        assertFails { FrozenCandidate.capture(source,temp,"candidate",binding) }
        val first = FrozenCandidate.capture(source,snapshots,"candidate",binding)
        assertFails { FrozenCandidate.capture(source,snapshots,"candidate",binding) }
        assertTrue(first.isIntact())
    }

    @Test fun oversizedFileAndTamperedManifestFailClosed() {
        val source = source()
        Files.write(source.resolve("large"),ByteArray(8*1024*1024+1))
        assertFails { capture(source) }
        Files.delete(source.resolve("large"))
        val candidate = capture(source)
        val manifest = candidate.directory.resolve("manifest")
        Files.writeString(manifest,Files.readString(manifest).replace("coding-session","forged-session"))
        assertFalse(candidate.isIntact())
        assertFails { FrozenCandidate.reopen(candidate.directory,candidate.manifestSha256) }
    }

    @Test fun staleSelfAndNotRunReceiptsCannotQualify() {
        val candidate = capture()
        val receipt = receipt(candidate)
        for (bad in listOf(receipt.copy(reviewerAttemptId=binding.codingAttemptId),
            receipt.copy(reviewerSessionId=binding.codingSessionId),receipt.copy(codingGeneration=1),
            receipt.copy(candidateTree="c".repeat(40)),receipt.copy(candidateManifestSha256="0".repeat(64)),
            receipt.copy(verdict=ReviewVerdict.NOT_RUN))) {
            assertEquals(ReviewDisposition.NEEDS_OWNER,
                ReviewEvidencePolicy.evaluate(candidate,bad,candidate.manifestSha256,0,2))
        }
        assertEquals(ReviewDisposition.NEEDS_OWNER,
            ReviewEvidencePolicy.evaluate(candidate,receipt,"d".repeat(64),0,2))
    }

    @Test fun failedOrMissingRequiredTestsCannotQualifyEvenWithReviewPass() {
        for ((n,status) in listOf(VerificationStatus.FAIL,VerificationStatus.NOT_RUN).withIndex()) {
            val source = Files.createDirectory(temp.resolve("source-$n"))
            Files.writeString(source.resolve("file"),"code")
            val candidate = FrozenCandidate.capture(source,Files.createDirectories(temp.resolve("snapshots")),
                "candidate-$n",binding.copy(verification=status))
            assertEquals(ReviewDisposition.NEEDS_OWNER,ReviewEvidencePolicy.evaluate(
                candidate,receipt(candidate),candidate.manifestSha256,0,2))
        }
    }

    @Test fun policyNeverAllowsThirdCorrectionAndRequiresMatchingGeneration() {
        for (generation in 0..2) {
            val source = Files.createDirectory(temp.resolve("source-$generation"))
            Files.writeString(source.resolve("file"),"generation $generation")
            val candidate = FrozenCandidate.capture(source,Files.createDirectories(temp.resolve("snapshots")),
                "candidate-$generation",binding.copy(generation=generation))
            val receipt = receipt(candidate,ReviewVerdict.CHANGES_REQUIRED)
            assertEquals(if (generation<2) ReviewDisposition.CORRECTION_REQUIRED else ReviewDisposition.NEEDS_OWNER,
                ReviewEvidencePolicy.evaluate(candidate,receipt,candidate.manifestSha256,generation,2))
            assertEquals(ReviewDisposition.NEEDS_OWNER,
                ReviewEvidencePolicy.evaluate(candidate,receipt,candidate.manifestSha256,generation,0))
        }
    }

    @Test fun distinctCandidateIdsCannotShareReceiptsOrReopenAfterRename() {
        val source = source()
        val root = Files.createDirectory(temp.resolve("snapshots"))
        val a = FrozenCandidate.capture(source,root,"candidate-A",binding)
        val b = FrozenCandidate.capture(source,root,"candidate-B",binding)
        assertNotEquals(a.manifestSha256,b.manifestSha256)
        assertEquals(ReviewDisposition.NEEDS_OWNER,
            ReviewEvidencePolicy.evaluate(b,receipt(a),b.manifestSha256,0,2))
        assertEquals(ReviewDisposition.NEEDS_OWNER,ReviewEvidencePolicy.evaluate(
            b,receipt(b).copy(candidateId=a.candidateId),b.manifestSha256,0,2))
        val renamed = root.resolve("candidate-C")
        Files.move(a.directory,renamed)
        assertFails { FrozenCandidate.reopen(renamed,a.manifestSha256) }
        assertFalse(a.isIntact())
    }
}
