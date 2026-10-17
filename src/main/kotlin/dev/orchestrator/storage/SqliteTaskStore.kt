package dev.orchestrator.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

/**
 * Durable storage only. This is NOT the WorkflowEngine and grants no execution or publication
 * permissions. In particular, there is deliberately no public method to advance workflow
 * phases or send outbox effects before the owner/policy guards are implemented.
 *
 * A new JDBC connection is opened per operation. SQLite WAL + transactions + UNIQUE constraints
 * protect the data if an Issue is polled again or the service is restarted.
 */
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
    fun insertProject(project: RegisteredProject) {
        require(project.id.isNotBlank())
        require(project.repositoryId > 0)
        require(project.workspaceRoot.isNotBlank())

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

    private fun <T> transaction(block: (Connection) -> T): T = connection().use { conn ->
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (failure: Throwable) {
            conn.rollback()
            throw failure
        }
    }

    private fun migrate() {
        connection().use { conn ->
            // WAL mode is configured outside a transaction.
            conn.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
            val currentVersion = conn.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use {
                    check(it.next())
                    it.getInt(1)
                }
            }

            check(currentVersion <= SCHEMA_VERSION) {
                "Database schema $currentVersion is newer than this application ($SCHEMA_VERSION)"
            }

            if (currentVersion == 0) {
                conn.autoCommit = false
                try {
                    SCHEMA_V1.forEach { sql ->
                        conn.createStatement().use { it.execute(sql) }
                    }
                    conn.createStatement().use {
                        it.execute("PRAGMA user_version = $SCHEMA_VERSION")
                    }
                    conn.commit()
                } catch (failure: Throwable) {
                    conn.rollback()
                    throw failure
                }
            }
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1

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
