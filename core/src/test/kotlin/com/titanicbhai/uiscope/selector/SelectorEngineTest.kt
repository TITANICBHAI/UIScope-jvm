package com.titanicbhai.uiscope.selector

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.model.WindowInfo
import com.titanicbhai.uiscope.testing.TestTrees
import com.titanicbhai.uiscope.testing.TestTrees.capture
import com.titanicbhai.uiscope.testing.TestTrees.n
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Appendix G3 (fixes M1) plus the rules of Appendix C. */
class SelectorEngineTest {
    private fun report(cap: Capture, path: String) = ElementIndex(cap).let { index ->
        SelectorEngine.suggest(index, index.byPath.getValue(ElementPath(path)))
    }

    private fun androidScreen(rows: Int = 4) = capture(
        n(
            "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2400),
            children = listOf(
                n("android.widget.TextView", identifier = "app:id/login_button", text = "Log in", bounds = Bounds(10, 10, 200, 50)),
                n(
                    "androidx.recyclerview.widget.RecyclerView", identifier = "app:id/list", bounds = Bounds(0, 100, 1080, 2000),
                    children = (0 until rows).map { r -> n("android.widget.TextView", identifier = "app:id/row", bounds = Bounds(0, 100 + r * 100, 1080, 90)) },
                ),
            ),
        ),
    )

    @Test
    fun `a unique id TextView is Reliable and not fragile`() {
        val r = report(androidScreen(), "0/0")
        assertEquals(Tier.RELIABLE, r.best.tier)
        assertEquals(Tier.RELIABLE, r.verdict.tier)
        assertEquals(1, r.best.matches)
        assertEquals(Strategy.IDENTIFIER, r.best.strategy)
        assertEquals("Unique resource-id `login_button` (1 match)", r.verdict.summary)
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
        assertTrue(r.candidates.none { it.tier == Tier.FRAGILE && it.strategy == Strategy.IDENTIFIER })
    }

    @Test
    fun `a unique text is Good with the language warning`() {
        val cap = capture(n("android.widget.FrameLayout", children = listOf(n("android.widget.Button", text = "Log in"))))
        val r = report(cap, "0/0")
        assertEquals(Tier.GOOD, r.best.tier)
        assertEquals(Strategy.TEXT, r.best.strategy)
        assertEquals("Unique text 'Log in' (1 match) — breaks if the app language changes", r.verdict.summary)
        assertEquals(setOf(SelectorWarning.TEXT_LOCALIZED), r.warnings)
    }

    @Test
    fun `a unique content description is Good and outranks unique text`() {
        val cap = capture(n("android.widget.FrameLayout", children = listOf(n("android.widget.ImageButton", description = "Close", text = "x1y"))))
        val r = report(cap, "0/0")
        assertEquals(Strategy.DESCRIPTION, r.best.strategy)
        assertEquals(Tier.GOOD, r.best.tier)
        assertFalse(SelectorWarning.TEXT_LOCALIZED in r.warnings)
        assertEquals("Unique content-desc 'Close' (1 match)", r.verdict.summary)
    }

