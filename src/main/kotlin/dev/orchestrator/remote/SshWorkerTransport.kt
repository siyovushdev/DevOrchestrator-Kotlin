package dev.orchestrator.remote

import dev.orchestrator.agent.StageBudget

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
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

    /** Streams the configured binary and hashes it in this process. The guest does not report the digest. */
    fun measure(codex: Path): MeasuredBinary? {
        val process=WorkerWireProcess.start(arguments().dropLast(1)+remoteCodexMeasureCommand(codex))
        return try {
            val error=Thread({ process.errorStream.use { stream -> val buffer=ByteArray(8192); var total=0
                while(true) { val n=stream.read(buffer); if(n<0) break; total+=n; if(total>1_048_576) break } } },"ssh-codex-measure").apply { isDaemon=true; start() }
            val measured=measureStream(process.inputStream)
            val finished=process.waitFor(60,TimeUnit.SECONDS) && process.exitValue()==0
            error.join(2_000)
            if(finished) measured else null
        } catch(_: Exception) { null }
        finally {
            if(process.isAlive) process.destroyForcibly()
            process.waitFor(2,TimeUnit.SECONDS)
        }
    }
}

/** Controller-side measurement of a Codex binary. elf, size and sha256 are computed here. */
internal data class MeasuredBinary(val elf: Boolean, val size: Long, val sha256: String) {
    val attested: Boolean get() = elf && size >= 1_000_000L && sha256.matches(Regex("[0-9a-f]{64}"))
}

internal fun measureStream(input: InputStream, limit: Long = 512L * 1024 * 1024): MeasuredBinary {
    val digest=MessageDigest.getInstance("SHA-256")
    val buf=ByteArray(1024 * 1024)
    val header=ByteArray(4)
    var headerN=0
    var size=0L
    while(true) {
        val n=input.read(buf)
        if(n<0) break
        if(headerN<4) {
            val take=minOf(4-headerN, n)
            buf.copyInto(header, headerN, 0, take)
            headerN+=take
        }
        digest.update(buf, 0, n)
        size+=n
        if(size>limit) throw IllegalStateException("CODEX_BINARY_LIMIT")
    }
    val elf=headerN==4 && header[0]==0x7f.toByte() && header[1]=='E'.code.toByte() &&
        header[2]=='L'.code.toByte() && header[3]=='F'.code.toByte()
    val sha=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    return MeasuredBinary(elf, size, sha)
}

/** Remote command streams file bytes. It is not the guest worker and it does not print a hash. */
internal fun remoteCodexMeasureCommand(codex: Path): String {
    val text=codex.toString()
    require(codex.isAbsolute && text.matches(Regex("/[A-Za-z0-9._+/-]{1,400}"))) { "CODEX_PATH" }
    return "exec /usr/bin/python3 -I -S -c 'import sys,pathlib\n" +
        "with pathlib.Path(sys.argv[1]).open(\"rb\") as src:\n" +
        "  while True:\n" +
        "    block=src.read(1048576)\n" +
        "    if not block:\n" +
        "      break\n" +
        "    sys.stdout.buffer.write(block)\n' '$text'"
}

/** Also used by offline fake-SSH tests; production has no configurable executable/command. */
internal object WorkerWireProcess {
    /** SIGKILL this process when its parent dies. exec keeps the signal, so ssh dies with the JVM. */
    internal const val PARENT_DEATH = "import ctypes,os,signal,sys\n" +
        "libc=ctypes.CDLL(None, use_errno=True)\n" +
        "if libc.prctl(1, signal.SIGKILL, 0, 0, 0) != 0:\n" +
        "    raise SystemExit(1)\n" +
        "if os.getppid() == 1:\n" +
        "    os.kill(os.getpid(), signal.SIGKILL)\n" +
        "os.execv(sys.argv[1], sys.argv[1:])\n"

    fun argv(command: List<String>): List<String> =
        listOf("/usr/bin/python3", "-I", "-S", "-c", PARENT_DEATH) + command

    fun start(command: List<String>): Process = ProcessBuilder(argv(command)).apply {
        environment().clear(); environment().putAll(mapOf("PATH" to "/usr/bin:/bin","LANG" to "C.UTF-8","HOME" to "/nonexistent"))
    }.start()

    fun exchange(command: List<String>, wire: ByteArray, timeoutMs: Long): ByteArray {
        require(wire.size<=2*RemoteProtocol.LIMIT && timeoutMs in 1..StageBudget.MAX_TRANSPORT_MILLIS)
        val process=start(command)
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
