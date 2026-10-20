package com.titanicbhai.uiscope.export

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.model.WindowInfo
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutlineAndRedactionTest {
    private val sentinels = listOf("SECRET-TEXT-9d1", "SECRET-DESC-4fa", "SECRET-NAME-77c", "SECRET-HELP-31b", "SECRET-TITLE-5e2", "SECRET-VALUE-c08")

    private fun sample(target: Target? = null) = capture(
        listOf(
            n(
                "android.widget.FrameLayout",
                bounds = Bounds(0, 0, 1080, 2400),
                children = listOf(
                    n(
                        "android.widget.TextView", identifier = "pkg:id/title", text = sentinels[0], description = sentinels[1], name = sentinels[2],
                        properties = mapOf("package" to "com.app", "HelpText" to sentinels[3], "Value" to sentinels[5], "ControlType" to "Text"),
                        bounds = Bounds(10, 20, 300, 40),
                    ),
                    n("android.view.View"),
                ),
            ),
        ),
        target = target ?: com.titanicbhai.uiscope.testing.TestTrees.androidTarget(),
    )

    @Test
    fun `outline lines carry class label id and bounds with indentation`() {
        val cap = capture(
            n(
                "android.widget.FrameLayout",
                bounds = Bounds(0, 0, 1080, 2400),
                children = listOf(
                    n("android.widget.TextView", identifier = "app:id/title", text = "Say \"hi\"\nnow\\", bounds = Bounds(10, 20, 300, 40)),
                    n("android.view.View", description = "Close", bounds = Bounds(0, 0, 5, 5), children = listOf(n("Pane", name = "Inner"))),
                ),
            ),
        )
        val expected = """
            FrameLayout [0,0,1080,2400]
              TextView "Say \"hi\"\nnow\\" #app:id/title [10,20,300,40]
              View "Close" [0,0,5,5]
                Pane "Inner" [0,0,100,100]
        """.trimIndent() + "\n"
        assertEquals(expected, OutlineExporter.export(cap))
    }

    @Test
    fun `outline keeps every element on one line`() {
        val text = OutlineExporter.export(capture(n(text = "a\nb\rc\u0001d")))
        assertEquals(1, text.trimEnd('\n').lines().size)
        assertTrue(text.contains("a\\nb\\rc\\u0001d"), text)
    }

    @Test
    fun `redacted capture has no sentinel in any export while the original has them all`() {
        val windows = sample(Target.Windows(WindowInfo(sentinels[4], "notepad.exe", 1)))
        // what each platform's export is expected to contain before redaction (Android has no Name; the Windows header has the title)
        for ((original, shown) in listOf(sample() to sentinels.take(2), windows to listOf(sentinels[0], sentinels[2], sentinels[4]))) {
            val originalOutput = XmlExporter.export(original) + OutlineExporter.export(original)
            val redactedOutput = XmlExporter.export(original.redacted()) + OutlineExporter.export(original.redacted())
            shown.forEach { assertTrue(originalOutput.contains(it), "control: $it should be in the unredacted export") }
            sentinels.forEach { assertFalse(redactedOutput.contains(it), "$it leaked into the redacted export") }
            assertFalse(original.redacted().toString().let { text -> sentinels.any { text.contains(it) } }, "leak in the model itself")
        }
    }

    @Test
    fun `redaction keeps structure and structural properties and leaves absent fields absent`() {
        val redacted = sample().redacted()
        val title = redacted.roots.single().children[0]
        assertEquals(REDACTED, title.text)
        assertEquals(REDACTED, title.contentDescription)
        assertEquals(REDACTED, title.name)
        assertEquals(REDACTED, title.properties["HelpText"])
        assertEquals(REDACTED, title.properties["Value"])
        assertEquals("com.app", title.properties["package"])
        assertEquals("Text", title.properties["ControlType"])
        assertEquals("pkg:id/title", title.identifier)
        assertEquals(Bounds(10, 20, 300, 40), title.bounds)
        assertEquals("android.widget.TextView", title.className)
        val empty = redacted.roots.single().children[1]
        assertEquals(null, empty.text)
        assertEquals(null, empty.contentDescription)
        assertEquals(sample().roots.single().path, redacted.roots.single().path)
    }

    @Test
    fun `windows title is redacted`() {
        val redacted = sample(Target.Windows(WindowInfo(sentinels[4], "notepad.exe", 1, "Win32"))).redacted()
        val window = (redacted.target as Target.Windows).window
        assertEquals(REDACTED, window.title)
        assertEquals("notepad.exe", window.processName)
        assertEquals("Win32", window.frameworkId)
    }
}
