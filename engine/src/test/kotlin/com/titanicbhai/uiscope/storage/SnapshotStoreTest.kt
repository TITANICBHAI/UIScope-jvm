package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.Screenshot
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.model.WindowInfo
import com.titanicbhai.uiscope.snapshot.RetentionPolicy
import com.titanicbhai.uiscope.snapshot.SnapshotMeta
import com.titanicbhai.uiscope.snapshot.SnapshotSort
import com.titanicbhai.uiscope.snapshot.SnapshotStore
import com.titanicbhai.uiscope.snapshot.SnapshotTargetType
import com.titanicbhai.uiscope.testing.FakeClock
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.n
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** G7 (B7, M12) and the snapshot database's migration harness (P1-7). Runs against a real SQLite. */
class SnapshotStoreTest {
    private fun SnapshotStore.listAll() = blocking1 { list() }.assertSuccess()

    // ---------------------------------------------------------------- basics

    @Test
    fun `a saved snapshot lists and loads back with tree screenshot and device`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val cap = rig.cap("Say \"hi\"\nnow")
        val meta = blocking1 { rig.store.save(cap, "My title", "my note") }.assertSuccess()
        assertEquals("My title", meta.title)
        assertEquals(2, meta.nodeCount)
        assertEquals(listOf("capture.json", "screenshot.png"), rig.files(meta.id))
        assertEquals(SnapshotTargetType.ANDROID, meta.targetType)

