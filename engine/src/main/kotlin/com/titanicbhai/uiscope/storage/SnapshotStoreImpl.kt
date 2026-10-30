package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.Remedy
import com.titanicbhai.uiscope.export.CaptureJson
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.CaptureWarning
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.Screenshot
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.snapshot.LoadedSnapshot
import com.titanicbhai.uiscope.snapshot.RetentionPlanner
import com.titanicbhai.uiscope.snapshot.RetentionPolicy
import com.titanicbhai.uiscope.snapshot.RetentionResult
import com.titanicbhai.uiscope.snapshot.SnapshotMeta
import com.titanicbhai.uiscope.snapshot.SnapshotSort
import com.titanicbhai.uiscope.snapshot.SnapshotStore
import com.titanicbhai.uiscope.snapshot.SnapshotTargetType
import com.titanicbhai.uiscope.snapshot.StorageUsage
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.withContext

/**
 * Snapshots on disk (plan §3.5, §4.7). Metadata lives in the SQLite file; the tree and the screenshot are files under
 * `<snapshots>/<id>/capture.json` and `screenshot.png`.
 *
 * Guarantees (G7):
 * - **Listing never opens a capture file**; it reads rows only.
 * - **A snapshot appears all at once.** Files are written into `.tmp-<id>`, the folder is renamed to `<id>` (atomic), and only then is
 *   the row inserted. A crash earlier leaves a `.tmp-*` folder that [open] deletes; a crash between rename and insert leaves a folder
 *   without a row that [open] adopts.
 * - **Delete removes files.** The folder is renamed to `.trash-<id>` first (so it can never be adopted again), then the row goes, then
 *   the files; a leftover `.trash-*` folder is removed at the next start.
 * - Paths are built only from validated ids and from file names this class chose; names found inside a capture file are never used.
 *
 * Never logged: titles, notes, labels. Ids are random and safe to log.
 */
