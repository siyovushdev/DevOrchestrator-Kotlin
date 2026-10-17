package dev.orchestrator.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orchestrator.storage.ProjectKind
import dev.orchestrator.storage.SqliteTaskStore
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant

/**
 * M1-06 trusted-owner configuration storage, not a runner or authentication layer.
 * Manifest capabilities are DECLARATIONS only; M2 must independently check the installed
 * binary, real supported models, auth, sandbox and policy before any attempt can start.
 *
 * Data is versioned in SQLite. Old manifests/profile revisions cannot be edited/deleted.
 * A role binding is a separately CAS-versioned pointer to an immutable profile revision.
 */
class SqliteAgentCatalog(databasePath: Path) {
    private val file = databasePath.toAbsolutePath().normalize()
    private val mapper = ObjectMapper()

    init {
        SqliteTaskStore(file) // migrate v1/v2/v3 to shared schema v4 safely
    }

    fun declareManifest(
        manifest: AgentCapabilityManifest,
        expectedPreviousVersion: Long?,
        ownerActorId: String,
    ): Boolean {
        validateActor(ownerActorId)
        return writeTransaction { conn ->
            val head = conn.prepareStatement(
                "SELECT provider, current_version FROM agent_manifest_heads WHERE adapter_id = ?",
            ).use { stmt ->
                stmt.setString(1, manifest.adapterId)
                stmt.executeQuery().use { r ->
                    if (r.next()) r.getString(1) to r.getLong(2) else null
                }
            }
            when {
                head == null -> {
                    if (expectedPreviousVersion != null) return@writeTransaction false
                    require(manifest.version == 1L)
                    conn.prepareStatement(
                        "INSERT INTO agent_manifest_heads(adapter_id,provider,current_version) VALUES (?,?,1)",
                    ).use {
                        it.setString(1, manifest.adapterId)
                        it.setString(2, manifest.provider)
                        check(it.executeUpdate() == 1)
                    }
                }
                else -> {
                    if (expectedPreviousVersion != head.second) return@writeTransaction false
                    require(manifest.provider == head.first) { "Provider identity is immutable" }
                    require(manifest.version == head.second + 1)
                    conn.prepareStatement(
                        "UPDATE agent_manifest_heads SET current_version = ? WHERE adapter_id = ? AND current_version = ?",
                    ).use {
                        it.setLong(1, manifest.version)
                        it.setString(2, manifest.adapterId)
                        it.setLong(3, head.second)
                        check(it.executeUpdate() == 1)
                    }
                }
            }
            conn.prepareStatement(
                """
                INSERT INTO agent_manifest_versions(adapter_id,version,capabilities_json,recorded_by,recorded_at)
                VALUES (?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, manifest.adapterId)
                it.setLong(2, manifest.version)
                it.setString(3, encodeManifest(manifest))
                it.setString(4, ownerActorId)
                it.setString(5, Instant.now().toString())
                check(it.executeUpdate() == 1)
            }
            recordAudit(conn, ownerActorId, "MANIFEST_DECLARED",
                manifest.adapterId, manifest.version)
            true
        }
    }

    fun findManifest(adapterId: String, version: Long? = null): AgentCapabilityManifest? =
        connection().use { conn -> readManifest(conn, adapterId, version) }

    /**
     * Adds a new immutable revision. Creating/updating profile does NOT change its active
     * role assignment or authorize any process. A separate Owner CAS bind is mandatory.
     */
    fun saveProfile(
        profile: AgentProfile,
        expectedPreviousVersion: Long?,
        ownerActorId: String,
    ): Boolean {
        validateActor(ownerActorId)
        return writeTransaction { conn ->
            val manifest = requireNotNull(readManifest(conn, profile.adapterId,
                profile.manifestVersion)) { "Unknown adapter manifest revision" }
            val kind = projectKind(conn, profile.repositoryId)
                ?: throw IllegalArgumentException("Unknown project repository ID")
            AgentProfileCompatibility.validateDeclared(manifest, profile, kind)

            val head = conn.prepareStatement(
                "SELECT repository_id,role,current_version FROM agent_profile_heads WHERE profile_id = ?",
            ).use {
                it.setString(1, profile.profileId)
                it.executeQuery().use { r ->
                    if (r.next()) Triple(r.getLong(1), r.getString(2), r.getLong(3))
                    else null
                }
            }
            when {
                head == null -> {
                    if (expectedPreviousVersion != null) return@writeTransaction false
                    require(profile.version == 1L)
                    conn.prepareStatement(
                        """
                        INSERT INTO agent_profile_heads(profile_id,repository_id,role,current_version)
                        VALUES (?,?,?,1)
                        """.trimIndent(),
                    ).use {
                        it.setString(1, profile.profileId)
                        it.setLong(2, profile.repositoryId)
                        it.setString(3, profile.role.name)
                        check(it.executeUpdate() == 1)
                    }
                }
                else -> {
                    require(head.first == profile.repositoryId && head.second == profile.role.name) {
                        "Profile cannot change its project/role identity"
                    }
                    if (expectedPreviousVersion != head.third) return@writeTransaction false
                    require(profile.version == head.third + 1)
                    val previous = requireNotNull(readProfile(conn, profile.profileId, head.third))
                    if (previous.copy(version = profile.version) == profile) return@writeTransaction false
                    conn.prepareStatement(
                        """
                        UPDATE agent_profile_heads SET current_version = ?
                        WHERE profile_id = ? AND current_version = ?
                        """.trimIndent(),
                    ).use {
                        it.setLong(1, profile.version)
                        it.setString(2, profile.profileId)
                        it.setLong(3, head.third)
                        check(it.executeUpdate() == 1)
                    }
                }
            }
            conn.prepareStatement(
                """
                INSERT INTO agent_profile_versions(
                    profile_id,version,adapter_id,manifest_version,provider,model,effort,
                    sandbox,timeout_seconds,max_budget_micro_usd,recorded_by,recorded_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, profile.profileId)
                it.setLong(2, profile.version)
                it.setString(3, profile.adapterId)
                it.setLong(4, profile.manifestVersion)
                it.setString(5, profile.provider)
                it.setString(6, profile.model)
                it.setString(7, profile.effort.name)
                it.setString(8, profile.sandbox.name)
                it.setInt(9, profile.timeoutSeconds)
                it.setLong(10, profile.maxBudgetMicroUsd)
                it.setString(11, ownerActorId)
                it.setString(12, Instant.now().toString())
                check(it.executeUpdate() == 1)
            }
            recordAudit(conn, ownerActorId,
                if (profile.version == 1L) "PROFILE_CREATED" else "PROFILE_REVISED",
                profile.profileId, profile.version)
            true
        }
    }

