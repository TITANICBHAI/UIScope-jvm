package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.keyboardModifiers
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.error.AppError
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.selector.SelectorEngine
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
    val onExpandAll: () -> Unit = {},
    val onCollapseAll: () -> Unit = {},
    val onExpandSubtree: (ElementPath) -> Unit = {},
    val onCollapseSubtree: (ElementPath) -> Unit = {},
    val onSetTarget: (InspectTarget) -> Unit = {},
    val onSetTreePaneWidth: (Int) -> Unit = {},
    val onSetDetailsPaneWidth: (Int) -> Unit = {},
)

private val DEFAULT_TREE_PANE = 320.dp
private val DEFAULT_DETAILS_PANE = 320.dp
private val SPLITTER_TOUCH = 12.dp

/**
 * Full serials are never shown or logged (the engine's LogSafe rule); the UI shows only the last four characters.
 * Duplicated here because `ui` must not depend on `engine` (plan 3.1). ASCII only - it lands in test matchers too.
 */
private fun serialTail(serial: String): String = "..." + serial.takeLast(4)

/** The exact empty state the plan prescribes for the PC target on a non-Windows build (4.1, P4-3). */
const val PC_TARGET_EMPTY_STATE = "Windows app inspection is available on Windows. Android inspection works on this OS."

/**
 * The Inspect screen (plan P4-3, 4.3/4.5): target bar on top, workspace of tree | canvas | details below, errors as a
 * banner over the last capture (M11). Takes the immutable [AndroidInspectUi] snapshot plus callbacks - no engine, no
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
    val searchFocus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    Column(
        modifier
            .fillMaxSize()
            .testTag("uiscope.inspect")
            .focusable()
            .onPreviewKeyEvent { event ->
                // The app-level shortcuts of 4.11 (no single-letter shortcuts). Enter-in-search and Alt+Up live in the tree pane.
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when {
                    event.key == Key.F5 -> {
                        callbacks.onCapture(); true
                    }
                    event.key == Key.R && event.keyboardModifiers.isCtrlPressed -> {
                        callbacks.onCapture(); true
                    }
                    event.key == Key.L && event.keyboardModifiers.isCtrlPressed -> {
                        callbacks.onSetLive(!ui.live.enabled); true
                    }
                    event.key == Key.F && event.keyboardModifiers.isCtrlPressed -> {
                        searchFocus.requestFocus(); true
                    }
                    event.key == Key.C && event.keyboardModifiers.isCtrlPressed && event.keyboardModifiers.isShiftPressed -> {
                        val index = ui.index
                        val node = ui.selected?.let { index?.byPath?.get(it) }
                        if (index != null && node != null) {
                            clipboard.setText(AnnotatedString(SelectorEngine.suggest(index, node).best.expression))
                        }
                        true
                    }
                    event.key == Key.Escape && ui.state is AndroidState.Capturing -> {
                        callbacks.onCancelCapture(); true
                    }
                    else -> false
                }
            },
    ) {
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
                else -> Workspace(ui, callbacks, treePaneWidth, detailsPaneWidth, searchFocus)
            }
        }
    }
}

/** Builds the AppError the copy layer needs; the detail stays empty - the codes carry the meaning (Appendix A). */
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
            Text("Looking for adb and devices...", modifier = Modifier.padding(UiScopeTokens.spaceM))
        }
    }
}

@Composable
private fun PcTargetEmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(PC_TARGET_EMPTY_STATE, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(UiScopeTokens.spaceXl))
    }
}

@Composable
private fun PlaceholderPane(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(UiScopeTokens.spaceXl))
    }
}