class SnapshotStoreImpl(
    private val database: SnapshotDatabase,
    private val snapshotsDir: Path,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val appVersion: String = "0.0.0",
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Orders titles for [SnapshotSort.TITLE]; the user's locale by default, so "Ä" sorts with "A". */
    private val collator: java.text.Collator = java.text.Collator.getInstance(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    /** All reads of capture files go through here (tests count them to prove that listing opens none). */
    private val readFile: (Path) -> ByteArray = { Files.readAllBytes(it) },
    private val logger: Logger = Logger.getLogger("uiscope.snapshots"),
) : SnapshotStore {

    /**
     * Opens the database, removes leftovers of interrupted saves and deletes, drops rows whose folder is gone and adopts folders
     * that have no row. Blocking: call it from an I/O thread during start-up.
     */
    fun open(): Outcome<Unit> {
        when (val opened = database.open()) {
            is Outcome.Failure -> return opened
            is Outcome.Success -> Unit
        }
        return try {
            Files.createDirectories(snapshotsDir)
            recover()
            Outcome.Success(Unit)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "snapshot recovery failed", e)
            Outcome.Failure(CodedError(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE, "Could not prepare the snapshots folder (${e.message})"))
        } catch (e: SQLException) {
            logger.log(Level.WARNING, "snapshot recovery failed", e)
            Outcome.Failure(CodedError(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE, "Could not read the snapshot database (${e.message})"))
        }
    }

    fun close() = database.close()

    override suspend fun save(capture: Capture, title: String?, note: String): Outcome<SnapshotMeta> = withContext(dispatchers.io) {
        val id = idGenerator()
        if (!validId(id)) return@withContext fail(ErrorCodes.SNAPSHOT_SAVE_FAILED, "Internal error: invalid snapshot id")
        val temp = snapshotsDir.resolve(TEMP_PREFIX + id)
        val finalDir = snapshotsDir.resolve(id)
        try {
            Files.createDirectories(temp)
            val screenshotSource = capture.screenshot?.let { Path.of(it.file) }?.takeIf { Files.isRegularFile(it) }
            var stored = capture
            var screenshotBytes = 0L
            if (capture.screenshot != null && screenshotSource != null) {
                Files.copy(screenshotSource, temp.resolve(SCREENSHOT_FILE), StandardCopyOption.REPLACE_EXISTING)
                screenshotBytes = Files.size(temp.resolve(SCREENSHOT_FILE))
                stored = capture.copy(screenshot = Screenshot(SCREENSHOT_FILE, capture.screenshot!!.width, capture.screenshot!!.height))
            } else if (capture.screenshot != null) {
                logger.warning("snapshot $id: the screenshot file of the capture is missing; saving the tree without it")
                stored = capture.copy(screenshot = null)
            }
            val json = CaptureJson.encode(stored, appVersion, includeSerial = true).toByteArray(Charsets.UTF_8)
            Files.write(temp.resolve(CAPTURE_FILE), json)
            moveAtomically(temp, finalDir)
            val meta = describe(id, stored, title, note, json.size + screenshotBytes, hasScreenshot = stored.screenshot != null)
            try {
                database.inTransaction { c -> insert(c, meta) }
            } catch (e: SQLException) {
                deleteTree(finalDir)
                throw e
            }
            logger.info("snapshot saved id=$id nodes=${meta.nodeCount} bytes=${meta.bytes}")
            Outcome.Success(meta)
        } catch (e: IOException) {
            deleteTree(temp)
            storageFailure(ErrorCodes.SNAPSHOT_SAVE_FAILED, "save", e)
        } catch (e: SQLException) {
            deleteTree(temp)
            sqlFailure(ErrorCodes.SNAPSHOT_SAVE_FAILED, "save", e)
        }
    }

    override suspend fun list(query: String?, sort: SnapshotSort): Outcome<List<SnapshotMeta>> = withContext(dispatchers.io) {
        try {
            val all = database.withConnection { c -> readAll(c) }
            val needle = query?.trim()?.lowercase().orEmpty()
            val filtered = if (needle.isEmpty()) all else all.filter { m ->
                listOf(m.title, m.note, m.appLabel, m.targetLabel).any { it.lowercase().contains(needle) }
            }
            Outcome.Success(
                when (sort) {
                    SnapshotSort.NEWEST -> filtered.sortedWith(compareByDescending<SnapshotMeta> { it.createdAt }.thenBy { it.id })
                    SnapshotSort.OLDEST -> filtered.sortedWith(compareBy<SnapshotMeta> { it.createdAt }.thenBy { it.id })
                    SnapshotSort.TITLE -> filtered.sortedWith(compareBy<SnapshotMeta, String>(collator) { it.title }.thenBy { it.id })
                },
            )
        } catch (e: SQLException) {
            sqlFailure(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE, "list", e)
        }
    }

    override suspend fun get(id: String): Outcome<SnapshotMeta> = withContext(dispatchers.io) {
        if (!validId(id)) return@withContext notFound()
        try {
            database.withConnection { c -> find(c, id) }?.let { Outcome.Success(it) } ?: notFound()
        } catch (e: SQLException) {
            sqlFailure(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE, "get", e)
        }
    }

    override suspend fun load(id: String): Outcome<LoadedSnapshot> = withContext(dispatchers.io) {
        val meta = when (val m = get(id)) {
            is Outcome.Failure -> return@withContext m
            is Outcome.Success -> m.value
        }
        val dir = snapshotsDir.resolve(meta.id)
        val bytes = try {
            readFile(dir.resolve(meta.captureFile))
        } catch (e: IOException) {
            logger.log(Level.WARNING, "snapshot ${meta.id}: capture file unreadable", e)
            return@withContext fail(ErrorCodes.SNAPSHOT_NOT_FOUND, "The files of this snapshot are missing or unreadable.")
        }
        val decoded = when (val d = CaptureJson.decode(String(bytes, Charsets.UTF_8), fallbackId = meta.id)) {
            is Outcome.Failure -> return@withContext d
            is Outcome.Success -> d.value
        }
        // The screenshot name comes from our own row, never from the file (a hand-edited capture.json cannot point elsewhere).
        val shotPath = meta.screenshotFile?.let { dir.resolve(it) }?.takeIf { Files.isRegularFile(it) }
        val capture = when {
            decoded.screenshot == null || shotPath != null ->
                decoded.copy(screenshot = decoded.screenshot?.copy(file = shotPath!!.toString()))
            else -> decoded.copy(
                screenshot = null,
                warnings = decoded.warnings + CaptureWarning(ErrorCodes.SCREENSHOT_FAILED, "The saved screenshot file is missing"),
            )
        }
        Outcome.Success(LoadedSnapshot(meta, capture, shotPath))
    }

    override suspend fun update(id: String, title: String?, note: String?, pinned: Boolean?): Outcome<SnapshotMeta> = withContext(dispatchers.io) {
        if (!validId(id)) return@withContext notFound()
        try {
            database.inTransaction { c ->
                val current = find(c, id) ?: return@inTransaction notFound()
                val next = current.copy(
                    title = title?.trim()?.takeIf { it.isNotEmpty() } ?: current.title,
                    note = note ?: current.note,
                    pinned = pinned ?: current.pinned,
                )
                c.prepareStatement("UPDATE snapshot SET title = ?, note = ?, pinned = ? WHERE id = ?").use { s ->
                    s.setString(1, next.title)
                    s.setString(2, next.note)
                    s.setInt(3, if (next.pinned) 1 else 0)
                    s.setString(4, id)
                    s.executeUpdate()
                }
                Outcome.Success(next)
            }
        } catch (e: SQLException) {
            sqlFailure(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE, "update", e)
        }
    }

    override suspend fun delete(id: String): Outcome<Unit> = withContext(dispatchers.io) { deleteNow(id) }

    override suspend fun enforce(policy: RetentionPolicy, protect: Set<String>): Outcome<RetentionResult> = withContext(dispatchers.io) {
        try {
            val all = database.withConnection { c -> readAll(c) }
            val victims = RetentionPlanner.victims(all, policy, protect)
            val deleted = ArrayList<String>()
            var freed = 0L
            for (v in victims) {
                when (val r = deleteNow(v.id)) {
                    is Outcome.Failure -> logger.warning("retention could not delete ${v.id}: ${r.error.code}")
                    is Outcome.Success -> {
                        deleted += v.id
                        freed += v.bytes
                    }
                }
            }
            if (deleted.isNotEmpty()) logger.info("retention deleted ${deleted.size} snapshot(s), freed $freed bytes")
            Outcome.Success(RetentionResult(deleted, freed))
        } catch (e: SQLException) {
            sqlFailure(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE, "enforce", e)
        }
    }

    override suspend fun usage(): Outcome<StorageUsage> = withContext(dispatchers.io) {
        try {
            val all = database.withConnection { c -> readAll(c) }
            val pinned = all.filter { it.pinned }
            Outcome.Success(StorageUsage(all.size, all.sumOf { it.bytes }, pinned.size, pinned.sumOf { it.bytes }))
        } catch (e: SQLException) {
            sqlFailure(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE, "usage", e)
        }
    }

    override suspend fun clearAll(includePinned: Boolean): Outcome<Int> = withContext(dispatchers.io) {
        try {
            val targets = database.withConnection { c -> readAll(c) }.filter { includePinned || !it.pinned }
            var count = 0
            for (m in targets) if (deleteNow(m.id) is Outcome.Success) count++
            Outcome.Success(count)
        } catch (e: SQLException) {
            sqlFailure(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE, "clear", e)
        }
    }

    // ---------------------------------------------------------------- internals

    private fun deleteNow(id: String): Outcome<Unit> {
        if (!validId(id)) return notFound()
        try {
            val exists = database.withConnection { c -> find(c, id) } != null
            if (!exists) return notFound()
            val dir = snapshotsDir.resolve(id)
            val trash = snapshotsDir.resolve(TRASH_PREFIX + id)
            var renamed = false
            if (Files.exists(dir)) {
                deleteTree(trash)
                try {
                    moveAtomically(dir, trash)
                    renamed = true
                } catch (e: IOException) {
                    logger.log(Level.WARNING, "snapshot $id: could not move its folder away (in use?)", e)
                    return fail(ErrorCodes.SNAPSHOT_DELETE_FAILED, "The snapshot's files are in use and could not be removed. Close anything showing it and try again.", Remedy.RETRY)
                }
            }
            try {
                database.inTransaction { c ->
                    c.prepareStatement("DELETE FROM snapshot WHERE id = ?").use { s ->
                        s.setString(1, id)
                        s.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                if (renamed) restore(trash, dir)
                throw e
            }
            if (renamed && !deleteTree(trash)) logger.warning("snapshot $id: its files could not all be deleted now; they are removed at the next start")
            return Outcome.Success(Unit)
        } catch (e: SQLException) {
            return sqlFailure(ErrorCodes.SNAPSHOT_DELETE_FAILED, "delete", e)
        }
    }

    private fun restore(trash: Path, dir: Path) {
        try {
            moveAtomically(trash, dir)
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not put a snapshot folder back after a failed delete", e)
        }
    }

    private fun recover() {
        Files.newDirectoryStream(snapshotsDir).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (name.startsWith(TEMP_PREFIX) || name.startsWith(TRASH_PREFIX)) {
                    if (!deleteTree(entry)) logger.warning("could not remove leftover folder $name")
                }
            }
        }
        val rows = database.withConnection { c -> readAll(c) }
        val known = rows.map { it.id }.toSet()
        for (row in rows) {
            if (!Files.isDirectory(snapshotsDir.resolve(row.id))) {
                logger.warning("snapshot ${row.id} has no folder; dropping its row")
                database.inTransaction { c -> c.prepareStatement("DELETE FROM snapshot WHERE id = ?").use { it.setString(1, row.id); it.executeUpdate() } }
            }
        }
        Files.newDirectoryStream(snapshotsDir).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (Files.isDirectory(entry) && !name.startsWith(".") && validId(name) && name !in known) adopt(name, entry)
            }
        }
    }

    private fun adopt(id: String, dir: Path) {
        val captureFile = dir.resolve(CAPTURE_FILE)
        val capture = try {
            when (val d = CaptureJson.decode(String(readFile(captureFile), Charsets.UTF_8), fallbackId = id)) {
                is Outcome.Success -> d.value
                is Outcome.Failure -> null
            }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "folder $id has no readable capture file; leaving it alone", e)
            null
        }
        if (capture == null) {
            logger.warning("folder $id is not a readable snapshot; leaving it alone")
            return
        }
        val hasShot = Files.isRegularFile(dir.resolve(SCREENSHOT_FILE))
        val bytes = Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        val stored = if (hasShot) capture.copy(screenshot = capture.screenshot?.copy(file = SCREENSHOT_FILE)) else capture.copy(screenshot = null)
        val meta = describe(id, stored, null, "", bytes, hasShot).copy(createdAt = Files.getLastModifiedTime(dir).toInstant())
        database.inTransaction { c -> insert(c, meta) }
        logger.info("adopted snapshot folder $id")
    }

    private fun describe(id: String, capture: Capture, title: String?, note: String, bytes: Long, hasScreenshot: Boolean): SnapshotMeta {
        val (type, targetLabel, appLabel) = when (val t = capture.target) {
            is Target.Android -> Triple(SnapshotTargetType.ANDROID, t.device.model ?: "Android device", t.foregroundPackage ?: "Android")
            is Target.Windows -> Triple(SnapshotTargetType.WINDOWS, t.window.title.ifBlank { t.window.processName }, t.window.processName)
        }
        val shown = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(capture.takenAt)
        val label = if (type == SnapshotTargetType.WINDOWS) targetLabel else appLabel
        return SnapshotMeta(
            id = id,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: "$label · $shown",
            note = note,
            pinned = false,
            createdAt = clock.instant(),
            capturedAt = capture.takenAt,
            targetType = type,
            targetLabel = targetLabel,
            appLabel = appLabel,
            nodeCount = countNodes(capture.roots),
            screenshotFile = if (hasScreenshot) SCREENSHOT_FILE else null,
            captureFile = CAPTURE_FILE,
            bytes = bytes,
        )
    }

    private fun countNodes(roots: List<ElementNode>): Int {
        var count = 0
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val n = pending.removeLast()
            count++
            n.children.forEach { pending.addLast(it) }
        }
        return count
    }

    private fun insert(c: java.sql.Connection, m: SnapshotMeta) {
        c.prepareStatement(
            "INSERT INTO snapshot (id, title, note, pinned, created_at, captured_at, target_type, target_label, app_label, node_count, screenshot_file, capture_file, bytes) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { s ->
            s.setString(1, m.id)
            s.setString(2, m.title)
            s.setString(3, m.note)
            s.setInt(4, if (m.pinned) 1 else 0)
            s.setLong(5, m.createdAt.toEpochMilli())
            s.setLong(6, m.capturedAt.toEpochMilli())
            s.setString(7, m.targetType.name)
            s.setString(8, m.targetLabel)
            s.setString(9, m.appLabel)
            s.setInt(10, m.nodeCount)
            s.setString(11, m.screenshotFile)
            s.setString(12, m.captureFile)
            s.setLong(13, m.bytes)
            s.executeUpdate()
        }
    }

    private fun readAll(c: java.sql.Connection): List<SnapshotMeta> =
        c.createStatement().use { s -> s.executeQuery("SELECT * FROM snapshot").use { rs -> buildList { while (rs.next()) add(row(rs)) } } }

    private fun find(c: java.sql.Connection, id: String): SnapshotMeta? =
        c.prepareStatement("SELECT * FROM snapshot WHERE id = ?").use { s ->
            s.setString(1, id)
            s.executeQuery().use { rs -> if (rs.next()) row(rs) else null }
        }

    private fun row(rs: ResultSet) = SnapshotMeta(
        id = rs.getString("id"),
        title = rs.getString("title"),
        note = rs.getString("note"),
        pinned = rs.getInt("pinned") == 1,
        createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
        capturedAt = Instant.ofEpochMilli(rs.getLong("captured_at")),
        targetType = SnapshotTargetType.entries.firstOrNull { it.name == rs.getString("target_type") } ?: SnapshotTargetType.ANDROID,
        targetLabel = rs.getString("target_label"),
        appLabel = rs.getString("app_label"),
        nodeCount = rs.getInt("node_count"),
        screenshotFile = rs.getString("screenshot_file"),
        captureFile = rs.getString("capture_file"),
        bytes = rs.getLong("bytes"),
    )

    private fun moveAtomically(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            logger.log(Level.FINE, "atomic move not supported, using a plain move", e)
            Files.move(from, to)
        }
    }

    /** Deletes a folder tree; true when nothing is left. Files that cannot be deleted are logged by name only. */
    private fun deleteTree(dir: Path): Boolean {
        if (!Files.exists(dir)) return true
        try {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            return true
        } catch (e: IOException) {
            logger.log(Level.WARNING, "could not delete ${dir.fileName}", e)
            return false
        }
    }

    private fun validId(id: String) = ID_PATTERN.matches(id)

    private fun notFound() = fail(ErrorCodes.SNAPSHOT_NOT_FOUND, "That snapshot no longer exists.")

    private fun fail(code: String, detail: String, remedy: Remedy? = null) = Outcome.Failure(CodedError(code, detail, remedy))

    private fun storageFailure(code: String, what: String, e: IOException): Outcome.Failure {
        logger.log(Level.WARNING, "snapshot $what failed", e)
        val full = (e.message ?: "").let { it.contains("No space left", true) || it.contains("not enough space", true) || it.contains("disk full", true) }
        return if (full) fail(ErrorCodes.DISK_FULL, "The disk is full.", Remedy.FREE_DISK_SPACE) else fail(code, "Could not $what the snapshot (${e.javaClass.simpleName}).", Remedy.RETRY)
    }

    private fun sqlFailure(code: String, what: String, e: SQLException): Outcome.Failure {
        logger.log(Level.WARNING, "snapshot $what failed (sqlite code ${e.errorCode})", e)
        val full = e.errorCode == SQLITE_FULL || (e.message ?: "").contains("disk is full", true)
        return if (full) fail(ErrorCodes.DISK_FULL, "The disk is full.", Remedy.FREE_DISK_SPACE) else fail(code, "The snapshot database reported a problem.", Remedy.RETRY)
    }

    companion object {
        const val CAPTURE_FILE = "capture.json"
        const val SCREENSHOT_FILE = "screenshot.png"
        private const val TEMP_PREFIX = ".tmp-"
        private const val TRASH_PREFIX = ".trash-"
        private const val SQLITE_FULL = 13
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,63}")
    }
}
