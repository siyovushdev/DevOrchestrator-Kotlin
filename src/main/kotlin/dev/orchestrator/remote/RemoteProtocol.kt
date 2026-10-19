package dev.orchestrator.remote

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.agent.nativecli.BoundedNativeProbeReader
import dev.orchestrator.agent.nativecli.ProbeProcessResult
import dev.orchestrator.agent.nativecli.TrustedGitCheckoutGuard
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.Base64

internal object RemoteProtocol {
    val mapper=ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    const val REPO=1424152600L
    const val LIMIT=24*1024*1024
    const val MAX_TOTAL=8*1024*1024
    fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun id(value: String)=value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
    fun sha(value: String,n: Int)=value.matches(Regex("[0-9a-f]{$n}"))
    fun path(value: String) {
        require(value.isNotBlank() && value.length<=1024 && value.none { it.isISOControl() || it=='\\' })
        val p=Path.of(value)
        require(!p.isAbsolute && p.normalize()==p && p.none { it.toString() in setOf(".","..",".git",".codex",".ssh") })
    }
    fun file(path: Path,limit: Int): ByteArray {
        require(Files.isRegularFile(path,NOFOLLOW_LINKS) && Files.size(path)<=limit &&
            (Files.getAttribute(path,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt()==1)
        return Files.newInputStream(path,NOFOLLOW_LINKS).use { it.readNBytes(limit+1) }.also { require(it.size<=limit) }
    }
    fun inventory(root: Path): JsonNode {
        require(root.toRealPath()==root)
        val files=mapper.createArrayNode(); var total=0
        var directories=0
        Files.walkFileTree(root,object: java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(p: Path,a: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if(p==root.resolve(".git")) return java.nio.file.FileVisitResult.SKIP_SUBTREE
                if(p!=root) path(root.relativize(p).toString())
                require(++directories<=2000)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(p: Path,a: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                val relative=root.relativize(p).toString(); path(relative)
                require(files.size()<1000)
                val bytes=file(p,1024*1024); total+=bytes.size; require(total<=MAX_TOTAL)
                files.add(mapper.valueToTree<JsonNode>(mapOf("path" to relative,"data" to Base64.getEncoder().encodeToString(bytes),
                    "sha256" to digest(bytes),"executable" to Files.isExecutable(p))))
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        return files
    }
    /** Only a NEW disposable directory, never a checkout, HOME or existing controller files. */
    fun materialize(files: JsonNode,destination: Path) {
        require(files.isArray && files.size()<=1000 && !Files.exists(destination,NOFOLLOW_LINKS))
        val decoded=mutableListOf<Pair<JsonNode,ByteArray>>(); val names=mutableSetOf<String>(); var total=0
        for(f in files) {
            require(f.isObject && f.size()==4 && f["path"]?.isTextual==true && f["data"]?.isTextual==true &&
                f["sha256"]?.isTextual==true && f["executable"]?.isBoolean==true)
            val name=f["path"].asText(); path(name); require(names.add(name))
            val bytes=Base64.getDecoder().decode(f["data"].asText()); total+=bytes.size
            require(bytes.size<=1024*1024 && total<=MAX_TOTAL && digest(bytes)==f["sha256"].asText())
            decoded+=f to bytes
        }
        require(names.none { name -> names.any { it.startsWith("$name/") } })
        require(names.flatMap { name -> generateSequence(Path.of(name).parent) { it.parent }.map { it.toString() }.toList() }.toSet().size<2000)
        Files.createDirectory(destination,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
        for((f,bytes) in decoded) {
            val target=destination.resolve(f["path"].asText()); Files.createDirectories(target.parent)
            Files.write(target,bytes,CREATE_NEW)
            Files.setPosixFilePermissions(target,java.nio.file.attribute.PosixFilePermissions.fromString(
                if(f["executable"].asBoolean()) "rwx------" else "rw-------"))
        }
    }
    fun git(cwd: Path,vararg args: String): String {
        val result=BoundedNativeProbeReader(maxOutputBytes=1_000_000).read(listOf("/usr/bin/git")+args,cwd)
        check(result is ProbeProcessResult.Exited && result.exitCode==0) { "REMOTE_GIT_EVIDENCE_INVALID" }
        return result.output.trim()
    }
    /** Fresh private object store, no repository hooks/config/filter commands from exported files. */
    fun tree(files: Path,scratch: Path): String {
        val template=Files.createDirectory(scratch.resolve("template")); val objects=scratch.resolve("objects.git")
        git(scratch,"init","--bare","--template=$template",objects.toString())
        fun command(vararg args: String)=git(files,"--git-dir=$objects","--work-tree=$files",
            "-c","core.hooksPath=/dev/null",*args)
        command("read-tree","--empty"); command("add","--force","--all","--",".")
        return command("write-tree").also { require(sha(it,40)) }
    }
    fun installationDigest(): String {
        val bytes=listOf("ssh-vm-worker.py","native-codex-supervisor.py","verify-python-unittest.py").flatMap { name ->
            RemoteProtocol::class.java.getResourceAsStream("/$name")!!.use { it.readAllBytes().toList() }
        }.toByteArray()
        return digest(bytes)
    }
}

internal data class RemoteTaskIdentity(val attemptId: String,val workflowId: String,val issueNumber: Int,
    val baseSha: String,val sourceTree: String,val generation: Int,val role: String,
    val candidateId: String="",val manifestSha256: String="",val codingSessionId: String="") {
    init {
        require(RemoteProtocol.id(attemptId) && RemoteProtocol.id(workflowId) && issueNumber>0 && generation in 0..2)
        require(role in setOf("CODING","TESTS","REVIEW") && RemoteProtocol.sha(baseSha,40) && RemoteProtocol.sha(sourceTree,40))
        if(role!="CODING") require(RemoteProtocol.id(candidateId) && RemoteProtocol.sha(manifestSha256,64) && RemoteProtocol.id(codingSessionId))
    }
}

internal class RemoteRequest(bytes: ByteArray) {
    private val content=bytes.copyOf()
    val bytes: ByteArray get()=content.copyOf()
    val sha256=RemoteProtocol.digest(content)
    val json: JsonNode get()=RemoteProtocol.mapper.readTree(content)
    val attemptId: String get()=json["identity"]["attemptId"].asText()
    init {
        require(content.size<=RemoteProtocol.LIMIT)
        val n=json
        require(n.isObject && n.fieldNames().asSequence().toSet()==setOf("version","repositoryId","identity","workerSha256","files","bundle","bundleSha256","prompt","model","effort","minimumTests","timeoutMs"))
        require(n["version"].isIntegralNumber && n["version"].asInt()==1 && n["repositoryId"].isIntegralNumber && n["repositoryId"].asLong()==RemoteProtocol.REPO)
        val i=n["identity"]
        require(i.isObject && i.fieldNames().asSequence().toSet()==setOf("attemptId","workflowId","issueNumber","baseSha","sourceTree","generation","role","candidateId","manifestSha256","codingSessionId"))
        require(i.fieldNames().asSequence().filter { it !in setOf("issueNumber","generation") }.all { i[it].isTextual } && i["issueNumber"].isInt && i["generation"].isInt)
        RemoteTaskIdentity(i["attemptId"].asText(),i["workflowId"].asText(),i["issueNumber"].asInt(),i["baseSha"].asText(),i["sourceTree"].asText(),i["generation"].asInt(),i["role"].asText(),i["candidateId"].asText(),i["manifestSha256"].asText(),i["codingSessionId"].asText())
        require(n["workerSha256"].asText()==RemoteProtocol.installationDigest() && n["files"].isArray)
        require(n["prompt"].isTextual && n["prompt"].asText().toByteArray().size<=65536 && n["model"].asText().matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}")))
        require(n["effort"].asText() in setOf("default","low","medium","high","xhigh") && n["minimumTests"].isInt && n["minimumTests"].asInt() in 1..10000 && n["timeoutMs"].isIntegralNumber && n["timeoutMs"].asLong() in 50..120000)
        val bundle=Base64.getDecoder().decode(n["bundle"].asText())
        require(bundle.size<=4*1024*1024 && RemoteProtocol.digest(bundle)==n["bundleSha256"].asText())
    }
    companion object {
        /** Caller must supply an already Owner-authorized PERSONAL checkout; not an Issue-facing API. */
        fun capture(identity: RemoteTaskIdentity,checkout: Path,scratch: Path,prompt: String,
                    model: String="fixture-model",effort: String="default",minimumTests: Int=1,timeoutMs: Long=120000): RemoteRequest {
            require(prompt.toByteArray().size<=65536 && minimumTests in 1..10000 && timeoutMs in 50..120000)
            require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}")) && effort in setOf("default","low","medium","high","xhigh"))
            require(TrustedGitCheckoutGuard.accepts(checkout))
            require(RemoteProtocol.git(checkout,"rev-parse","HEAD")==identity.baseSha)
            val image=RemoteProtocol.inventory(checkout)
            val copied=scratch.resolve("input"); RemoteProtocol.materialize(image,copied)
            val hashScratch=Files.createDirectory(scratch.resolve("hash"))
            require(RemoteProtocol.tree(copied,hashScratch)==identity.sourceTree)
            val bundle=scratch.resolve("base.bundle")
            RemoteProtocol.git(checkout,"-c","core.hooksPath=/dev/null","bundle","create",bundle.toString(),"HEAD")
            val bundleBytes=RemoteProtocol.file(bundle,4*1024*1024)
            val value=mapOf("version" to 1,"repositoryId" to RemoteProtocol.REPO,"identity" to identity,
                "workerSha256" to RemoteProtocol.installationDigest(),"files" to image,
                "bundle" to Base64.getEncoder().encodeToString(bundleBytes),"bundleSha256" to RemoteProtocol.digest(bundleBytes),
                "prompt" to prompt,"model" to model,"effort" to effort,"minimumTests" to minimumTests,"timeoutMs" to timeoutMs)
            val bytes=RemoteProtocol.mapper.writeValueAsBytes(value); require(bytes.size<=RemoteProtocol.LIMIT)
            return RemoteRequest(bytes)
        }
    }
}
