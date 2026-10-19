package com.titanicbhai.uiscope.model

data class ElementNode(
    val id: String,
    val name: String,
    val className: String,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: Bounds? = null,
    val isEnabled: Boolean = true,
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isFocused: Boolean = false,
    val isChecked: Boolean? = null,
    val packageName: String? = null,
    val depth: Int = 0,
    val siblingIndex: Int = 0,
    val children: List<ElementNode> = emptyList(),
    val properties: Map<String, String?> = emptyMap()
)

data class Bounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)
