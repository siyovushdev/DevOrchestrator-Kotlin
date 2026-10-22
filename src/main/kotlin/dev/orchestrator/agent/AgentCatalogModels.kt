package dev.orchestrator.agent

import dev.orchestrator.storage.ProjectKind

/**
 * Wall-clock budget for one Coding or Review process.
 * Git fetch and publication keep their own shorter deadlines.
 * 120s was the pilot cap around the whole `codex exec` process, including startup,
 * proxy and every tool round. A real turn does not fit there. The ceiling stays finite.
 */
internal object StageBudget {
    const val MIN_SECONDS = 30
    const val DEFAULT_SECONDS = 1200
    const val MAX_SECONDS = 3600
    const val MAX_MILLIS = MAX_SECONDS * 1000L
    const val TRANSPORT_GRACE_MILLIS = 15_000L
    const val MAX_TRANSPORT_MILLIS = MAX_MILLIS + TRANSPORT_GRACE_MILLIS
}

/** Roles and settings are typed. They are NOT CLI arguments or OS permissions. */
enum class AgentRole { CODING, REVIEW, PLANNER, CHIEF }
enum class ReasoningEffort { DEFAULT, LOW, MEDIUM, HIGH, XHIGH }
enum class AgentSandbox { NATIVE_PERSONAL_WRITE, NATIVE_PERSONAL_READ_ONLY }

/**
 * Owner-registered *declared* capabilities; no M1 manifest is runtime-attested.
 * M2 must run a separate adapter preflight before any attempt is launched.
 */
data class AgentCapabilityManifest(
    val adapterId: String,
    val provider: String,
    val version: Long,
    val roles: Set<AgentRole>,
    val sandboxes: Set<AgentSandbox>,
    val modelEfforts: Map<String, Set<ReasoningEffort>>,
) {
    init {
        requireValidId(adapterId, "adapterId")
        requireValidId(provider, "provider")
        require(version > 0)
        require(roles.isNotEmpty() && sandboxes.isNotEmpty() && modelEfforts.isNotEmpty())
        require(modelEfforts.size <= 128)
        for ((model, efforts) in modelEfforts) {
            requireValidModel(model)
            require(efforts.isNotEmpty()) { "Missing effort capability for $model" }
        }
    }
}

/** Immutable, project-scoped settings. A new version never overwrites an old one. */
data class AgentProfile(
    val profileId: String,
    val version: Long,
    val repositoryId: Long,
    val role: AgentRole,
    val adapterId: String,
    val manifestVersion: Long,
    val provider: String,
    val model: String,
    val effort: ReasoningEffort,
    val sandbox: AgentSandbox,
    val timeoutSeconds: Int,
    /** Maximum estimated spend in micro-USD. Enforced by the future runner, NOT this model. */
    val maxBudgetMicroUsd: Long,
) {
    init {
        requireValidId(profileId, "profileId")
        requireValidId(adapterId, "adapterId")
        requireValidId(provider, "provider")
        requireValidModel(model)
        require(version > 0 && manifestVersion > 0 && repositoryId > 0)
        require(timeoutSeconds in 30..7200)
        require(maxBudgetMicroUsd in 1..1_000_000_000)
    }
}

data class AgentRoleAssignment(
    val repositoryId: Long,
    val role: AgentRole,
    val profileId: String,
    val profileVersion: Long,
    val assignmentVersion: Long,
)

enum class AgentProfileStatus {
    /** Schema compatibility only. No local CLI/model auth/health has been checked. */
    DECLARED_UNVERIFIED,
}

data class AssignedAgentProfile(
    val assignment: AgentRoleAssignment,
    val profile: AgentProfile,
    val manifest: AgentCapabilityManifest,
    val status: AgentProfileStatus = AgentProfileStatus.DECLARED_UNVERIFIED,
)

data class AgentConfigAudit(
    val sequence: Long,
    val actorId: String,
    val action: String,
    val target: String,
    val revision: Long,
    val recordedAt: String,
)

internal val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val SAFE_MODEL = Regex("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}")

internal fun requireValidId(value: String, field: String) {
    require(SAFE_ID.matches(value)) { "Invalid $field" }
}

internal fun requireValidModel(value: String) {
    require(SAFE_MODEL.matches(value)) { "Invalid model identifier" }
}

/** Check declared compatibility, never claim that an installed CLI really supports it. */
object AgentProfileCompatibility {
    fun validateDeclared(manifest: AgentCapabilityManifest, profile: AgentProfile, kind: ProjectKind) {
        require(kind == ProjectKind.PERSONAL) {
            "Native PERSONAL agent profiles cannot enable WORK; separate isolation approval required"
        }
        require(manifest.adapterId == profile.adapterId)
        require(manifest.version == profile.manifestVersion)
        require(manifest.provider == profile.provider)
        require(profile.role in manifest.roles) { "Adapter does not declare this role" }
        require(profile.sandbox in manifest.sandboxes) { "Adapter does not declare this sandbox" }
        val efforts = manifest.modelEfforts[profile.model]
            ?: throw IllegalArgumentException("Model not declared for selected adapter")
        require(profile.effort in efforts) { "Model does not declare selected effort" }
        val expectedSandbox = when (profile.role) {
            AgentRole.CODING -> AgentSandbox.NATIVE_PERSONAL_WRITE
            AgentRole.REVIEW, AgentRole.PLANNER, AgentRole.CHIEF ->
                AgentSandbox.NATIVE_PERSONAL_READ_ONLY
        }
        require(profile.sandbox == expectedSandbox) {
            "Role cannot use this native sandbox"
        }
    }
}

/**
 * M2 adapter implementations will attest live CLI version/auth/model/effort and OS isolation.
 * Presence of a declared profile or this interface never grants launch permission.
 */
interface AgentRuntimePreflight {
    fun verify(profile: AgentProfile, manifest: AgentCapabilityManifest): AgentRuntimePreflightResult
}

sealed interface AgentRuntimePreflightResult {
    data class Verified(val evidenceId: String) : AgentRuntimePreflightResult
    data class Blocked(val reason: String) : AgentRuntimePreflightResult
}
