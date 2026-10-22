package dev.orchestrator.service

import dev.orchestrator.agent.StageBudget
import org.junit.jupiter.api.Test
import java.sql.DriverManager
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
    @Test fun optionalCodexSha256IsAnOperatorPinAndThePathMustBeAbsolute() {
        val file=setup()
        val base=Files.readString(file)
        assertNull(PersonalConfig.read(file).codexSha256)
        Files.writeString(file, base+"codexSha256=\n")
        assertNull(PersonalConfig.read(file).codexSha256)
        Files.writeString(file, base+"codexSha256=${"ab".repeat(32)}\n")
        assertEquals("ab".repeat(32), PersonalConfig.read(file).codexSha256)
        Files.writeString(file, base+"codexSha256=ABC\n")
        assertFails { PersonalConfig.read(file) }
        Files.writeString(file, base.replace("codex=/fake/codex","codex=/not a path"))
        assertFails { PersonalConfig.read(file) }
    }
    @Test fun wrongRepositoryAndPublicConfigCannotGrantAuthority() {
        val file=setup(); val valid=Files.readString(file)
        Files.writeString(file,valid.replace("1424152600","123")); assertFails { PersonalConfig.read(file) }
        Files.writeString(file,valid); Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"))
        assertFails { PersonalConfig.read(file) }
    }
    @Test fun limitsAndCancelStayInsideCodeCapsWithoutAToken() {
        val file=setup()
        serviceCommand(arrayOf("limits", file.toString(), "default", "1", "30", "45"))
        val db=temp.resolve("state/service.db")
        assertEquals(ExecutionLimits(1,30,45), readExecutionLimits(db, 8))
        serviceCommand(arrayOf("limits", file.toString(), "9", "0", "60", "90"))
        assertEquals(ExecutionLimits(0,60,90), readExecutionLimits(db, 9))
        assertEquals(ExecutionLimits(1,30,45), readExecutionLimits(db, 8))
        assertEquals(ExecutionLimits.DEFAULT, readExecutionLimits(temp.resolve("missing-limits.db"), 3))
        serviceCommand(arrayOf("limits", file.toString(), "default", "2", StageBudget.DEFAULT_SECONDS.toString(), StageBudget.MAX_SECONDS.toString()))
        assertEquals(ExecutionLimits(2, StageBudget.DEFAULT_SECONDS, StageBudget.MAX_SECONDS), readExecutionLimits(db, 8))
        serviceCommand(arrayOf("cancel", file.toString(), "9"))
        assertTrue(java.nio.file.Files.isRegularFile(temp.resolve("state/control/cancel-9")))
        assertTrue(readLocalServiceStatus(db).contains("corrections=0"))
        assertTrue(readLocalServiceStatus(db).contains("stage<=${StageBudget.MAX_SECONDS}s"))
        serviceCommand(arrayOf("doctor", file.toString()))
        assertFails { serviceCommand(arrayOf("limits", file.toString(), "default", "3", "30", "45")) }
        assertFails { serviceCommand(arrayOf("limits", file.toString(), "default", "1", "${StageBudget.MAX_SECONDS + 1}", "45")) }
        assertFails { serviceCommand(arrayOf("limits", file.toString(), "default", "1", "${StageBudget.MIN_SECONDS - 1}", "45")) }
        assertEquals(ExecutionLimits(2, StageBudget.DEFAULT_SECONDS, StageBudget.MAX_SECONDS), readExecutionLimits(db, 8))
    }
    @Test fun pilotLimitTableAcceptsTheRaisedFiniteCeiling() {
        val file=setup()
        val db=temp.resolve("state/service.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().execute("""CREATE TABLE owner_limits(
                scope TEXT PRIMARY KEY,
                max_corrections INTEGER NOT NULL CHECK(max_corrections BETWEEN 0 AND 2),
                coding_timeout_seconds INTEGER NOT NULL CHECK(coding_timeout_seconds BETWEEN 1 AND 120),
                review_timeout_seconds INTEGER NOT NULL CHECK(review_timeout_seconds BETWEEN 1 AND 120),
                version INTEGER NOT NULL CHECK(version > 0),
                actor TEXT NOT NULL,
                recorded_at TEXT NOT NULL)""")
            connection.createStatement().execute("INSERT INTO owner_limits VALUES ('issue:4',1,120,120,1,'owner:local-command','t')")
        }
        serviceCommand(arrayOf("limits", file.toString(), "4", "1", StageBudget.DEFAULT_SECONDS.toString(), StageBudget.DEFAULT_SECONDS.toString()))
        assertEquals(ExecutionLimits(1, StageBudget.DEFAULT_SECONDS, StageBudget.DEFAULT_SECONDS), readExecutionLimits(db, 4))
    }
    @Test fun `default CLI profile runs as the logged-in user and ssh-vm keeps the dedicated account`() {
        val cli = PersonalConfig(true, Path.of("/state"), Path.of("/agent-home"), Path.of("/unused/cursor-cli"),
            "account-default", 1, 10809, 90, "cursor-cli", profileId = "default", cursorCli = Path.of("/usr/local/bin/agent"))
        requireDedicatedPersonalAccount(cli, "siovush")
        val ssh = cli.copy(runtimeMode = "ssh-vm", profileId = "legacy", cursorCli = null, codex = Path.of("/usr/bin/codex"))
        val denied = assertFailsWith<IllegalStateException> { requireDedicatedPersonalAccount(ssh, "siovush") }
        assertEquals("Dedicated PERSONAL account required", denied.message)
        requireDedicatedPersonalAccount(ssh, "devorch-e2e")
        assertFailsWith<IllegalStateException> {
            requireDedicatedPersonalAccount(cli.copy(runtimeMode = "local", profileId = "legacy", cursorCli = null), "siovush")
        }
        assertFailsWith<IllegalStateException> {
            requireDedicatedPersonalAccount(cli.copy(runtimeMode = "cursor-cloud", profileId = "cloud-api", cursorCli = null), "siovush")
        }
        val file = setup()
        val root = temp.resolve("state")
        val home = temp.resolve("agent-home")
        Files.writeString(file, """enabled=true
runtimeMode=cursor-cli
profile=default
repository=siyovushdev/devorch-kotlin-e2e-smoke
repositoryId=1424152600
owner=siyovushdev
root=$root
agentHome=$home
cursorCli=/usr/local/bin/agent
model=account-default
""")
        val stopped = assertFailsWith<ServiceStopped> { serviceCommand(arrayOf("service", file.toString())) }
        assertTrue(stopped.message!!.contains("IllegalArgumentException"))
        assertFalse(stopped.message!!.contains("requireDedicatedPersonalAccount"))
        assertFalse(stopped.message!!.contains("IllegalStateException"))
    }
    @Test fun symlinkAndSharedStateAreRejected() {
        val file=setup(); val link=Files.createSymbolicLink(temp.resolve("config-link"),file)
        assertFails { PersonalConfig.read(link) }
        Files.setPosixFilePermissions(temp.resolve("state"),PosixFilePermissions.fromString("rwxrwxrwx"))
        assertFails { PersonalConfig.read(file) }
    }
}
