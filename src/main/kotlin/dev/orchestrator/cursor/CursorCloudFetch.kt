package dev.orchestrator.cursor

import dev.orchestrator.agent.nativecli.NativeProcessRunner
import dev.orchestrator.publish.SMOKE_TARGET
import dev.orchestrator.storage.AttemptState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Checks a remote ref out into an empty directory. The token stays in process memory. */
internal fun interface CursorRefFetcher {
    fun fetch(ref: String, workTree: Path): String
}

internal class PersonalGitFetcher(private val token: String) : CursorRefFetcher {
    override fun fetch(ref: String, workTree: Path): String {
        require(ref.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,200}")) && !ref.contains("..") && !ref.startsWith("-"))
        require(token.isNotBlank() && token.none { it.isISOControl() })
        val scratch = workTree.parent.resolve(workTree.fileName.toString() + ".fetch")
        Files.createDirectory(scratch, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val template = Files.createDirectory(scratch.resolve("template"))
        val askpass = scratch.resolve("askpass.sh")
        Files.writeString(askpass, """#!/bin/sh
case "${'$'}1" in
  *Username*) printf '%s\n' x-access-token ;;
  *Password*) printf '%s\n' "${'$'}DEVORCH_PUBLISH_TOKEN" ;;
  *) exit 1 ;;
esac
""")
        check(askpass.toFile().setExecutable(true, true))
        val git = Path.of("/usr/bin/git")
        val env = mapOf(
            "HOME" to scratch.toString(), "PATH" to "/usr/bin:/bin", "LANG" to "C",
            "GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_TERMINAL_PROMPT" to "0",
            "GIT_ASKPASS" to askpass.toString(),
        )
        var command = 0
        fun run(vararg args: String, privateToken: Boolean) {
            val artifacts = Files.createDirectory(scratch.resolve("run-${command++}"))
            val observed = NativeProcessRunner(Path.of("/usr/bin/python3")).run(
                listOf(git.toRealPath().toString()) + args, workTree, env, artifacts, byteArrayOf(), 120_000,
                privateEnvironment = if (privateToken) mapOf("DEVORCH_PUBLISH_TOKEN" to token) else emptyMap(),
            ) { true }
            check(observed.state == AttemptState.SUCCEEDED) { "CURSOR_FETCH_FAILED" }
        }
        run("init", "--template=$template", "--initial-branch=main", privateToken = false)
        run(
            "-c", "credential.helper=", "-c", "http.followRedirects=false", "fetch", "--no-tags",
            "--no-auto-maintenance", "https://github.com/${SMOKE_TARGET.fullName}.git", ref,
            privateToken = true,
        )
        run("-c", "core.hooksPath=/dev/null", "checkout", "--detach", "FETCH_HEAD", privateToken = false)
        val head = dev.orchestrator.remote.RemoteProtocol.git(workTree, "-c", "core.hooksPath=/dev/null", "rev-parse", "HEAD")
        require(head.matches(Regex("[0-9a-f]{40}")))
        return head
    }
}