    @Test
    fun `duplicates need an index with the correct N`() {
        val r = report(androidScreen(rows = 14), "0/1/3")
        assertEquals(Tier.NEEDS_INDEX, r.best.tier)
        assertEquals(Tier.NEEDS_INDEX, r.verdict.tier)
        assertEquals(14, r.verdict.sharedMatches)
        assertEquals(Selector.Index(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/row"), 3), r.best.selector)
        assertEquals(1, r.best.matches, "an index candidate selects exactly one element")
        assertEquals("`row` matches 14 elements; index 3 breaks if the list changes", r.verdict.summary)
        val shared = r.candidates.first { it.strategy == Strategy.IDENTIFIER }
        assertEquals(14, shared.matches)
        assertEquals(Tier.NEEDS_INDEX, shared.tier)
        assertTrue(r.candidates.indexOf(r.best) < r.candidates.indexOf(shared), "the unique index candidate ranks before the shared attribute")
    }

    @Test
    fun `no stable attribute gives the path based verdict`() {
        val cap = capture(n("android.widget.FrameLayout", identifier = "app:id/root", children = listOf(n("android.view.View", children = listOf(n("android.view.View"))))))
        val r = report(cap, "0/0/0")
        assertEquals(Tier.FRAGILE, r.best.tier)
        assertEquals("No stable attribute; path-based selector only", r.verdict.summary)
        assertIs<Selector.Anchor>(r.best.selector, "an anchored path beats the absolute path")
        assertEquals(Selector.Anchor(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/root"), listOf(0, 0)), r.best.selector)
        assertTrue(r.candidates.any { it.selector is Selector.Path })
    }

    @Test
    fun `coordinates are listed last and are never the best`() {
        val cap = capture(n("android.widget.FrameLayout", bounds = Bounds(0, 0, 100, 100), children = listOf(n("android.view.View", bounds = Bounds(10, 10, 20, 20)))))
        val r = report(cap, "0/0")
        assertIs<Selector.Coordinates>(r.candidates.last().selector)
        assertTrue(r.best.selector !is Selector.Coordinates)
        assertEquals(Selector.Coordinates(20, 20), r.candidates.last().selector)
    }

    @Test
    fun `short ids raise no warning in a small tree`() {
        val ids = listOf("ok", "fab", "nav", "btn1", "tab2")
        val cap = capture(n("android.widget.FrameLayout", children = ids.map { n("android.widget.Button", identifier = "app:id/$it") }))
        for (i in ids.indices) {
            val r = report(cap, "0/$i")
            assertTrue(SelectorWarning.IDS_OBFUSCATED_TREE !in r.warnings, "${ids[i]} must not be flagged")
            assertEquals(Tier.RELIABLE, r.best.tier, ids[i])
        }
    }

    @Test
    fun `obfuscation is tree level and needs at least 10 ids and 60 percent`() {
        fun tree(short: Int, long: Int) = capture(
            n(
                "android.widget.FrameLayout",
                children = (0 until short).map { n("android.view.View", identifier = "app:id/${('a' + it % 26)}$it") } +
                    (0 until long).map { n("android.view.View", identifier = "app:id/login_button_$it") },
            ),
        )
        assertTrue(TextHeuristics.idsLookObfuscated(ElementIndex(tree(6, 4))), "6 of 10 = 60 %")
        assertFalse(TextHeuristics.idsLookObfuscated(ElementIndex(tree(5, 5))), "50 %")
        assertFalse(TextHeuristics.idsLookObfuscated(ElementIndex(tree(9, 0))), "100 % but only 9 ids")
        assertTrue(TextHeuristics.idsLookObfuscated(ElementIndex(tree(10, 0))))
        // the warning shows up on the report and demotes a unique id from Reliable to Good
        val r = report(tree(8, 2), "0/9")
        assertTrue(SelectorWarning.IDS_OBFUSCATED_TREE in r.warnings)
        assertEquals(Tier.GOOD, r.best.tier)
        // duplicates of one short id count once
        val dupes = capture(n("android.widget.FrameLayout", children = (0 until 20).map { n("android.view.View", identifier = "app:id/ab") }))
        assertFalse(TextHeuristics.idsLookObfuscated(ElementIndex(dupes)))
    }

    @Test
    fun `dynamic looking text is flagged and fragile`() {
        val dynamic = listOf("12:45", "9:05", "Total $5", "10 items", "99%", "Loading…", "x".repeat(41), "03/04/2026", "1.5.2024", "€12", "Order 2024")
        val stable = listOf("Don't allow", "Say \"hi\"", "Page 1", "日本語", "Log in", "x".repeat(40), "back\\slash", "100 items".replace("100", "Many"))
        dynamic.forEach { assertTrue(TextHeuristics.looksDynamic(it), "'$it' should look dynamic") }
        stable.forEach { assertFalse(TextHeuristics.looksDynamic(it), "'$it' should look stable") }
        val cap = capture(n("android.widget.FrameLayout", children = listOf(n("android.widget.TextView", text = "12:45"))))
        val r = report(cap, "0/0")
        assertTrue(SelectorWarning.TEXT_DYNAMIC in r.candidates.first { it.strategy == Strategy.TEXT }.warnings)
        assertEquals(Tier.FRAGILE, r.candidates.first { it.strategy == Strategy.TEXT }.tier)
        assertTrue(r.best.selector is Selector.Anchor || r.best.selector is Selector.Path, "path beats a dynamic text: ${r.best}")
    }

    @Test
    fun `windows ids names and numeric ids`() {
        val target = Target.Windows(WindowInfo("Calc", "calc.exe", 1, "XAML"))
        fun win(vararg kids: com.titanicbhai.uiscope.testing.N) = capture(
            listOf(n("Window", name = "Calculator", properties = mapOf("ControlType" to "Window"), children = kids.toList())),
            target = target,
        )
        val unique = win(n("Button", identifier = "num1Button", name = "One", properties = mapOf("ControlType" to "Button")))
        val u = report(unique, "0/0")
        assertEquals(Tier.RELIABLE, u.best.tier)
        assertEquals("Unique AutomationId `num1Button` (1 match)", u.verdict.summary)

        val numeric = win(n("Edit", identifier = "15", properties = mapOf("ControlType" to "Edit")))
        val nr = report(numeric, "0/0")
        assertEquals(Tier.GOOD, nr.best.tier)
        assertTrue(SelectorWarning.ID_NUMERIC_ONLY in nr.warnings)

        val byName = win(n("Button", name = "Save", properties = mapOf("ControlType" to "Button")), n("Button", name = "Cancel", properties = mapOf("ControlType" to "Button")))
        val br = report(byName, "0/0")
        assertEquals(Strategy.CONTROL_TYPE_NAME, br.best.strategy)
        assertEquals(Tier.GOOD, br.best.tier)
        assertTrue(SelectorWarning.TEXT_LOCALIZED in br.warnings)
        assertEquals("Unique ControlType + Name 'Button + Save' (1 match) — breaks if the app language changes", br.verdict.summary)

        val dup = win(
            n("ListItem", identifier = "row", properties = mapOf("ControlType" to "ListItem")),
            n("ListItem", identifier = "row", properties = mapOf("ControlType" to "ListItem")),
        )
        val dr = report(dup, "0/1")
        assertEquals(Tier.NEEDS_INDEX, dr.best.tier)
        assertEquals(Selector.Index(Selector.Attr(SelectorKey.IDENTIFIER, "row"), 1), dr.best.selector)
    }

    @Test
    fun `windows anchors can be a unique ControlType and Name`() {
        val target = Target.Windows(WindowInfo("App", "app.exe", 1))
        val cap = capture(
            listOf(n("Window", name = "Main", properties = mapOf("ControlType" to "Window"), children = listOf(n("Pane", children = listOf(n("Pane")))))),
            target = target,
        )
        val r = report(cap, "0/0/0")
        assertEquals(Selector.Anchor(Selector.Attr(Condition(SelectorKey.CONTROL_TYPE, "Window"), Condition(SelectorKey.NAME, "Main")), listOf(0, 0)), r.best.selector)
    }

    // ── "every matches equals an independent evaluation" ──────────────────────────────────────

    private fun preorder(nodes: List<ElementNode>): List<ElementNode> = nodes.flatMap { listOf(it) + preorder(it.children) }

    /** Written separately from SelectorEvaluator on purpose: plain recursion, no index, no groups. */
    private fun independent(cap: Capture, selector: Selector): List<ElementNode> {
        val all = preorder(cap.roots)
        fun attr(a: Selector.Attr) = all.filter { node ->
            a.conditions.all { c ->
                when (c.key) {
                    SelectorKey.IDENTIFIER -> node.identifier == c.value
                    SelectorKey.TEXT -> node.text == c.value
                    SelectorKey.DESCRIPTION -> node.contentDescription == c.value
                    SelectorKey.NAME -> node.name == c.value
                    SelectorKey.CONTROL_TYPE -> node.properties["ControlType"] == c.value
                    SelectorKey.CLASS -> node.className == c.value
                }
            }
        }
        return when (selector) {
            is Selector.Attr -> attr(selector)
            is Selector.Index -> attr(selector.base).drop(selector.index).take(1)
            is Selector.Anchor -> attr(selector.anchor).mapNotNull { start -> selector.relativePath.fold(start as ElementNode?) { cur, i -> cur?.children?.getOrNull(i) } }.distinctBy { it.path.value }
            is Selector.Path -> all.filter { it.path == selector.path }
            is Selector.Coordinates -> all.filter { it.bounds.contains(selector.x, selector.y) }.takeLast(1)
        }
    }

    private fun richCapture() = capture(
        listOf(
            n(
                "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2400),
                children = listOf(
                    n(
                        "android.widget.LinearLayout", identifier = "app:id/content", bounds = Bounds(0, 0, 1080, 2400),
                        children = listOf(
                            n("android.widget.TextView", identifier = "app:id/title", text = "Orders", bounds = Bounds(0, 0, 400, 80)),
                            n("android.widget.Button", text = "Don't allow", description = "deny", bounds = Bounds(0, 100, 400, 80)),
                            n("android.widget.Button", text = "OK", bounds = Bounds(0, 200, 400, 80)),
                            n("android.widget.Button", text = "OK", bounds = Bounds(0, 300, 400, 80)),
                            n("android.widget.TextView", text = "12:45", bounds = Bounds(0, 400, 400, 80)),
                            n("android.view.ViewGroup", bounds = Bounds(0, 500, 1080, 800), children = (0 until 3).map { r -> n("android.widget.TextView", identifier = "app:id/row", text = "Row ${'A' + r}", bounds = Bounds(0, 500 + r * 100, 1080, 90)) }),
                            n("android.view.View", bounds = Bounds(0, 0, 0, 0)),
                        ),
                    ),
                    n("android.widget.FrameLayout", bounds = Bounds(100, 600, 880, 600), children = listOf(n("android.widget.Button", identifier = "app:id/dialog_ok", text = "Allow", bounds = Bounds(200, 900, 400, 100)))),
                ),
            ),
        ),
    )

    @Test
    fun `every candidate's matches equals an independent evaluation for every element`() {
        val cap = richCapture()
        val index = ElementIndex(cap)
        var checked = 0
        for (node in index.nodes) {
            val r = SelectorEngine.suggest(index, node)
            for (c in r.candidates) {
                assertEquals(independent(cap, c.selector).size, c.matches, "${node.path} ${c.expression}")
                assertTrue(independent(cap, c.selector).any { it.path == node.path } || c.selector is Selector.Coordinates, "${node.path} ${c.expression} must select the node")
                if (c.matches != 1) assertTrue(c.tier != Tier.RELIABLE && c.tier != Tier.GOOD, "${node.path} ${c.expression} is ${c.tier} with ${c.matches} matches")
                checked++
            }
            assertTrue(r.best.isUnique)
            assertTrue(r.best.selector !is Selector.Coordinates)
            // unique candidates come first, in non-decreasing tier order; coordinates last
            val unique = r.candidates.filter { it.isUnique && it.selector !is Selector.Coordinates }
            assertEquals(unique, r.candidates.take(unique.size))
            assertEquals(unique.sortedBy { it.tier.rank }.map { it.tier }, unique.map { it.tier })
        }
        assertTrue(checked > 50, "only $checked candidates were checked")
    }

    @Test
    fun `evaluator semantics for index anchor path and coordinates`() {
        val index = ElementIndex(richCapture())
        val rows = SelectorEvaluator.evaluate(index, Selector.Attr(SelectorKey.IDENTIFIER, "app:id/row"))
        assertEquals(3, rows.size)
        assertEquals(rows[2], SelectorEvaluator.evaluate(index, Selector.Index(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/row"), 2)).single())
        assertTrue(SelectorEvaluator.evaluate(index, Selector.Index(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/row"), 3)).isEmpty())
        assertEquals("Orders", SelectorEvaluator.evaluate(index, Selector.Anchor(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/content"), listOf(0))).single().text)
        assertTrue(SelectorEvaluator.evaluate(index, Selector.Anchor(Selector.Attr(SelectorKey.IDENTIFIER, "app:id/content"), listOf(99))).isEmpty())
        assertEquals("android.widget.FrameLayout", SelectorEvaluator.evaluate(index, Selector.Path(ElementPath("0"))).single().className)
        assertEquals("Allow", SelectorEvaluator.evaluate(index, Selector.Coordinates(300, 950)).single().text, "the dialog button is on top")
        assertTrue(SelectorEvaluator.evaluate(index, Selector.Coordinates(5000, 5000)).isEmpty())
        assertTrue(SelectorEvaluator.evaluate(index, Selector.Attr(Condition(SelectorKey.TEXT, "ok"))).isEmpty(), "equality is case sensitive")
    }

    @Test
    fun `neutral expressions are stable and escape their values`() {
        assertEquals("Attr(identifier=\"a:id/x\", text=\"Say \\\"hi\\\"\\n\")", Selector.Attr(Condition(SelectorKey.IDENTIFIER, "a:id/x"), Condition(SelectorKey.TEXT, "Say \"hi\"\n")).neutral())
        assertEquals("Index(Attr(class=\"A\"), 3)", Selector.Index(Selector.Attr(SelectorKey.CLASS, "A"), 3).neutral())
        assertEquals("Anchor(Attr(identifier=\"r\"), 2/0/1)", Selector.Anchor(Selector.Attr(SelectorKey.IDENTIFIER, "r"), listOf(2, 0, 1)).neutral())
        assertEquals("Path(0/3/1)", Selector.Path(ElementPath("0/3/1")).neutral())
        assertEquals("Point(5,6)", Selector.Coordinates(5, 6).neutral())
    }

    @Test
    fun `suggest on 10k nodes takes under 50 ms`() {
        val index = ElementIndex(TestTrees.capture(TestTrees.balanced(10_000)))
        val targets = listOf("0", "0/3/5/2", "0/7/7/7/7").map { index.byPath.getValue(ElementPath(it)) }
        repeat(3) { targets.forEach { SelectorEngine.suggest(index, it) } }
        for (node in targets) {
            val best = (1..5).minOf { val s = System.nanoTime(); SelectorEngine.suggest(index, node); (System.nanoTime() - s) / 1_000_000.0 }
            assertTrue(best < 50.0, "suggest for ${node.path} took $best ms (limit 50 ms)")
        }
    }
}
