package dev.orchestrator.service

import dev.orchestrator.publish.SMOKE_TARGET
import dev.orchestrator.storage.GitHubIssueRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class PersonalFetchTest {
    @TempDir lateinit var temp: Path
    @Test fun revokedConfigurationRefusesFetchBeforeAnyProcessOrCredentialFile() {
        val artifacts=Files.createDirectory(temp.resolve("fetch"))
        val checkout=Files.createDirectory(temp.resolve("checkout"))
        val issue=GitHubIssueRecord(1424152600,17,1017,Instant.EPOCH,"Task","body","a".repeat(40),true)
        assertFails { fetchPersonalCheckout("FAKE",issue,checkout,artifacts,{false}) }
        Files.list(artifacts).use { assertEquals(0,it.count()) }
    }
    @Test fun productionFetchUsesSupervisorSupportedPrivateCredentialWithoutPersistingSecret() {
        val artifacts=Files.createDirectory(temp.resolve("fetch"))
        val checkout=Files.createDirectory(temp.resolve("checkout"))
        val git=temp.resolve("fake-git")
        val token="FAKE_PRIVATE_FETCH_CREDENTIAL_123"
        // Fake network binary only. Actual production fetch argv, askpass and native supervisor execute.
        Files.writeString(git,"""#!/usr/bin/python3
import os, subprocess, sys
assert sys.argv[1:]==['-c','credential.helper=','-c','http.followRedirects=false','fetch','--no-tags','--no-auto-maintenance','https://github.com/${SMOKE_TARGET.fullName}.git','${"a".repeat(40)}']
secret=os.environ['DEVORCH_PUBLISH_TOKEN']
assert secret=='$token'
assert not any(k in os.environ for k in ['GH_TOKEN','GITHUB_TOKEN','DEVORCH_PERSONAL_TOKEN'])
assert subprocess.check_output([os.environ['GIT_ASKPASS'],'Username for GitHub']).decode().strip()=='x-access-token'
assert subprocess.check_output([os.environ['GIT_ASKPASS'],'Password for GitHub']).decode().strip()==secret
print('FETCH_ADAPTER_EXECUTED')
""")
        check(git.toFile().setExecutable(true))
        val issue=GitHubIssueRecord(1424152600,17,1017,Instant.EPOCH,"Task","body","a".repeat(40),true)
        fetchPersonalCheckout(token,issue,checkout,artifacts,{true},git)
        assertTrue(Files.readString(artifacts.resolve("events.log")).contains("FETCH_ADAPTER_EXECUTED"))
        Files.list(artifacts).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach {
            assertFalse(Files.readString(it).contains(token),"Credential persisted in ${it.fileName}")
        } }
    }
}
