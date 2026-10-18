package dev.orchestrator.agent.nativecli

import dev.orchestrator.agent.AgentRole
import dev.orchestrator.agent.AgentSandbox
import dev.orchestrator.agent.AssignedAgentProfile
import dev.orchestrator.agent.ReasoningEffort
import java.nio.file.Path

/**
 * A command PLAN only. No execution takes place by constructing this object.
 * It must not be built from untrusted GitHub Issue text or agent output.
 */
data class CodexExecCommandPlan(
    val argumentVector: List<String>,
    val workingDirectory: Path,
    val outputLastMessage: Path,
    val immutableProfileId: String,
    val immutableProfileVersion: Long,
    val immutableManifestVersion: Long,
    val role: AgentRole,
) {
    /** Exact read/write mode is pinned; never infer from agent prompt content. */
    val sandbox: AgentSandbox get() = when (role) {
        AgentRole.CODING -> AgentSandbox.NATIVE_PERSONAL_WRITE
        AgentRole.REVIEW -> AgentSandbox.NATIVE_PERSONAL_READ_ONLY
        else -> throw IllegalStateException("Not a Coding/Review command")
    }
}

object CodexExecCommandPlanner {
    /**
     * Native CLI flag compatibility and model availability are NOT asserted here.
     * Preflight MUST verify CLI version/flags before a process attempt. M2 full runner
     * must separately establish credentials, scope, sandbox and immutable source SHA.
     */
    fun plan(
        verifiedBinary: Path,
        workspace: Path,
        artifactFile: Path,
        assigned: AssignedAgentProfile,
    ): CodexExecCommandPlan {
        require(verifiedBinary.isAbsolute && verifiedBinary.normalize() == verifiedBinary)
        require(workspace.isAbsolute && workspace.normalize() == workspace)
        require(artifactFile.isAbsolute && artifactFile.normalize() == artifactFile)
        require(assigned.profile.role == assigned.assignment.role)
        require(assigned.profile.repositoryId == assigned.assignment.repositoryId)
        require(assigned.profile.profileId == assigned.assignment.profileId)
        require(assigned.profile.version == assigned.assignment.profileVersion)
        require(assigned.profile.manifestVersion == assigned.manifest.version)
        require(assigned.profile.adapterId == "codex-cli" &&
            assigned.manifest.adapterId == "codex-cli" &&
            assigned.manifest.provider == "openai" &&
            assigned.profile.provider == "openai") {
            "Only an explicitly registered Codex CLI profile may form this plan"
        }
        val role = assigned.profile.role
        require(role == AgentRole.CODING || role == AgentRole.REVIEW)
        val sandbox = when (role) {
            AgentRole.CODING -> AgentSandbox.NATIVE_PERSONAL_WRITE
            AgentRole.REVIEW -> AgentSandbox.NATIVE_PERSONAL_READ_ONLY
            else -> error("Unreachable role")
        }
        require(assigned.profile.sandbox == sandbox) {
            "Invalid sandbox for selected agent role"
        }
        val args = mutableListOf(
            verifiedBinary.toString(),
            "exec",
            "--sandbox",
            when (sandbox) {
                AgentSandbox.NATIVE_PERSONAL_WRITE -> "workspace-write"
                AgentSandbox.NATIVE_PERSONAL_READ_ONLY -> "read-only"
            },
            "--model", assigned.profile.model,
            "--json",
            "--output-last-message", artifactFile.toString(),
            "--config", "approval_policy=never",
        )
        if (assigned.profile.effort != ReasoningEffort.DEFAULT) {
            args += listOf(
                "--config",
                "model_reasoning_effort=${assigned.profile.effort.name.lowercase()}",
            )
        }
        // Prompt supplied to stdin by future owner-authorized runner, NEVER argv/shell.
        args += "-"
        return CodexExecCommandPlan(
            argumentVector = args.toList(),
            workingDirectory = workspace,
            outputLastMessage = artifactFile,
            immutableProfileId = assigned.profile.profileId,
            immutableProfileVersion = assigned.profile.version,
            immutableManifestVersion = assigned.manifest.version,
            role = role,
        )
    }
}
