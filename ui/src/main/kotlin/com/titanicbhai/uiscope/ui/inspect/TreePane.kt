package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens

/** One visible row: the node plus its depth for the indent. Pure data so the navigation math stays unit-testable. */
data class TreeRow(val path: ElementPath, val depth: Int, val hasChildren: Boolean, val label: String, val secondary: String?)

/** The AWT modifiers of a key event (the desktop KeyEvent has neither keyboardModifiers nor the pointer isXxxPressed extensions). */
private fun androidx.compose.ui.input.key.KeyEvent.alt(): Boolean =
    (nativeKeyEvent as? java.awt.event.InputEvent)?.modifiersEx?.and(java.awt.event.InputEvent.ALT_DOWN_MASK) != 0

private fun androidx.compose.ui.input.key.KeyEvent.shift(): Boolean =
    (nativeKeyEvent as? java.awt.event.InputEvent)?.modifiersEx?.and(java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0

/** Pre-order walk that only descends into expanded subtrees; parents before children, paint order within a level. */
fun visibleRows(index: ElementIndex, expanded: Set<ElementPath>): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    val pending = ArrayDeque<Pair<ElementNode, Int>>()
    index.capture.roots.asReversed().forEach { pending.addLast(it to 0) }
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeLast()
        out += TreeRow(node.path, depth, node.children.isNotEmpty(), primaryLabel(node), secondaryLabel(node))
        if (node.path in expanded) {
            node.children.asReversed().forEach { pending.addLast(it to depth + 1) }
        }
    }
    return out
}

private fun primaryLabel(node: ElementNode): String = node.text ?: node.name ?: node.identifier ?: node.className.substringAfterLast('.')

private fun secondaryLabel(node: ElementNode): String? = node.identifier ?: node.className.substringAfterLast('.')

/** Case-insensitive match of the query over text, description/Name, identifier and class (§4.5). Empty query matches nothing. */
fun searchTree(index: ElementIndex, query: String): List<ElementPath> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return index.nodes.filter { node ->
        node.text?.contains(needle, ignoreCase = true) == true ||
            node.contentDescription?.contains(needle, ignoreCase = true) == true ||
            node.name?.contains(needle, ignoreCase = true) == true ||
            node.identifier?.contains(needle, ignoreCase = true) == true ||
            node.className.contains(needle, ignoreCase = true)
    }.map { it.path }
}

/** The visible row [delta] positions away from [current] (clamped at the ends); null when there is nothing visible. */
fun moveSelection(rows: List<TreeRow>, current: ElementPath?, delta: Int): ElementPath? {
    if (rows.isEmpty()) return null
    val at = rows.indexOfFirst { it.path == current }
    val target = (if (at < 0) (if (delta > 0) -1 else rows.size) else at) + delta
    return rows[target.coerceIn(0, rows.size - 1)].path
}

private fun kindIcon(className: String): ImageVector = when {
    className.contains("Button", ignoreCase = true) -> Icons.Filled.TouchApp
    className.contains("Text", ignoreCase = true) || className.contains("Edit", ignoreCase = true) -> Icons.Filled.TextFields
    className.contains("Image", ignoreCase = true) -> Icons.Filled.Image
    else -> Icons.Filled.CropSquare
}

/**
 * The tree pane (plan P4-4, §4.5): virtualized rows keyed by [ElementPath], search with count and next/previous,
 * expand/collapse all, keyboard navigation (↑/↓ move, ←/→ collapse/expand, Alt+↑ parent), badges and a context menu.
 * Expansion and selection live in the state holder — nothing here re-collapses when a new capture arrives (M2).
 * `Enter` will copy the best selector once P4-6 lands.
 */
