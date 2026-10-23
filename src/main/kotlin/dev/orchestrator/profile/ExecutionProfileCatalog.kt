package dev.orchestrator.profile

import dev.orchestrator.agent.ReasoningEffort
import dev.orchestrator.agent.StageBudget
import dev.orchestrator.storage.RuntimeDatabase
import dev.orchestrator.storage.mutate
import dev.orchestrator.storage.rows
import java.nio.file.Path
import java.time.Instant

enum class ExecutionRuntime { cli, cloud }

enum class ExecutionAuth { subscription, api }

/** One role, one runtime. A stage reads only its own row. */
data class RoleExecution(
    val runtime: ExecutionRuntime,
    val auth: ExecutionAuth,
    val model: String,
    val effort: ReasoningEffort,
) {
    init {
        require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}")))
        require(runtime == ExecutionRuntime.cli && auth == ExecutionAuth.subscription ||
            runtime == ExecutionRuntime.cloud && auth == ExecutionAuth.api) {
            "cli uses subscription login; cloud uses the API key"
        }
    }

    val adapterId: String get() = if (runtime == ExecutionRuntime.cli) "cursor-cli" else "cursor-cloud"
}

/**
 * Immutable profile versions. `default` is the logged-in CLI. `cloud-api` is the
 * existing Cloud Agents API and is used only when that id is selected.
 */
data class ExecutionProfile(
    val id: String,
    val version: Long,
    val maxCorrections: Int,
    val stageTimeoutSeconds: Int,
    val coder: RoleExecution,
    val reviewer: RoleExecution,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{0,63}")))
        require(version > 0)
        require(maxCorrections in 0..2)
        require(stageTimeoutSeconds in StageBudget.MIN_SECONDS..StageBudget.MAX_SECONDS)
    }

    fun role(coderRole: Boolean): RoleExecution = if (coderRole) coder else reviewer

    companion object {
        fun defaultProfile(version: Long = 1): ExecutionProfile = ExecutionProfile(
            "default", version, 2, StageBudget.DEFAULT_SECONDS,
            RoleExecution(ExecutionRuntime.cli, ExecutionAuth.subscription, "account-default", ReasoningEffort.DEFAULT),
            RoleExecution(ExecutionRuntime.cli, ExecutionAuth.subscription, "account-default", ReasoningEffort.DEFAULT),
        )

        fun cloudApi(version: Long = 1): ExecutionProfile = ExecutionProfile(
            "cloud-api", version, 2, StageBudget.DEFAULT_SECONDS,
            RoleExecution(ExecutionRuntime.cloud, ExecutionAuth.api, "account-default", ReasoningEffort.DEFAULT),
            RoleExecution(ExecutionRuntime.cloud, ExecutionAuth.api, "account-default", ReasoningEffort.DEFAULT),
        )
    }
}

internal class ExecutionProfileCatalog(private val database: Path) {
    private val db = RuntimeDatabase(database)

    init { schema(db) }

    fun ensureBuiltins() {
        if (find("default", 1) == null) insert(ExecutionProfile.defaultProfile())
        if (find("cloud-api", 1) == null) insert(ExecutionProfile.cloudApi())
    }

    fun find(id: String, version: Long? = null): ExecutionProfile? = db.read { c ->
        val sql = if (version == null)
            """SELECT * FROM execution_profile_versions WHERE profile_id=? ORDER BY version DESC LIMIT 1"""
        else """SELECT * FROM execution_profile_versions WHERE profile_id=? AND version=?"""
        val args: Array<Any?> = if (version == null) arrayOf(id) else arrayOf(id, version)
        c.rows(sql, *args) { readProfile(it) }.singleOrNull()
    }

    /** Next version only. An existing version row is never updated. */
    fun save(next: ExecutionProfile): ExecutionProfile {
        val stored = next.copy(version = (find(next.id)?.version ?: 0) + 1)
        insert(stored)
        return stored
    }

    /** Pins the newest version. A second call returns the pin and ignores newer versions. */
    fun pinLatest(workflowId: String, profileId: String): ExecutionProfile {
        ensureBuiltins()
        pinned(workflowId)?.let { return it }
        val profile = requireNotNull(find(profileId)) { "UNKNOWN_PROFILE" }
        db.write { c ->
            c.mutate(
                "INSERT INTO execution_profile_pins(workflow_id,profile_id,version) VALUES (?,?,?)",
                workflowId, profile.id, profile.version,
            )
        }
        return profile
    }

