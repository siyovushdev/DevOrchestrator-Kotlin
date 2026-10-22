package dev.orchestrator.service

import dev.orchestrator.agent.StageBudget
import dev.orchestrator.storage.RuntimeDatabase
import dev.orchestrator.storage.mutate
import dev.orchestrator.storage.rows
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.time.Instant

/** Owner-chosen ceilings inside the existing code caps. Issue text cannot set these. */
internal data class ExecutionLimits(
    val maxCorrections: Int,
    val codingTimeoutSeconds: Int,
    val reviewTimeoutSeconds: Int,
) {
    companion object {
        val DEFAULT = ExecutionLimits(2, StageBudget.DEFAULT_SECONDS, StageBudget.DEFAULT_SECONDS)
    }
}

internal fun ownerCancelFlag(root: Path, issueNumber: Int): Path =
    root.resolve("control").resolve("cancel-$issueNumber")

internal fun ensureOwnerSchema(c: Connection) {
    val limitsSql = """CREATE TABLE IF NOT EXISTS owner_limits(
        scope TEXT PRIMARY KEY,
        max_corrections INTEGER NOT NULL CHECK(max_corrections BETWEEN 0 AND 2),
        coding_timeout_seconds INTEGER NOT NULL CHECK(coding_timeout_seconds BETWEEN ${StageBudget.MIN_SECONDS} AND ${StageBudget.MAX_SECONDS}),
        review_timeout_seconds INTEGER NOT NULL CHECK(review_timeout_seconds BETWEEN ${StageBudget.MIN_SECONDS} AND ${StageBudget.MAX_SECONDS}),
        version INTEGER NOT NULL CHECK(version > 0),
        actor TEXT NOT NULL,
        recorded_at TEXT NOT NULL)"""
    c.mutate(limitsSql)
    val existing = c.rows("SELECT sql FROM sqlite_master WHERE type='table' AND name='owner_limits'") { it.getString(1) }.singleOrNull().orEmpty()
    if (existing.contains("BETWEEN 1 AND 120")) {
        c.mutate("ALTER TABLE owner_limits RENAME TO owner_limits_pilot")
        c.mutate(limitsSql.replace("IF NOT EXISTS ", ""))
        c.mutate("""INSERT INTO owner_limits(scope,max_corrections,coding_timeout_seconds,review_timeout_seconds,version,actor,recorded_at)
            SELECT scope,max_corrections,MAX(${StageBudget.MIN_SECONDS},coding_timeout_seconds),
            MAX(${StageBudget.MIN_SECONDS},review_timeout_seconds),version,actor,recorded_at FROM owner_limits_pilot""")
        c.mutate("DROP TABLE owner_limits_pilot")
    }
    c.mutate("""CREATE TABLE IF NOT EXISTS owner_cancels(
        issue_number INTEGER PRIMARY KEY CHECK(issue_number > 0),
        state TEXT NOT NULL,
        detail TEXT NOT NULL,
        actor TEXT NOT NULL,
        recorded_at TEXT NOT NULL)""")
    c.mutate("""CREATE TABLE IF NOT EXISTS owner_audit(
        id INTEGER PRIMARY KEY,
        recorded_at TEXT NOT NULL,
        actor TEXT NOT NULL,
        action TEXT NOT NULL,
        scope TEXT NOT NULL,
        detail TEXT NOT NULL)""")
}

internal fun readExecutionLimits(database: Path, issueNumber: Int): ExecutionLimits {
    if (!Files.isRegularFile(database)) return ExecutionLimits.DEFAULT
    return RuntimeDatabase(database).read { c ->
        if (c.rows("SELECT 1 FROM sqlite_master WHERE type='table' AND name='owner_limits'") { 1 }.isEmpty())
            return@read ExecutionLimits.DEFAULT
        fun read(scope: String) = c.rows("""SELECT max_corrections,coding_timeout_seconds,review_timeout_seconds
            FROM owner_limits WHERE scope=?""", scope) {
            ExecutionLimits(it.getInt(1), it.getInt(2), it.getInt(3))
        }.singleOrNull()
        read("issue:$issueNumber") ?: read("default") ?: ExecutionLimits.DEFAULT
    }
}

