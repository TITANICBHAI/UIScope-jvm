package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.logging.Level
import java.util.logging.Logger

/** One forward-only schema step. [version] numbers must run 1, 2, 3 … without gaps. */
class SnapshotMigration(val version: Int, val statements: List<String>)

/** The snapshot database schema (plan §3.5). Version 1 is the snapshots-only baseline; every later change adds a migration. */
object SnapshotSchema {
    val V1 = SnapshotMigration(
        1,
        listOf(
            """
            CREATE TABLE snapshot (
                id              TEXT    NOT NULL PRIMARY KEY,
                title           TEXT    NOT NULL,
                note            TEXT    NOT NULL DEFAULT '',
                pinned          INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0, 1)),
                created_at      INTEGER NOT NULL,
                captured_at     INTEGER NOT NULL,
                target_type     TEXT    NOT NULL,
                target_label    TEXT    NOT NULL,
                app_label       TEXT    NOT NULL,
                node_count      INTEGER NOT NULL,
                screenshot_file TEXT,
                capture_file    TEXT    NOT NULL,
                bytes           INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX snapshot_created ON snapshot (created_at DESC, id)",
        ),
    )

    val ALL: List<SnapshotMigration> = listOf(V1)
}

/**
 * The SQLite file behind the snapshot list (plan §3.5): metadata only, WAL, `busy_timeout=5000`, `foreign_keys=ON`.
 *
 * Plain `java.sql` is used (the SQLite driver is a runtime dependency only). Every operation opens its own short-lived connection, so
 * threads never share one; SQLite's WAL mode lets readers and a writer run side by side and the busy timeout makes writers queue.
 * [open] applies migrations in one transaction each and records the version in `PRAGMA user_version`.
 *
 * @param sqliteTmpDir If given, `org.sqlite.tmpdir` is pointed there so the driver's native library is unpacked into the app's own
 *   folder and does not depend on `%TEMP%` (plan §3.5). It is a JVM-wide setting and must be set before the first connection.
 */
class SnapshotDatabase(
    private val file: Path,
    private val migrations: List<SnapshotMigration> = SnapshotSchema.ALL,
    private val sqliteTmpDir: Path? = null,
    private val logger: Logger = Logger.getLogger("uiscope.snapshots.db"),
) {
    init {
        require(migrations.isNotEmpty() && migrations.map { it.version } == (1..migrations.size).toList()) {
            "Migrations must be numbered 1..n without gaps"
        }
    }

    /**
     * Creates or upgrades the database. A file that is not a database is moved to `<name>.corrupt` and a new one is created (the
     * snapshot folders are still on disk; the store re-adopts them). A database from a *newer* UIScope is left untouched and reported.
     */
    fun open(): Outcome<Unit> {
        sqliteTmpDir?.let {
            Files.createDirectories(it)
            System.setProperty("org.sqlite.tmpdir", it.toAbsolutePath().toString())
        }
        return try {
            Files.createDirectories(file.toAbsolutePath().parent)
            migrate()
        } catch (e: SQLException) {
            if (isNotADatabase(e) && Files.exists(file)) {
                logger.log(Level.WARNING, "snapshot database is damaged (sqlite code ${e.errorCode}); keeping a copy and starting a new one")
                quarantine()
                try {
                    migrate()
                } catch (second: SQLException) {
                    failure("Could not create the snapshot database (${second.message})", second)
                }
            } else {
                failure("Could not open the snapshot database (${e.message})", e)
            }
        } catch (e: java.io.IOException) {
            failure("Could not prepare the snapshot database folder (${e.message})", e)
        }
    }

    /** Runs [block] on a fresh connection with the standard pragmas; the connection is closed afterwards. */
    fun <T> withConnection(block: (Connection) -> T): T {
        val connection = connect()
        try {
            return block(connection)
        } finally {
            connection.close()
        }
    }

    /** Runs [block] in a transaction: committed when it returns, rolled back when it throws. */
    fun <T> inTransaction(block: (Connection) -> T): T = withConnection { c ->
        c.autoCommit = false
        try {
            val result = block(c)
            c.commit()
            result
        } catch (e: SQLException) {
            rollbackQuietly(c, e)
            throw e
        } catch (e: RuntimeException) {
            rollbackQuietly(c, e)
            throw e
        }
    }

    /** Folds the write-ahead log back into the main file; call when the application exits. */
    fun close() {
        try {
            withConnection { c -> c.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
        } catch (e: SQLException) {
            logger.log(Level.WARNING, "could not checkpoint the snapshot database on exit", e)
        }
    }

    fun userVersion(): Int = withConnection { c ->
        c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
    }

    private fun migrate(): Outcome<Unit> {
        withConnection { c ->
            c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        }
        val current = userVersion()
        val latest = migrations.last().version
        if (current > latest) {
            return Outcome.Failure(
                CodedError(
                    ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE,
                    "The snapshot database was written by a newer version of UIScope (database version $current, this version understands $latest). Update UIScope.",
                ),
            )
        }
        for (m in migrations.filter { it.version > current }) {
            inTransaction { c ->
                c.createStatement().use { s ->
                    m.statements.forEach { sql -> s.execute(sql) }
                    s.execute("PRAGMA user_version = ${m.version}")
                }
            }
            logger.info("snapshot database migrated to version ${m.version}")
        }
        return Outcome.Success(Unit)
    }

    private fun connect(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())
        try {
            connection.createStatement().use { s ->
                s.execute("PRAGMA busy_timeout=5000")
                s.execute("PRAGMA foreign_keys=ON")
            }
        } catch (e: SQLException) {
            connection.close()
            throw e
        }
        return connection
    }

    private fun rollbackQuietly(c: Connection, cause: Exception) {
        try {
            c.rollback()
        } catch (e: SQLException) {
            logger.log(Level.WARNING, "rollback failed after: ${cause.javaClass.simpleName}", e)
        }
    }

    private fun quarantine() {
        val target = file.resolveSibling(file.fileName.toString() + ".corrupt")
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
        listOf("-wal", "-shm").forEach { Files.deleteIfExists(file.resolveSibling(file.fileName.toString() + it)) }
    }

    private fun isNotADatabase(e: SQLException): Boolean =
        e.errorCode == SQLITE_NOTADB || e.errorCode == SQLITE_CORRUPT ||
            (e.message ?: "").contains("not a database", ignoreCase = true) || (e.message ?: "").contains("malformed", ignoreCase = true)

    private fun failure(detail: String, cause: Exception): Outcome<Unit> {
        logger.log(Level.WARNING, "snapshot database problem", cause)
        return Outcome.Failure(CodedError(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE, detail))
    }

    private companion object {
        const val SQLITE_CORRUPT = 11
        const val SQLITE_NOTADB = 26
    }
}