    fun pinned(workflowId: String): ExecutionProfile? = db.read { c ->
        if (c.rows("SELECT 1 FROM sqlite_master WHERE name='execution_profile_pins'") { 1 }.isEmpty()) return@read null
        val row = c.rows("SELECT profile_id,version FROM execution_profile_pins WHERE workflow_id=?", workflowId) {
            it.getString(1) to it.getLong(2)
        }.singleOrNull() ?: return@read null
        find(row.first, row.second)
    }

    private fun insert(profile: ExecutionProfile) {
        db.write { c ->
            c.mutate(
                """INSERT INTO execution_profile_versions(profile_id,version,max_corrections,stage_timeout_seconds,
                   coder_runtime,coder_auth,coder_model,coder_effort,reviewer_runtime,reviewer_auth,reviewer_model,reviewer_effort,recorded_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                profile.id, profile.version, profile.maxCorrections, profile.stageTimeoutSeconds,
                profile.coder.runtime.name, profile.coder.auth.name, profile.coder.model, profile.coder.effort.name,
                profile.reviewer.runtime.name, profile.reviewer.auth.name, profile.reviewer.model, profile.reviewer.effort.name,
                Instant.now().toString(),
            )
        }
    }

    private fun readProfile(row: java.sql.ResultSet): ExecutionProfile = ExecutionProfile(
        row.getString("profile_id"), row.getLong("version"), row.getInt("max_corrections"), row.getInt("stage_timeout_seconds"),
        role(row, "coder"), role(row, "reviewer"),
    )

    private fun role(row: java.sql.ResultSet, prefix: String) = RoleExecution(
        ExecutionRuntime.valueOf(row.getString("${prefix}_runtime")),
        ExecutionAuth.valueOf(row.getString("${prefix}_auth")),
        row.getString("${prefix}_model"),
        ReasoningEffort.valueOf(row.getString("${prefix}_effort")),
    )

    companion object {
        private fun schema(db: RuntimeDatabase) {
            db.write { c ->
                c.mutate(
                    """CREATE TABLE IF NOT EXISTS execution_profile_versions(
                        profile_id TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        max_corrections INTEGER NOT NULL CHECK(max_corrections BETWEEN 0 AND 2),
                        stage_timeout_seconds INTEGER NOT NULL CHECK(stage_timeout_seconds BETWEEN ${StageBudget.MIN_SECONDS} AND ${StageBudget.MAX_SECONDS}),
                        coder_runtime TEXT NOT NULL, coder_auth TEXT NOT NULL, coder_model TEXT NOT NULL, coder_effort TEXT NOT NULL,
                        reviewer_runtime TEXT NOT NULL, reviewer_auth TEXT NOT NULL, reviewer_model TEXT NOT NULL, reviewer_effort TEXT NOT NULL,
                        recorded_at TEXT NOT NULL,
                        PRIMARY KEY(profile_id, version))""",
                )
                c.mutate(
                    """CREATE TABLE IF NOT EXISTS execution_profile_pins(
                        workflow_id TEXT PRIMARY KEY,
                        profile_id TEXT NOT NULL,
                        version INTEGER NOT NULL)""",
                )
                c.mutate("CREATE TRIGGER IF NOT EXISTS execution_profile_version_no_update BEFORE UPDATE ON execution_profile_versions BEGIN SELECT RAISE(ABORT,'immutable profile version'); END")
                c.mutate("CREATE TRIGGER IF NOT EXISTS execution_profile_version_no_delete BEFORE DELETE ON execution_profile_versions BEGIN SELECT RAISE(ABORT,'immutable profile version'); END")
                c.mutate("CREATE TRIGGER IF NOT EXISTS execution_profile_pin_no_update BEFORE UPDATE ON execution_profile_pins BEGIN SELECT RAISE(ABORT,'immutable profile pin'); END")
                c.mutate("CREATE TRIGGER IF NOT EXISTS execution_profile_pin_no_delete BEFORE DELETE ON execution_profile_pins BEGIN SELECT RAISE(ABORT,'immutable profile pin'); END")
            }
        }
    }
}
