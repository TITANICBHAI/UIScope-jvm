package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.Screenshot
import com.titanicbhai.uiscope.snapshot.SnapshotMeta
import com.titanicbhai.uiscope.testing.FakeClock
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.n
import com.titanicbhai.uiscope.testing.assertSuccess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers

/** Runs a suspending call to completion on the calling thread (60 s limit) and returns its result. */
internal fun <T> blocking1(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(60_000) { block() } }

/** A real SQLite-backed store in a temp folder, with a fake clock and counted file reads (shared by the storage tests). */
internal class SnapshotRig(val root: Path, migrations: List<SnapshotMigration> = SnapshotSchema.ALL, ids: (() -> String)? = null) {
    val clock = FakeClock()
    val reads = AtomicInteger()
    val dbFile: Path = root.resolve("db").resolve("snapshots.db")
    val snapshots: Path = root.resolve("snapshots")
    val db = SnapshotDatabase(dbFile, migrations, sqliteTmpDir = root.resolve("tmp"))
    private val counter = AtomicInteger()
    val store = SnapshotStoreImpl(
        db, snapshots, StandardDispatcherProvider(Dispatchers.Unconfined), clock, appVersion = "1.0.0", zone = ZoneOffset.UTC, collator = java.text.Collator.getInstance(java.util.Locale.ROOT),
        idGenerator = ids ?: { "snap-%04d".format(counter.incrementAndGet()) },
        readFile = { reads.incrementAndGet(); Files.readAllBytes(it) },
    ).also { it.open().assertSuccess() }

    /** A file that starts with a valid PNG signature and IHDR (1080x2400) and is padded to [bytes]; enough for the bundle validator. */
    fun shot(name: String = "live.png", bytes: Int = 1000): Path {
        val head = java.io.ByteArrayOutputStream()
        head.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13))
        head.write("IHDR".toByteArray())
        for (v in listOf(1080, 2400)) head.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
        head.write(byteArrayOf(8, 6, 0, 0, 0, 0, 0, 0, 0))
        val content = head.toByteArray().copyOf(bytes)
        return root.resolve(name).also { Files.write(it, content) }
    }

    fun cap(text: String = "hello", withShot: Boolean = true, id: String = "capture-1"): Capture =
        TestTrees.capture(n("android.widget.FrameLayout", children = listOf(n("android.widget.TextView", identifier = "app:id/t", text = text))), id = id)
            .let { if (withShot) it.copy(screenshot = Screenshot(shot().toString(), 1080, 2400)) else it }

    fun saveAt(minute: Long, text: String = "x", title: String? = null): SnapshotMeta {
        clock.set(java.time.Instant.parse("2026-03-01T10:00:00Z").plus(Duration.ofMinutes(minute)))
        return blocking1 { store.save(cap(text), title) }.assertSuccess()
    }

    fun files(id: String): List<String> = Files.list(snapshots.resolve(id)).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
}
