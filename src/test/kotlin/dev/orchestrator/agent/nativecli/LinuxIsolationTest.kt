package dev.orchestrator.agent.nativecli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LinuxIsolationTest {
    @TempDir lateinit var temp: Path
    @Test fun controllerHomeAncestorsAndStateOverlapsAreRejectedWithoutReadingSecrets() {
        val old=System.getProperty("user.home")
        try {
            val controller=Files.createDirectory(temp.resolve("controller"))
            Files.createDirectory(controller.resolve(".codex"))
            val model=Files.createDirectory(controller.resolve("model"))
            Files.createDirectory(model.resolve(".codex"))
            System.setProperty("user.home",controller.toString())
            assertFails { LinuxIsolation.requireModelHome(controller) }
            assertFails { LinuxIsolation.requireModelHome(temp) }
            assertFails { LinuxIsolation.requireModelHome(model,model.resolve("service.db")) }
            assertFails { LinuxIsolation.requireModelHome(model,controller) }
            val relative=Path.of("").toAbsolutePath().relativize(model.resolve("relative.db"))
            assertFails { LinuxIsolation.requireModelHome(model,relative) }
            val alias=Files.createSymbolicLink(temp.resolve("model-alias"),model)
            assertFails { LinuxIsolation.requireModelHome(model,alias.resolve("state.db")) }
            LinuxIsolation.requireModelHome(model,controller.resolve("state.db"))
        } finally { System.setProperty("user.home",old) }
    }
}