internal fun writeExecutionLimits(
    database: Path,
    scope: String,
    limits: ExecutionLimits,
    actor: String = "owner:local-command",
): String {
    require(scope == "default" || scope.matches(Regex("issue:[1-9][0-9]*")))
    require(limits.maxCorrections in 0..2 &&
        limits.codingTimeoutSeconds in StageBudget.MIN_SECONDS..StageBudget.MAX_SECONDS &&
        limits.reviewTimeoutSeconds in StageBudget.MIN_SECONDS..StageBudget.MAX_SECONDS) {
        "LIMIT_OUTSIDE_CODE_CAP"
    }
    require(actor.matches(Regex("owner:[A-Za-z0-9._:-]{1,64}")))
    return RuntimeDatabase(database).write { c ->
        ensureOwnerSchema(c)
        val current = c.rows("""SELECT max_corrections,coding_timeout_seconds,review_timeout_seconds,version
            FROM owner_limits WHERE scope=?""", scope) {
            ExecutionLimits(it.getInt(1), it.getInt(2), it.getInt(3)) to it.getInt(4)
        }.singleOrNull()
        if (current?.first == limits) return@write "LIMITS_UNCHANGED ${current.second}"
        val version = (current?.second ?: 0) + 1
        val now = Instant.now().toString()
        val written = if (current == null) {
            c.mutate("""INSERT INTO owner_limits(scope,max_corrections,coding_timeout_seconds,review_timeout_seconds,version,actor,recorded_at)
                VALUES (?,?,?,?,?,?,?)""",
                scope, limits.maxCorrections, limits.codingTimeoutSeconds, limits.reviewTimeoutSeconds, version, actor, now)
        } else {
            c.mutate("""UPDATE owner_limits SET max_corrections=?,coding_timeout_seconds=?,review_timeout_seconds=?,
                version=?,actor=?,recorded_at=? WHERE scope=? AND version=?""",
                limits.maxCorrections, limits.codingTimeoutSeconds, limits.reviewTimeoutSeconds,
                version, actor, now, scope, current.second)
        }
        check(written == 1)
        c.mutate("INSERT INTO owner_audit(recorded_at,actor,action,scope,detail) VALUES (?,?,?,?,?)",
            now, actor, "SET_LIMITS", scope,
            "corrections=${limits.maxCorrections};coding=${limits.codingTimeoutSeconds};review=${limits.reviewTimeoutSeconds};version=$version")
        "LIMITS_SET $scope v$version corrections=${limits.maxCorrections} coding=${limits.codingTimeoutSeconds}s review=${limits.reviewTimeoutSeconds}s"
    }
}

/** Records intent and a controller-side flag. Does not launch, signal a guest, or send GitHub writes. */
internal fun requestOwnerCancel(
    database: Path,
    root: Path,
    issueNumber: Int,
    actor: String = "owner:local-command",
): String {
    require(issueNumber > 0)
    require(actor.matches(Regex("owner:[A-Za-z0-9._:-]{1,64}")))
    val dir = root.resolve("control")
    if (!Files.isDirectory(dir))
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    val flag = ownerCancelFlag(root, issueNumber)
    if (!Files.exists(flag)) {
        try { Files.writeString(flag, Instant.now().toString(), CREATE_NEW, WRITE) }
        catch (_: java.nio.file.FileAlreadyExistsException) { /* concurrent owner command */ }
    }
    return RuntimeDatabase(database).write { c ->
        ensureOwnerSchema(c)
        val now = Instant.now().toString()
        val existing = c.rows("SELECT state FROM owner_cancels WHERE issue_number=?", issueNumber) { it.getString(1) }.singleOrNull()
        if (existing == null) {
            c.mutate("INSERT INTO owner_cancels(issue_number,state,detail,actor,recorded_at) VALUES (?,?,?,?,?)",
                issueNumber, "requested", "OWNER_REQUESTED", actor, now)
            c.mutate("INSERT INTO owner_audit(recorded_at,actor,action,scope,detail) VALUES (?,?,?,?,?)",
                now, actor, "CANCEL_REQUESTED", "issue:$issueNumber", "requested")
        }
        "CANCEL_REQUESTED #$issueNumber"
    }
}

