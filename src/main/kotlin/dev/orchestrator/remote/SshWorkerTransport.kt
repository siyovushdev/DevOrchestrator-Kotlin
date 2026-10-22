package dev.orchestrator.remote

import dev.orchestrator.agent.StageBudget

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A fixed PERSONAL endpoint. Config never comes from Issue text or model output. */
internal data class SshWorkerConfig(val identityFile: Path, val knownHosts: Path) {
    val peer: String get()="vboxuser@127.0.0.1:2222:"+RemoteProtocol.digest(RemoteProtocol.file(knownHosts,65536))+":"+identityFile
    init {
        for(p in listOf(identityFile,knownHosts)) {
            require(p.isAbsolute && p.toRealPath()==p && Files.isRegularFile(p,NOFOLLOW_LINKS))
            require(Files.getOwner(p).name==System.getProperty("user.name"))
            require((Files.getAttribute(p,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt()==1)
            require(Files.getPosixFilePermissions(p).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") })
        }
    }
}

internal fun interface WorkerTransport {
    /** One bounded request, no retries. STDERR is never protocol or owner diagnostics. */
    fun exchange(wire: ByteArray, timeoutMs: Long): ByteArray
}

internal class SshWorkerTransport(private val config: SshWorkerConfig): WorkerTransport {
    companion object {
        const val COMMAND="exec /usr/bin/python3 -I -S /home/vboxuser/.local/lib/devorch-worker/ssh-vm-worker.py serve /home/vboxuser/.config/devorch-worker.json"
    }
    internal fun arguments(): List<String> = listOf("/usr/bin/ssh","-F","/dev/null","-T","-a","-x",
        "-o","BatchMode=yes","-o","IdentitiesOnly=yes","-o","IdentityAgent=none",
        "-o","StrictHostKeyChecking=yes","-o","UserKnownHostsFile=${config.knownHosts}",
        "-o","GlobalKnownHostsFile=/dev/null","-o","ForwardAgent=no","-o","ClearAllForwardings=yes",
        "-o","PermitLocalCommand=no","-o","ProxyCommand=none","-o","ProxyJump=none",
        "-o","ControlMaster=no","-o","ControlPath=none","-o","ConnectTimeout=5",
        "-o","ServerAliveInterval=5","-o","ServerAliveCountMax=2","-i",config.identityFile.toString(),
        "-p","2222","vboxuser@127.0.0.1",COMMAND)

    override fun exchange(wire: ByteArray, timeoutMs: Long): ByteArray =
        WorkerWireProcess.exchange(arguments(),wire,timeoutMs)
}

/** Also used by offline fake-SSH tests; production has no configurable executable/command. */
internal object WorkerWireProcess {
    fun exchange(command: List<String>, wire: ByteArray, timeoutMs: Long): ByteArray {
        require(wire.size<=2*RemoteProtocol.LIMIT && timeoutMs in 1..StageBudget.MAX_TRANSPORT_MILLIS)
        val process=ProcessBuilder(command).apply {
            environment().clear(); environment().putAll(mapOf("PATH" to "/usr/bin:/bin","LANG" to "C.UTF-8","HOME" to "/nonexistent"))
        }.start()
        fun <T> async(action: () -> T): CompletableFuture<T> {
            val future=CompletableFuture<T>()
            Thread({ try { future.complete(action()) } catch(e: Exception) { future.completeExceptionally(e) } },"ssh-worker-io")
                .apply { isDaemon=true; start() }
            return future
        }
        val output=async { process.inputStream.use { it.readNBytes(RemoteProtocol.LIMIT+1) }.also {
            check(it.size<=RemoteProtocol.LIMIT) { "REMOTE_RESPONSE_LIMIT" }
        } }
        val error=async { process.errorStream.use { stream ->
            val buffer=ByteArray(8192); var total=0
            while(true) { val size=stream.read(buffer); if(size<0) break; total+=size; check(total<=1_048_576) { "REMOTE_STDERR_LIMIT" } }
        } }
        val input=async { process.outputStream.use { it.write(wire); it.write('\n'.code); it.flush() } }
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        fun remaining()=(deadline-System.nanoTime()).coerceAtLeast(1)
        try {
            input.get(remaining(),TimeUnit.NANOSECONDS)
            val bytes=output.get(remaining(),TimeUnit.NANOSECONDS)
            error.get(remaining(),TimeUnit.NANOSECONDS)
            check(process.waitFor(remaining(),TimeUnit.NANOSECONDS) && process.exitValue()==0) { "SSH_EXCHANGE_FAILED" }
            check(bytes.isNotEmpty()) { "REMOTE_RESPONSE_MISSING" }
            return bytes
        } finally {
            if(process.isAlive) process.destroyForcibly()
            process.outputStream.close(); process.inputStream.close(); process.errorStream.close()
            process.waitFor(2,TimeUnit.SECONDS)
        }
    }
}
