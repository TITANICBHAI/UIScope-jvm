package com.titanicbhai.uiscope.storage

import com.titanicbhai.uiscope.StandardDispatcherProvider
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.snapshot.BundleCodec
import com.titanicbhai.uiscope.testing.assertFailure
import com.titanicbhai.uiscope.testing.assertSuccess
import com.titanicbhai.uiscope.testing.withTempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotBundlesTest {
    private fun bundles(rig: SnapshotRig) =
        SnapshotBundles(rig.store, StandardDispatcherProvider(Dispatchers.Unconfined), rig.root.resolve("tmp"), "1.0.0", rig.clock)

    @Test
    fun `export then import into another store reproduces the snapshot without the serial`() {
        withTempDir { dir ->
            val a = SnapshotRig(dir.resolve("a"))
            val b = SnapshotRig(dir.resolve("b"))
            val original = a.saveAt(1, text = "Say \"hi\"", title = "Login screen")
            blocking1 { a.store.update(original.id, note = "the note") }.assertSuccess()
            val file = dir.resolve("share.uiscope")
            blocking1 { bundles(a).export(original.id, file) }.assertSuccess()
            assertTrue(Files.size(file) > 0)
            assertFalse(Files.list(dir).use { s -> s.anyMatch { it.fileName.toString().endsWith(".part") } }, "a .part file was left behind")

            val imported = blocking1 { bundles(b).import(file) }.assertSuccess()
            assertEquals("Login screen", imported.title)
            assertEquals("the note", imported.note)
            assertEquals(original.nodeCount, imported.nodeCount)
            val loadedA = blocking1 { a.store.load(original.id) }.assertSuccess()
            val loadedB = blocking1 { b.store.load(imported.id) }.assertSuccess()
            assertEquals(loadedA.capture.roots, loadedB.capture.roots)
            assertEquals("", (loadedB.capture.target as Target.Android).device.serial)
            assertTrue(loadedB.screenshotPath != null && Files.isRegularFile(loadedB.screenshotPath!!))
            assertEquals(emptyList(), Files.list(dir.resolve("b").resolve("tmp")).use { it.toList() }.filter { it.fileName.toString().startsWith("import-") })
        }
    }

    @Test
    fun `an export to an unwritable place is a typed error and leaves nothing behind`() {
        withTempDir { dir ->
            val rig = SnapshotRig(dir)
            val id = rig.saveAt(1).id
            val missing = dir.resolve("no-such-folder").resolve("x.uiscope")
            blocking1 { bundles(rig).export(id, missing) }.assertFailure(ErrorCodes.EXPORT_FAILED)
            blocking1 { bundles(rig).export("snap-9999", dir.resolve("y.uiscope")) }.assertFailure(ErrorCodes.SNAPSHOT_NOT_FOUND)
            assertFalse(Files.exists(dir.resolve("y.uiscope")))
        }
    }

    @Test
    fun `import refuses bad files with a typed error and saves nothing`() {
        withTempDir { dir ->
            val rig = SnapshotRig(dir)
            fun write(name: String, bytes: ByteArray): Path = dir.resolve(name).also { Files.write(it, bytes) }
            val slip = ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { z ->
                    z.putNextEntry(ZipEntry("../../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
                }
            }.toByteArray()
            for (file in listOf(write("slip.uiscope", slip), write("junk.uiscope", "not a zip".toByteArray()), write("empty.uiscope", ByteArray(0)), dir.resolve("absent.uiscope"))) {
                blocking1 { bundles(rig).import(file) }.assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
            }
            assertEquals(0, rig.store.listAllCount())
            assertFalse(Files.exists(dir.resolve("evil.txt")) || Files.exists(dir.parent.resolve("evil.txt")))
        }
    }

    @Test
    fun `a file over the size cap is refused before it is read`() {
        withTempDir { dir ->
            val rig = SnapshotRig(dir)
            val big = dir.resolve("big.uiscope")
            java.io.RandomAccessFile(big.toFile(), "rw").use { it.setLength(BundleCodec.MAX_BYTES + 1) } // sparse, instant
            val e = blocking1 { bundles(rig).import(big) }.assertFailure(ErrorCodes.SNAPSHOT_IMPORT_INVALID)
            assertTrue(e.detail!!.contains("100 MB"))
        }
    }

    private fun com.titanicbhai.uiscope.snapshot.SnapshotStore.listAllCount() = blocking1 { list() }.assertSuccess().size
}
