package dev.orchestrator.storage

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Uses the SAME SQLite file, schema and writer lock as intake/policy/catalog. */
internal class RuntimeDatabase(private val file: Path) {
    init { SqliteTaskStore(file) }

    fun <T> read(block: (Connection) -> T): T = connection().use(block)

    fun <T> write(block: (Connection) -> T): T = connection().use { conn ->
        conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val result = block(conn)
            conn.createStatement().use { it.execute("COMMIT") }
            result
        } catch (failure: Throwable) {
            try { conn.createStatement().use { it.execute("ROLLBACK") } }
            catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun connection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath().normalize()}")
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
}

internal fun Connection.mutate(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { s ->
    args.forEachIndexed { index, value -> s.setObject(index + 1, value) }
    s.executeUpdate()
}

internal fun <T> Connection.rows(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { s ->
        args.forEachIndexed { index, value -> s.setObject(index + 1, value) }
        s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
    }
