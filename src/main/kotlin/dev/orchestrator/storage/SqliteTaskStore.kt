package dev.orchestrator.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import dev.orchestrator.policy.ProjectPolicy

/**
 * Durable storage only. This is NOT the WorkflowEngine and grants no execution or publication
 * permissions. In particular, there is deliberately no public method to advance workflow
 * phases or send outbox effects before the owner/policy guards are implemented.
 *
 * A new JDBC connection is opened per operation. SQLite WAL + transactions + UNIQUE constraints
 * protect the data if an Issue is polled again or the service is restarted.
 */
data class ProjectPolicyAudit(
    val version: Long,
    val eventKind: String,
    val actorId: String,
    val reason: String,
    val beforePersonalAuto: Boolean?,
    val afterPersonalAuto: Boolean,
    val recordedAt: String,
)

class SqliteTaskStore(databasePath: Path) {
    private val file: Path = databasePath.toAbsolutePath().normalize()

    init {
        Files.createDirectories(file.parent)
        migrate()
    }

    fun schemaVersion(): Int = connection().use { conn ->
        conn.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                check(result.next())
                result.getInt(1)
            }
        }
    }

    /**
     * Storage primitive for a trusted owner-controlled registry (M1-03).
     * Not exposed through HTTP or agent tools. It does not authorize any agent execution.
     */
    fun insertProject(project: RegisteredProject, ownerActorId: String) {
        validateOwnerActor(ownerActorId)
        require(project.id.isNotBlank())
        require(project.repositoryId > 0)
        require(project.workspaceRoot.isNotBlank())
        val root = Path.of(project.workspaceRoot)
        require(root.isAbsolute && root.normalize() == root && root.parent != null) {
            "Project root must be absolute, normalized and not filesystem root"
        }

        transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO projects(project_id, repository_id, kind, workspace_root)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, project.id)
                statement.setLong(2, project.repositoryId)
                statement.setString(3, project.kind.name)
                statement.setString(4, project.workspaceRoot)
                statement.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO project_policies(repository_id) VALUES (?)").use {
                it.setLong(1, project.repositoryId)
                check(it.executeUpdate() == 1)
            }
            auditPolicy(conn, project.repositoryId, 1, "REGISTERED", ownerActorId,
                "Project registered with default DENY", null, false)
        }
    }

    /** Read an owner-managed policy snapshot. No approvals are inferred from project kind. */
    fun findPolicy(repositoryId: Long): ProjectPolicy? = connection().use { conn ->
        conn.prepareStatement(
            """
            SELECT p.project_id, p.repository_id, p.kind, p.workspace_root,
                   p.policy_version, pp.personal_auto_execution
            FROM projects p JOIN project_policies pp ON pp.repository_id = p.repository_id
            WHERE p.repository_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, repositoryId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else ProjectPolicy(
                    projectId = result.getString("project_id"),
                    repositoryId = result.getLong("repository_id"),
                    kind = ProjectKind.valueOf(result.getString("kind")),
                    workspaceRoot = Path.of(result.getString("workspace_root")),
                    personalAutoExecution = result.getInt("personal_auto_execution") == 1,
                    version = result.getLong("policy_version"),
                )
            }
        }
    }

    /**
     * Trusted-owner storage primitive, NOT authentication. Only an authenticated controller
     * may supply ownerActorId. No HTTP, Issue or agent integration exists at this stage.
     * Changes use BEGIN IMMEDIATE + CAS, full audit, and no-op requests do not write.
     */
    fun updatePolicyFromTrustedOwner(
        policy: ProjectPolicy,
        expectedVersion: Long,
        ownerActorId: String,
        reason: String,
    ): Boolean {
        validateOwnerActor(ownerActorId)
        require(reason.isNotBlank() && reason.length <= 500 && reason.none { it.isISOControl() })
        require(expectedVersion > 0 && expectedVersion < Long.MAX_VALUE)
        require(policy.version == expectedVersion + 1) {
            "Policy snapshot version must be next CAS version"
        }
        return transaction { conn ->
            val before = conn.prepareStatement(
                """
                SELECT pp.personal_auto_execution
                FROM projects p JOIN project_policies pp ON pp.repository_id = p.repository_id
                WHERE p.repository_id = ? AND p.project_id = ? AND p.kind = ?
                  AND p.workspace_root = ? AND p.policy_version = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, policy.repositoryId)
                statement.setString(2, policy.projectId)
                statement.setString(3, policy.kind.name)
                statement.setString(4, policy.workspaceRoot.toString())
                statement.setLong(5, expectedVersion)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getInt(1) == 1 else null
                }
            }
            if (before == null || before == policy.personalAutoExecution) {
                return@transaction false
            }
            val changed = conn.prepareStatement(
                """
                UPDATE projects SET policy_version = policy_version + 1
                WHERE repository_id = ? AND project_id = ? AND kind = ?
                  AND workspace_root = ? AND policy_version = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, policy.repositoryId)
                statement.setString(2, policy.projectId)
                statement.setString(3, policy.kind.name)
                statement.setString(4, policy.workspaceRoot.toString())
                statement.setLong(5, expectedVersion)
                statement.executeUpdate() == 1
            }
            check(changed) { "Policy changed during serialized transaction" }
            conn.prepareStatement(
                "UPDATE project_policies SET personal_auto_execution = ? WHERE repository_id = ?",
            ).use { statement ->
                statement.setInt(1, if (policy.personalAutoExecution) 1 else 0)
                statement.setLong(2, policy.repositoryId)
                check(statement.executeUpdate() == 1)
            }
            auditPolicy(conn, policy.repositoryId, expectedVersion + 1, "UPDATED",
                ownerActorId, reason, before, policy.personalAutoExecution)
            true
        }
    }

    private fun validateOwnerActor(ownerActorId: String) {
        require(ownerActorId.isNotBlank() && ownerActorId.length <= 128 &&
            ownerActorId.none { it.isISOControl() }) { "Missing/invalid authenticated owner ID" }
    }

    private fun auditPolicy(
        conn: Connection, repositoryId: Long, version: Long, kind: String,
        actorId: String, reason: String, before: Boolean?, after: Boolean,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO project_policy_events(
                repository_id, policy_version, event_kind, actor_id, reason,
                old_personal_auto_execution, new_personal_auto_execution, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, repositoryId)
            statement.setLong(2, version)
            statement.setString(3, kind)
            statement.setString(4, actorId)
            statement.setString(5, reason)
            if (before == null) statement.setNull(6, java.sql.Types.INTEGER)
            else statement.setInt(6, if (before) 1 else 0)
            statement.setInt(7, if (after) 1 else 0)
            statement.setString(8, Instant.now().toString())
            check(statement.executeUpdate() == 1)
        }
    }

    fun policyHistory(repositoryId: Long): List<ProjectPolicyAudit> = connection().use { conn ->
        conn.prepareStatement(
            """
            SELECT policy_version, event_kind, actor_id, reason,
                   old_personal_auto_execution, new_personal_auto_execution, recorded_at
            FROM project_policy_events WHERE repository_id = ? ORDER BY policy_version
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, repositoryId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val oldValue = result.getInt("old_personal_auto_execution")
                        val before = if (result.wasNull()) null else oldValue == 1
                        add(ProjectPolicyAudit(
                            version = result.getLong("policy_version"),
                            eventKind = result.getString("event_kind"),
                            actorId = result.getString("actor_id"),
                            reason = result.getString("reason"),
                            beforePersonalAuto = before,
                            afterPersonalAuto = result.getInt("new_personal_auto_execution") == 1,
                            recordedAt = result.getString("recorded_at"),
                        ))
                    }
                }
            }
        }
    }

    fun policyVersions(repositoryId: Long): List<Long> = connection().use { conn ->
        conn.prepareStatement(
            "SELECT policy_version FROM project_policy_events WHERE repository_id = ? ORDER BY policy_version",
        ).use { statement ->
            statement.setLong(1, repositoryId)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getLong(1)) }
            }
        }
    }

    /**
     * Creates a single QUEUED row for the exact GitHub repository ID and Issue number.
     * Duplicate polls return the original record and do not add an extra event.
     */
    fun enqueue(repositoryId: Long, issueNumber: Int, proposedWorkflowId: String): WorkflowSnapshot {
        require(repositoryId > 0)
        require(issueNumber > 0)
        require(proposedWorkflowId.isNotBlank())

        return transaction { conn ->
            val registered = conn.prepareStatement(
                "SELECT 1 FROM projects WHERE repository_id = ?",
            ).use { statement ->
                statement.setLong(1, repositoryId)
                statement.executeQuery().use { it.next() }
            }
            require(registered) { "Unknown repository ID: $repositoryId" }

            val now = Instant.now().toString()
            val created = conn.prepareStatement(
                """
                INSERT INTO workflows(
                    workflow_id, repository_id, issue_number, phase, hold,
                    version, corrections_used, created_at, updated_at
                )
                VALUES (?, ?, ?, 'QUEUED', 'NONE', 0, 0, ?, ?)
                ON CONFLICT(repository_id, issue_number) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, proposedWorkflowId)
                statement.setLong(2, repositoryId)
                statement.setInt(3, issueNumber)
                statement.setString(4, now)
                statement.setString(5, now)
                statement.executeUpdate() == 1
            }

            val current = requireNotNull(readWorkflow(conn, repositoryId, issueNumber))
            if (created) {
                insertEvent(conn, current.id, "WORKFLOW_QUEUED", null, now)
            }
            current
        }
    }

    fun findWorkflow(repositoryId: Long, issueNumber: Int): WorkflowSnapshot? =
        connection().use { conn -> readWorkflow(conn, repositoryId, issueNumber) }

    /**
     * A safe, one-way CAS that records a blocker. Clearing NEEDS_OWNER must be implemented
     * later behind a trusted owner decision; merely passing a new poll cannot clear it.
     */
    fun markNeedsOwner(workflowId: String, expectedVersion: Long, reason: String): Boolean {
        require(workflowId.isNotBlank())
        require(expectedVersion >= 0)
        require(reason.isNotBlank())

        return transaction { conn ->
            val now = Instant.now().toString()
            val changed = conn.prepareStatement(
                """
                UPDATE workflows
                SET hold = 'NEEDS_OWNER', hold_reason = ?, version = version + 1,
                    updated_at = ?
                WHERE workflow_id = ? AND version = ?
                  AND hold != 'NEEDS_OWNER'
                  AND phase NOT IN ('DRAFT_PR_CREATED', 'FAILED', 'CANCELLED')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, reason)
                statement.setString(2, now)
                statement.setString(3, workflowId)
                statement.setLong(4, expectedVersion)
                statement.executeUpdate() == 1
            }
            if (changed) insertEvent(conn, workflowId, "NEEDS_OWNER", reason, now)
            changed
        }
    }

    fun listEvents(workflowId: String): List<WorkflowEvent> =
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT event_id, workflow_id, event_type, detail, recorded_at
                FROM workflow_events WHERE workflow_id = ? ORDER BY event_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, workflowId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                WorkflowEvent(
                                    sequence = result.getLong("event_id"),
                                    workflowId = result.getString("workflow_id"),
                                    type = result.getString("event_type"),
                                    detail = result.getString("detail"),
                                    recordedAt = result.getString("recorded_at"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun readWorkflow(
        conn: Connection,
        repositoryId: Long,
        issueNumber: Int,
    ): WorkflowSnapshot? =
        conn.prepareStatement(
            """
            SELECT workflow_id, repository_id, issue_number, phase, hold, hold_reason,
                   version, corrections_used
            FROM workflows WHERE repository_id = ? AND issue_number = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, repositoryId)
            statement.setInt(2, issueNumber)
            statement.executeQuery().use { result ->
                if (result.next()) result.toSnapshot() else null
            }
        }

    private fun ResultSet.toSnapshot(): WorkflowSnapshot = WorkflowSnapshot(
        id = getString("workflow_id"),
        repositoryId = getLong("repository_id"),
        issueNumber = getInt("issue_number"),
        phase = WorkflowPhase.valueOf(getString("phase")),
        hold = WorkflowHold.valueOf(getString("hold")),
        holdReason = getString("hold_reason"),
        version = getLong("version"),
        correctionsUsed = getInt("corrections_used"),
    )

    private fun insertEvent(
        conn: Connection,
        workflowId: String,
        type: String,
        detail: String?,
        recordedAt: String,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO workflow_events(workflow_id, event_type, detail, recorded_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, workflowId)
            statement.setString(2, type)
            statement.setString(3, detail)
            statement.setString(4, recordedAt)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun connection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$file")
        try {
            conn.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
            }
            return conn
        } catch (failure: Throwable) {
            conn.close()
            throw failure
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T =
        connection().use { conn -> transaction(conn, block) }

    /**
     * SQLite's default BEGIN DEFERRED allows a read before a write lock. Two concurrent
     * pollers may both read the registry, then one fails with SQLITE_BUSY_SNAPSHOT while
     * trying to insert. Acquire the writer reservation BEFORE the first read instead.
     *
     * Execute BEGIN/COMMIT through SQL: JDBC autoCommit remains true, so conn.commit()
     * must not be used with this explicit SQLite transaction.
     */
    private fun <T> transaction(conn: Connection, block: (Connection) -> T): T {
        conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val result = block(conn)
            conn.createStatement().use { it.execute("COMMIT") }
            return result
        } catch (failure: Throwable) {
            try {
                conn.createStatement().use { it.execute("ROLLBACK") }
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun migrate() {
        connection().use { conn ->
            // journal_mode must be set outside a transaction; lock before reading the
            // version so two initializers cannot both create v1 from the same stale value.
            conn.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
            transaction(conn) { db ->
                val currentVersion = db.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use {
                        check(it.next())
                        it.getInt(1)
                    }
                }

                check(currentVersion <= SCHEMA_VERSION) {
                    "Database schema $currentVersion is newer than this application ($SCHEMA_VERSION)"
                }

                if (currentVersion == 0) {
                    SCHEMA_V1.forEach { sql ->
                        db.createStatement().use { it.execute(sql) }
                    }
                    db.createStatement().use {
                        it.execute("PRAGMA user_version = 1")
                    }
                }
                if (currentVersion < 2) {
                    SCHEMA_V2.forEach { sql -> db.createStatement().use { it.execute(sql) } }
                    db.createStatement().use { it.execute("PRAGMA user_version = 2") }
                }
            }
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 2

        val SCHEMA_V2 = listOf(
            """
            CREATE TABLE project_policies(
                repository_id INTEGER PRIMARY KEY REFERENCES projects(repository_id),
                personal_auto_execution INTEGER NOT NULL DEFAULT 0
                    CHECK(personal_auto_execution IN (0,1))
            )
            """.trimIndent(),
            """
            CREATE TABLE project_policy_events(
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                repository_id INTEGER NOT NULL REFERENCES projects(repository_id),
                policy_version INTEGER NOT NULL CHECK(policy_version > 0),
                event_kind TEXT NOT NULL CHECK(event_kind IN ('REGISTERED', 'MIGRATED', 'UPDATED')),
                actor_id TEXT NOT NULL,
                reason TEXT NOT NULL,
                old_personal_auto_execution INTEGER
                    CHECK(old_personal_auto_execution IS NULL OR old_personal_auto_execution IN (0,1)),
                new_personal_auto_execution INTEGER NOT NULL
                    CHECK(new_personal_auto_execution IN (0,1)),
                recorded_at TEXT NOT NULL,
                UNIQUE(repository_id, policy_version)
            )
            """.trimIndent(),
            """
            INSERT INTO project_policies(repository_id)
            SELECT repository_id FROM projects
            """.trimIndent(),
            """
            INSERT INTO project_policy_events(
                repository_id, policy_version, event_kind, actor_id, reason,
                old_personal_auto_execution, new_personal_auto_execution, recorded_at
            )
            SELECT repository_id, policy_version, 'MIGRATED', 'system:migration',
                   'Schema v1 to v2: default DENY', NULL, 0,
                   STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now') FROM projects
            """.trimIndent(),
        )

        val SCHEMA_V1 = listOf(
            """
            CREATE TABLE projects(
                project_id TEXT PRIMARY KEY NOT NULL,
                repository_id INTEGER UNIQUE NOT NULL CHECK(repository_id > 0),
                kind TEXT NOT NULL CHECK(kind IN ('PERSONAL', 'WORK')),
                workspace_root TEXT NOT NULL,
                policy_version INTEGER NOT NULL DEFAULT 1 CHECK(policy_version > 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE workflows(
                workflow_id TEXT PRIMARY KEY NOT NULL,
                repository_id INTEGER NOT NULL REFERENCES projects(repository_id),
                issue_number INTEGER NOT NULL CHECK(issue_number > 0),
                phase TEXT NOT NULL DEFAULT 'QUEUED'
                    CHECK(phase IN (
                        'QUEUED', 'CODING', 'VERIFYING', 'REVIEWING', 'READY_FOR_PR',
                        'PUBLISHING', 'DRAFT_PR_CREATED', 'FAILED', 'CANCELLED'
                    )),
                hold TEXT NOT NULL DEFAULT 'NONE'
                    CHECK(hold IN ('NONE', 'PAUSED', 'NEEDS_OWNER')),
                hold_reason TEXT,
                version INTEGER NOT NULL DEFAULT 0 CHECK(version >= 0),
                corrections_used INTEGER NOT NULL DEFAULT 0
                    CHECK(corrections_used BETWEEN 0 AND 2),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                UNIQUE(repository_id, issue_number)
            )
            """.trimIndent(),
            """
            CREATE TABLE agent_attempts(
                attempt_id TEXT PRIMARY KEY NOT NULL,
                workflow_id TEXT NOT NULL REFERENCES workflows(workflow_id),
                role TEXT NOT NULL CHECK(role IN ('CODING', 'REVIEW')),
                generation INTEGER NOT NULL CHECK(generation >= 0),
                state TEXT NOT NULL CHECK(state IN (
                    'PLANNED', 'STARTING', 'RUNNING', 'SUCCEEDED', 'FAILED',
                    'UNKNOWN', 'CANCELLED'
                )),
                source_tree TEXT,
                recorded_at TEXT NOT NULL,
                UNIQUE(workflow_id, role, generation)
            )
            """.trimIndent(),
            """
            CREATE TABLE effects_outbox(
                effect_id TEXT PRIMARY KEY NOT NULL,
                workflow_id TEXT NOT NULL REFERENCES workflows(workflow_id),
                kind TEXT NOT NULL CHECK(kind IN ('COMMIT', 'PUSH', 'DRAFT_PR')),
                state TEXT NOT NULL CHECK(state IN (
                    'PREPARED', 'SENDING', 'CONFIRMED', 'REJECTED', 'UNCERTAIN'
                )),
                idempotency_key TEXT NOT NULL UNIQUE,
                intended_target TEXT NOT NULL,
                expected_sha TEXT,
                recorded_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE workflow_events(
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                workflow_id TEXT NOT NULL REFERENCES workflows(workflow_id),
                event_type TEXT NOT NULL,
                detail TEXT,
                recorded_at TEXT NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX idx_workflow_events ON workflow_events(workflow_id, event_id)",
            "CREATE INDEX idx_attempts_workflow ON agent_attempts(workflow_id)",
            "CREATE INDEX idx_effects_workflow ON effects_outbox(workflow_id)",
        )
    }
}
