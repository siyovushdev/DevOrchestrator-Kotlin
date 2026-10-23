package dev.orchestrator.profile

import dev.orchestrator.agent.ReasoningEffort
import dev.orchestrator.agent.StageBudget
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ExecutionProfileCatalogTest {
    @TempDir lateinit var temp: Path

    @Test fun `default profile is the logged-in CLI and cloud-api stays separate`() {
        val catalog = ExecutionProfileCatalog(temp.resolve("profiles.db"))
        catalog.ensureBuiltins()
        val fallback = catalog.find("default")!!
        val cloud = catalog.find("cloud-api")!!
        assertEquals(1, fallback.version)
        assertEquals(2, fallback.maxCorrections)
        assertEquals(StageBudget.DEFAULT_SECONDS, fallback.stageTimeoutSeconds)
        assertEquals(ExecutionRuntime.cli, fallback.coder.runtime)
        assertEquals(ExecutionAuth.subscription, fallback.coder.auth)
        assertEquals("cursor-cli", fallback.coder.adapterId)
        assertEquals(ExecutionRuntime.cli, fallback.reviewer.runtime)
        assertEquals(ExecutionAuth.subscription, fallback.reviewer.auth)
        assertEquals(ExecutionRuntime.cloud, cloud.coder.runtime)
        assertEquals(ExecutionAuth.api, cloud.coder.auth)
        assertEquals("cursor-cloud", cloud.reviewer.adapterId)
        assertTrue(fallback.coder.adapterId != cloud.coder.adapterId)
    }

    @Test fun `a new version does not overwrite the pinned one`() {
        val catalog = ExecutionProfileCatalog(temp.resolve("profiles.db"))
        catalog.ensureBuiltins()
        val first = catalog.pinLatest("issue-1", "default")
        val saved = catalog.save(first.copy(maxCorrections = 1, stageTimeoutSeconds = 30, coder = first.coder.copy(model = "composer-2.5", effort = ReasoningEffort.HIGH)))
        assertEquals(2, saved.version)
        assertEquals(2, catalog.find("default", 1)!!.maxCorrections)
        assertEquals("account-default", catalog.find("default", 1)!!.coder.model)
        assertEquals(ReasoningEffort.DEFAULT, catalog.find("default", 1)!!.coder.effort)
        assertEquals(1, saved.maxCorrections)
        assertEquals("composer-2.5", saved.coder.model)
        val still = catalog.pinLatest("issue-1", "default")
        assertEquals(1, still.version)
        assertEquals(2, catalog.pinLatest("issue-2", "default").version)
    }

    @Test fun `timeout above sixty minutes is rejected`() {
        val catalog = ExecutionProfileCatalog(temp.resolve("profiles.db"))
        catalog.ensureBuiltins()
        val current = catalog.find("default")!!
        assertFails { catalog.save(current.copy(stageTimeoutSeconds = StageBudget.MAX_SECONDS + 1)) }
        assertEquals(1, catalog.find("default")!!.version)
    }

    @Test fun `cli cannot select api auth and cloud cannot select subscription`() {
        assertFails { RoleExecution(ExecutionRuntime.cli, ExecutionAuth.api, "account-default", ReasoningEffort.DEFAULT) }
        assertFails { RoleExecution(ExecutionRuntime.cloud, ExecutionAuth.subscription, "account-default", ReasoningEffort.DEFAULT) }
    }

    @Test fun `one stage resolves exactly one adapter`() {
        val profile = ExecutionProfile.defaultProfile()
        assertEquals("cursor-cli", profile.coder.adapterId)
        assertEquals("cursor-cli", profile.reviewer.adapterId)
        assertEquals(setOf("cursor-cli"), setOf(profile.coder.adapterId, profile.reviewer.adapterId))
        val cloud = ExecutionProfile.cloudApi()
        assertEquals(setOf("cursor-cloud"), setOf(cloud.coder.adapterId, cloud.reviewer.adapterId))
        assertTrue(profile.coder.adapterId != cloud.coder.adapterId)
    }
}