        val loaded = blocking1 { rig.store.load(meta.id) }.assertSuccess()
        assertEquals(cap.roots, loaded.capture.roots)
        assertEquals(cap.target, loaded.capture.target) // the local copy keeps the serial
        assertEquals(rig.snapshots.resolve(meta.id).resolve("screenshot.png"), loaded.screenshotPath)
        assertEquals(loaded.screenshotPath.toString(), loaded.capture.screenshot!!.file)
        assertEquals(1080, loaded.capture.screenshot!!.width)
        assertEquals(1000L + Files.size(rig.snapshots.resolve(meta.id).resolve("capture.json")), meta.bytes)
        assertEquals(listOf(meta), rig.store.listAll())
        }
    }

    @Test
    fun `the default title is app and time and the screenshot may be missing`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val meta = rig.saveAt(5)
        assertEquals("com.example · 2026-01-01 00:00", meta.title) // package and the capture time (UTC), not the save time

        val noShot = blocking1 { rig.store.save(rig.cap(withShot = false)) }.assertSuccess()
        assertNull(noShot.screenshotFile)
        assertEquals(listOf("capture.json"), rig.files(noShot.id))

        val missing = rig.cap().copy(screenshot = Screenshot(dir.resolve("gone.png").toString(), 1, 1))
        val saved = blocking1 { rig.store.save(missing) }.assertSuccess()
        assertNull(saved.screenshotFile, "a screenshot file that vanished is saved without it, not as an error")
        }
    }

    @Test
    fun `windows captures are labelled by window and process`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val cap = rig.cap(withShot = false).copy(target = Target.Windows(WindowInfo("Untitled - Notepad", "notepad.exe", 4242)))
        val meta = blocking1 { rig.store.save(cap) }.assertSuccess()
        assertEquals(SnapshotTargetType.WINDOWS, meta.targetType)
        assertEquals("Untitled - Notepad", meta.targetLabel)
        assertEquals("notepad.exe", meta.appLabel)
        assertTrue(meta.title.startsWith("Untitled - Notepad · "))
        }
    }

    @Test
    fun `search and sort work on metadata`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        rig.saveAt(1, title = "Äpfel login")
        rig.saveAt(2, title = "banana")
        rig.saveAt(3, title = "Cherry")
        assertEquals(listOf("Cherry", "banana", "Äpfel login"), blocking1 { rig.store.list(sort = SnapshotSort.NEWEST) }.assertSuccess().map { it.title })
        assertEquals(listOf("Äpfel login", "banana", "Cherry"), blocking1 { rig.store.list(sort = SnapshotSort.OLDEST) }.assertSuccess().map { it.title })
        assertEquals(listOf("Äpfel login", "banana", "Cherry"), blocking1 { rig.store.list(sort = SnapshotSort.TITLE) }.assertSuccess().map { it.title })
        assertEquals(listOf("Äpfel login"), blocking1 { rig.store.list("ÄPFEL") }.assertSuccess().map { it.title })
        assertEquals(3, blocking1 { rig.store.list("com.example") }.assertSuccess().size, "the app label is searched too")
        assertEquals(0, blocking1 { rig.store.list("nothing like this") }.assertSuccess().size)
        }
    }

    @Test
    fun `rename note and pin persist and a blank title is ignored`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val id = rig.saveAt(1, title = "first").id
        val updated = blocking1 { rig.store.update(id, title = "  renamed  ", note = "a note", pinned = true) }.assertSuccess()
        assertEquals("renamed", updated.title)
        assertTrue(updated.pinned)
        assertEquals("renamed", blocking1 { rig.store.update(id, title = "   ") }.assertSuccess().title)
        val again = blocking1 { rig.store.get(id) }.assertSuccess()
        assertEquals(updated, again)
        blocking1 { rig.store.update("snap-9999", title = "x") }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
        }
    }

    // ---------------------------------------------------------------- G7

    @Test
    fun `retention deletes the PNG and capture files and pinned snapshots survive`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val ids = (1..5L).map { rig.saveAt(it).id }
        blocking1 { rig.store.update(ids[0], pinned = true) }.assertSuccess()
        val result = blocking1 { rig.store.enforce(RetentionPolicy(maxCount = 2, maxBytes = Long.MAX_VALUE)) }.assertSuccess()
        assertEquals(listOf(ids[1], ids[2]), result.deleted)
        assertTrue(result.freedBytes > 0)
        for (gone in listOf(ids[1], ids[2])) {
            assertFalse(Files.exists(rig.snapshots.resolve(gone)), "folder of $gone is still there")
        }
        for (kept in listOf(ids[0], ids[3], ids[4])) {
            assertEquals(listOf("capture.json", "screenshot.png"), rig.files(kept))
        }
        assertEquals(setOf(ids[0], ids[3], ids[4]), rig.store.listAll().map { it.id }.toSet())
        assertEquals(emptyList(), Files.list(rig.snapshots).use { s -> s.map { it.fileName.toString() }.filter { it.startsWith(".") }.toList() })
        }
    }

    @Test
    fun `the byte limit deletes oldest first and the just saved snapshot can be protected`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val a = rig.saveAt(1)
        val b = rig.saveAt(2)
        val c = rig.saveAt(3)
        val limit = c.bytes + 10
        val result = blocking1 { rig.store.enforce(RetentionPolicy(100, limit), protect = setOf(c.id)) }.assertSuccess()
        assertEquals(listOf(a.id, b.id), result.deleted)
        assertEquals(listOf(c.id), rig.store.listAll().map { it.id })
        // A single snapshot larger than the limit is kept when protected.
        assertEquals(emptyList(), blocking1 { rig.store.enforce(RetentionPolicy(100, 1), protect = setOf(c.id)) }.assertSuccess().deleted)
        }
    }

    @Test
    fun `listing opens no capture file`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        repeat(30) { rig.saveAt(it.toLong()) }
        val before = rig.reads.get()
        repeat(3) {
            assertEquals(30, rig.store.listAll().size)
            blocking1 { rig.store.list("x", SnapshotSort.TITLE) }.assertSuccess()
            blocking1 { rig.store.usage() }.assertSuccess()
            blocking1 { rig.store.get("snap-0001") }.assertSuccess()
        }
        assertEquals(before, rig.reads.get(), "a list/get/usage call read a capture file")
        // Even with every capture file destroyed, the list still works.
        Files.list(rig.snapshots).use { s -> s.forEach { Files.write(it.resolve("capture.json"), byteArrayOf(0)) } }
        assertEquals(30, rig.store.listAll().size)
        blocking1 { rig.store.load("snap-0001") }.assertFailure(ErrorCodes.PARSE_FAILED)
        }
    }

    @Test
    fun `a write that was cut off between the temp folder and the move is invisible and cleaned up`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val kept = rig.saveAt(1)
        // What a killed save leaves behind: a temp folder with a partial capture.
        val torn = rig.snapshots.resolve(".tmp-snap-0099")
        Files.createDirectories(torn)
        Files.writeString(torn.resolve("capture.json"), "{\"schemaVersion\": 1, \"roo")
        assertEquals(listOf(kept.id), rig.store.listAll().map { it.id })

        val restarted = SnapshotRig(dir)
        assertEquals(listOf(kept.id), restarted.store.listAll().map { it.id })
        assertFalse(Files.exists(torn), "the leftover temp folder was not removed at start-up")
        }
    }

    @Test
    fun `a crash after the move but before the row is repaired by adopting the folder`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val saved = rig.saveAt(1, title = "will lose its row")
        rig.db.inTransaction { c -> c.createStatement().use { it.executeUpdate("DELETE FROM snapshot") } }
        assertEquals(0, rig.store.listAll().size)

        val restarted = SnapshotRig(dir)
        val adopted = restarted.store.listAll()
        assertEquals(listOf(saved.id), adopted.map { it.id })
        assertEquals(2, adopted[0].nodeCount)
        assertEquals("screenshot.png", adopted[0].screenshotFile)
        blocking1 { restarted.store.load(saved.id) }.assertSuccess()
        }
    }

    @Test
    fun `a row whose folder disappeared is dropped at start-up`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val a = rig.saveAt(1)
        val b = rig.saveAt(2)
        rig.snapshots.resolve(a.id).toFile().deleteRecursively()
        assertEquals(listOf(b.id), SnapshotRig(dir).store.listAll().map { it.id })
        }
    }

    @Test
    fun `two threads can insert and list at the same time`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir, ids = { java.util.UUID.randomUUID().toString() })
        val errors = CopyOnWriteArrayList<String>()
        val pool = Executors.newFixedThreadPool(9)
        val start = CountDownLatch(1)
        val writers = (0 until 8).map { w ->
            pool.submit {
                start.await()
                repeat(10) { i ->
                    val out = blocking1 { rig.store.save(rig.cap("w$w-$i")) }
                    if (out !is Outcome.Success) errors += "save failed: $out"
                }
            }
        }
        val reader = pool.submit {
            start.await()
            while (writers.any { !it.isDone }) {
                val out = blocking1 { rig.store.list() }
                if (out !is Outcome.Success) errors += "list failed: $out"
            }
        }
        start.countDown()
        writers.forEach { it.get() }
        reader.get()
        pool.shutdown()
        assertEquals(emptyList(), errors)
        assertEquals(80, rig.store.listAll().size)
        assertEquals(80, Files.list(rig.snapshots).use { s -> s.filter { !it.fileName.toString().startsWith(".") }.count() }.toInt())
        }
    }

    // ---------------------------------------------------------------- delete, ids, hostile files

    @Test
    fun `delete removes the files and the snapshot is not adopted back`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val id = rig.saveAt(1).id
        blocking1 { rig.store.delete(id) }.assertSuccess()
        assertFalse(Files.exists(rig.snapshots.resolve(id)))
        blocking1 { rig.store.get(id) }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
        blocking1 { rig.store.delete(id) }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
        assertEquals(0, SnapshotRig(dir).store.listAll().size)
        }
    }

    @Test
    fun `clear all keeps pinned snapshots on request`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val ids = (1..4L).map { rig.saveAt(it).id }
        blocking1 { rig.store.update(ids[0], pinned = true) }.assertSuccess()
        assertEquals(3, blocking1 { rig.store.clearAll(includePinned = false) }.assertSuccess())
        assertEquals(listOf(ids[0]), rig.store.listAll().map { it.id })
        assertEquals(1, blocking1 { rig.store.clearAll() }.assertSuccess())
        assertEquals(0, Files.list(rig.snapshots).use { it.count() })
        val usage = blocking1 { rig.store.usage() }.assertSuccess()
        assertEquals(0, usage.count)
        }
    }

    @Test
    fun `usage counts everything and pinned separately`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val a = rig.saveAt(1)
        val b = rig.saveAt(2)
        blocking1 { rig.store.update(b.id, pinned = true) }.assertSuccess()
        val u = blocking1 { rig.store.usage() }.assertSuccess()
        assertEquals(2, u.count)
        assertEquals(a.bytes + b.bytes, u.bytes)
        assertEquals(1, u.pinnedCount)
        assertEquals(b.bytes, u.pinnedBytes)
        }
    }

    @Test
    fun `ids that try to leave the snapshots folder are refused and nothing outside is touched`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        rig.saveAt(1)
        val outside = dir.resolve("outside.txt").also { Files.writeString(it, "keep me") }
        val victimDir = dir.resolve("victim").also { Files.createDirectories(it); Files.writeString(it.resolve("capture.json"), "{}") }
        for (bad in listOf("..", "../victim", "..\\victim", "/etc", "C:\\Windows", "", " ", "a/b", ".tmp-snap-0001", "x".repeat(200))) {
            blocking1 { rig.store.get(bad) }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
            blocking1 { rig.store.load(bad) }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
            blocking1 { rig.store.delete(bad) }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
            blocking1 { rig.store.update(bad, title = "x") }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
        }
        assertEquals("keep me", Files.readString(outside))
        assertTrue(Files.exists(victimDir.resolve("capture.json")))
        }
    }

    @Test
    fun `a tampered database row cannot make the store touch folders outside the snapshots folder`() {
        withTempDir { dir ->
            val rig = SnapshotRig(dir)
            val victim = dir.resolve("victim").also { Files.createDirectories(it); Files.writeString(it.resolve("capture.json"), "{}"); Files.writeString(it.resolve("precious.txt"), "keep") }
            val real = rig.saveAt(1)
            // Someone edited snapshots.db by hand: a row whose id climbs out of the folder.
            rig.db.inTransaction { c ->
                c.prepareStatement("INSERT INTO snapshot SELECT ?, title, note, pinned, created_at, captured_at, target_type, target_label, app_label, node_count, screenshot_file, capture_file, bytes FROM snapshot WHERE id = ?").use {
                    it.setString(1, "../victim"); it.setString(2, real.id); it.executeUpdate()
                }
            }
            blocking1 { rig.store.load("../victim") }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
            blocking1 { rig.store.delete("../victim") }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
            blocking1 { rig.store.update("../victim", title = "x") }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
            blocking1 { rig.store.clearAll() }.assertSuccess()
            blocking1 { rig.store.enforce(RetentionPolicy(0, 0)) }.assertSuccess()
            assertEquals("keep", Files.readString(victim.resolve("precious.txt")), "a folder outside the snapshots folder was touched")
            assertTrue(Files.exists(victim.resolve("capture.json")))
        }
    }

    @Test
    fun `a hand edited capture file cannot point the screenshot elsewhere`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val meta = rig.saveAt(1)
        val file = rig.snapshots.resolve(meta.id).resolve("capture.json")
        Files.writeString(file, Files.readString(file).replace("\"screenshot\": \"screenshot.png\"", "\"screenshot\": \"../../../secret.png\""))
        val loaded = blocking1 { rig.store.load(meta.id) }.assertSuccess()
        assertEquals(rig.snapshots.resolve(meta.id).resolve("screenshot.png").toString(), loaded.capture.screenshot!!.file)
        }
    }

    @Test
    fun `a missing screenshot file gives a capture without a screenshot and a warning`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val meta = rig.saveAt(1)
        Files.delete(rig.snapshots.resolve(meta.id).resolve("screenshot.png"))
        val loaded = blocking1 { rig.store.load(meta.id) }.assertSuccess()
        assertNull(loaded.screenshotPath)
        assertNull(loaded.capture.screenshot)
        assertTrue(loaded.capture.warnings.any { it.code == ErrorCodes.SCREENSHOT_FAILED })
        }
    }

    @Test
    fun `a missing or damaged capture file is a typed error`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val meta = rig.saveAt(1)
        val file = rig.snapshots.resolve(meta.id).resolve("capture.json")
        Files.writeString(file, "not json at all")
        blocking1 { rig.store.load(meta.id) }.assertFailure(ErrorCodes.PARSE_FAILED)
        Files.delete(file)
        blocking1 { rig.store.load(meta.id) }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
        }
    }

    // ---------------------------------------------------------------- database

    @Test
    fun `the database uses WAL a busy timeout and foreign keys`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        rig.db.withConnection { c ->
            fun one(sql: String) = c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getString(1) } }
            assertEquals("wal", one("PRAGMA journal_mode"))
            assertEquals("1", one("PRAGMA foreign_keys"))
            assertEquals("5000", one("PRAGMA busy_timeout"))
        }
        assertEquals(1, rig.db.userVersion())
        assertEquals(dir.resolve("tmp").toAbsolutePath().toString(), System.getProperty("org.sqlite.tmpdir"))
        }
    }

    @Test
    fun `a stub version 2 migration applies to a version 1 database and keeps its data`() {
        withTempDir { dir ->
        val v1 = SnapshotRig(dir)
        val saved = v1.saveAt(1, title = "kept across the upgrade")
        v1.store.close()
        val v2 = SnapshotMigration(2, listOf("ALTER TABLE snapshot ADD COLUMN color TEXT NOT NULL DEFAULT 'none'", "CREATE INDEX snapshot_color ON snapshot (color)"))
        val upgraded = SnapshotRig(dir, migrations = SnapshotSchema.ALL + v2)
        assertEquals(2, upgraded.db.userVersion())
        assertEquals(listOf(saved.copy()), upgraded.store.listAll())
        upgraded.db.withConnection { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT color FROM snapshot").use { assertTrue(it.next()); assertEquals("none", it.getString(1)) } }
        }
        }
    }

    @Test
    fun `a failing migration changes nothing`() {
        withTempDir { dir ->
        SnapshotRig(dir).saveAt(1)
        val broken = SnapshotMigration(2, listOf("ALTER TABLE snapshot ADD COLUMN ok TEXT", "THIS IS NOT SQL"))
        val db = SnapshotDatabase(dir.resolve("db").resolve("snapshots.db"), SnapshotSchema.ALL + broken)
        db.open().assertFailure(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE)
        assertEquals(1, SnapshotDatabase(dir.resolve("db").resolve("snapshots.db")).userVersion())
        SnapshotDatabase(dir.resolve("db").resolve("snapshots.db")).withConnection { c ->
            val columns = c.createStatement().use { s -> s.executeQuery("PRAGMA table_info(snapshot)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } } }
            assertFalse("ok" in columns, "half a migration was left behind")
        }
        }
    }

    @Test
    fun `a database from a newer version is reported and left alone`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        rig.saveAt(1)
        val v2 = SnapshotMigration(2, listOf("ALTER TABLE snapshot ADD COLUMN future TEXT"))
        SnapshotRig(dir, migrations = SnapshotSchema.ALL + v2) // upgrades to 2
        val sizeBefore = Files.size(rig.dbFile)
        val old = SnapshotDatabase(rig.dbFile, SnapshotSchema.ALL)
        val error = old.open().assertFailure(ErrorCodes.SNAPSHOT_STORE_UNAVAILABLE)
        assertTrue(error.detail!!.contains("newer version"))
        assertTrue(Files.exists(rig.dbFile) && !Files.exists(rig.dbFile.resolveSibling("snapshots.db.corrupt")))
        assertTrue(Files.size(rig.dbFile) >= sizeBefore)
        }
    }

    @Test
    fun `migrations must be numbered without gaps`() {
        val gap = runCatching { SnapshotDatabase(Path.of("x.db"), listOf(SnapshotSchema.V1, SnapshotMigration(3, emptyList()))) }
        assertTrue(gap.isFailure)
        assertTrue(runCatching { SnapshotDatabase(Path.of("x.db"), emptyList()) }.isFailure)
    }

    @Test
    fun `a damaged database file is kept aside and the snapshots are adopted again`() {
        withTempDir { dir ->
        val rig = SnapshotRig(dir)
        val a = rig.saveAt(1)
        val b = rig.saveAt(2)
        rig.store.close()
        Files.deleteIfExists(rig.dbFile.resolveSibling("snapshots.db-wal"))
        Files.deleteIfExists(rig.dbFile.resolveSibling("snapshots.db-shm"))
        Files.write(rig.dbFile, ByteArray(8192) { 0x41 })
        val recovered = SnapshotRig(dir)
        assertTrue(Files.exists(rig.dbFile.resolveSibling("snapshots.db.corrupt")))
        assertEquals(setOf(a.id, b.id), recovered.store.listAll().map { it.id }.toSet())
        assertNotNull(blocking1 { recovered.store.load(a.id) }.assertSuccess())
        }
    }
}
