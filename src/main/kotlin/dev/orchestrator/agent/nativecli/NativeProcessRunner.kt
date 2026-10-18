package dev.orchestrator.agent.nativecli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.storage.AttemptState
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only the local supervised process observer constructs these facts; agent text is not proof. */
internal data class ProcessObservation(val state: AttemptState, val reason: String, val evidence: JsonNode? = null)

/** Shared Linux subreaper, bounded streams and birth/terminal/cleanup verification. */
internal class NativeProcessRunner(private val python: Path, private val outputLimit: Int = 1_048_576) {
    private val mapper = ObjectMapper()

    fun run(argv: List<String>, workspace: Path, environment: Map<String,String>, artifacts: Path,
            prompt: ByteArray, limitMs: Long, lastMessage: Path? = null,
            privateEnvironment: Map<String,String> = emptyMap(),
            onStarted: (Map<String,String>) -> Boolean): ProcessObservation {
        var interrupted = false
        var process: Process? = null
        try {
            val helper = artifacts.resolve("supervisor.py")
            javaClass.getResourceAsStream("/native-codex-supervisor.py")!!.use { Files.copy(it,helper) }
            val config = artifacts.resolve("launch.json")
            val helperReceipt = artifacts.resolve("supervisor-receipt.json")
            Files.writeString(config,mapper.writeValueAsString(mapOf("argv" to argv,
                "cwd" to workspace.toString(),"env" to environment,"timeout_ms" to limitMs,
                "private_env_names" to privateEnvironment.keys.toList(),
                "receipt" to helperReceipt.toString())),CREATE_NEW,WRITE)
            val builder = ProcessBuilder(python.toRealPath().toString(),"-I","-S",helper.toString(),config.toString())
                .directory(artifacts.toFile()).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment().putAll(mapOf("HOME" to artifacts.toString(),"PATH" to "/usr/bin:/bin","LANG" to "C"))
            // Publisher credentials travel in process memory/env, never launch.json/logs.
            require(privateEnvironment.keys.all { it == "DEVORCH_PUBLISH_TOKEN" })
            builder.environment().putAll(privateEnvironment)
            process = builder.start()
            val proc = process
            val birth = processIdentity(proc.pid())
            if (!onStarted(birth)) {
                proc.toHandle().destroy()
                proc.waitFor(8,TimeUnit.SECONDS)
                return ProcessObservation(AttemptState.UNKNOWN,"RUNNING_RECEIPT_REJECTED")
            }
            val oversized = AtomicBoolean(false)
            val readFailed = AtomicBoolean(false)
            val inputFailed = AtomicBoolean(false)
            val drain = Thread({
                try {
                    Files.newOutputStream(artifacts.resolve("events.log"),CREATE_NEW,WRITE).use { output ->
                        proc.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            var captured = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count<0) break
                                val kept = minOf(count,outputLimit-captured)
                                output.write(buffer,0,kept)
                                captured += kept
                                if (kept<count && oversized.compareAndSet(false,true)) proc.toHandle().destroy()
                            }
                        }
                    }
                } catch (_: Exception) { readFailed.set(true); proc.toHandle().destroy() }
            },"devorch-native-output").apply { isDaemon=true; start() }
            val writer = Thread({
                try { proc.outputStream.use { it.write(prompt) } }
                catch (_: Exception) { inputFailed.set(true) }
            },"devorch-native-input").apply { isDaemon=true; start() }
            val deadline = System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(limitMs+10_000)
            var stopped = false
            var ended = false
            while (System.nanoTime()<deadline) {
                try { if (proc.waitFor(50,TimeUnit.MILLISECONDS)) { ended=true; break } }
                catch (_: InterruptedException) { interrupted=true; proc.toHandle().destroy(); stopped=true }
                if (interrupted && stopped) break
                if (lastMessage != null && Files.exists(lastMessage,NOFOLLOW_LINKS) && Files.size(lastMessage)>outputLimit) {
                    oversized.set(true); proc.toHandle().destroy()
                }
            }
            if (!ended) {
                proc.toHandle().destroy()
                ended = proc.waitFor(8,TimeUnit.SECONDS)
                if (!ended) { proc.destroyForcibly(); proc.waitFor(2,TimeUnit.SECONDS) }
            }
            drain.join(2000); writer.join(2000)
            if (!ended || drain.isAlive || writer.isAlive || readFailed.get())
                return ProcessObservation(AttemptState.UNKNOWN,"PROCESS_OR_STREAM_OUTCOME_UNKNOWN")
            val observed = mapper.readTree(boundedFile(helperReceipt,8192))
            val clean = observed.path("cleanup_echild").asBoolean(false) &&
                observed.path("supervisor").path("pid").asLong()==proc.pid() &&
                observed.path("supervisor").path("start_ticks").asText()==birth["start_ticks"] &&
                observed.path("boot_id").asText()==birth["boot_id"] &&
                observed.path("machine_id").asText()==birth["machine_id"] &&
                observed.path("child").path("pid").asLong()>0 &&
                observed.path("child").path("start_ticks").asText().isNotEmpty() &&
                observed.path("exit_code").isInt && proc.exitValue()==0
            if (!clean) return ProcessObservation(AttemptState.UNKNOWN,"CLEANUP_NOT_PROVEN",observed)
            if (interrupted || oversized.get() || inputFailed.get() || observed["exit_code"].asInt()!=0 ||
                observed.path("cause").asText()!="exited")
                return ProcessObservation(AttemptState.FAILED,when {
                    interrupted -> "INTERRUPTED"; oversized.get() -> "OUTPUT_LIMIT"
                    inputFailed.get() -> "INPUT_FAILED"
                    else -> observed.path("cause").asText()+"_exit_"+observed["exit_code"].asInt()
                },observed)
            return ProcessObservation(AttemptState.SUCCEEDED,"EXITED_WITH_CLEANUP",observed)
        } catch (_: Exception) {
            process?.destroy()
            try { process?.waitFor(8,TimeUnit.SECONDS) } catch (_: InterruptedException) { interrupted=true }
            return ProcessObservation(AttemptState.UNKNOWN,"RUNNER_EVIDENCE_INCOMPLETE")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun boundedFile(path: Path, limit: Int): ByteArray {
        require(Files.isRegularFile(path,NOFOLLOW_LINKS) &&
            (Files.getAttribute(path,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt()==1 && Files.size(path)<=limit)
        return Files.newInputStream(path,NOFOLLOW_LINKS).use { it.readNBytes(limit+1) }.also { check(it.size<=limit) }
    }

    private fun processIdentity(pid: Long): Map<String,String> {
        val fields = Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(')').trim().split(Regex("\\s+"))
        return mapOf("pid" to pid.toString(),"start_ticks" to fields[19],
            "boot_id" to Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim(),
            "machine_id" to Files.readString(Path.of("/etc/machine-id")).trim())
    }

}
