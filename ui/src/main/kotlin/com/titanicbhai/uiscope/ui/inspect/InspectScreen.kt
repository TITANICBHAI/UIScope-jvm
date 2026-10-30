package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.settings.InspectTarget
import com.titanicbhai.uiscope.state.AndroidInspectUi
import com.titanicbhai.uiscope.state.AndroidState
import com.titanicbhai.uiscope.ui.error.AppErrorView
import com.titanicbhai.uiscope.ui.error.ErrorVariant
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens

/** Everything the user can do on the Inspect screen; the shell wires these to the state holder. The screen itself only renders. */
data class InspectCallbacks(
    val onStart: () -> Unit = {},
    val onSelectDevice: (String) -> Unit = {},
    val onCapture: () -> Unit = {},
    val onCancelCapture: () -> Unit = {},
    val onSetLive: (Boolean) -> Unit = {},
    val onSelect: (ElementPath?) -> Unit = {},
    val onToggleExpanded: (ElementPath) -> Unit = {},
    val onSetTarget: (InspectTarget) -> Unit = {},
    val onSetTreePaneWidth: (Int) -> Unit = {},
    val onSetDetailsPaneWidth: (Int) -> Unit = {},
)

private val DEFAULT_TREE_PANE = 320.dp
private val DEFAULT_DETAILS_PANE = 320.dp
private val SPLITTER_TOUCH = 12.dp

/**
 * Full serials are never shown or logged (the engine's LogSafe rule); the UI shows only the last four characters.
 * Duplicated here because `ui` must not depend on `engine` (plan §3.1).
 */
private fun serialTail(serial: String): String = "…" + serial.takeLast(4)

/** The exact empty state the plan prescribes for the PC target on a non-Windows build (§4.1, P4-3). */
const val PC_TARGET_EMPTY_STATE = "Windows app inspection is available on Windows. Android inspection works on this OS."

/**
 * The Inspect screen (plan P4-3, §4.3/§4.5): target bar on top, workspace of tree | canvas | details below, errors as a
 * banner over the last capture (M11). Takes the immutable [AndroidInspectUi] snapshot plus callbacks — no engine, no
 * process, no file work in composition. The Windows engine arrives with Phase 3; until then its target shows a placeholder.
 */
@Composable
fun InspectScreen(
    ui: AndroidInspectUi,
    target: InspectTarget,
    windowsPcAvailable: Boolean,
    callbacks: InspectCallbacks,
    treePaneWidth: Int? = null,
    detailsPaneWidth: Int? = null,
    modifier: Modifier = Modifier,
) {
    var selectedTarget by remember { mutableStateOf(target) }
    Column(modifier.fillMaxSize()) {
        TargetBar(
            target = selectedTarget,
            windowsPcAvailable = windowsPcAvailable,
            onTarget = {
                selectedTarget = it
                callbacks.onSetTarget(it)
            },
            ui = ui,
            callbacks = callbacks,
        )
        ui.problem?.let { problem ->
            AppErrorView(problem, ErrorVariant.BANNER, onDismiss = null)
        }
        when (selectedTarget) {
            InspectTarget.WINDOWS -> if (windowsPcAvailable) PlaceholderPane("The Windows engine arrives with Phase 3 (task P3-8).") else PcTargetEmptyState()
            InspectTarget.ANDROID -> when (val state = ui.state) {
                is AndroidState.AdbMissing -> AppErrorView(CodedError(ErrorCodes.ADB_NOT_FOUND), ErrorVariant.FULL_SCREEN, onAction = { callbacks.onStart() })
                is AndroidState.NoDevice -> FullScreenWithRetry(ErrorCodes.NO_DEVICE, callbacks)
                is AndroidState.Unauthorized -> AppErrorView(CodedError(ErrorCodes.DEVICE_UNAUTHORIZED), ErrorVariant.FULL_SCREEN, onAction = { callbacks.onStart() })
                is AndroidState.Offline -> AppErrorView(CodedError(ErrorCodes.DEVICE_OFFLINE), ErrorVariant.FULL_SCREEN, onAction = { callbacks.onStart() })
                is AndroidState.NoPermissions -> AppErrorView(CodedError(ErrorCodes.NO_PERMISSIONS), ErrorVariant.FULL_SCREEN)
                is AndroidState.Starting -> StartingPane()
                else -> Workspace(ui, callbacks, treePaneWidth, detailsPaneWidth)
            }
        }
    }
}

/** Builds the AppError the copy layer needs; the detail stays empty — the codes carry the meaning (Appendix A). */
private fun AppErrorFor(code: String, detail: String?): AppError = CodedError(code, detail)

@Composable
private fun FullScreenWithRetry(code: String, callbacks: InspectCallbacks) {
    AppErrorView(AppErrorFor(code, null), ErrorVariant.FULL_SCREEN, onAction = { callbacks.onStart() })
}

@Composable
private fun StartingPane() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Looking for adb and devices…", modifier = Modifier.padding(UiScopeTokens.spaceM))
        }
    }
}