    fun findProfile(profileId: String, version: Long? = null): AgentProfile? =
        connection().use { conn -> readProfile(conn, profileId, version) }

    /**
     * Separate CAS assignment. Rebinding from version 1 to 2 is explicit, never implicit.
     * The profile MUST belong to this repository and role, and be its current revision.
     */
    fun bindRole(
        repositoryId: Long,
        role: AgentRole,
        profileId: String,
        profileVersion: Long,
        expectedPreviousBindingVersion: Long?,
        ownerActorId: String,
    ): AgentRoleAssignment? {
        validateActor(ownerActorId)
        require(repositoryId > 0 && profileVersion > 0)
        requireValidId(profileId, "profileId")
        return writeTransaction { conn ->
            val currentProfileVersion = conn.prepareStatement(
                """
                SELECT current_version FROM agent_profile_heads
                WHERE profile_id = ? AND repository_id = ? AND role = ?
                """.trimIndent(),
            ).use {
                it.setString(1, profileId)
                it.setLong(2, repositoryId)
                it.setString(3, role.name)
                it.executeQuery().use { r -> if (r.next()) r.getLong(1) else null }
            }
            require(currentProfileVersion == profileVersion) {
                "Assigned profile is missing, belongs to another project/role or is stale"
            }
            val profile = requireNotNull(readProfile(conn, profileId, profileVersion))
            val manifest = requireNotNull(readManifest(conn, profile.adapterId,
                profile.manifestVersion))
            AgentProfileCompatibility.validateDeclared(manifest, profile,
                requireNotNull(projectKind(conn, repositoryId)))

            val current = readAssignment(conn, repositoryId, role)
            if (current != null && current.profileId == profileId &&
                current.profileVersion == profileVersion) {
                return@writeTransaction current // true no-op; no audit/write
            }
            val nextVersion = if (current == null) {
                if (expectedPreviousBindingVersion != null) return@writeTransaction null
                conn.prepareStatement(
                    """
                    INSERT INTO agent_role_bindings(repository_id,role,profile_id,profile_version,
                        assignment_version,recorded_by,updated_at)
                    VALUES (?,?,?,?,1,?,?)
                    """.trimIndent(),
                ).use {
                    it.setLong(1, repositoryId)
                    it.setString(2, role.name)
                    it.setString(3, profileId)
                    it.setLong(4, profileVersion)
                    it.setString(5, ownerActorId)
                    it.setString(6, Instant.now().toString())
                    check(it.executeUpdate() == 1)
                }
                1L
            } else {
                if (expectedPreviousBindingVersion != current.assignmentVersion) {
                    return@writeTransaction null
                }
                require(current.assignmentVersion < Long.MAX_VALUE)
                conn.prepareStatement(
                    """
                    UPDATE agent_role_bindings SET profile_id=?, profile_version=?,
                        assignment_version=assignment_version+1, recorded_by=?,updated_at=?
                    WHERE repository_id=? AND role=? AND assignment_version=?
                    """.trimIndent(),
                ).use {
                    it.setString(1, profileId)
                    it.setLong(2, profileVersion)
                    it.setString(3, ownerActorId)
                    it.setString(4, Instant.now().toString())
                    it.setLong(5, repositoryId)
                    it.setString(6, role.name)
                    it.setLong(7, current.assignmentVersion)
                    check(it.executeUpdate() == 1)
                }
                current.assignmentVersion + 1
            }
            recordAudit(conn, ownerActorId, "ROLE_BOUND",
                "$repositoryId/${role.name}/$profileId:$profileVersion", nextVersion)
            AgentRoleAssignment(repositoryId, role, profileId, profileVersion, nextVersion)
        }
    }

