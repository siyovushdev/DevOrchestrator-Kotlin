package dev.orchestrator.agent.nativecli

import dev.orchestrator.storage.AttemptState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real supervisor process. No Codex, network, or controller secret. */
class OwnerStopTest {
    @TempDir lateinit var temp: Path

    @Test fun provenOwnerStopIsCancelledRatherThanSuccessful() {
        val artifacts = Files.createDirectory(temp.resolve("artifacts"))
        val workspace = Files.createDirectory(temp.resolve("work"))
        val flag = AtomicBoolean(false)
        val observed = NativeProcessRunner(Path.of("/usr/bin/python3")).run(
            listOf("/usr/bin/python3", "-I", "-c", "import time; time.sleep(30)"),
            workspace,
            mapOf("HOME" to artifacts.toString(), "PATH" to "/usr/bin:/bin", "LANG" to "C"),
            artifacts, ByteArray(0), 15_000, stopRequested = { flag.get() },
        ) {
            flag.set(true)
            true
        }
        assertEquals(AttemptState.CANCELLED, observed.state)
        assertEquals("OWNER_STOP", observed.reason)
        assertTrue(observed.evidence?.path("cleanup_echild")?.asBoolean() == true)
    }
}
