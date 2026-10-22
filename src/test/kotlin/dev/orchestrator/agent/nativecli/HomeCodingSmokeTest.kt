package dev.orchestrator.agent.nativecli

import dev.orchestrator.agent.*
import dev.orchestrator.github.GitHubIssueReader
import dev.orchestrator.github.GitHubRepositoryTarget
import dev.orchestrator.github.HttpGitHubJsonRead
import dev.orchestrator.storage.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

/** FUTURE Owner-invoked one-shot on HOME. Default/CI SKIP. Never run by the coding session. */
@EnabledIfEnvironmentVariable(named="DEVORCH_HOME_CODING_GO",matches="issue35-owner-approved-once")
class HomeCodingSmokeTest {
    @Test fun oneBoundedCodingAttemptOnPreparedHomeClone() {
        check(System.getProperty("user.name")=="devorch-e2e") { "HOME isolated test user required" }
        val home = Path.of(requireNotNull(System.getenv("HOME"))).toRealPath()
        val root = Path.of(requireNotNull(System.getenv("DEVORCH_HOME_SMOKE_ROOT"))).toRealPath()
        require(root.startsWith(home) && root!=home)
        val model = requireNotNull(System.getenv("DEVORCH_CODEX_MODEL")) { "Owner must select exact model" }
        val token = requireNotNull(System.getenv("DEVORCH_GITHUB_READ_TOKEN")) { "Single-repo read authority required" }
        require(token.isNotBlank())
        val clone = root.resolve("checkout")
        val db = root.resolve("run.db")
        check(!Files.exists(db)) { "Existing run.db: inspect previous receipts; never rerun an unknown/consumed smoke" }
        val target = GitHubRepositoryTarget("siyovushdev","devorch-kotlin-e2e-smoke",1424152600)
        val issue = GitHubIssueReader(HttpGitHubJsonRead(token)).read(target).single { it.issueNumber==1 }
        check(issue.open)
        val store = SqliteTaskStore(db)
        store.insertProject(RegisteredProject("home-smoke",target.repositoryId,ProjectKind.PERSONAL,root.toString()),
            "owner:home-one-shot")
        val policy = assertNotNull(store.findPolicy(target.repositoryId))
        check(store.updatePolicyFromTrustedOwner(policy.copy(version=2,personalAutoExecution=true),1,
            "owner:home-one-shot","Separately approved HOME Coding smoke only"))
        store.acceptGitHubIssue(issue,"home-smoke-issue-1")
        val catalog = SqliteAgentCatalog(db)
        catalog.declareManifest(AgentCapabilityManifest("codex-cli","openai",1,setOf(AgentRole.CODING),
            setOf(AgentSandbox.NATIVE_PERSONAL_WRITE),mapOf(model to setOf(ReasoningEffort.DEFAULT))),null,"owner:home-one-shot")
        catalog.saveProfile(AgentProfile("home-coding",1,target.repositoryId,AgentRole.CODING,"codex-cli",1,
            "openai",model,ReasoningEffort.DEFAULT,AgentSandbox.NATIVE_PERSONAL_WRITE,120,100_000),null,"owner:home-one-shot")
        catalog.bindRole(target.repositoryId,AgentRole.CODING,"home-coding",1,null,"owner:home-one-shot")
        check(TrustedGitCheckoutGuard.accepts(clone))
        val tree = BoundedNativeProbeReader().read(listOf("/usr/bin/git","--git-dir=${clone.resolve(".git")}",
            "--work-tree=$clone","rev-parse","--verify","HEAD^{tree}"),clone)
        check(tree is ProbeProcessResult.Exited && tree.exitCode==0)
        val intent = assertNotNull(CodingAttemptJournal(db).plan(target.repositoryId,1,0,"home-coding-1",
            clone,tree.output.trim()))
        val artifacts = Files.createDirectory(root.resolve("artifacts"))
        val result = NativeCodingRunner(db,target.repositoryId,home.resolve(".local/bin/codex"),home,artifacts,
            timeout=Duration.ofSeconds(120)).run(intent.attemptId)
        assertEquals(AttemptState.SUCCEEDED,result.state,result.reason)
        assertNotNull(result.candidateTree)
        assertTrue(Files.size(result.artifacts!!.resolve("candidate.diff"))>0)
        println("HOME_REAL_CODING_CANDIDATE_CAPTURED; tests/review/publisher NOT_RUN_BY_CONTROLLER; inspect run.db/artifacts")
    }
}
