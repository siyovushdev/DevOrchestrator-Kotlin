package dev.orchestrator.profile

import dev.orchestrator.agent.nativecli.ProcessObservation
import dev.orchestrator.publish.PublicationHttp
import dev.orchestrator.publish.PublicationPush
import dev.orchestrator.service.PersonalConfig
import dev.orchestrator.service.PersonalRuntime
import dev.orchestrator.storage.AttemptState
import dev.orchestrator.storage.GitHubIssueRecord
import dev.orchestrator.storage.RuntimeDatabase
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
