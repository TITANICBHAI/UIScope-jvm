package com.titanicbhai.uiscope.perf

import com.titanicbhai.uiscope.export.CaptureJson
import com.titanicbhai.uiscope.export.XmlExporter
import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.testing.N
import com.titanicbhai.uiscope.testing.TestTrees
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7-4's big-tree smoke net: builds an ~11k-node capture and runs index + JSON + XML over it. The bound is generous
 * (it catches pathological regressions, not day-to-day jitter); the per-run timings are logged and the recorded
 * indicative numbers live in `docs/perf.md`. The reference-hardware budgets of Appendix F need a real desktop and
 * stay in `docs/MANUAL_QA.md`.
 */
class PerfSmokeTest {

    private val logger = Logger.getLogger("uiscope.perf")

    private fun node(depth: Int, fanout: Int): N {
        val children = if (depth <= 0) emptyList() else List(fanout) { i ->
            node(depth - 1, fanout).let { child ->
                // Give each node a distinguishable identifier and text so the index and exporters do real work.
                TestTrees.n(
                    className = "android.widget.TextView",
                    identifier = "app:id/n-${depth}-${i}",
                    text = "item $depth-$i",
                    bounds = Bounds(i * 10, depth * 10, 90, 40),
                    children = listOf(child),
                )
            }
        }
        return TestTrees.n(className = "android.view.View", children = children)
    }

    @Test
    fun `an 11k-node capture indexes and exports without a pathological slowdown`() {
        val capture = TestTrees.capture(
            roots = listOf(node(depth = 3, fanout = 22)),
            id = "perf-10k",
            screen = ScreenInfo(width = 1080, height = 2400),
        )
        val nodeCount = capture.roots.sumOf { count(it) }
        assertEquals(1 + 22 + 22 * 22 + 22 * 22 * 22, nodeCount, "the fixture is a big tree")

        val indexStart = System.nanoTime()
        val index = ElementIndex(capture)
        val indexMillis = (System.nanoTime() - indexStart) / 1_000_000

        val jsonStart = System.nanoTime()
        val json = CaptureJson.encode(capture, appVersion = "perf", includeSerial = false)
        val jsonMillis = (System.nanoTime() - jsonStart) / 1_000_000

        val xmlStart = System.nanoTime()
        val xml = XmlExporter.export(capture)
        val xmlMillis = (System.nanoTime() - xmlStart) / 1_000_000

        logger.info("perf smoke: nodes=$nodeCount index=${indexMillis}ms json(${json.length} chars)=${jsonMillis}ms xml(${xml.length} chars)=${xmlMillis}ms")

        assertEquals(nodeCount, index.byPath.size, "every node is indexed")
        assertTrue(indexMillis < 30_000, "indexing regressed pathologically: ${indexMillis}ms")
        assertTrue(jsonMillis < 30_000, "the JSON export regressed pathologically: ${jsonMillis}ms")
        assertTrue(xmlMillis < 30_000, "the XML export regressed pathologically: ${xmlMillis}ms")
    }

    private fun count(node: N): Int = 1 + node.children.sumOf { count(it) }
}
