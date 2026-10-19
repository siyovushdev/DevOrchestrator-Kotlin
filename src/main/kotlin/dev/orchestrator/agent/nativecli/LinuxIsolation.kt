package dev.orchestrator.agent.nativecli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A fixed bubblewrap invocation, not a sandbox implementation. No host-root or /proc bind. */
internal object LinuxIsolation {
    private val binary = Path.of("/usr/bin/bwrap")
    fun requireModelHome(home: Path, vararg protectedPaths: Path) {
        val controllerHome=Path.of(System.getProperty("user.home")).toRealPath()
        require(home.isAbsolute && home.normalize()==home && home.toRealPath()==home)
        require(!controllerHome.startsWith(home)) { "Controller HOME or its ancestor cannot be delegated" }
        val protected=protectedPaths.map { it.toFile().canonicalFile.toPath() }
        require(protected.none { it.startsWith(home) || home.startsWith(it) }) { "Model HOME overlaps controller data" }
        val model=home.resolve(".codex")
        require(Files.isDirectory(model) && model.toRealPath()==model) { "Dedicated model HOME required" }
    }

    fun prefix(workspace: Path, writable: Boolean = false, home: Path? = null,
               executable: Path? = null, output: Path? = null): List<String> {
        check(Files.isExecutable(binary)) { "ISOLATION_UNAVAILABLE: install Ubuntu bubblewrap" }
        val args = mutableListOf(binary.toString(), "--unshare-user", "--unshare-pid", "--unshare-ipc",
            "--unshare-uts", "--unshare-cgroup", "--die-with-parent", "--new-session", "--cap-drop", "ALL",
            "--ro-bind", "/usr", "/usr", "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp")
        for (name in listOf("bin", "sbin", "lib", "lib64")) {
            val path = Path.of("/$name")
            if (Files.isSymbolicLink(path)) args += listOf("--symlink", Files.readSymbolicLink(path).toString(), path.toString())
            else if (Files.exists(path)) args += listOf("--ro-bind", path.toString(), path.toString())
        }
        for (name in listOf("ld.so.cache", "ssl/certs", "resolv.conf", "hosts", "nsswitch.conf")) {
            val path = Path.of("/etc/$name")
            if (Files.exists(path)) args += listOf("--ro-bind", path.toString(), path.toString())
        }
        fun bind(path: Path, write: Boolean) {
            check(path.isAbsolute && path.normalize()==path && path.toRealPath()==path) { "ISOLATION_PATH_INVALID" }
            args += listOf(if (write) "--bind" else "--ro-bind", path.toString(), path.toString())
        }
        bind(workspace, writable)
        if (home == null) args += "--unshare-net" else {
            // Only the dedicated model HOME is deliberately delegated. Never controller HOME.
            requireModelHome(home,workspace)
            bind(home.resolve(".codex"), true)
        }
        if (executable != null && !executable.startsWith(Path.of("/usr"))) {
            bind(executable.toRealPath(), false)
            // Native Codex installs its matching helper beside the CLI.
            val bin=executable.toRealPath().parent
            for (name in listOf("code-mode-host","codex-code-mode-host","../codex-resources/bwrap","../codex-path/rg")) {
                val helper=bin.resolve(name).normalize()
                if (Files.exists(helper,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(helper,java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                        helper.toRealPath()==helper && Files.isExecutable(helper)) { "Invalid native Codex helper" }
                    bind(helper, false)
                }
            }
        }
        if (output != null) bind(output, true)
        args += listOf("--chdir", workspace.toString(), "--")
        return args
    }

    fun requireAvailable() {
        val p = ProcessBuilder(prefix(Path.of("/usr")) + "/usr/bin/true")
            .redirectErrorStream(true).start()
        p.outputStream.close()
        val finished = p.waitFor(5, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        check(finished && p.exitValue()==0) { "ISOLATION_UNAVAILABLE: bubblewrap namespaces denied; no execution allowed" }
    }
}
