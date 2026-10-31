package com.titanicbhai.uiscope.ui.inspect

import com.titanicbhai.uiscope.export.REDACTED
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.testing.TestTrees
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** P5-2: the exact pipeline the dialog copies - the redaction sentinel scan and the subtree scope. */
class ExportContentTest {

    private fun capture() = TestTrees.capture(
        roots = listOf(
            TestTrees.n(
                className = "android.view.View",
                identifier = "app:id/root",
                children = listOf(
                    TestTrees.n(className = "android.widget.TextView", identifier = "app:id/a", text = "SECRET-SENTINEL-A"),
                    TestTrees.n(className = "android.widget.TextView", identifier = "app:id/b", text = "SECRET-SENTINEL-B"),
                ),
            ),
        ),
        id = "cap-export",
        screen = ScreenInfo(width = 800, height = 600),
    )

    private fun index() = ElementIndex(capture())

    @Test
    fun `a redacted export contains none of the original text`() {
        val cap = capture()
        val redacted = exportContent(cap, index(), null, wholeCapture = true, ExportDialogFormat.JSON, redact = true, appVersion = "test")
            ?.toString(Charsets.UTF_8)
        assertFalse(redacted.orEmpty().contains("SECRET-SENTINEL-A"), "the sentinel must not survive redaction")
        assertTrue(redacted.orEmpty().contains(REDACTED), "redacted fields carry the marker: $redacted")
        val plain = exportContent(cap, index(), null, wholeCapture = true, ExportDialogFormat.JSON, redact = false, appVersion = "test")
            ?.toString(Charsets.UTF_8)
        assertTrue(plain.orEmpty().contains("SECRET-SENTINEL-A"), "without redaction the text is exported")
    }

    @Test
    fun `the subtree scope exports only the selected node's tree`() {
        val index = index()
        val pathOfB = index.byPath.entries.first { it.value.identifier == "app:id/b" }.key
        val subtree = exportContent(index.capture, index, pathOfB, wholeCapture = false, ExportDialogFormat.OUTLINE, redact = false, appVersion = "test")
            ?.toString(Charsets.UTF_8)
        assertTrue(subtree.orEmpty().contains("SECRET-SENTINEL-B"), "the selected node's text is in the outline: $subtree")
        assertFalse(subtree.orEmpty().contains("SECRET-SENTINEL-A"), "the sibling stays out of the subtree export")
    }

    @Test
    fun `the suggested file name carries the format extension`() {
        assertEquals("uiscope-cap-export.json", suggestedName(capture(), ExportDialogFormat.JSON))
        assertEquals("uiscope-cap-export.png", suggestedName(capture(), ExportDialogFormat.PNG))
    }
}
