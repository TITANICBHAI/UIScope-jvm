package com.titanicbhai.uiscope.ui

import com.titanicbhai.uiscope.error.ErrorCodes
import java.lang.reflect.Modifier as JavaModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** P4-10 acceptance: every error code of the implementation and of Appendix A has copy; most have a primary action. */
class StringsCatalogueTest {

    @Test
    fun `every error code of the implementation and of Appendix A has copy`() {
        val codes = ErrorCodes::class.java.declaredFields
            .filter { it.type == String::class.java && JavaModifier.isStatic(it.modifiers) }
            .map { it.get(null) as String } + listOf(
            "UIA_UNAVAILABLE", "ELEVATED_TARGET", "WINDOW_GONE", "WINDOW_MINIMIZED", "LIMITED_ACCESSIBILITY", "TREE_TRUNCATED", "HOTKEY_IN_USE",
        )
        // Appendix A marks these two with no primary action ("-"); every other code has one.
        val withoutAction = setOf(ErrorCodes.SNAPSHOT_IMPORT_INVALID, ErrorCodes.CAPTURE_SCHEMA_UNSUPPORTED)
        for (code in codes.distinct()) {
            val copy = Strings.error(code)
            assertTrue(copy.title.isNotBlank(), "$code has no title")
            assertTrue(copy.what.isNotBlank(), "$code has no explanation")
            if (code !in withoutAction) assertNotNull(copy.action, "$code has no primary action")
        }
    }

    @Test
    fun `an unknown code falls back to the UNEXPECTED copy`() {
        assertEquals(Strings.error(ErrorCodes.UNEXPECTED), Strings.error("NO_SUCH_CODE_MADE_UP"))
    }
}
