package dev.orchestrator.agent.nativecli

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.storage.AttemptState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** No inference/login/GitHub: only installed Codex's sandbox child with fake credentials. */
@EnabledIfEnvironmentVariable(named="DEVORCH_CODEX_ISOLATION_BINARY",matches=".+")
class InstalledCodexIsolationTest {
    @TempDir lateinit var temp: Path
    @Test fun installedCodexSandboxChildCannotReadOrAlterControllerCanary() {
        val binary=Path.of(System.getenv("DEVORCH_CODEX_ISOLATION_BINARY")).toRealPath()
        val workspace=Files.createDirectory(temp.resolve("checkout"))
        val home=Files.createDirectory(temp.resolve("model-home"))
        Files.createDirectory(home.resolve(".codex"))
        val artifacts=Files.createDirectory(temp.resolve("artifacts"))
        val output=Files.createDirectory(temp.resolve("output"))
        val secret=temp.resolve("controller.env")
        Files.writeString(secret,"FAKE_CONTROLLER_CANARY_ONLY")
        val code="""
from pathlib import Path
for target in [${ObjectMapper().writeValueAsString(secret.toString())}, '/proc/${ProcessHandle.current().pid()}/environ']:
    for mode in ['r','r+']:
        try:
            with open(target,mode) as f:
                if mode=='r': f.read()
                else: f.write('compromised')
        except OSError: pass
        else: raise AssertionError('controller data reachable')
Path('sandbox-child-ok').write_text('isolated')
print('INSTALLED_CODEX_SANDBOX_CHILD_ISOLATED')
"""
        val command=LinuxIsolation.prefix(workspace,true,home,binary,output)+listOf(binary.toString(),"sandbox",
            "--config","sandbox_mode=\"workspace-write\"","--","/usr/bin/python3","-c",code)
        val observed=NativeProcessRunner(Path.of("/usr/bin/python3")).run(command,workspace,
            mapOf("HOME" to home.toString(),"CODEX_HOME" to home.resolve(".codex").toString(),"PATH" to "/usr/bin:/bin","LANG" to "C.UTF-8"),
            artifacts,byteArrayOf(),15000) { true }
        assertEquals(AttemptState.SUCCEEDED,observed.state,observed.reason+" "+Files.readString(artifacts.resolve("events.log")))
        assertEquals("FAKE_CONTROLLER_CANARY_ONLY",Files.readString(secret))
        assertEquals("isolated",Files.readString(workspace.resolve("sandbox-child-ok")))
    }
}
