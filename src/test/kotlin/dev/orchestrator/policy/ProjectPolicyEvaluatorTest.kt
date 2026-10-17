package dev.orchestrator.policy

import dev.orchestrator.storage.ProjectKind
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectPolicyEvaluatorTest {
    private val evaluator = ProjectPolicyEvaluator()
    private val personal = ProjectPolicy("demo", 123L, ProjectKind.PERSONAL, Path.of("/tmp/personal"))
    private val work = ProjectPolicy("work", 456L, ProjectKind.WORK, Path.of("/tmp/work"))
    private fun check(
        policies: Collection<ProjectPolicy>, id: Long, path: String, action: ProjectAction
    ) = evaluator.evaluate(policies, id, Path.of(path), action)

    @Test fun unknownAndDuplicateIdentityDenied() {
        assertEquals(PolicyDecision.Denied(PolicyDenial.UNKNOWN_REPOSITORY),
            check(listOf(personal), 999, "/tmp/personal/repo", ProjectAction.INTAKE))
        assertEquals(PolicyDecision.Denied(PolicyDenial.UNKNOWN_REPOSITORY),
            check(listOf(personal, personal), 123, "/tmp/personal/repo", ProjectAction.EXECUTE))
    }

    @Test fun personalRequiresExplicitOptIn() {
        assertEquals(PolicyDecision.Allowed, check(listOf(personal), 123, "/tmp/personal/repo", ProjectAction.INTAKE))
        assertEquals(PolicyDecision.Denied(PolicyDenial.PERSONAL_AUTO_DISABLED),
            check(listOf(personal), 123, "/tmp/personal/repo", ProjectAction.EXECUTE))
        assertEquals(PolicyDecision.Allowed,
            check(listOf(personal.copy(personalAutoExecution = true)), 123,
                "/tmp/personal/repo", ProjectAction.PUBLISH_DRAFT))
    }

    @Test fun workNeverReceivesPermanentProjectWideApproval() {
        assertEquals(PolicyDecision.Allowed,
            check(listOf(work), 456, "/tmp/work/repo", ProjectAction.INTAKE))
        assertEquals(PolicyDecision.Denied(PolicyDenial.WORK_EXECUTION_NEEDS_OWNER),
            check(listOf(work), 456, "/tmp/work/repo", ProjectAction.EXECUTE))
        assertEquals(PolicyDecision.Denied(PolicyDenial.WORK_PUBLICATION_NEEDS_OWNER),
            check(listOf(work), 456, "/tmp/work/repo", ProjectAction.PUBLISH_DRAFT))
        // No policy fields can grant WORK execution. Future Owner GO is task-specific.
        assertFailsWith<IllegalArgumentException> {
            work.copy(personalAutoExecution = true)
        }
    }

    @Test fun pathTraversalAndBoundaryDenied() {
        val policies = listOf(personal.copy(personalAutoExecution = true))
        for (path in listOf("/tmp/personal", "/tmp/personal-evil/repo",
                "/tmp/personal/../work/repo", "/tmp/personal/../personal/repo", "relative/path")) {
            assertEquals(PolicyDecision.Denied(PolicyDenial.INVALID_WORKSPACE),
                check(policies, 123, path, ProjectAction.EXECUTE))
        }
    }

    @Test fun invalidOwnerSnapshotsRejected() {
        assertFailsWith<IllegalArgumentException> { personal.copy(workspaceRoot = Path.of("/")) }
        assertFailsWith<IllegalArgumentException> {
            work.copy(personalAutoExecution = true)
        }
        assertFailsWith<IllegalArgumentException> {
            personal.copy(workspaceRoot = Path.of("relative"))
        }
    }
}
