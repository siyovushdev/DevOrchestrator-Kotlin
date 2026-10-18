package dev.orchestrator.review

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64

internal enum class VerificationStatus { PASS, FAIL, NOT_RUN }

/** IDs are supplied by the trusted controller, never accepted from an agent report. */
internal data class CandidateBinding(
    val workflowId: String,
    val codingAttemptId: String,
    val codingSessionId: String,
    val generation: Int,
    val baseSha: String,
    val candidateTree: String,
    val verification: VerificationStatus,
    val verificationEvidenceId: String,
) {
    init {
        listOf(workflowId,codingAttemptId,codingSessionId,verificationEvidenceId).forEach {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        }
        require(generation in 0..2)
        require(baseSha.matches(Regex("[0-9a-f]{40}")) && candidateTree.matches(Regex("[0-9a-f]{40}")))
    }
}

/**
 * Integrity snapshot, NOT OS immutability, Git-tree attestation or process-cleanup proof.
 * The protected controller must establish those facts before binding these IDs.
 */
internal class FrozenCandidate private constructor(
    val directory: Path,
    val binding: CandidateBinding,
    val manifestSha256: String,
    private val expectedFiles: Map<String, FileDigest>,
) {
    val filesDirectory: Path get() = directory.resolve("files")

    fun isIntact(): Boolean = try {
        canonicalDirectory(directory)
        val manifest = boundedBytes(directory.resolve("manifest"), MAX_MANIFEST)
        sha256(manifest) == manifestSha256 && inventory(filesDirectory) == expectedFiles &&
            Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() } ==
                setOf("files", "manifest")
    } catch (_: Exception) { false }

    companion object {
        private const val MAX_FILES = 10_000
        private const val MAX_FILE_BYTES = 8 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        private const val MAX_MANIFEST = 4 * 1024 * 1024
        private data class FileDigest(val size: Long, val sha256: String, val executable: Boolean)

        /** Destination is new; failures preserve incomplete evidence, never overwrite/delete. */
        fun capture(source: Path, snapshotRoot: Path, candidateId: String, binding: CandidateBinding): FrozenCandidate {
            require(candidateId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            canonicalDirectory(source)
            canonicalDirectory(snapshotRoot)
            require(!source.startsWith(snapshotRoot) && !snapshotRoot.startsWith(source))
            val before = inventory(source, skipRootGit = true)
            val destination = Files.createDirectory(snapshotRoot.resolve(candidateId))
            val files = Files.createDirectory(destination.resolve("files"))
            for ((relative, expected) in before) {
                val target = files.resolve(relative)
                Files.createDirectories(target.parent)
                Files.newInputStream(source.resolve(relative), NOFOLLOW_LINKS).use { input ->
                    Files.newOutputStream(target, CREATE_NEW, WRITE).use { output ->
                        val buffer = ByteArray(8192)
                        var count = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            check(count <= expected.size && count <= MAX_FILE_BYTES)
                            output.write(buffer,0,n)
                        }
                        check(count == expected.size)
                    }
                }
                Files.setPosixFilePermissions(target,PosixFilePermissions.fromString(
                    if (expected.executable) "rwxr-xr-x" else "rw-r--r--"))
            }
            check(inventory(source, skipRootGit = true) == before) { "Candidate changed during capture" }
            check(inventory(files) == before) { "Snapshot differs from candidate" }
            val header = listOf("DEVORCH-FROZEN-1",binding.workflowId,binding.codingAttemptId,
                binding.codingSessionId,binding.generation.toString(),binding.baseSha,binding.candidateTree,
                binding.verification.name,binding.verificationEvidenceId).joinToString("\t")
            val manifest = (header + "\n" + before.entries.joinToString("") { (path,digest) ->
                "${digest.size}\t${digest.sha256}\t${if (digest.executable) "x" else "-"}\t${Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))}\n"
            }).toByteArray(Charsets.UTF_8)
            check(manifest.size <= MAX_MANIFEST)
            Files.write(destination.resolve("manifest"),manifest,CREATE_NEW,WRITE)
            return FrozenCandidate(destination,binding,sha256(manifest),before.toMap()).also { check(it.isIntact()) }
        }

        /** expectedManifestSha256 must come from durable trusted state, not this directory. */
        fun reopen(directory: Path, expectedManifestSha256: String): FrozenCandidate {
            require(expectedManifestSha256.matches(Regex("[0-9a-f]{64}")))
            canonicalDirectory(directory)
            val bytes = boundedBytes(directory.resolve("manifest"),MAX_MANIFEST)
            require(sha256(bytes) == expectedManifestSha256)
            val lines = bytes.toString(Charsets.UTF_8).split('\n')
            require(lines.last().isEmpty())
            val h = lines.first().split('\t')
            require(h.size == 9 && h[0] == "DEVORCH-FROZEN-1")
            val binding = CandidateBinding(h[1],h[2],h[3],h[4].toInt(),h[5],h[6],
                VerificationStatus.valueOf(h[7]),h[8])
            val files = sortedMapOf<String,FileDigest>()
            for (line in lines.drop(1).dropLast(1)) {
                val fields = line.split('\t')
                require(fields.size == 4 && files.size < MAX_FILES && fields[2] in setOf("x","-"))
                val name = Base64.getDecoder().decode(fields[3]).toString(Charsets.UTF_8)
                safeRelative(name)
                val size = fields[0].toLong()
                require(size in 0..MAX_FILE_BYTES.toLong() && fields[1].matches(Regex("[0-9a-f]{64}")))
                require(files.put(name,FileDigest(size,fields[1],fields[2] == "x")) == null)
            }
            require(files.values.sumOf { it.size } <= MAX_TOTAL_BYTES)
            return FrozenCandidate(directory,binding,expectedManifestSha256,files.toMap()).also { require(it.isIntact()) }
        }

        private fun inventory(root: Path, skipRootGit: Boolean = false): Map<String,FileDigest> {
            canonicalDirectory(root)
            val result = sortedMapOf<String,FileDigest>()
            var entries = 0
            var bytes = 0L
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    check(++entries <= MAX_FILES * 2)
                    if (skipRootGit && dir == root.resolve(".git")) return FileVisitResult.SKIP_SUBTREE
                    if (dir != root) safeRelative(root.relativize(dir).toString())
                    check(!attrs.isSymbolicLink)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    check(++entries <= MAX_FILES * 2 && result.size < MAX_FILES)
                    val name = root.relativize(file).toString()
                    safeRelative(name)
                    check(attrs.isRegularFile && !attrs.isSymbolicLink && attrs.size() <= MAX_FILE_BYTES)
                    check((Files.getAttribute(file,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt() == 1)
                    val content = boundedBytes(file,MAX_FILE_BYTES)
                    check(content.size.toLong() == attrs.size())
                    bytes += content.size
                    check(bytes <= MAX_TOTAL_BYTES)
                    val executable = PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(file,NOFOLLOW_LINKS)
                    result[name] = FileDigest(content.size.toLong(),sha256(content),executable)
                    return FileVisitResult.CONTINUE
                }
            })
            return result.toMap()
        }

        private fun safeRelative(value: String) {
            require(value.isNotEmpty() && value.length <= 4096 && value.none { it.isISOControl() })
            val path = Path.of(value)
            require(!path.isAbsolute && path.normalize() == path &&
                path.none { it.toString() in setOf(".","..",".git") })
        }

        private fun canonicalDirectory(path: Path) {
            require(path.isAbsolute && path.normalize() == path && path.toRealPath() == path &&
                Files.isDirectory(path,NOFOLLOW_LINKS))
        }

        private fun boundedBytes(path: Path, limit: Int): ByteArray {
            check(Files.isRegularFile(path,NOFOLLOW_LINKS) &&
                (Files.getAttribute(path,"unix:nlink",NOFOLLOW_LINKS) as Number).toInt() == 1)
            return Files.newInputStream(path,NOFOLLOW_LINKS).use { it.readNBytes(limit+1) }
                .also { check(it.size <= limit) }
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