@Composable
fun TreePane(
    index: ElementIndex?,
    expanded: Set<ElementPath>,
    selected: ElementPath?,
    onToggleExpanded: (ElementPath) -> Unit,
    onSelect: (ElementPath?) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onExpandSubtree: (ElementPath) -> Unit,
    onCollapseSubtree: (ElementPath) -> Unit,
    duplicateIds: (ElementNode) -> Boolean,
    modifier: Modifier = Modifier,
    searchFocusRequester: FocusRequester? = null,
) {
    var query by remember { mutableStateOf("") }
    var matchCursor by remember { mutableStateOf(0) }
    val rows = remember(index, expanded) { index?.let { visibleRows(it, expanded) } ?: emptyList() }
    val matches = remember(index, query) { index?.let { searchTree(it, query) } ?: emptyList() }
    val listState = rememberLazyListState()
    val paneFocus = remember { FocusRequester() }
    val focusRequester = searchFocusRequester ?: paneFocus

    // Keep the selected row on screen after a capture restored the selection (§4.5).
    LaunchedEffect(selected, index) {
        val at = rows.indexOfFirst { it.path == selected }
        if (at >= 0) listState.animateScrollToItem(at)
    }
    // A search jump expands everything above the match so it is actually visible.
    LaunchedEffect(matches, matchCursor) {
        if (matches.isNotEmpty() && index != null) {
            val current = matches[matchCursor.coerceIn(matches.indices)]
            var parent = index.parentOf(current)
            val above = HashSet<ElementPath>()
            while (parent != null) {
                above += parent.path
                parent = index.parentOf(parent.path)
            }
            if (above.isNotEmpty() && index.capture.roots.isNotEmpty()) {
                // Expansion of the ancestors goes through the pane-local set only if the caller exposes it;
                // the state holder's expandSubtree covers the whole subtree, so expand the root chain directly.
                index.capture.roots.firstOrNull()?.let { root -> if (root.path !in expanded) onExpandSubtree(root.path) }
            }
            val at = rows.indexOfFirst { it.path == current }
            if (at >= 0) listState.animateScrollToItem(at)
        }
    }

    fun select(delta: Int) {
        onSelect(moveSelection(rows, selected, delta))
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        if (index == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No capture yet", style = MaterialTheme.typography.bodySmall) }
            return@Surface
        }
        Column(Modifier.fillMaxSize().focusRequester(focusRequester).focusable().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
            when {
                event.key == Key.DirectionUp && event.alt() -> {
                    index?.parentOf(selected)?.let { onSelect(it.path) }; true
                }
                event.key == Key.DirectionDown -> {
                    select(1); true
                }
                event.key == Key.DirectionUp -> {
                    select(-1); true
                }
                event.key == Key.DirectionRight -> {
                    selected?.let { onExpandSubtree(it) }; true
                }
                event.key == Key.DirectionLeft -> {
                    selected?.let { onCollapseSubtree(it) }; true
                }
                else -> false
            }
        }) {
            // ── header: search + expand/collapse all ────────────────────────────────────────────
            Row(Modifier.fillMaxWidth().padding(horizontal = UiScopeTokens.spaceS, vertical = UiScopeTokens.spaceXs), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        matchCursor = 0
                    },
                    placeholder = { Text("Search", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).height(52.dp).focusRequester(focusRequester).onPreviewKeyEvent { event ->
                        // Enter / Shift+Enter walk the matches; Esc clears (plan 4.5).
                        if (event.type != KeyEventType.KeyUp || matches.isEmpty()) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Enter && !event.shift() -> {
                                matchCursor = (matchCursor + 1) % matches.size
                                onSelect(matches[matchCursor.coerceIn(matches.indices)])
                                true
                            }
                            event.key == Key.Enter && event.shift() -> {
                                matchCursor = (matchCursor - 1 + matches.size) % matches.size
                                onSelect(matches[matchCursor.coerceIn(matches.indices)])
                                true
                            }
                            event.key == Key.Escape -> {
                                query = ""
                                matchCursor = 0
                                true
                            }
                            else -> false
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                if (matches.isNotEmpty()) {
                    Text(
                        "${matchCursor.coerceIn(matches.indices) + 1} of ${matches.size}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = UiScopeTokens.spaceXs),
                    )
                    IconButton(onClick = { matchCursor = (matchCursor - 1 + matches.size) % matches.size }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
                    }
                    IconButton(onClick = { matchCursor = (matchCursor + 1) % matches.size }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
                    }
                }
                IconButton(onClick = onExpandAll) { Icon(Icons.Filled.UnfoldMore, contentDescription = "Expand all") }
                IconButton(onClick = onCollapseAll) { Icon(Icons.Filled.UnfoldLess, contentDescription = "Collapse all") }
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(rows, key = { it.path.toString() }) { row ->
                    val node = index.byPath[row.path] ?: return@items
                    val isMatch = row.path in matches
                    val isCurrentMatch = isMatch && matches.isNotEmpty() && row.path == matches[matchCursor.coerceIn(matches.indices)]
                    TreeRow(
                        node = node,
                        row = row,
                        expanded = row.path in expanded,
                        selected = row.path == selected,
                        highlighted = when {
                            isCurrentMatch -> MaterialTheme.colorScheme.primaryContainer
                            isMatch -> MaterialTheme.colorScheme.secondaryContainer
                            else -> Color.Transparent
                        },
                        duplicateId = duplicateIds(node),
                        onToggle = { onToggleExpanded(row.path) },
                        onClick = { onSelect(row.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TreeRow(
    node: ElementNode,
    row: TreeRow,
    expanded: Boolean,
    selected: Boolean,
    highlighted: Color,
    duplicateId: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else highlighted)
                .clickable(onClick = onClick)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                menuOpen = true
                                event.changes.first().consume()
                            }
                        }
                    }
                }
                .padding(start = (row.depth * 16).dp, end = UiScopeTokens.spaceS, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                if (row.hasChildren) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier
                            .width(20.dp)
                            .clickable(onClick = onToggle),
                    )
                }
            }
            Icon(kindIcon(node.className), contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = UiScopeTokens.spaceXs)) {
                Text(row.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.secondary?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Badge(node.flags.clickable, "clickable")
            Badge(node.flags.scrollable, "scrollable")
            Badge(node.flags.offscreen, "offscreen")
            Badge(duplicateId, "duplicate id")
            Badge(node.flags.password, "password")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Copy path") }, onClick = {
                clipboard.setText(AnnotatedString(node.path.toString()))
                menuOpen = false
            })
            DropdownMenuItem(text = { Text("Copy bounds") }, onClick = {
                clipboard.setText(AnnotatedString("${node.bounds.x},${node.bounds.y} ${node.bounds.width}x${node.bounds.height}"))
                menuOpen = false
            })
            DropdownMenuItem(text = { Text("Expand subtree") }, onClick = {
                onToggle()
                menuOpen = false
            })
        }
    }
}

@Composable
private fun Badge(visible: Boolean, label: String) {
    if (!visible) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(start = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
    }
}
