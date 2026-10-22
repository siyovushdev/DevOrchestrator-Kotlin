package dev.orchestrator.agent.nativecli

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.remote.RemoteProtocol
import dev.orchestrator.remote.RemoteRequest
import dev.orchestrator.remote.SshWorkerConfig
import dev.orchestrator.remote.SshWorkerTransport
import dev.orchestrator.storage.RuntimeDatabase
import dev.orchestrator.storage.mutate
import dev.orchestrator.storage.rows
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** ELF Codex binary plus a provider usage record. Two JSON lines from a script are not enough. */
internal object CodexAttestation {
    private val mapper = ObjectMapper()
    fun nativeBinary(path: Path): Boolean = try {
        val head = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(4) }
        head.size == 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() && Files.size(path) >= 1_000_000
    } catch (_: Exception) { false }

    fun providerTurn(events: String): Boolean = try {
        val lines = events.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.map { mapper.readTree(it) }.toList()
        val usage = lines.single { it.path("type").asText() == "turn.completed" }.path("usage")
        usage.path("input_tokens").isIntegralNumber && usage.path("input_tokens").asInt() > 0 &&
            usage.path("output_tokens").isIntegralNumber
    } catch (_: Exception) { false }
}

/**
 * No-inference probe. The artificial file is not a controller or GitHub secret.
 * A stand-in that exits 0 without running the probe does not create the marker.
 */
internal object LocalSecretCanary {
    const val SECRET = "ARTIFICIAL_POLICY_SECRET"
    fun pass(codex: Path, workspace: Path, home: Path, artifacts: Path, python: Path): Boolean {
        val canary = home.resolve(".codex").resolve("devorch-policy-canary")
        if (!Files.exists(canary)) Files.writeString(canary, SECRET)
        if (Files.readString(canary) != SECRET) return false
        val run = Files.createDirectories(artifacts.resolve("canary-${System.nanoTime()}"))
        val marker = run.resolve("canary-pass")
        val script = "from pathlib import Path\n" +
            "secret=Path(" + ObjectMapper().writeValueAsString(canary.toString()) + ")\n" +
            "try:\n secret.read_bytes()\nexcept OSError:\n pass\nelse:\n raise SystemExit(91)\n" +
            "Path(" + ObjectMapper().writeValueAsString(marker.toString()) + ").write_text('ok')\n"
        val observed = NativeProcessRunner(python).run(listOf(codex.toString(), "sandbox", "--ignore-user-config",
            "--ignore-rules", "--strict-config", "--", python.toString(), "-I", "-S", "-c", script),
            workspace, mapOf("HOME" to home.toString(), "CODEX_HOME" to home.resolve(".codex").toString(),
                "PATH" to "/usr/bin:/bin", "LANG" to "C"), run, ByteArray(0), 15_000) { true }
        return observed.state == dev.orchestrator.storage.AttemptState.SUCCEEDED &&
            Files.readString(canary) == SECRET && Files.isRegularFile(marker)
    }
}

/** Signals a supervisor the controller started, only when pid, start ticks and boot id still match. */
internal object LocalProcessControl {
    private val mapper = ObjectMapper()
    fun stopOrphans(database: Path) {
        if (!Files.isRegularFile(database)) return
        val details = try {
            RuntimeDatabase(database).read { c ->
                if (c.rows("SELECT 1 FROM sqlite_master WHERE name='workflow_events'") { 1 }.isEmpty()) emptyList()
                else c.rows("""SELECT detail FROM workflow_events WHERE event_type IN
                    ('CODING_PROCESS_STARTED','REVIEW_PROCESS_STARTED','VERIFICATION_PROCESS_STARTED')
                    ORDER BY event_id DESC LIMIT 8""") { it.getString(1) }
            }
        } catch (_: Exception) { return }
        val boot = try { Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim() } catch (_: Exception) { return }
        for (detail in details) {
            val node = try { mapper.readTree(detail) } catch (_: Exception) { continue }
            val identity = if (node.path("identity").isObject) node.path("identity") else node
            val pid = identity.path("pid").asLong(0)
            val ticks = identity.path("start_ticks").asText()
            if (pid <= 1 || ticks.isBlank() || identity.path("boot_id").asText() != boot) continue
            if (sameProcess(pid, ticks)) ProcessHandle.of(pid).ifPresent { it.destroy() }
        }
    }

    fun cancelGuests(database: Path, ssh: SshWorkerConfig) {
        if (!Files.isRegularFile(database)) return
        val db = RuntimeDatabase(database)
        db.write { c -> c.mutate("""CREATE TABLE IF NOT EXISTS remote_import_blocks(
            attempt_id TEXT PRIMARY KEY, reason TEXT NOT NULL)""") }
        val saved = try {
            db.read { c ->
                if (c.rows("SELECT 1 FROM sqlite_master WHERE name='remote_attempts'") { 1 }.isEmpty()) emptyList()
                else c.rows("SELECT attempt_id,request FROM remote_attempts WHERE state IN ('SENDING','RUNNING','UNKNOWN','EVIDENCE_STORED')") {
                    it.getString(1) to it.getBytes(2)
                }
            }
        } catch (_: Exception) { return }
        val transport = SshWorkerTransport(ssh)
        for ((id, request) in saved) {
            db.write { c -> c.mutate("INSERT OR IGNORE INTO remote_import_blocks(attempt_id,reason) VALUES(?,'OWNER_CANCEL')", id) }
            try {
                val remote = RemoteRequest(request)
                val wire = RemoteProtocol.mapper.writeValueAsBytes(mapOf("op" to "cancel", "attemptId" to remote.attemptId,
                    "requestSha256" to remote.sha256))
                transport.exchange(wire, 15_000)
            } catch (_: Exception) { /* The import block still forbids adopting a late result. */ }
        }
    }

    private fun sameProcess(pid: Long, ticks: String): Boolean = try {
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        stat.substringAfterLast(')').trim().split(Regex("\\s+")).getOrNull(19) == ticks
    } catch (_: Exception) { false }
}
