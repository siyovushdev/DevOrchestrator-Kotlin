package dev.orchestrator.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class PersonalConfigTest {
    @TempDir lateinit var temp: Path
    private fun setup(): Path {
        val root=Files.createDirectory(temp.resolve("state"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val home=Files.createDirectory(temp.resolve("agent-home"))
        val file=temp.resolve("personal.properties")
        Files.writeString(file,"""repository=siyovushdev/devorch-kotlin-e2e-smoke
repositoryId=1424152600
owner=siyovushdev
root=$root
agentHome=$home
codex=/fake/codex
model=fake-model
""")
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))
        return file
    }
    @Test fun defaultsOffAndCliDoesNotNeedTokenOrLaunchAnything() {
        val file=setup(); assertFalse(PersonalConfig.read(file).enabled)
        serviceCommand(arrayOf("service",file.toString()))
        assertFalse(Files.exists(temp.resolve("state/service.db")))
        serviceCommand(arrayOf("status",file.toString()))
        assertFalse(Files.exists(temp.resolve("state/service.db")))
    }
    @Test fun sshQueueModeIsExplicitlyNotReadyBeforeCredentialsDatabaseOrExecution() {
        val file=setup()
        Files.writeString(file,Files.readString(file)+"runtimeMode=ssh-vm\nenabled=true\n")
        assertEquals("ssh-vm",PersonalConfig.read(file).runtimeMode)
        val failure=assertFailsWith<ServiceStopped> { serviceCommand(arrayOf("run",file.toString())) }
        assertTrue(failure.message!!.contains("REMOTE_WORKFLOW_BRIDGE_NOT_READY"))
        assertFalse(Files.exists(temp.resolve("state/service.db")))
        assertFailsWith<ServiceStopped> { PersonalRuntime(PersonalConfig.read(file),"ARTIFICIAL_TOKEN",permitted={ true }) }
        Files.writeString(file,Files.readString(file).replace("enabled=true","enabled=false"))
        serviceCommand(arrayOf("service",file.toString()))
        assertFalse(Files.exists(temp.resolve("state/service.db")))
    }
    @Test fun wrongRepositoryAndPublicConfigCannotGrantAuthority() {
        val file=setup(); val valid=Files.readString(file)
        Files.writeString(file,valid.replace("1424152600","123")); assertFails { PersonalConfig.read(file) }
        Files.writeString(file,valid); Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"))
        assertFails { PersonalConfig.read(file) }
    }
    @Test fun symlinkAndSharedStateAreRejected() {
        val file=setup(); val link=Files.createSymbolicLink(temp.resolve("config-link"),file)
        assertFails { PersonalConfig.read(link) }
        Files.setPosixFilePermissions(temp.resolve("state"),PosixFilePermissions.fromString("rwxrwxrwx"))
        assertFails { PersonalConfig.read(file) }
    }
}