/** Target switch on the left, device picker and capture controls on the right; the row wraps so nothing drops off at minimum width. */
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                AssistChip(onClick = { onTarget(InspectTarget.ANDROID) }, label = { Text("Android") })
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
                                text = { Text("${device.model ?: "device"} (${serialTail(device.serial)}) (${device.state})") },
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
        selected != null -> "${selected.model ?: "device"} (${serialTail(selected.serial)})"
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
    searchFocus: FocusRequester,
) {
    val treeWidth = remember { mutableStateOf(treePaneWidth?.dp ?: DEFAULT_TREE_PANE) }
    val detailsWidth = remember { mutableStateOf(detailsPaneWidth?.dp ?: DEFAULT_DETAILS_PANE) }
    val testHighlight = remember { mutableStateOf<TestHighlight?>(null) }
    LaunchedEffect(treePaneWidth) { treePaneWidth?.let { treeWidth.value = it.dp } }
    LaunchedEffect(detailsPaneWidth) { detailsPaneWidth?.let { detailsWidth.value = it.dp } }
    LaunchedEffect(ui.capture) { testHighlight.value = null } // a new capture invalidates a Test-selector run
    val selectedNode = ui.selected?.let { path -> ui.index?.byPath?.get(path) }
    Row(Modifier.fillMaxSize()) {
        com.titanicbhai.uiscope.ui.inspect.TreePane(
            index = ui.index,
            expanded = ui.expanded,
            selected = ui.selected,
            onToggleExpanded = callbacks.onToggleExpanded,
            onSelect = callbacks.onSelect,
            onExpandAll = callbacks.onExpandAll,
            onCollapseAll = callbacks.onCollapseAll,
            onExpandSubtree = callbacks.onExpandSubtree,
            onCollapseSubtree = callbacks.onCollapseSubtree,
            duplicateIds = { node -> ui.index?.hasDuplicateIdentifier(node) == true },
            searchFocusRequester = searchFocus,
            modifier = Modifier.width(treeWidth.value).fillMaxHeight(),
        )
        Splitter(onDrag = { delta -> treeWidth.value = (treeWidth.value + delta).coerceIn(160.dp, 900.dp) }, onDragEnd = { callbacks.onSetTreePaneWidth(treeWidth.value.value.toInt()) })
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (ui.capture == null) {
                EmptyWorkspaceHint(ui)
            } else {
                CanvasPane(
                    capture = ui.capture,
                    index = ui.index,
                    selected = ui.selected,
                    onSelect = callbacks.onSelect,
                    testHighlight = testHighlight.value,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Splitter(onDrag = { delta -> detailsWidth.value = (detailsWidth.value - delta).coerceIn(160.dp, 900.dp) }, onDragEnd = { callbacks.onSetDetailsPaneWidth(detailsWidth.value.value.toInt()) })
        DetailsPane(
            node = selectedNode,
            path = ui.selected,
            index = ui.index,
            onTestHighlight = { testHighlight.value = it },
            width = detailsWidth.value,
        )
    }
}

@Composable
private fun EmptyWorkspaceHint(ui: AndroidInspectUi) {
    val hint = when (val state = ui.state) {
        is AndroidState.ChooseDevice -> "Several devices are connected - pick one in the device picker above."
        is AndroidState.Ready -> "Press Capture now to read the screen."
        is AndroidState.CaptureFailed -> "The last capture failed; the previous capture is still shown when there was one."
        is AndroidState.Disconnected -> "The device disconnected. The last capture is still shown; reconnect and press Capture now."
        else -> "Connect a device and capture to begin."
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(hint, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(UiScopeTokens.spaceXl))
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

/** Selector | Properties | Path (plan 4.5/4.6); the selector and code tab arrives with P4-6. */
@Composable
private fun DetailsPane(
    node: ElementNode?,
    path: ElementPath?,
    index: com.titanicbhai.uiscope.model.ElementIndex?,
    onTestHighlight: (TestHighlight?) -> Unit,
    width: Dp,
) {
    var tab by remember { mutableStateOf(0) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(width).fillMaxHeight()) {
        Column {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Selector") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Properties") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Path") })
            }
            when (tab) {
                0 -> if (node != null && index != null) SelectorTab(node = node, index = index, onTestHighlight = onTestHighlight) else PlaceholderPane("Select an element in the tree")
                1 -> PropertiesTab(node)
                else -> PathTab(path)
            }
        }
    }
}

internal data class PropertyRow(val section: String, val label: String, val value: String)

internal fun propertyRows(node: ElementNode): List<PropertyRow> = buildList {
    node.identifier?.let { add(PropertyRow("Identity", "resource-id", it)) }
    add(PropertyRow("Identity", "class", node.className))
    node.name?.let { add(PropertyRow("Identity", "name", it)) }
    node.text?.let { add(PropertyRow("Identity", "text", it)) }
    node.contentDescription?.let { add(PropertyRow("Identity", "content-desc", it)) }
    node.flags.enabled.let { add(PropertyRow("State", "enabled", it.toString())) }
    node.flags.clickable.let { add(PropertyRow("State", "clickable", it.toString())) }
    node.flags.longClickable.let { add(PropertyRow("State", "long-clickable", it.toString())) }
    node.flags.checkable.let { add(PropertyRow("State", "checkable", it.toString())) }
    node.flags.checked?.let { add(PropertyRow("State", "checked", it.toString())) }
    node.flags.focusable.let { add(PropertyRow("State", "focusable", it.toString())) }
    node.flags.focused.let { add(PropertyRow("State", "focused", it.toString())) }
    node.flags.scrollable.let { add(PropertyRow("State", "scrollable", it.toString())) }
    node.flags.selected.let { add(PropertyRow("State", "selected", it.toString())) }
    node.flags.offscreen.let { add(PropertyRow("State", "offscreen", it.toString())) }
    node.flags.password.let { add(PropertyRow("State", "password", it.toString())) }
    add(PropertyRow("Geometry", "bounds", "${node.bounds.x},${node.bounds.y} ${node.bounds.width}x${node.bounds.height}"))
    for ((key, value) in node.properties) add(PropertyRow("Platform", key, value))
}

/** Grouped property rows (plan 4.5): click a value to copy it; Copy all as JSON puts every row on the clipboard. */
@Composable
internal fun PropertiesTab(node: ElementNode?, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    if (node == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Select an element in the tree", style = MaterialTheme.typography.bodySmall) }
        return
    }
    val rows = remember(node) { propertyRows(node) }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            OutlinedButton(
                onClick = {
                    val json = rows.joinToString(prefix = "{", postfix = "}", separator = ",\n") { row ->
                        "${com.titanicbhai.uiscope.json.JsonWriter.string(row.label)}: ${com.titanicbhai.uiscope.json.JsonWriter.string(row.value)}"
                    }
                    clipboard.setText(AnnotatedString(json))
                },
                modifier = Modifier.padding(UiScopeTokens.spaceS),
            ) { Text("Copy all as JSON") }
        }
        for ((section, sectionRows) in rows.groupBy { it.section }) {
            item(key = "section-$section") {
                Text(section, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceXs))
            }
            items(sectionRows, key = { "${it.section}-${it.label}" }) { row ->
                Row(
                    Modifier.fillMaxWidth().clickable { clipboard.setText(AnnotatedString(row.value)) }.padding(horizontal = UiScopeTokens.spaceM, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
                    Text(row.value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
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