internal fun ownerCancelRequested(root: Path, issueNumber: Int): Boolean =
    Files.isRegularFile(ownerCancelFlag(root, issueNumber))

internal fun recordOwnerCancel(
    database: Path,
    issueNumber: Int,
    state: String,
    detail: String,
    actor: String = "owner:local-command",
) {
    require(state in setOf("requested", "cancelled", "blocked", "unknown"))
    RuntimeDatabase(database).write { c ->
        ensureOwnerSchema(c)
        val now = Instant.now().toString()
        val updated = c.mutate("""UPDATE owner_cancels SET state=?,detail=?,actor=?,recorded_at=? WHERE issue_number=?""",
            state, detail.take(500), actor, now, issueNumber)
        if (updated == 0)
            c.mutate("INSERT INTO owner_cancels(issue_number,state,detail,actor,recorded_at) VALUES (?,?,?,?,?)",
                issueNumber, state, detail.take(500), actor, now)
        c.mutate("INSERT INTO owner_audit(recorded_at,actor,action,scope,detail) VALUES (?,?,?,?,?)",
            now, actor, "CANCEL_RESULT", "issue:$issueNumber", "$state:${detail.take(200)}")
    }
}

internal fun describeOwnerControls(database: Path): String {
    if (!Files.isRegularFile(database)) return ""
    // Read-only: status must not take a write lock or migrate while the service runs.
    return java.sql.DriverManager.getConnection("jdbc:sqlite:file:$database?mode=ro").use { c ->
        if (c.rows("SELECT 1 FROM sqlite_master WHERE type='table' AND name='owner_limits'") { 1 }.isEmpty())
            return@use ""
        val limits = c.rows("""SELECT scope,max_corrections,coding_timeout_seconds,review_timeout_seconds,version
            FROM owner_limits ORDER BY scope""") {
            "${it.getString(1)}: corrections=${it.getInt(2)} coding=${it.getInt(3)}s review=${it.getInt(4)}s v${it.getInt(5)}"
        }
        val cancels = if (c.rows("SELECT 1 FROM sqlite_master WHERE name='owner_cancels'") { 1 }.isEmpty()) emptyList()
            else c.rows("SELECT issue_number,state,detail FROM owner_cancels ORDER BY issue_number") {
                "Cancel #${it.getInt(1)}: ${it.getString(2)} ${it.getString(3)}"
            }
        if (limits.isEmpty() && cancels.isEmpty()) return@use ""
        (listOf("Limits code cap: corrections<=2, stage<=${StageBudget.MAX_SECONDS}s") + limits + cancels).joinToString("\n")
    }
}

internal fun formatOwnerReport(
    testsRun: Int?,
    failures: Int?,
    errors: Int?,
    verdict: String?,
    findings: List<String>,
): String {
    fun plain(value: String) = value.replace(Regex("[\\p{Cntrl}]"), " ").trim()
    val tests = if (testsRun == null) "Тесты: нет сохранённого итога."
        else "Тесты: $testsRun выполнено, сбои ${failures ?: "?"} , ошибки ${errors ?: "?"}."
    val review = when (verdict) {
        "PASS" -> "Независимое ревью: замечаний нет."
        "CHANGES_REQUIRED" -> "Независимое ревью: " + findings.take(5).joinToString("; ") { plain(it) }.take(500)
        else -> "Независимое ревью: нет сохранённого вердикта."
    }
    val risk = "Риски: слияние вручную; денежный лимит провайдера не контролируется; исправлений не больше 2; стадия не дольше ${StageBudget.MAX_SECONDS} с."
    return listOf(tests, review, risk).joinToString("\n").take(1200)
}