    /** Read-only pinned snapshot, never a command to launch. */
    fun findAssignedProfile(repositoryId: Long, role: AgentRole): AssignedAgentProfile? =
        connection().use { conn ->
            val assigned = readAssignment(conn, repositoryId, role) ?: return@use null
            // All referenced versions are immutable. No cross-project role mapping accepted.
            val profile = readProfile(conn, assigned.profileId, assigned.profileVersion)
                ?: throw IllegalStateException("Missing immutable profile revision")
            require(profile.repositoryId == repositoryId && profile.role == role)
            val manifest = readManifest(conn, profile.adapterId, profile.manifestVersion)
                ?: throw IllegalStateException("Missing immutable manifest revision")
            AgentProfileCompatibility.validateDeclared(manifest, profile,
                requireNotNull(projectKind(conn, repositoryId)))
            AssignedAgentProfile(assigned, profile, manifest)
        }

    fun listConfigAudit(): List<AgentConfigAudit> = connection().use { conn ->
        conn.createStatement().use {
            it.executeQuery(
                "SELECT event_id,actor_id,event_type,target_id,revision,recorded_at " +
                    "FROM agent_config_events ORDER BY event_id",
            ).use { result ->
                buildList {
                    while (result.next()) add(AgentConfigAudit(
                        sequence = result.getLong(1),
                        actorId = result.getString(2),
                        action = result.getString(3),
                        target = result.getString(4),
                        revision = result.getLong(5),
                        recordedAt = result.getString(6),
                    ))
                }
            }
        }
    }

    private fun readAssignment(
        conn: Connection, repositoryId: Long, role: AgentRole,
    ): AgentRoleAssignment? = conn.prepareStatement(
        """
        SELECT profile_id,profile_version,assignment_version
        FROM agent_role_bindings WHERE repository_id=? AND role=?
        """.trimIndent(),
    ).use {
        it.setLong(1, repositoryId)
        it.setString(2, role.name)
        it.executeQuery().use { r ->
            if (!r.next()) null else AgentRoleAssignment(
                repositoryId, role, r.getString(1), r.getLong(2), r.getLong(3),
            )
        }
    }

    private fun readProfile(
        conn: Connection, profileId: String, version: Long? = null,
    ): AgentProfile? = conn.prepareStatement(
        """
        SELECT h.repository_id,h.role,v.version,v.adapter_id,v.manifest_version,
               v.provider,v.model,v.effort,v.sandbox,v.timeout_seconds,v.max_budget_micro_usd
        FROM agent_profile_heads h
        JOIN agent_profile_versions v ON v.profile_id=h.profile_id
        WHERE h.profile_id=? AND v.version=COALESCE(?,h.current_version)
        """.trimIndent(),
    ).use {
        it.setString(1, profileId)
        if (version == null) it.setNull(2, Types.BIGINT) else it.setLong(2, version)
        it.executeQuery().use { r ->
            if (!r.next()) null else AgentProfile(
                profileId = profileId,
                repositoryId = r.getLong("repository_id"),
                role = AgentRole.valueOf(r.getString("role")),
                version = r.getLong("version"),
                adapterId = r.getString("adapter_id"),
                manifestVersion = r.getLong("manifest_version"),
                provider = r.getString("provider"),
                model = r.getString("model"),
                effort = ReasoningEffort.valueOf(r.getString("effort")),
                sandbox = AgentSandbox.valueOf(r.getString("sandbox")),
                timeoutSeconds = r.getInt("timeout_seconds"),
                maxBudgetMicroUsd = r.getLong("max_budget_micro_usd"),
            )
        }
    }

