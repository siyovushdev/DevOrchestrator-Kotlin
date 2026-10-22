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
internal data class SshWorkerConfig(val identityFile: Path, val knownHosts: Path, val codexBinary: Path? = null) {
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
    private val live=java.util.concurrent.atomic.AtomicReference<LiveExecution?>(null)
    /** Measurement taken from `/proc/pid/exe` during the submit, or null if that process was not seen alive. */
    fun liveExecution(): LiveExecution?=live.get()
    internal fun arguments(): List<String> = listOf("/usr/bin/ssh","-F","/dev/null","-T","-a","-x",
        "-o","BatchMode=yes","-o","IdentitiesOnly=yes","-o","IdentityAgent=none",
        "-o","StrictHostKeyChecking=yes","-o","UserKnownHostsFile=${config.knownHosts}",
        "-o","GlobalKnownHostsFile=/dev/null","-o","ForwardAgent=no","-o","ClearAllForwardings=yes",
        "-o","PermitLocalCommand=no","-o","ProxyCommand=none","-o","ProxyJump=none",
        "-o","ControlMaster=no","-o","ControlPath=none","-o","ConnectTimeout=5",
        "-o","ServerAliveInterval=5","-o","ServerAliveCountMax=2","-i",config.identityFile.toString(),
        "-p","2222","vboxuser@127.0.0.1",COMMAND)

    override fun exchange(wire: ByteArray, timeoutMs: Long): ByteArray {
        val submit=try { RemoteProtocol.mapper.readTree(wire).path("op").asText()=="submit" } catch(_: Exception) { false }
        val codex=config.codexBinary
        val observer=if(submit && codex!=null) {
            live.set(null)
            try { WorkerWireProcess.start(arguments().dropLast(1)+liveExeCommand(codex)) } catch(_: Exception) { null }
        } else null
        val reader=observer?.let { process -> Thread({
            try {
                val input=process.inputStream
                if(readAsciiLine(input)!="MATCH") return@Thread
                val path=readAsciiLine(input)
                val measured=measureStream(input)
                if(path.isNotEmpty()) live.set(LiveExecution(path, measured, true))
            } catch(_: Exception) { }
        },"ssh-live-exe").apply { isDaemon=true; start() } }
        try {
            return WorkerWireProcess.exchange(arguments(),wire,timeoutMs)
        } finally {
            if(observer!=null && observer.isAlive) observer.destroyForcibly()
            reader?.join(2_000)
            observer?.waitFor(2,TimeUnit.SECONDS)
        }
    }

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
internal data class MeasuredBinary(val elf: Boolean, val size: Long, val sha256: String)

/**
 * Bytes read from `/proc/pid/exe` while that process was alive. `matchesConfigured`
 * is true only when that mapped file is the operator's codex path. argv0 is not consulted.
 * Without an operator sha256 pin, ELF and size are the only content checks.
 */
internal data class LiveExecution(val exePath: String, val measured: MeasuredBinary, val matchesConfigured: Boolean)

internal fun codexAttestationOk(live: LiveExecution?, configuredPath: String, expectedSha256: String?): Boolean =
    live!=null && live.matchesConfigured && configuredPath.isNotEmpty() &&
        live.measured.elf && live.measured.size>=1_000_000L && live.measured.sha256.matches(Regex("[0-9a-f]{64}")) &&
        (expectedSha256.isNullOrBlank() || live.measured.sha256==expectedSha256)

/** Walk descendants and hash `/proc/pid/exe` of the live process whose file is `configured`. */
internal fun attestLiveDescendant(rootPid: Long, configured: Path): LiveExecution? {
    val expected=try { configured.toRealPath() } catch(_: Exception) { return null }
    val pending=ArrayDeque<Long>()
    val seen=HashSet<Long>()
    pending.add(rootPid)
    while(pending.isNotEmpty()) {
        val pid=pending.removeFirst()
        if(!seen.add(pid) || pid<=1) continue
        procChildren(pid).forEach { pending.add(it) }
        if(pid==rootPid) continue
        val live=readProcExe(pid) ?: continue
        val exe=Path.of(live.first.removeSuffix(" (deleted)"))
        val real=try { exe.toRealPath() } catch(_: Exception) { continue }
        if(real==expected) return LiveExecution(live.first, live.second, true)
    }
    return null
}

private fun procChildren(pid: Long): List<Long> = try {
    Files.readString(Path.of("/proc/$pid/task/$pid/children")).trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }.map { it.toLong() }
} catch(_: Exception) { emptyList() }

