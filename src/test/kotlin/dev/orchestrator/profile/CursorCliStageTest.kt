package dev.orchestrator.profile

import dev.orchestrator.agent.nativecli.ProcessObservation
import dev.orchestrator.publish.PublicationHttp
import dev.orchestrator.publish.PublicationPush
import dev.orchestrator.service.PersonalConfig
import dev.orchestrator.service.PersonalRuntime
import dev.orchestrator.storage.AttemptState
import dev.orchestrator.storage.GitHubIssueRecord
import dev.orchestrator.storage.RuntimeDatabase
import dev.orchestrator.storage.mutate
import dev.orchestrator.storage.rows
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CursorCliStageTest {
    @TempDir lateinit var temp: Path

    @Test fun `each stage spawns one CLI and does not call cloud or Codex`() {
        val cli = fixture()
        val log = temp.resolve("agent.log")
        val world = runtime(cli, null)
        world.runtime.prepare(world.issue)
        val pinned = ExecutionProfileCatalog(world.task.resolve("run.db")).pinned("issue-9001")!!
        assertEquals("default", pinned.id)
        assertEquals("cursor-cli", pinned.coder.adapterId)
        assertEquals("cursor-cli", pinned.reviewer.adapterId)
        val progress = mutableListOf<String>()
        repeat(3) { progress += world.runtime.step(world.issue).stage }
        val spawns = if (Files.exists(log)) Files.readAllLines(log) else emptyList()
        assertEquals(listOf("tests", "review", "review"), progress, spawns.joinToString(" | "))
        assertEquals(2, spawns.size)
        assertTrue(spawns.all { it.contains("--force") && it.contains("--trust") && it.contains("--sandbox") && it.contains("disabled") && it.contains("--workspace") })
        assertTrue(spawns.none { it.contains("--api-key") || it.contains("api.cursor.com") || it.contains("CURSOR_API_KEY") })
        val stages = RuntimeDatabase(world.task.resolve("run.db")).read { c ->
            c.rows("SELECT attempt_id,adapter,runtime,auth,model,effort FROM cli_stage_runs ORDER BY recorded_at") {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
            }
        }
        assertEquals(2, stages.map { it[0] }.toSet().size)
        assertTrue(stages.all { it[1] == "cursor-cli" && it[2] == "cli" && it[3] == "subscription" && it[4] == "account-default" && it[5] == "DEFAULT" })
    }

    @Test fun `verdict nested in the CLI result text is accepted and a missing verdict does not throw`() {
        val verdict = """{"verdict":"PASS","candidate_id":"candidate","manifest_sha256":"${"ab".repeat(32)}","candidate_tree":"${"cd".repeat(20)}","generation":0,"findings":[]}"""
        val nested = """{"type":"result","subtype":"success","is_error":false,"result":${ObjectMapper().writeValueAsString("Review notes\n$verdict")}}"""
        val parsed = CursorCliWorkflowBridge.parseVerdict(nested)
        assertEquals("PASS", parsed?.path("verdict")?.asText())
        assertEquals("candidate", parsed?.path("candidate_id")?.asText())
        assertEquals(null, CursorCliWorkflowBridge.parseVerdict("""{"type":"result","subtype":"success","is_error":false,"result":"no verdict here"}"""))
        val cli = fixture("nested")
        val log = temp.resolve("agent.log")
        val world = runtime(cli, null)
        world.runtime.prepare(world.issue)
        val progress = mutableListOf<String>()
        repeat(3) { progress += world.runtime.step(world.issue).stage }
        assertEquals(listOf("tests", "review", "review"), progress)
        assertEquals(2, Files.readAllLines(log).size)
        val states = RuntimeDatabase(world.task.resolve("run.db")).read { c ->
            c.rows("SELECT state FROM cli_stage_runs ORDER BY recorded_at") { it.getString(1) }
        }
        assertEquals(listOf("FINISHED", "FINISHED"), states)
    }

    @Test fun `a bytecode-only review hold continues without a second reviewer`() {
        val cli = fixture("success")
        val log = temp.resolve("agent.log")
        val world = runtime(cli, null)
        world.runtime.prepare(world.issue)
        assertEquals("tests", world.runtime.step(world.issue).stage)
        val untested = Files.walk(world.task.resolve("artifacts")).use { stream ->
            stream.filter { it.fileName.toString() == "untested" && Files.isDirectory(it) }.findFirst().orElseThrow()
        }
        Files.createDirectories(untested.resolve("files/src/__pycache__"))
        Files.createDirectories(untested.resolve("files/tests/__pycache__"))
        Files.write(untested.resolve("files/src/__pycache__/is_blank.cpython-310.pyc"), byteArrayOf(1, 2, 3))
        Files.write(untested.resolve("files/tests/__pycache__/test_is_blank.cpython-310.pyc"), byteArrayOf(4, 5, 6))
        assertEquals("review", world.runtime.step(world.issue).stage)
        assertEquals(1, Files.readAllLines(log).size)
        val db = RuntimeDatabase(world.task.resolve("run.db"))
        val mapper = ObjectMapper()
        val verified = mapper.readTree(db.read { c -> c.rows("SELECT evidence FROM workflow_verifications") { it.getString(1) }.single() })
        val workflow = db.read { c -> c.rows("SELECT workflow_id, corrections_used FROM workflows") { it.getString(1) to it.getInt(2) }.single() }
        val candidateDir = Path.of(verified.path("directory").asText())
        Files.write(candidateDir.resolve("files/src/__pycache__/is_blank.cpython-310.pyc"), byteArrayOf(9, 9, 9, 9))
        Files.write(candidateDir.resolve("files/tests/__pycache__/test_is_blank.cpython-310.pyc"), byteArrayOf(8, 8, 8))
        val sourceFile = candidateDir.resolve("files/clamp.py")
        val original = Files.readString(sourceFile)
        Files.writeString(sourceFile, "tampered source\n")
        val reviewId = "${workflow.first}-review-${workflow.second}"
        val detail = mapper.createObjectNode()
        val receipt = detail.putObject("receipt")
        receipt.put("candidateId", verified.path("candidateId").asText())
        receipt.put("reviewerAttemptId", reviewId)
        receipt.put("reviewerSessionId", "review-session")
        receipt.put("candidateManifestSha256", verified.path("manifestSha256").asText())
        receipt.put("candidateTree", verified.path("candidateTree").asText())
        receipt.put("codingGeneration", workflow.second)
        receipt.put("verdict", "PASS")
        detail.putArray("findings")
        db.write { c ->
            c.mutate("UPDATE workflows SET hold='NEEDS_OWNER', hold_reason='CORRECTION_LIMIT_OR_REVIEW_BINDING' WHERE workflow_id=?", workflow.first)
            c.mutate("INSERT INTO agent_attempts(attempt_id,workflow_id,role,generation,state,source_tree,recorded_at) VALUES (?,?,'REVIEW',?,'SUCCEEDED',?,?)",
                reviewId, workflow.first, workflow.second, verified.path("candidateTree").asText(), "t")
            c.mutate("INSERT INTO workflow_events(workflow_id,event_type,detail,recorded_at) VALUES (?, 'REVIEW_COMPLETED', ?, 't')",
                workflow.first, mapper.writeValueAsString(detail))
        }
        assertFalse(world.runtime.continueBytecodeReviewHold(world.issue))
        assertEquals("REVIEWING", db.read { c -> c.rows("SELECT phase FROM workflows") { it.getString(1) }.single() })
        assertEquals("CORRECTION_LIMIT_OR_REVIEW_BINDING", db.read { c -> c.rows("SELECT hold_reason FROM workflows") { it.getString(1) }.single() })
        Files.writeString(sourceFile, original)
        assertTrue(world.runtime.continueBytecodeReviewHold(world.issue))
        assertEquals("READY_FOR_PR", db.read { c -> c.rows("SELECT phase FROM workflows") { it.getString(1) }.single() })
        assertEquals("NONE", db.read { c -> c.rows("SELECT hold FROM workflows") { it.getString(1) }.single() })
        assertEquals(1, Files.readAllLines(log).size)
        assertEquals(1, db.read { c -> c.rows("SELECT 1 FROM agent_attempts WHERE role='REVIEW'") { 1 }.size })
        assertEquals("SUCCEEDED", db.read { c -> c.rows("SELECT state FROM agent_attempts WHERE role='REVIEW'") { it.getString(1) }.single() })
        world.runtime.step(world.issue)
        assertEquals(1, Files.readAllLines(log).size)
    }

    @Test fun `a saved exit zero review is imported on the next step without a second spawn`() {
        val cli = fixture("success")
        val log = temp.resolve("agent.log")
        val world = runtime(cli, null)
        world.runtime.prepare(world.issue)
        assertEquals("tests", world.runtime.step(world.issue).stage)
        assertEquals("review", world.runtime.step(world.issue).stage)
        assertEquals(1, Files.readAllLines(log).size)
        val db = RuntimeDatabase(world.task.resolve("run.db"))
        val mapper = ObjectMapper()
        val verified = mapper.readTree(db.read { c -> c.rows("SELECT evidence FROM workflow_verifications") { it.getString(1) }.single() })
        val workflow = db.read { c -> c.rows("SELECT workflow_id, version, corrections_used FROM workflows") { listOf(it.getString(1), it.getLong(2).toString(), it.getInt(3).toString()) }.single() }
        val codingId = verified.path("codingAttemptId").asText()
        val context = db.read { c ->
            c.rows("SELECT issue_body_sha256, base_sha, policy_version, assignment_version, profile_id, profile_version, adapter_id, manifest_version, workspace, repository_id, issue_number FROM attempt_contexts WHERE attempt_id=?", codingId) {
                listOf(it.getString(1), it.getString(2), it.getInt(3).toString(), it.getInt(4).toString(), it.getString(5), it.getLong(6).toString(), it.getString(7), it.getLong(8).toString(), it.getString(9), it.getLong(10).toString(), it.getInt(11).toString())
            }.single()
        }
        val reviewId = "${workflow[0]}-review-${workflow[2]}"
        val verdict = mapper.createObjectNode()
        verdict.put("verdict", "PASS")
        verdict.put("candidate_id", verified.path("candidateId").asText())
        verdict.put("manifest_sha256", verified.path("manifestSha256").asText())
        verdict.put("candidate_tree", verified.path("candidateTree").asText())
        verdict.put("generation", verified.path("generation").asInt())
        verdict.putArray("findings")
        val stdout = mapper.createObjectNode()
        stdout.put("type", "result")
        stdout.put("subtype", "success")
        stdout.put("is_error", false)
        stdout.put("result", "Saved review\n${mapper.writeValueAsString(verdict)}")
        val spawn = Files.createDirectories(world.task.resolve("artifacts").resolve("cli-spawn-saved"))
        Files.writeString(spawn.resolve("events.log"), mapper.writeValueAsString(stdout))
        Files.writeString(spawn.resolve("supervisor-receipt.json"), """{"exit_code":0,"cause":"exited","cleanup_echild":true,"boot_id":"boot","machine_id":"machine","supervisor":{"pid":42,"start_ticks":"99"},"child":{"pid":43,"start_ticks":"100"},"reaped":1}""")
        val started = """{"attempt_id":"$reviewId","identity":{"pid":"42","start_ticks":"99","boot_id":"boot","machine_id":"machine"},"adapter":"cursor-cli"}"""
        db.write { c ->
            c.mutate("INSERT INTO agent_attempts(attempt_id,workflow_id,role,generation,state,source_tree,recorded_at) VALUES (?,?,'REVIEW',?,'RUNNING',?,?)",
                reviewId, workflow[0], workflow[2].toInt(), verified.path("candidateTree").asText(), "t")
            c.mutate("""INSERT INTO attempt_contexts(attempt_id,repository_id,issue_number,issue_body_sha256,base_sha,policy_version,assignment_version,
                profile_id,profile_version,adapter_id,manifest_version,workspace,planned_workflow_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                reviewId, context[9].toLong(), context[10].toInt(), context[0], context[1], context[2].toInt(), context[3].toInt(), context[4], context[5].toLong(), context[6], context[7].toLong(), verified.path("directory").asText(), workflow[1].toLong() + 1)
            c.mutate("""INSERT INTO review_inputs(attempt_id,workflow_id,generation,candidate_id,manifest_sha256,candidate_tree,candidate_directory,coding_session_id)
                VALUES (?,?,?,?,?,?,?,?)""",
                reviewId, workflow[0], workflow[2].toInt(), verified.path("candidateId").asText(), verified.path("manifestSha256").asText(), verified.path("candidateTree").asText(), verified.path("directory").asText(), verified.path("codingSessionId").asText())
            c.mutate("""INSERT INTO cli_stage_runs(attempt_id,workflow_id,adapter,runtime,auth,model,effort,state,recorded_at) VALUES (?,?,?,?,?,?,?,'LAUNCHING',?)""",
                reviewId, workflow[0], "cursor-cli", "cli", "subscription", "account-default", "DEFAULT", "t")
            c.mutate("INSERT INTO workflow_events(workflow_id,event_type,detail,recorded_at) VALUES (?, 'REVIEW_PROCESS_STARTED', ?, 't')", workflow[0], started)
        }
        val progress = world.runtime.step(world.issue)
        assertEquals("review", progress.stage, progress.reason)
        assertEquals(1, Files.readAllLines(log).size)
        assertEquals("FINISHED", db.read { c -> c.rows("SELECT state FROM cli_stage_runs WHERE attempt_id=?", reviewId) { it.getString(1) }.single() })
        world.runtime.recover(world.issue)
        assertEquals("SUCCEEDED", db.read { c -> c.rows("SELECT state FROM agent_attempts WHERE attempt_id=?", reviewId) { it.getString(1) }.single() })
        assertEquals(1, db.read { c -> c.rows("SELECT 1 FROM agent_attempts WHERE role='REVIEW'") { 1 }.size })
        assertEquals("READY_FOR_PR", db.read { c -> c.rows("SELECT phase FROM workflows") { it.getString(1) }.single() })
    }

    @Test fun `recover inside an open write holds an unfinished coding start and does not launch review`() {
        val cli = fixture()
        val world = runtime(cli, null)
        world.runtime.prepare(world.issue)
        val db = RuntimeDatabase(world.task.resolve("run.db"))
        db.write { c -> c.mutate("UPDATE agent_attempts SET state='STARTING' WHERE role='CODING'") }
        world.runtime.recover(world.issue)
        assertEquals(listOf("CODING" to "UNKNOWN"), db.read { c ->
            c.rows("SELECT role, state FROM agent_attempts ORDER BY role") { it.getString(1) to it.getString(2) }
        })
        assertEquals("UNRECONCILED_CODING_START", db.read { c -> c.rows("SELECT hold_reason FROM workflows") { it.getString(1) }.single() })
    }

    @Test fun `a review without a verdict holds and does not spawn another reviewer`() {
        val cli = fixture("noverdict")
        val log = temp.resolve("agent.log")
        val world = runtime(cli, null)
        world.runtime.prepare(world.issue)
        assertEquals("tests", world.runtime.step(world.issue).stage)
        assertEquals("review", world.runtime.step(world.issue).stage)
        val held = world.runtime.step(world.issue)
        assertEquals("held", held.stage)
        assertEquals("CLI_VERDICT_MISSING", held.reason)
        assertEquals(2, Files.readAllLines(log).size)
        world.runtime.step(world.issue)
        assertEquals(2, Files.readAllLines(log).size)
    }

    @Test fun `exit zero with a live child finishes coding after the child is reaped`() {
        val cli = fixture("child")
        val world = runtime(cli, null)
        world.runtime.prepare(world.issue)
        val progress = world.runtime.step(world.issue)
        assertEquals("tests", progress.stage)
        val pid = Files.readString(temp.resolve("sleeper.pid")).trim().toLong()
        val alive = Path.of("/proc/$pid")
        if (Files.exists(alive)) assertFalse(Files.readString(alive.resolve("cmdline")).contains(cli.fileName.toString()))
        val receipt = Files.walk(world.task.resolve("artifacts")).use { stream ->
            stream.filter { it.fileName.toString() == "supervisor-receipt.json" }.findFirst().orElseThrow()
        }
        val node = ObjectMapper().readTree(Files.readString(receipt))
        assertEquals(0, node.path("exit_code").asInt())
        assertEquals("exited", node.path("cause").asText())
        assertTrue(node.path("cleanup_echild").asBoolean())
        assertTrue(node.path("reaped").asInt() >= 2)
        val state = RuntimeDatabase(world.task.resolve("run.db")).read { c ->
            c.rows("SELECT state FROM cli_stage_runs") { it.getString(1) }.single()
        }
        assertEquals("FINISHED", state)
    }

    @Test fun `a non-zero exit or is_error does not finish the stage`() {
        assertFalse(CursorCliWorkflowBridge.cursorCliSucceeded("""{"type":"result","subtype":"success","is_error":true}"""))
        assertFalse(CursorCliWorkflowBridge.cursorCliSucceeded("""{"type":"result","subtype":"error","is_error":false}"""))
        assertTrue(CursorCliWorkflowBridge.cursorCliSucceeded("""{"type":"result","subtype":"success","is_error":false}"""))
        for (kind in listOf("error", "nonzero")) {
            val cli = fixture(kind)
            val log = temp.resolve("agent.log")
            val world = runtime(cli, null)
            world.runtime.prepare(world.issue)
            val progress = world.runtime.step(world.issue)
            assertEquals("held", progress.stage, kind)
            assertEquals("CODING_RUNNER_FAILED", progress.reason, kind)
            assertEquals(1, if (Files.exists(log)) Files.readAllLines(log).size else 0, kind)
            world.runtime.step(world.issue)
            assertEquals(1, Files.readAllLines(log).size, kind)
            Files.delete(log)
        }
    }

    @Test fun `a missing CLI fails the stage and does not fall through`() {
        val missing = temp.resolve("missing-agent")
        val calls = AtomicInteger()
        val world = runtime(missing, { _, _, _, _, _ ->
            calls.incrementAndGet()
            ProcessObservation(AttemptState.UNKNOWN, "SHOULD_NOT_RUN")
        })
        world.runtime.prepare(world.issue)
        val progress = world.runtime.step(world.issue)
        assertEquals("held", progress.stage)
        assertEquals("CLI_MISSING", progress.reason)
        assertEquals(0, calls.get())
        world.runtime.step(world.issue)
        assertEquals(0, calls.get())
    }

    @Test fun `an unknown CLI result is not spawned again`() {
        val calls = AtomicInteger()
        val cli = temp.resolve("agent")
        Files.writeString(cli, "#!/bin/sh\nexit 0\n")
        cli.toFile().setExecutable(true)
        val world = runtime(cli, { _, _, _, _, _ ->
            calls.incrementAndGet()
            ProcessObservation(AttemptState.UNKNOWN, "LOST")
        })
        world.runtime.prepare(world.issue)
        assertEquals("held", world.runtime.step(world.issue).stage)
        assertEquals(1, calls.get())
        clearHold(world)
        world.runtime.step(world.issue)
        assertEquals(1, calls.get())
    }

    private data class World(val task: Path, val runtime: PersonalRuntime, val issue: GitHubIssueRecord)

    private fun runtime(cli: Path, run: ((List<String>, Path, ByteArray, Long, (Map<String, String>) -> Boolean) -> ProcessObservation)?): World {
        val root = Files.createDirectory(temp.resolve("state-${cli.fileName}"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val home = Files.createDirectory(temp.resolve("home-${cli.fileName}"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val template = Files.createDirectory(temp.resolve("template-${cli.fileName}"))
        val seed = Files.createDirectory(temp.resolve("seed-${cli.fileName}"))
        val bare = temp.resolve("bare-${cli.fileName}.git")
        Files.writeString(seed.resolve("README"), "Base\n")
        git(seed, "init", "-q", "--initial-branch=main", "--template=$template")
        git(seed, "add", "README")
        git(seed, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "base")
        val base = git(seed, "rev-parse", "HEAD")
        git(temp, "init", "--bare", "--template=$template", bare.toString())
        git(seed, "push", bare.toString(), "HEAD:refs/heads/main")
        val config = PersonalConfig(true, root, home, Path.of("/unused/cursor-cli"), "account-default", 1, 10809, 60, "cursor-cli", null, null, "default", cli)
        val issue = GitHubIssueRecord(1424152600, 8, 9001, Instant.parse("2026-10-22T00:00:00Z"), "Add clamp", "Add clamp.", base, true)
        val http = PublicationHttp { _, _, _ -> error("NO_CLOUD") }
        val push = PublicationPush { _, _, _ -> error("NO_PUSH") }
        val runtime = PersonalRuntime(config, "test-token", { true }, http, push,
            fetch = { record, checkout, _ -> git(checkout, "fetch", "--no-tags", bare.toString(), record.baseSha) },
            cliOverride = CursorCliSettings(cli, home, run),
        )
        return World(root.resolve("task-9001"), runtime, issue)
    }

    private fun fixture(kind: String = "success"): Path {
        val cli = temp.resolve(if (kind == "success") "agent" else "agent-$kind")
        val body = when (kind) {
            "child" -> """
import os, time
pid = os.fork()
if pid == 0:
    time.sleep(30)
    os._exit(0)
pathlib.Path(__file__).with_name("sleeper.pid").write_text(str(pid))
print('{"type":"result","subtype":"success","is_error":false}')
"""
            "error" -> """
print('{"type":"result","subtype":"success","is_error":true}')
"""
            "nonzero" -> """
print('{"type":"result","subtype":"success","is_error":false}')
raise SystemExit(2)
"""
            "nested", "noverdict" -> """
if "Review the saved" in prompt:
    start = prompt.rfind('{"verdict"')
    verdict = prompt[start:].strip()
    import json
    text = verdict if ${if (kind == "nested") "True" else "False"} else "I looked and found no structured verdict."
    print(json.dumps({"type":"result","subtype":"success","is_error":False,"result": text}))
    raise SystemExit(0)
pathlib.Path("clamp.py").write_text("def clamp(value, low, high):\n    return min(high, max(low, value))\n")
pathlib.Path("tests").mkdir(exist_ok=True)
pathlib.Path("tests/test_clamp.py").write_text("import unittest\nfrom clamp import clamp\nclass T(unittest.TestCase):\n    def test_ok(self):\n        self.assertEqual(1, clamp(1, 0, 2))\n")
print('{"type":"result","subtype":"success","is_error":false}')
"""
            else -> """
if "Review the saved" in prompt:
    start = prompt.rfind('{"verdict"')
    print(prompt[start:].strip())
    print('{"type":"result","subtype":"success","is_error":false}')
    raise SystemExit(0)
pathlib.Path("clamp.py").write_text("def clamp(value, low, high):\n    return min(high, max(low, value))\n")
pathlib.Path("tests").mkdir(exist_ok=True)
pathlib.Path("tests/test_clamp.py").write_text("import unittest\nfrom clamp import clamp\nclass T(unittest.TestCase):\n    def test_ok(self):\n        self.assertEqual(1, clamp(1, 0, 2))\n")
print('{"type":"result","subtype":"success","is_error":false}')
"""
        }
        Files.writeString(cli, """#!/usr/bin/env python3
import pathlib, sys
prompt = sys.argv[-1]
pathlib.Path(__file__).with_name("agent.log").open("a").write(" ".join(sys.argv[:-1]) + "\n")
${body.trimIndent()}
""")
        cli.toFile().setExecutable(true)
        return cli
    }

    private fun clearHold(world: World) {
        java.sql.DriverManager.getConnection("jdbc:sqlite:${world.task.resolve("run.db")}").use { connection ->
            connection.createStatement().executeUpdate("UPDATE workflows SET hold='NONE', hold_reason=NULL")
        }
    }

    private fun git(cwd: Path, vararg args: String): String {
        val builder = ProcessBuilder(listOf("/usr/bin/git", "-c", "core.hooksPath=/dev/null") + args).directory(cwd.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to temp.toString(), "GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null"))
        val process = builder.start()
        process.outputStream.close()
        val out = process.inputStream.readNBytes(100_000).toString(Charsets.UTF_8)
        check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { out }
        return out.trim()
    }
}
