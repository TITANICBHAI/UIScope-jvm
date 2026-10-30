package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.settings.ThemeMode
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import com.titanicbhai.uiscope.ui.theme.UiScopeTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P4-5: canvas clicks map through CaptureViewport and HitTest - a plain click selects the topmost element under the point. */
class CanvasPaneTest {

    @get:Rule
    val rule = createComposeRule()

    private class Recording {
        val selected = ArrayList<String?>()
    }

    private fun cap() = capture(
        listOf(
            n(className = "android.widget.FrameLayout", bounds = com.titanicbhai.uiscope.model.Bounds(0, 0, 1000, 1000), children = listOf(
                n(className = "android.widget.Button", identifier = "app:id/center", text = "center button", bounds = com.titanicbhai.uiscope.model.Bounds(400, 400, 200, 200)),
            )),
        ),
        id = "canvas-1",
        screen = ScreenInfo(1000, 1000),
    )

    private fun setContent(r: Recording) {
        rule.setContent {
            UiScopeTheme(ThemeMode.LIGHT) {
                CanvasPane(
                    capture = cap(),
                    index = ElementIndex(cap()),
                    selected = null,
                    onSelect = { r.selected += it?.toString() },
                )
            }
        }
    }

    @Test
    fun `a plain click at the canvas centre selects the button under the point`() {
        val r = Recording()
        setContent(r)
        rule.waitForIdle()
        rule.onNodeWithTag("uiscope.canvas").performTouchInput { click(center) }
        rule.waitForIdle()
        // The fitted viewport maps the canvas centre to capture (500,500) - inside the 400..600 button (and its parent).
        assertEquals(listOf<String?>("0/0"), r.selected)
    }

    @Test
    fun `fit and 100 percent controls are present and toolbar clicks never select`() {
        val r = Recording()
        setContent(r)
        rule.onNodeWithText("Fit").assertIsDisplayed()
        rule.onNodeWithText("100%").assertIsDisplayed()
        rule.onNodeWithText("Fit").performClick()
        rule.onNodeWithText("100%").performClick()
        rule.waitForIdle()
        assertTrue(r.selected.isEmpty(), "toolbar clicks must not select elements")
    }
}
