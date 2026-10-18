package dev.orchestrator.agent.nativecli

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Narrow, deny-by-default static check BEFORE the first Git child process.
 *
 * This does NOT attest checkout provenance or protect from same-UID modification/TOCTOU.
 * Only minimal standalone clones with inert local git configuration are accepted for
 * flags-only diagnostics. M2 launch needs controller-owned protected metadata.
 */
internal object TrustedGitCheckoutGuard {
    private val nofollow = arrayOf(LinkOption.NOFOLLOW_LINKS)
    private val filterAttribute = Regex("""(?i)(?:^|\s)[!-]?(?:filter(?:=|\s|$))""")
    private val sectionPattern = Regex("""\[([a-zA-Z]+)(?: "([a-zA-Z0-9._/-]+)")?]""")
    private val keyValuePattern = Regex("""([a-zA-Z][a-zA-Z0-9]*)\s*=\s*(.*)""")
    private const val MAX_CONFIG_BYTES = 32_768L
    private const val MAX_ATTRIBUTE_BYTES = 65_536L
    private const val MAX_WALK_ENTRIES = 250_000

    fun accepts(root: Path): Boolean = try {
        val git = root.resolve(".git")
        if (!Files.isDirectory(root, *nofollow) ||
            !Files.isDirectory(git, *nofollow) ||
            !Files.isRegularFile(git.resolve("config"), *nofollow) ||
            !Files.isRegularFile(git.resolve("HEAD"), *nofollow) ||
            !Files.isDirectory(git.resolve("objects"), *nofollow) ||
            !Files.isDirectory(git.resolve("refs"), *nofollow)
        ) return false
        // These layouts can redirect repository metadata, or change effective worktree.
        if (listOf("commondir", "gitdir", "config.worktree", "worktrees", "modules")
                .any { Files.exists(git.resolve(it), LinkOption.NOFOLLOW_LINKS) }) return false
        if (Files.exists(git.resolve("objects/info/alternates"), LinkOption.NOFOLLOW_LINKS)) return false
        if (!validLocalConfig(git.resolve("config"))) return false
        val localAttributes = git.resolve("info/attributes")
        if (Files.exists(localAttributes, LinkOption.NOFOLLOW_LINKS) &&
            !inertAttributes(localAttributes)) return false

        var visited = 0
        var safe = true
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path, attrs: BasicFileAttributes,
            ): FileVisitResult {
                visited++
                if (visited > MAX_WALK_ENTRIES) { safe = false; return FileVisitResult.TERMINATE }
                if (dir == git) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                visited++
                if (visited > MAX_WALK_ENTRIES) { safe = false; return FileVisitResult.TERMINATE }
                if (file.fileName.toString() == ".gitattributes" && !inertAttributes(file)) {
                    safe = false
                    return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }
        })
        safe
    } catch (_: Exception) {
        false
    }

    private fun validLocalConfig(file: Path): Boolean {
        if (Files.size(file) > MAX_CONFIG_BYTES || Files.isSymbolicLink(file)) return false
        var section = ""
        var sawCore = false
        for (original in Files.readAllLines(file)) {
            val line = original.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[")) {
                val match = sectionPattern.matchEntire(line) ?: return false
                val name = match.groupValues[1].lowercase()
                val subsection = match.groupValues[2]
                section = when {
                    name == "core" && subsection.isEmpty() -> { sawCore = true; "core" }
                    name == "remote" && subsection == "origin" -> "remote"
                    name == "branch" && subsection.matches(Regex("[A-Za-z0-9._/-]{1,100}")) &&
                        !subsection.contains("..") -> "branch"
                    else -> return false // excludes filter, include/includeIf and extensions
                }
                continue
            }
            val entry = keyValuePattern.matchEntire(line) ?: return false
            val key = entry.groupValues[1].lowercase()
            val value = entry.groupValues[2].trim()
            if (value.isEmpty() || value.length > 512 ||
                value.contains('\\') || value.contains('"') || value.contains('\\u0000')
            ) return false
            val allowed = when (section) {
                "core" -> when (key) {
                    "repositoryformatversion" -> value == "0"
                    "filemode", "bare", "logallrefupdates",
                    "ignorecase", "symlinks", "precomposeunicode" ->
                        value.lowercase() in setOf("true", "false")
                    else -> false
                }
                "remote" -> when (key) {
                    "url" -> value.startsWith("https://github.com/") ||
                        value.matches(Regex("git@github\\.com:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?"))
                    "fetch" -> value == "+refs/heads/*:refs/remotes/origin/*"
                    else -> false
                }
                "branch" -> when (key) {
                    "remote" -> value == "origin"
                    "merge" -> value.startsWith("refs/heads/") &&
                        value.matches(Regex("[A-Za-z0-9._/-]+")) && !value.contains("..")
                    else -> false
                }
                else -> false
            }
            if (!allowed) return false
        }
        return sawCore
    }

    private fun inertAttributes(file: Path): Boolean {
        if (!Files.isRegularFile(file, *nofollow) ||
            Files.size(file) > MAX_ATTRIBUTE_BYTES) return false
        val content = Files.readString(file)
        // For first native smoke, all explicit filters and macros are forbidden.
        // Allow only ordinary text/line endings or simple merge attributes.
        if (filterAttribute.containsMatchIn(content)) return false
        if (content.lineSequence().any { line ->
                val trimmed = line.trim()
                trimmed.startsWith("[attr]") || trimmed.contains("working-tree-encoding") ||
                    trimmed.contains("ident") || trimmed.contains("diff=")
            }) return false
        return true
    }
}
