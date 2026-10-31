package com.titanicbhai.uiscope.windows

import com.titanicbhai.uiscope.model.Bounds
import com.titanicbhai.uiscope.model.ElementFlags
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.model.ElementPaths

/**
 * Maps the helper's Appendix B node JSON (already parsed by [JsonReader]) into [ElementNode] trees. The helper enforces
 * its own depth/node limits; unknown fields are ignored. Paths are structural placeholders until [ElementPaths.assign]
 * runs on the finished roots.
 */
internal object WindowsNodeMapper {
    private const val MAX_DEPTH = 48
    private const val MAX_NODES = 10_000

    fun mapRoots(roots: List<*>): List<ElementNode> {
        val counter = intArrayOf(0)
        val mapped = roots.map { mapNode(it, 0, counter) }.filterNotNull()
        return ElementPaths.assign(mapped)
    }

    private fun mapNode(raw: Any?, depth: Int, counter: IntArray): ElementNode? {
        if (depth > MAX_DEPTH || counter[0] >= MAX_NODES) return null
        @Suppress("UNCHECKED_CAST")
        val map = raw as? Map<String, Any?> ?: return null
        counter[0]++

        @Suppress("UNCHECKED_CAST")
        val children = (map["children"] as? List<Any?>)?.mapNotNull { mapNode(it, depth + 1, counter) } ?: emptyList()

        return ElementNode(
            path = ElementPath.root(),
            name = map["name"] as? String,
            className = (map["className"] as? String) ?: (map["controlType"] as? String) ?: "Unknown",
            identifier = (map["automationId"] as? String)?.ifBlank { null },
            text = null, // Windows element values are not read (Appendix B); the Name is the label
            bounds = bounds(map["rect"]),
            flags = ElementFlags(
                enabled = map["isEnabled"] as? Boolean != false,
                password = map["isPassword"] as? Boolean == true,
                offscreen = map["isOffscreen"] as? Boolean == true,
            ),
            properties = properties(map),
            children = children,
        )
    }

    private fun bounds(raw: Any?): Bounds {
        @Suppress("UNCHECKED_CAST")
        val rect = raw as? Map<String, Any?> ?: return Bounds(0, 0, 0, 0)
        fun long(key: String) = (rect[key] as? Long)?.toInt() ?: 0
        return Bounds(long("x"), long("y"), long("width"), long("height"))
    }

    private fun properties(map: Map<String, Any?>): Map<String, String> = buildMap {
        (map["controlType"] as? String)?.let { put("ControlType", it) }
        (map["frameworkId"] as? String)?.let { put("FrameworkId", it) }
        (map["runtimeId"] as? String)?.let { put("RuntimeId", it) }
    }
}
