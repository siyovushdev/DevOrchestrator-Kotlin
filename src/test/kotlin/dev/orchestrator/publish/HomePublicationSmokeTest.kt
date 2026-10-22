package dev.orchestrator.publish

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.storage.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import kotlin.test.*

/** FUTURE ONLY after separate exact-head Owner publication GO. Never launches any model. */
@EnabledIfEnvironmentVariable(named="DEVORCH_HOME_PUBLISH_GO",matches="issue37-owner-approved-once")
class HomePublicationSmokeTest {
    @Test fun publishPreservedReviewedCandidateOnce() {
        check(System.getProperty("user.name")=="devorch-e2e")
        val home=Path.of(requireNotNull(System.getenv("HOME"))).toRealPath()
        val database=Path.of(requireNotNull(System.getenv("DEVORCH_PUBLISH_DB"))).toRealPath()
        val artifacts=Path.of(requireNotNull(System.getenv("DEVORCH_PUBLISH_ROOT"))).toRealPath()
        check(database.startsWith(home) && database.fileName.toString()=="publication-run.db" && artifacts.startsWith(home))
        val store=SqliteTaskStore(database)
        val workflow=assertNotNull(store.findWorkflow(SMOKE_TARGET.repositoryId,1))
        assertEquals(WorkflowPhase.READY_FOR_PR,workflow.phase)
        assertEquals(WorkflowHold.NONE,workflow.hold); assertEquals(0,workflow.correctionsUsed)
        assertNull(PublicationJournal(database).findPlan(workflow.id)) // Consumed run is inspection-only.
        val verified=RuntimeDatabase(database).read { c -> c.rows(
            "SELECT evidence FROM workflow_verifications WHERE workflow_id=? AND generation=0 AND state='SUCCEEDED'",workflow.id,
        ) { ObjectMapper().readTree(it.getString(1)) }.single() }
        val savedTree="55e86cfb40966afe68485b1f5fd3ce4c4bb0ca31"
        assertEquals(savedTree,verified.path("candidateTree").asText())
        val token=requireNotNull(System.getenv("DEVORCH_PUBLISH_TOKEN"))
        check(token.startsWith("github_pat_")) { "Separate disposable-repo-only fine-grained PAT required" }
        val base="0121b178ded841a041492c850b38a3abe37e0512"
        val publisher=DraftPrPublisher(database,artifacts,base,GitHubPublicationApi(GitHubPublicationHttp(token)),
            GitHubBranchPush(token),{ plan,_ ->
                System.getenv("DEVORCH_HOME_PUBLISH_GO")=="issue37-owner-approved-once" &&
                    plan.candidateTree==savedTree && plan.baseSha==base && plan.repositoryId==1424152600L
            })
        val result=publisher.publish()
        assertEquals("DRAFT_PR_CREATED",result.status)
        assertNotNull(result.url)
        assertEquals("ALREADY_CONFIRMED",publisher.publish().status) // No second send on repeat.
        println("HOME_PUBLICATION_CONFIRMED ${result.url}; no Coding/Review/Merge invoked")
    }
}
