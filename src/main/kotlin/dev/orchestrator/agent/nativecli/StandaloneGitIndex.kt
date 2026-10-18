package dev.orchestrator.agent.nativecli

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Bounded, non-executing index inspection before any Git process. Unknown formats deny. */
internal object StandaloneGitIndex {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 250_000

    fun accepts(index: Path): Boolean = try {
        val bytes = Files.newInputStream(index).use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size !in 32..MAX_BYTES) return false
        val end = bytes.size - 20 // Only SHA-1 repository format 0 is accepted by the config guard.
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-1").digest(bytes.copyOf(end)),
                bytes.copyOfRange(end, bytes.size))) return false
        val data = ByteBuffer.wrap(bytes, 0, end)
        if (data.int != 0x44495243 || data.int !in 2..3) return false // DIRC
        val count = data.int
        if (count !in 0..MAX_ENTRIES) return false
        repeat(count) {
            val start = data.position()
            if (data.remaining() < 62) return false
            data.position(start + 24)
            val mode = data.int
            // Reject gitlinks (160000), sparse directories, unknown modes and extensions.
            if (mode !in setOf(0x81a4, 0x81ed, 0xa000)) return false
            data.position(start + 60)
            val flags = data.short.toInt() and 0xffff
            // CE_VALID hides changed tracked content from status; these indexes are
            // outside the minimal standalone profile, as are extended/sparse entries.
            if (flags and 0xc000 != 0) return false
            val pathStart = data.position()
            while (data.hasRemaining() && bytes[data.position()] != 0.toByte()) data.get()
            if (!data.hasRemaining()) return false
            val length = data.position() - pathStart
            if (length == 0 || (flags and 0xfff) != minOf(length, 0xfff)) return false
            val path = bytes.copyOfRange(pathStart, data.position()).toString(Charsets.UTF_8)
            if (path.startsWith('/') || path.split('/').any { it in setOf("", ".", "..", ".git") })
                return false
            data.get() // NUL, followed by entry-relative 8-byte alignment
            while ((data.position() - start) % 8 != 0) {
                if (!data.hasRemaining() || data.get() != 0.toByte()) return false
            }
        }
        while (data.hasRemaining()) {
            if (data.remaining() < 8) return false
            val signature = data.int
            val length = data.int
            // TREE is an inert optional cache; split-index, sparse-index and unknown metadata deny.
            if (signature != 0x54524545 || length < 0 || length > data.remaining()) return false
            data.position(data.position() + length)
        }
        true
    } catch (_: Exception) {
        false
    }
}
