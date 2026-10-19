package dev.orchestrator.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties

/** Local Owner authority, never loaded from GitHub or agent output. Reloaded at each boundary. */
internal data class PersonalConfig(
    val enabled: Boolean, val root: Path, val home: Path, val codex: Path,
    val model: String, val minimumTests: Int, val proxyPort: Int, val pollSeconds: Long,
    val runtimeMode: String = "local",
) {
    companion object {
        fun read(file: Path): PersonalConfig {
            require(file.isAbsolute && file.normalize()==file && file.toRealPath()==file)
            require(Files.isRegularFile(file,NOFOLLOW_LINKS) && Files.size(file)<=8192)
            val perms=Files.getPosixFilePermissions(file,NOFOLLOW_LINKS)
            require(perms.none { it in setOf(PosixFilePermission.GROUP_WRITE,PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.GROUP_READ,PosixFilePermission.OTHERS_READ) }) { "Owner config must be private" }
            require(Files.getOwner(file).name==System.getProperty("user.name"))
            val p=Properties().apply { Files.newInputStream(file,NOFOLLOW_LINKS).use { load(it) } }
            require(p.getProperty("repository")=="siyovushdev/devorch-kotlin-e2e-smoke" &&
                p.getProperty("repositoryId")=="1424152600" && p.getProperty("owner")=="siyovushdev")
            require(p.getProperty("enabled","false") in setOf("true","false"))
            fun directory(key: String): Path = Path.of(p.getProperty(key)).also {
                require(it.isAbsolute && it.normalize()==it && it.toRealPath()==it && Files.isDirectory(it,NOFOLLOW_LINKS))
            }
            val mode=p.getProperty("runtimeMode","local"); require(mode in setOf("local","ssh-vm"))
            val root=directory("root"); val home=directory("agentHome")
            require(Files.getPosixFilePermissions(root).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") })
            require(Files.getOwner(root).name==System.getProperty("user.name"))
            require(!Path.of(System.getProperty("user.home")).toAbsolutePath().normalize().startsWith(home)) {
                "Controller HOME or its ancestor cannot be delegated"
            }
            require(root!=home && !root.startsWith(home) && !home.startsWith(root))
            val binary=Path.of(p.getProperty("codex"))
            require(binary.isAbsolute)
            val model=requireNotNull(p.getProperty("model"))
            require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}")))
            val minimum=p.getProperty("minimumTests","1").toInt(); require(minimum in 1..10000)
            val port=p.getProperty("proxyPort","10809").toInt(); require(port in 1..65535)
            val poll=p.getProperty("pollSeconds","90").toLong(); require(poll in 60..120)
            return PersonalConfig(p.getProperty("enabled","false")=="true",root,home,binary,model,minimum,port,poll,mode)
        }
    }
}