@Composable
private fun PcTargetEmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(PC_TARGET_EMPTY_STATE, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(UiScopeTokens.spaceXl))
    }
}

@Composable
private fun PlaceholderPane(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(UiScopeTokens.spaceXl))
    }
}

/** Target switch on the left, device picker and capture controls on the right; nothing drops off at minimum width (chips wrap). */
@Composable
private fun TargetBar(
    target: InspectTarget,
    windowsPcAvailable: Boolean,
    onTarget: (InspectTarget) -> Unit,
    ui: AndroidInspectUi,
    callbacks: InspectCallbacks,
) {
    var devicesOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceS)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                AssistChip(onClick = { onTarget(InspectTarget.ANDROID) }, label = { Text("Android") }, leadingIcon = null)
                if (windowsPcAvailable) {
                    AssistChip(onClick = { onTarget(InspectTarget.WINDOWS) }, label = { Text("Windows app") })
                }
                Spacer(Modifier.weight(1f))
                if (target == InspectTarget.ANDROID) {
                    OutlinedButton(onClick = { devicesOpen = true }) {
                        Text(deviceLabel(ui), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = devicesOpen, onDismissRequest = { devicesOpen = false }) {
                        if (ui.devices.isEmpty()) DropdownMenuItem(text = { Text("No devices") }, onClick = { devicesOpen = false })
                        for (device in ui.devices) {
                            DropdownMenuItem(
                                text = { Text("${device.model ?: "device"} · ${serialTail(device.serial)} (${device.state})") },
                                onClick = {
                                    devicesOpen = false
                                    callbacks.onSelectDevice(device.serial)
                                },
                            )
                        }
                        DropdownMenuItem(text = { Text("Look for adb and devices again") }, leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) }, onClick = {
                            devicesOpen = false
                            callbacks.onStart()
                        })
                    }
                    val capturing = ui.state is AndroidState.Capturing
                    if (capturing) {
                        OutlinedButton(onClick = { callbacks.onCancelCapture() }) { Text("Cancel") }
                    } else {
                        Button(onClick = { callbacks.onCapture() }, enabled = ui.state is AndroidState.Ready || ui.state is AndroidState.Captured || ui.state is AndroidState.CaptureFailed) {
                            Text("Capture now")
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.toggleable(value = ui.live.enabled, role = Role.Switch, onValueChange = { callbacks.onSetLive(it) }),
                    ) {
                        Switch(checked = ui.live.enabled, onCheckedChange = null)
                        Text("Live", modifier = Modifier.padding(start = UiScopeTokens.spaceXs))
                    }
                }
            }
        }
    }
}

private fun deviceLabel(ui: AndroidInspectUi): String {
    val selected = ui.devices.firstOrNull { it.serial == ui.selectedSerial } ?: ui.devices.firstOrNull()
    return when {
        ui.devices.isEmpty() -> "No devices"
        selected != null -> "${selected.model ?: "device"} · ${serialTail(selected.serial)}"
        else -> "${ui.devices.size} devices"
    }
}

/** tree | splitter | canvas | splitter | details, with the widths the user dragged (persisted through Settings). */
@Composable
private fun Workspace(
    ui: AndroidInspectUi,
    callbacks: InspectCallbacks,
    treePaneWidth: Int?,
    detailsPaneWidth: Int?,
) {
    val treeWidth = remember { mutableStateOf(treePaneWidth?.dp ?: DEFAULT_TREE_PANE) }
    val detailsWidth = remember { mutableStateOf(detailsPaneWidth?.dp ?: DEFAULT_DETAILS_PANE) }
    androidx.compose.runtime.LaunchedEffect(treePaneWidth) { treePaneWidth?.let { treeWidth.value = it.dp } }
    androidx.compose.runtime.LaunchedEffect(detailsPaneWidth) { detailsPaneWidth?.let { detailsWidth.value = it.dp } }
    val selectedNode = ui.selected?.let { path -> ui.index?.byPath?.get(path) }
    Row(Modifier.fillMaxSize()) {
        TreePane(
            index = ui.index,
            expanded = ui.expanded,
            selected = ui.selected,
            onSelect = callbacks.onSelect,
            onToggleExpanded = callbacks.onToggleExpanded,
            modifier = Modifier.width(treeWidth.value).fillMaxHeight(),
        )
        Splitter(onDrag = { delta -> treeWidth.value = (treeWidth.value + delta).coerceIn(160.dp, 900.dp) }, onDragEnd = { callbacks.onSetTreePaneWidth(treeWidth.value.value.toInt()) })
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (ui.capture == null) {
                EmptyWorkspaceHint(ui)
            } else {
                PlaceholderPane("The canvas with the screenshot arrives with task P4-5.")
            }
        }
        Splitter(onDrag = { delta -> detailsWidth.value = (detailsWidth.value - delta).coerceIn(160.dp, 900.dp) }, onDragEnd = { callbacks.onSetDetailsPaneWidth(detailsWidth.value.value.toInt()) })
        DetailsPane(
            node = selectedNode,
            path = ui.selected,
            width = detailsWidth.value,
        )
    }
}