    private fun readManifest(
        conn: Connection, adapterId: String, version: Long? = null,
    ): AgentCapabilityManifest? = conn.prepareStatement(
        """
        SELECT h.provider,v.version,v.capabilities_json
        FROM agent_manifest_heads h
        JOIN agent_manifest_versions v ON v.adapter_id=h.adapter_id
        WHERE h.adapter_id=? AND v.version=COALESCE(?,h.current_version)
        """.trimIndent(),
    ).use {
        it.setString(1, adapterId)
        if (version == null) it.setNull(2, Types.BIGINT) else it.setLong(2, version)
        it.executeQuery().use { r ->
            if (!r.next()) null else decodeManifest(
                adapterId, r.getString("provider"), r.getLong("version"),
                r.getString("capabilities_json"),
            )
        }
    }

    private fun encodeManifest(m: AgentCapabilityManifest): String =
        mapper.writeValueAsString(mapOf(
            "roles" to m.roles.map { it.name }.sorted(),
            "sandboxes" to m.sandboxes.map { it.name }.sorted(),
            "models" to m.modelEfforts.toSortedMap().mapValues { (_, efforts) ->
                efforts.map { it.name }.sorted()
            },
        ))

    private fun decodeManifest(
        adapterId: String, provider: String, version: Long, json: String,
    ): AgentCapabilityManifest {
        val root = mapper.readTree(json)
        require(root != null && root.isObject)
        val roles = requireArray(root, "roles").map { AgentRole.valueOf(it) }.toSet()
        val sandboxes = requireArray(root, "sandboxes")
            .map { AgentSandbox.valueOf(it) }.toSet()
        val models = root.get("models")
        require(models != null && models.isObject)
        val modelEfforts = linkedMapOf<String, Set<ReasoningEffort>>()
        val fields = models.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            require(entry.value.isArray)
            modelEfforts[entry.key] = entry.value.map { node ->
                require(node.isTextual)
                ReasoningEffort.valueOf(node.asText())
            }.toSet()
        }
        return AgentCapabilityManifest(adapterId, provider, version, roles,
            sandboxes, modelEfforts)
    }

    private fun requireArray(root: JsonNode, name: String): List<String> {
        val value = root.get(name)
        require(value != null && value.isArray)
        return value.map {
            require(it.isTextual)
            it.asText()
        }
    }

    private fun projectKind(conn: Connection, repositoryId: Long): ProjectKind? =
        conn.prepareStatement("SELECT kind FROM projects WHERE repository_id=?").use {
            it.setLong(1, repositoryId)
            it.executeQuery().use { r ->
                if (r.next()) ProjectKind.valueOf(r.getString(1)) else null
            }
        }

    private fun validateActor(id: String) {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9:@._-]{0,127}"))) {
            "Trusted owner actor identity missing/invalid (authentication not provided here)"
        }
    }

    private fun recordAudit(
        conn: Connection, actor: String, action: String, target: String, revision: Long,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO agent_config_events(actor_id,event_type,target_id,revision,recorded_at)
            VALUES (?,?,?,?,?)
            """.trimIndent(),
        ).use {
            it.setString(1, actor)
            it.setString(2, action)
            it.setString(3, target)
            it.setLong(4, revision)
            it.setString(5, Instant.now().toString())
            check(it.executeUpdate() == 1)
        }
    }

    private fun connection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$file")
        try {
            conn.createStatement().use {
                it.execute("PRAGMA foreign_keys=ON")
                it.execute("PRAGMA busy_timeout=5000")
            }
            return conn
        } catch (failure: Throwable) {
            conn.close()
            throw failure
        }
    }

    private fun <T> writeTransaction(operation: (Connection) -> T): T =
        connection().use { conn ->
            conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                val result = operation(conn)
                conn.createStatement().use { it.execute("COMMIT") }
                result
            } catch (failure: Throwable) {
                try { conn.createStatement().use { it.execute("ROLLBACK") } }
                catch (rollbackError: Throwable) { failure.addSuppressed(rollbackError) }
                throw failure
            }
        }
}
