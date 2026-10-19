package com.titanicbhai.uiscope.testing

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.DeviceInfo
import com.titanicbhai.uiscope.model.ElementFlags
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.ElementPaths
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.model.Target
import java.time.Clock

/** A tree description without paths; [TestTrees.capture] turns it into real nodes with structural paths. */
class N(
    val className: String,
    val identifier: String?,
    val text: String?,
    val description: String?,
    val name: String?,
    val bounds: Bounds,
    val flags: ElementFlags,
    val properties: Map<String, String>,
    val children: List<N>,
)

/** Small DSL for building captures in tests without a parser. */
object TestTrees {
    @Suppress("LongParameterList")
    fun n(
        className: String = "android.view.View",
        identifier: String? = null,
        text: String? = null,
        description: String? = null,
        name: String? = null,
        bounds: Bounds = Bounds(0, 0, 100, 100),
        flags: ElementFlags = ElementFlags(),
        properties: Map<String, String> = emptyMap(),
        children: List<N> = emptyList(),
    ): N = N(className, identifier, text, description, name, bounds, flags, properties, children)

    fun androidTarget(serial: String = "TEST0001"): Target = Target.Android(DeviceInfo(serial, "Test phone", "14", 34), "com.example")

    fun capture(
        roots: List<N>,
        id: String = "capture-1",
        clock: Clock = FakeClock(),
        screen: ScreenInfo = ScreenInfo(1080, 2400),
        target: Target = androidTarget(),
    ): Capture = Capture(
        id = id,
        takenAt = clock.instant(),
        target = target,
        screen = screen,
        screenshot = null,
        roots = ElementPaths.assign(roots.map(::toNode)),
    )

    fun capture(root: N, id: String = "capture-1"): Capture = capture(listOf(root), id)

    /**
     * A tree with exactly [count] nodes (breadth-first fill, [fanout] children per node), so depth stays small.
     * Identifiers repeat every 50 nodes (duplicates), texts are unique, bounds are distinct.
     */
    fun balanced(count: Int, fanout: Int = 8): N {
        require(count >= 1 && fanout >= 2)
        fun build(index: Int): N {
            val first = index * fanout + 1
            val kids = (first until minOf(first + fanout, count)).map(::build)
            return n(
                className = if (kids.isEmpty()) "android.widget.TextView" else "android.view.ViewGroup",
                identifier = "app:id/id_${index % 50}",
                text = "text $index",
                bounds = Bounds(index % 1000, index / 1000, 50, 20),
                children = kids,
            )
        }
        return build(0)
    }

    private fun toNode(spec: N): ElementNode = ElementNode(
        path = ElementPath.root(),
        name = spec.name,
        className = spec.className,
        identifier = spec.identifier,
        text = spec.text,
        contentDescription = spec.description,
        bounds = spec.bounds,
        flags = spec.flags,
        properties = spec.properties,
        children = spec.children.map(::toNode),
    )
}