private fun readProcExe(pid: Long): Pair<String, MeasuredBinary>? {
    val stat=Path.of("/proc/$pid/stat")
    if(!Files.isRegularFile(stat)) return null
    val state=try { Files.readString(stat).substringAfterLast(')').trim().split(Regex("\\s+")).firstOrNull() } catch(_: Exception) { null }
    if(state==null || state=="Z" || state=="X") return null
    val link=Path.of("/proc/$pid/exe")
    val path=try { Files.readSymbolicLink(link).toString() } catch(_: Exception) { return null }
    val measured=try { Files.newInputStream(link).use { measureStream(it) } } catch(_: Exception) { return null }
    return path to measured
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

private fun readAsciiLine(input: InputStream, limit: Int = 1024): String {
    val buf=java.io.ByteArrayOutputStream()
    while(buf.size()<limit) {
        val b=input.read()
        if(b<0 || b=='\n'.code) break
        buf.write(b)
    }
    return buf.toString(Charsets.UTF_8)
}

/**
 * One SSH session polls /proc until a live descendant of the worker is the configured
 * binary, then streams `/proc/pid/exe`. It does not run the guest worker and does not
 * open the path again after the process has gone.
 */
internal fun liveExeCommand(codex: Path): String {
    val text=codex.toString()
    require(codex.isAbsolute && text.matches(Regex("/[A-Za-z0-9._+/-]{1,400}"))) { "CODEX_PATH" }
    val script="""
import os, sys, time
expect=sys.argv[1]
def cmdline(pid):
    try: data=open('/proc/%s/cmdline'%pid,'rb').read()
    except OSError: return ''
    return data.replace(b'\x00', b' ').decode('utf-8','replace')
def kids(pid):
    try: text=open('/proc/%s/task/%s/children'%(pid,pid)).read().split()
    except OSError: return []
    out=[]
    for item in text:
        try: child=int(item)
        except ValueError: continue
        out.append(child); out.extend(kids(child))
    return out
def alive(pid):
    try: stat=open('/proc/%s/stat'%pid).read()
    except OSError: return False
    state=stat.rsplit(')',1)[1].split()[0]
    return state not in ('Z','X')
deadline=time.time()+3600
while time.time()<deadline:
    roots=[]
    for name in os.listdir('/proc'):
        if not name.isdigit(): continue
        cmd=cmdline(name)
        if 'ssh-vm-worker.py' in cmd and ' serve ' in cmd and '_execute' not in cmd:
            roots.append(int(name))
    for root in roots:
        for pid in kids(root):
            if not alive(pid): continue
            try: path=os.readlink('/proc/%s/exe'%pid)
            except OSError: continue
            if path.endswith(' (deleted)'): path=path[:-len(' (deleted)')]
            try:
                if os.path.realpath(path)!=os.path.realpath(expect): continue
            except OSError:
                continue
            sys.stdout.buffer.write(b'MATCH\n')
            sys.stdout.buffer.write(path.encode()+b'\n')
            with open('/proc/%s/exe'%pid,'rb') as src:
                while True:
                    block=src.read(1048576)
                    if not block: break
                    sys.stdout.buffer.write(block)
            sys.stdout.flush()
            sys.exit(0)
    time.sleep(0.2)
sys.exit(2)
"""
    val quoted=script.trimIndent().replace("'", "'\"'\"'")
    return "exec /usr/bin/python3 -I -S -c '$quoted' '$text'"
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
