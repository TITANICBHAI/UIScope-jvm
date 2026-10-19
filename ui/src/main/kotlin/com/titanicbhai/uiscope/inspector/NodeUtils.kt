package com.titanicbhai.uiscope.inspector

import androidx.compose.ui.graphics.Color
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.theme.*

fun nodeColors(node: ElementNode): Pair<Color, Color> {
    val cls = node.className
    return when {
        cls.contains("Layout") || cls.contains("Frame") ||
        cls.contains("Constraint") || cls.contains("Coordinator") ||
        cls.contains("Group") || cls.contains("Container") ||
        cls.contains("Pane") || cls.contains("Panel") ->
            NodeLayout to NodeLayoutBorder

        cls.contains("Text") && !node.properties.containsKey("editable") ->
            NodeText to NodeTextBorder

        cls.contains("Button") || cls.contains("Chip") ||
        (node.isClickable && !node.isScrollable) ->
            NodeButton to NodeButtonBorder

        cls.contains("Image") || cls.contains("Icon") ||
        cls.contains("Photo") || cls.contains("Avatar") ->
            NodeImage to NodeImageBorder

        cls.contains("EditText") || cls.contains("TextField") ||
        cls.contains("Input") || cls.contains("Field") ->
            NodeInput to NodeInputBorder

        else -> NodeOther to NodeOtherBorder
    }
}

fun friendlyNodeKind(node: ElementNode): String {
    val cls = node.className
    return when {
        cls.contains("Button") || cls.contains("Chip") -> "Button"
        cls.contains("EditText") || cls.contains("TextField") || cls.contains("Input") -> "Text Input"
        cls.contains("TextView") || (cls.contains("Text") && !cls.contains("EditText")) -> "Text"
        cls.contains("ImageView") || cls.contains("Image") || cls.contains("Icon") -> "Image"
        cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("ScrollView") -> "Scroll"
        cls.contains("Layout") || cls.contains("Frame") || cls.contains("Constraint") -> "Container"
        cls.contains("CheckBox") -> "Checkbox"
        cls.contains("Switch") || cls.contains("Toggle") -> "Switch"
        cls.contains("RadioButton") -> "Radio"
        cls.contains("ProgressBar") -> "Progress"
        cls.contains("Toolbar") || cls.contains("ActionBar") -> "Bar"
        cls.contains("Tab") -> "Tab"
        cls.contains("Dialog") -> "Dialog"
        cls.contains("Menu") -> "Menu"
        cls.contains("Spinner") || cls.contains("DropDown") -> "Dropdown"
        cls.contains("Pane") || cls.contains("Panel") -> "Panel"
        cls.contains("Window") -> "Window"
        else -> cls.substringAfterLast('.').takeIf { it.isNotBlank() } ?: "Node"
    }
}

fun tierColor(confidence: Int): Color = when {
    confidence >= 80 -> AccentGreen
    confidence >= 55 -> AccentYellow
    else -> AccentOrange
}

fun tierLabel(confidence: Int): String = when {
    confidence >= 80 -> "STRONG"
    confidence >= 55 -> "MEDIUM"
    else -> "WEAK"
}
