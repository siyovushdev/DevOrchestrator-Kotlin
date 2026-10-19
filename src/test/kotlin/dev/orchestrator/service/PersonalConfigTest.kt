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
    @Test fun sshModeRequiresExplicitPrivatePinnedFilesAndDefaultsOff() {
        val file=setup()
        Files.writeString(file,Files.readString(file)+"runtimeMode=ssh-vm\n")
        assertFails { PersonalConfig.read(file) }
        val key=Files.writeString(temp.resolve("identity"),"ARTIFICIAL_KEY")
        val hosts=Files.writeString(temp.resolve("known-hosts"),"[127.0.0.1]:2222 fixture-host-key")
        for(p in listOf(key,hosts)) Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"))
        Files.writeString(file,Files.readString(file)+"sshIdentityFile=$key\nsshKnownHosts=$hosts\n")
        val config=PersonalConfig.read(file); assertFalse(config.enabled); assertEquals("ssh-vm",config.runtimeMode)
        serviceCommand(arrayOf("service",file.toString()))
        assertFalse(Files.exists(temp.resolve("state/service.db")))
        Files.setPosixFilePermissions(hosts,PosixFilePermissions.fromString("rw-r--r--"))
        assertFails { PersonalConfig.read(file) }
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