@Composable
private fun EmptyWorkspaceHint(ui: AndroidInspectUi) {
    val hint = when (val state = ui.state) {
        is AndroidState.ChooseDevice -> "Several devices are connected — pick one in the device picker above."
        is AndroidState.Ready -> "Press Capture now to read the screen."
        is AndroidState.CaptureFailed -> "The last capture failed; the previous capture is still shown when there was one."
        is AndroidState.Disconnected -> "The device disconnected. The last capture is still shown; reconnect and press Capture now."
        else -> "Connect a device and capture to begin."
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(hint, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(UiScopeTokens.spaceXl))
    }
}

/** A 12 dp-wide draggable divider. */
@Composable
private fun Splitter(onDrag: (Dp) -> Unit, onDragEnd: () -> Unit) {
    Box(
        Modifier
            .width(SPLITTER_TOUCH)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            .pointerInput(Unit) {
                var accumulation = 0f
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDrag = { change, amount ->
                        change.consume()
                        accumulation += amount.x
                        if (kotlin.math.abs(accumulation) >= 1f) {
                            onDrag(Dp(accumulation))
                            accumulation = 0f
                        }
                    },
                )
            },
    )
}

/** One visible row of the tree: parents before children, children only under expanded parents (full search/keyboard arrives with P4-4). */
@Composable
fun TreePane(
    index: ElementIndex?,
    expanded: Set<ElementPath>,
    selected: ElementPath?,
    onSelect: (ElementPath?) -> Unit,
    onToggleExpanded: (ElementPath) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        if (index == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No capture yet", style = MaterialTheme.typography.bodySmall) }
            return@Surface
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(visibleNodes(index, expanded), key = { it.node.path.toString() }) { row ->
                TreeRow(
                    node = row.node,
                    depth = row.depth,
                    hasChildren = row.node.children.isNotEmpty(),
                    expanded = row.node.path in expanded,
                    selected = row.node.path == selected,
                    onClick = { onSelect(row.node.path) },
                    onToggle = { onToggleExpanded(row.node.path) },
                )
            }
        }
    }
}

private data class TreeRow(val node: ElementNode, val depth: Int)

/** Pre-order walk that only descends into expanded subtrees. */
private fun visibleNodes(index: ElementIndex, expanded: Set<ElementPath>): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    val pending = ArrayDeque<Pair<ElementNode, Int>>()
    index.capture.roots.asReversed().forEach { pending.addLast(it to 0) }
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeLast()
        out += TreeRow(node, depth)
        if (node.path in expanded) {
            node.children.asReversed().forEach { pending.addLast(it to depth + 1) }
        }
    }
    return out
}

@Composable
private fun TreeRow(node: ElementNode, depth: Int, hasChildren: Boolean, expanded: Boolean, selected: Boolean, onClick: () -> Unit, onToggle: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = (depth * 16).dp, end = UiScopeTokens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (hasChildren) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .width(20.dp)
                        .clickable(onClick = onToggle),
                )
            }
        }
        Text(
            node.text ?: node.name ?: node.identifier ?: node.className.substringAfterLast('.'),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(node.className.substringAfterLast('.'), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Selector | Properties | Path (plan §4.5); the selector and code tab arrives with P4-6. */
@Composable
private fun DetailsPane(node: ElementNode?, path: ElementPath?, width: Dp) {
    var tab by remember { mutableStateOf(0) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(width).fillMaxHeight()) {
        Column {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Selector") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Properties") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Path") })
            }
            when (tab) {
                0 -> PlaceholderPane("The selector verdict and generated code arrive with task P4-6.")
                1 -> PropertiesTab(node)
                else -> PathTab(path)
            }
        }
    }
}

@Composable
private fun PropertiesTab(node: ElementNode?) {
    if (node == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Select an element in the tree", style = MaterialTheme.typography.bodySmall) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(propertyRows(node)) { (label, value) ->
            Column(Modifier.fillMaxWidth().padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceXs)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun propertyRows(node: ElementNode): List<Pair<String, String>> = buildList {
    node.identifier?.let { add("resource-id" to it) }
    add("class" to node.className)
    node.name?.let { add("name" to it) }
    node.text?.let { add("text" to it) }
    node.contentDescription?.let { add("content-desc" to it) }
    add("bounds" to "${node.bounds.x},${node.bounds.y} ${node.bounds.width}x${node.bounds.height}")
    node.flags.clickable.takeIf { it }?.let { add("clickable" to "true") }
    node.flags.checkable.takeIf { it }?.let { add("checkable" to "true") }
    node.flags.scrollable.takeIf { it }?.let { add("scrollable" to "true") }
    node.flags.password.takeIf { it }?.let { add("password" to "true") }
    for ((key, value) in node.properties) add(key to value)
}

@Composable
private fun PathTab(path: ElementPath?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Text(
            path?.toString() ?: "Select an element in the tree",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(UiScopeTokens.spaceM),
        )
    }
}
