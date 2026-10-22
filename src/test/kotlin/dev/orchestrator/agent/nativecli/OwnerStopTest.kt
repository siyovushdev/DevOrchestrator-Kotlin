package dev.orchestrator.agent.nativecli

import dev.orchestrator.storage.AttemptState
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun consistentWriteBoundaryPassesTheCanaryWithoutFixtureBypass() {
        val home=Files.createDirectories(temp.resolve("agent-home"))
        val codexHome=Files.createDirectories(home.resolve(".codex"))
        val secret=codexHome.resolve("devorch-policy-canary")
        Files.writeString(secret, LocalSecretCanary.SECRET)
        Files.setPosixFilePermissions(secret, setOf(PosixFilePermission.OWNER_READ))
        val workspace=Files.createDirectories(temp.resolve("workspace"))
        val fake=temp.resolve("sandbox-codex")
        Files.writeString(fake, """#!/usr/bin/python3
import os, sys
cmd=sys.argv[sys.argv.index('--')+1:]
os.execv(cmd[0], cmd)
""")
        fake.toFile().setExecutable(true)
        assertTrue(LocalSecretCanary.pass(fake, workspace, home, Path.of("/usr/bin/python3"), true))
        val exit=temp.resolve("exit-codex")
        Files.writeString(exit, "#!/usr/bin/python3\nimport sys\nsys.exit(0)\n")
        exit.toFile().setExecutable(true)
        assertFalse(LocalSecretCanary.pass(exit, workspace, home, Path.of("/usr/bin/python3"), true))
    }

    @Test fun supervisorStopsTheChildWhenItsParentExits() {
        val artifacts=Files.createDirectory(temp.resolve("artifacts"))
        val helper=artifacts.resolve("supervisor.py")
        javaClass.getResourceAsStream("/native-codex-supervisor.py")!!.use { Files.copy(it, helper) }
        val receipt=artifacts.resolve("supervisor-receipt.json")
        val config=artifacts.resolve("launch.json")
        Files.writeString(config, """{"argv":["/bin/sleep","30"],"cwd":"${artifacts}","env":{"PATH":"/usr/bin:/bin"},"timeout_ms":20000,"receipt":"${receipt}"}""")
        val parent=artifacts.resolve("parent.py")
        Files.writeString(parent, """
import os, subprocess, sys, time
env=os.environ.copy(); env['DEVORCH_DIE_WITH_PARENT']='1'
child=subprocess.Popen([sys.executable,'-I','-S',sys.argv[1],sys.argv[2]], env=env)
time.sleep(0.4)
os._exit(0)
""".trimIndent())
        val proc=ProcessBuilder("/usr/bin/python3", parent.toString(), helper.toString(), config.toString()).start()
        assertTrue(proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8)
        while (!Files.exists(receipt) && System.nanoTime()<deadline) Thread.sleep(50)
        val text=Files.readString(receipt)
        assertTrue(text.contains("stop_requested") || text.contains("supervisor_error"), text)
    }
}
