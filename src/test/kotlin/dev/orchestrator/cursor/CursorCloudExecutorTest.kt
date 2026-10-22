package dev.orchestrator.cursor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.publish.PublicationHttp
import dev.orchestrator.storage.PublicationPlan
import dev.orchestrator.publish.PublicationPush
import dev.orchestrator.publish.PublicationResponse
import dev.orchestrator.storage.GitHubIssueRecord
import dev.orchestrator.service.PersonalConfig
import dev.orchestrator.service.PersonalRuntime
import dev.orchestrator.service.PersonalService
import dev.orchestrator.service.ServiceGitHub
import dev.orchestrator.service.TaskProgress
import dev.orchestrator.service.TaskRuntime
import dev.orchestrator.storage.AttemptState
import dev.orchestrator.storage.SqliteTaskStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CursorCloudExecutorTest {
    @TempDir lateinit var temp: Path
    private val mapper = ObjectMapper()
    private val repo = 1424152600L

    @Test fun `config does not require Codex or SSH`() {
        val root = privateDir("state")
        val home = privateDir("agent-home")
        val file = temp.resolve("personal.properties")
        Files.writeString(file, """
            repository=siyovushdev/devorch-kotlin-e2e-smoke
            repositoryId=1424152600
            owner=siyovushdev
            runtimeMode=cursor-cloud
            root=$root
            agentHome=$home
            model=account-default
            enabled=false
        """.trimIndent())
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        val config = PersonalConfig.read(file)
        assertEquals("cursor-cloud", config.runtimeMode)
        assertEquals(Path.of("/unused/cursor-cloud"), config.codex)
        assertEquals(null, config.ssh)
        Files.writeString(file, Files.readString(file) + "\ncodex=/usr/bin/false\n")
        assertFails { PersonalConfig.read(file) }
    }

    @Test fun `exclude skips the held issue and does not resume it`() {
        val root = privateDir("state")
        val db = root.resolve("service.db")
        var posts = 0
        var prepared = 0
        val github = object : ServiceGitHub {
            override fun issues() = listOf(issue(6, 5927591593), issue(8, 9001))
            override fun authorized(issue: GitHubIssueRecord) = true
            override fun postStatus(number: Int, body: String) { posts += 1 }
        }
        val runtime = object : TaskRuntime {
            override fun prepare(issue: GitHubIssueRecord) { prepared += 1; assertEquals(8, issue.issueNumber) }
            override fun recover(issue: GitHubIssueRecord) {}
            override fun step(issue: GitHubIssueRecord) = TaskProgress("coding")
        }
        val service = PersonalService(db, github, runtime) { true }
        assertEquals("EXCLUDED", service.exclude(6, 5927591593))
        assertEquals("EXCLUDED", service.exclude(6, 5927591593))
        assertEquals("ID_MISMATCH", service.exclude(6, 1))
        assertEquals("ACTIVE", service.tick())
        assertEquals(1, prepared)
        assertTrue(posts > 0)
    }

    @Test fun `lost create and auth failure do not create a second agent`() {
        val lost = runOnce("drop")
        assertEquals(1, lost.fake.creates.size)
        clearHold(lost)
        lost.runtime.step(lost.issue)
        assertEquals(1, lost.fake.creates.size)
        assertTrue(hold(lost).orEmpty().contains("CURSOR_OUTCOME_UNKNOWN"))

        val auth = runOnce("auth")
        assertEquals("CURSOR_AUTH_FAILED", hold(auth))
        assertEquals(1, auth.fake.creates.size)
        clearHold(auth)
        auth.runtime.step(auth.issue)
        assertEquals(1, auth.fake.creates.size)
        assertTrue(hold(auth) != null)
    }

    @Test fun `review push is an isolation break and is not retried`() {
        val world = world("isolate")
        world.runtime.prepare(world.issue)
        repeat(4) { world.runtime.step(world.issue) }
        assertEquals("CURSOR_ISOLATION_BREAK", hold(world))
        val before = world.fake.creates.size
        clearHold(world)
        world.runtime.step(world.issue)
        assertEquals(before, world.fake.creates.size)
        assertTrue(world.fake.creates.none { it.path("name").asText().startsWith("devorch-review-") && world.fake.creates.count { n -> n.path("name").asText().startsWith("devorch-review-") } > 1 })
        assertEquals(1, world.fake.creates.count { it.path("name").asText().startsWith("devorch-review-") })
    }

    @Test fun `cancel stops the Cursor run and does not import it`() {
        val world = world("cancel")
        world.fake.onRunning = {
            Files.createDirectories(world.root.resolve("control"))
            Files.writeString(world.root.resolve("control").resolve("cancel-${world.issue.issueNumber}"), "now")
        }
        world.runtime.prepare(world.issue)
        val progress = world.runtime.step(world.issue)
        assertEquals("cancelled", progress.stage)
        assertEquals(1, world.fake.cancels)
        assertEquals(1, world.fake.creates.size)
        assertFalse(Files.exists(world.task.resolve("artifacts").resolve("snap-${codingId(world)}")))
    }

    @Test fun `coding and review are separate sessions and one draft PR is published`() {
        System.setProperty("devorch.publication.trace", "1")
        val world = world("pass")
        world.runtime.prepare(world.issue)
        var last = TaskProgress("coding")
        repeat(8) {
            last = world.runtime.step(world.issue)
            if (last.stage == "done" || last.stage == "held") return@repeat
        }
        assertEquals("done", last.stage, "${last.reason} ${hold(world)}")
        assertEquals("https://github.com/siyovushdev/devorch-kotlin-e2e-smoke/pull/7", last.url)
        assertEquals(1, world.pulls[0])
        val bodies = world.fake.creates
        assertEquals(2, bodies.size)
        assertTrue(bodies.all { !it.path("autoCreatePR").asBoolean(true) && !it.has("envVars") && !it.has("model") })
        assertTrue(bodies.all { !it.toString().contains("test-token") && !it.toString().contains("CURSOR_API_KEY") })
        val ids = bodies.map { it.path("agentId").asText() }
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("bc-") })
        assertEquals(listOf("devorch-coding-8", "devorch-review-8"), bodies.map { it.path("name").asText() })
    }

    @Test fun `a failed test starts a new coding session instead of replaying the first`() {
        val world = world("correct")
        world.runtime.prepare(world.issue)
        var last = TaskProgress("coding")
        repeat(10) {
            last = world.runtime.step(world.issue)
            if (last.stage == "done" || last.stage == "held") return@repeat
        }
        assertEquals("done", last.stage, "${last.reason} ${hold(world)}")
        val coding = world.fake.creates.filter { it.path("name").asText().startsWith("devorch-coding-") }
        assertEquals(2, coding.size)
        assertTrue(coding[0].path("agentId").asText() != coding[1].path("agentId").asText())
        assertEquals(1, world.pulls[0])
    }

    @Test fun `controller tests use bubblewrap when it is installed`() {
        val binary = Path.of("/usr/bin/bwrap")
        if (!Files.isExecutable(binary)) assertFails { CursorCloudWorkflowBridge.productionIsolation(Path.of("/usr")) }
        else assertEquals(binary.toString(), CursorCloudWorkflowBridge.productionIsolation(Path.of("/usr")).first())
    }

    private data class World(val root: Path, val task: Path, val runtime: PersonalRuntime, val issue: GitHubIssueRecord, val fake: FakeCursor, val pulls: IntArray)

    private fun runOnce(mode: String): World {
        val world = world(mode)
        world.runtime.prepare(world.issue)
        world.runtime.step(world.issue)
        return world
    }

    private fun world(mode: String): World {
        val root = privateDir("state-$mode-${System.nanoTime()}")
        val home = privateDir("home-$mode-${System.nanoTime()}")
        val template = Files.createDirectory(temp.resolve("template-$mode-${System.nanoTime()}"))
        val seed = Files.createDirectory(temp.resolve("seed-$mode-${System.nanoTime()}"))
        val bare = temp.resolve("bare-$mode-${System.nanoTime()}.git")
        Files.writeString(seed.resolve("README"), "Base\n")
        git(seed, "init", "-q", "--initial-branch=main", "--template=$template")
        git(seed, "add", "README")
        git(seed, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "base")
        val base = git(seed, "rev-parse", "HEAD")
        fun commit(branch: String, clamp: String) {
            git(seed, "checkout", "-q", "-B", branch, base)
            Files.writeString(seed.resolve("clamp.py"), clamp)
            Files.createDirectories(seed.resolve("tests"))
            Files.writeString(seed.resolve("tests/__init__.py"), "")
            Files.writeString(seed.resolve("tests/test_clamp.py"), """
                import unittest
                from clamp import clamp
                class TestClamp(unittest.TestCase):
                    def test_inside(self):
                        self.assertEqual(2, clamp(2, 0, 5))
                    def test_low(self):
                        self.assertEqual(0, clamp(-1, 0, 5))
                    def test_high(self):
                        self.assertEqual(5, clamp(9, 0, 5))
            """.trimIndent())
            git(seed, "add", "clamp.py", "tests")
            git(seed, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", branch)
        }
        commit("cursor/bad", "def clamp(value, low, high):\n    return 0\n")
        git(seed, "checkout", "-q", "-b", "cursor/good")
        Files.writeString(seed.resolve("clamp.py"), "def clamp(value, low, high):\n    if value < low: return low\n    if value > high: return high\n    return value\n")
        git(seed, "add", "clamp.py")
        git(seed, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fix clamp")
        git(temp, "init", "--bare", "--template=$template", bare.toString())
        git(seed, "push", bare.toString(), "HEAD:refs/heads/main", "cursor/bad", "cursor/good")
        val fake = FakeCursor(mode)
        val published = arrayOfNulls<PublicationPlan>(1)
        val pulls = intArrayOf(0)
        val http = PublicationHttp { method, path, body -> publication(method, path, body, base, published, pulls) }
        val push = PublicationPush { plan, _, artifacts ->
            published[0] = plan
            Files.writeString(artifacts.resolve("events.log"), "*\t${plan.commitSha}:refs/heads/${plan.headBranch}\t[new branch]\n")
            dev.orchestrator.agent.nativecli.ProcessObservation(AttemptState.SUCCEEDED, "PUSHED", mapper.createObjectNode())
        }
        val config = PersonalConfig(true, root, home, Path.of("/unused/cursor-cloud"), "account-default", 1, 10809, 60, "cursor-cloud")
        val issue = issue(8, 9001, base)
        val fetcher = CursorRefFetcher { ref, work ->
            git(work, "init", "-q", "--initial-branch=main", "--template=$template")
            git(work, "fetch", "--no-tags", bare.toString(), ref)
            git(work, "-c", "core.hooksPath=/dev/null", "checkout", "--detach", "FETCH_HEAD")
            git(work, "rev-parse", "HEAD")
        }
        val runtime = PersonalRuntime(
            config, "test-token", { true }, http, push,
            fetch = { record, checkout, _ -> git(checkout, "fetch", "--no-tags", bare.toString(), record.baseSha) },
            cursorOverride = CursorCloudSettings(api = fake, fetch = fetcher, poll = Duration.ofMillis(1), testCommand = { emptyList() }),
        )
        return World(root, root.resolve("task-9001"), runtime, issue, fake, pulls)
    }

    private fun publication(method: String, path: String, body: String?, base: String, published: Array<PublicationPlan?>, pulls: IntArray): PublicationResponse {
        val decoded = URLDecoder.decode(path, Charsets.UTF_8)
        val root = "/repos/siyovushdev/devorch-kotlin-e2e-smoke"
        val plan = published[0]
        fun ref(sha: String, branch: String) = """{"ref":"refs/heads/$branch","object":{"type":"commit","sha":"$sha"}}"""
        fun pr(number: Int): String {
            val current = requireNotNull(plan)
            return """{"number":$number,"html_url":"https://github.com/siyovushdev/devorch-kotlin-e2e-smoke/pull/$number","state":"open","merged_at":null,"draft":true,
                "base":{"ref":"main","sha":"$base","repo":{"id":1424152600,"full_name":"siyovushdev/devorch-kotlin-e2e-smoke"}},
                "head":{"ref":"${current.headBranch}","sha":"${current.commitSha}","repo":{"id":1424152600,"full_name":"siyovushdev/devorch-kotlin-e2e-smoke"}}}"""
        }
        return when {
            method == "GET" && decoded == root -> PublicationResponse(200, """{"id":1424152600,"full_name":"siyovushdev/devorch-kotlin-e2e-smoke","default_branch":"main","archived":false}""")
            method == "GET" && decoded == "$root/git/ref/heads/main" -> PublicationResponse(200, ref(base, "main"))
            method == "GET" && decoded.startsWith("$root/git/ref/heads/") && plan == null -> PublicationResponse(404, """{"message":"missing"}""")
            method == "GET" && decoded == "$root/git/ref/heads/${plan?.headBranch}" -> PublicationResponse(200, ref(plan!!.commitSha, plan.headBranch))
            method == "GET" && decoded == "$root/git/commits/${plan?.commitSha}" -> PublicationResponse(200, """{"sha":"${plan!!.commitSha}","tree":{"sha":"${plan.candidateTree}"},"parents":[{"sha":"$base"}]}""")
            method == "GET" && decoded.startsWith("$root/pulls?") && pulls[0] == 0 -> PublicationResponse(200, "[]")
            method == "GET" && decoded.startsWith("$root/pulls?") -> PublicationResponse(200, "[${pr(7)}]")
            method == "POST" && decoded == "$root/pulls" -> { pulls[0] += 1; check(body!!.contains("\"draft\":true")); PublicationResponse(201, pr(7)) }
            method == "GET" && decoded == "$root/pulls/7" -> PublicationResponse(200, pr(7))
            else -> PublicationResponse(404, """{"message":"unmapped $method $decoded"}""")
        }
    }

    private fun hold(world: World): String? =
        SqliteTaskStore(world.task.resolve("run.db")).findWorkflow(repo, world.issue.issueNumber)?.holdReason

    private fun clearHold(world: World) {
        val db = world.task.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().executeUpdate("UPDATE workflows SET hold='NONE', hold_reason=NULL")
        }
    }

    private fun codingId(world: World): String = "issue-${world.issue.githubIssueId}-coding-0"

    private fun issue(number: Int, id: Long, base: String = "a".repeat(40)) =
        dev.orchestrator.storage.GitHubIssueRecord(repo, number, id, Instant.parse("2026-10-22T00:00:00Z"), "Add clamp", "Add clamp(value, low, high) with unittest.", base, true)

    private fun privateDir(name: String) =
        Files.createDirectory(temp.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

    private fun git(cwd: Path, vararg args: String): String {
        val builder = ProcessBuilder(listOf("/usr/bin/git", "-c", "core.hooksPath=/dev/null") + args).directory(cwd.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to temp.toString(), "GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null"))
        val process = builder.start()
        process.outputStream.close()
        val out = process.inputStream.readNBytes(200_000).toString(Charsets.UTF_8)
        check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { out }
        return out.trim()
    }

    private class FakeCursor(private val mode: String) : CursorCloudApi {
        val creates = mutableListOf<JsonNode>()
        var cancels = 0
        var onRunning: () -> Unit = {}
        private var coding = 0
        private var cancelled = false
        private val mapper = ObjectMapper()
        private var reviewPrompt = ""
        override fun createAgent(body: String): CursorHttpResult {
            val node = mapper.readTree(body)
            creates.add(node)
            if (mode == "auth") return CursorHttpResult(401, """{"error":"unauthorized"}""")
            if (mode == "drop") throw CursorTransportException("CURSOR_TRANSPORT")
            val id = node.path("agentId").asText()
            val run = "run-" + id.removePrefix("bc-")
            val review = node.path("name").asText().startsWith("devorch-review")
            if (review) reviewPrompt = node.path("prompt").path("text").asText()
            val status = if (mode == "cancel" && !review) "CREATING" else "FINISHED"
            return CursorHttpResult(200, """{"agent":{"id":"$id","latestRunId":"$run"},"run":{"id":"$run","agentId":"$id","status":"$status"}}""")
        }
        override fun getAgent(id: String) = CursorHttpResult(404, """{"error":"missing"}""")
        override fun cancelRun(agentId: String, runId: String): CursorHttpResult {
            cancels += 1
            cancelled = true
            return CursorHttpResult(200, """{"id":"$runId"}""")
        }
        override fun getRun(agentId: String, runId: String): CursorHttpResult {
            if (cancelled) return CursorHttpResult(200, """{"id":"$runId","agentId":"$agentId","status":"CANCELLED"}""")
            if (mode == "cancel") {
                onRunning()
                return CursorHttpResult(200, """{"id":"$runId","agentId":"$agentId","status":"RUNNING"}""")
            }
            val review = creates.last().path("name").asText().startsWith("devorch-review")
            val node = mapper.createObjectNode()
            node.put("id", runId)
            node.put("agentId", agentId)
            node.put("status", "FINISHED")
            if (review) {
                val start = reviewPrompt.lastIndexOf("{\"verdict\"")
                node.put("result", reviewPrompt.substring(start).trim())
                val branches = node.putObject("git").putArray("branches")
                if (mode == "isolate") branches.addObject().put("repoUrl", "github.com/siyovushdev/devorch-kotlin-e2e-smoke").put("branch", "cursor/review-edit")
            } else {
                coding += 1
                node.put("result", "implemented")
                val branch = if (mode == "correct" && coding == 1) "cursor/bad" else "cursor/good"
                node.putObject("git").putArray("branches").addObject()
                    .put("repoUrl", "github.com/siyovushdev/devorch-kotlin-e2e-smoke").put("branch", branch)
            }
            return CursorHttpResult(200, mapper.writeValueAsString(node))
        }
    }
}
