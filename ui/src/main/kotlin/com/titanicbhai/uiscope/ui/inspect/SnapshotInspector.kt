package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.snapshot.LoadedSnapshot
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every container path in the node's own subtree (the SnapshotInspector's expand-subtree set). */
private fun subtreeOf(index: ElementIndex, path: ElementPath): Set<ElementPath> {
    val start = index.byPath[path] ?: return emptySet()
    val result = mutableSetOf<ElementPath>()
    fun walk(node: ElementNode) {
        if (node.children.isNotEmpty()) result += node.path
        node.children.forEach(::walk)
    }
    walk(start)
    return result
}

/** The "not live" banner's exact shape: when the snapshot was captured and what it is not. */
internal fun snapshotBanner(loaded: LoadedSnapshot): String {
    val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(loaded.meta.capturedAt)
    return "Snapshot from $time - not live"
}

/**
 * The offline, read-only Inspector for a loaded snapshot (plan P5-1, 4.7): the same tree | canvas | details
 * workspace as the live screens, over the snapshot's capture, under a "not live" banner. Everything is in
 * memory - no engine, no device, no files read in composition.
 */
@Composable
fun SnapshotInspector(
    loaded: LoadedSnapshot,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val capture = loaded.capture
    // P7-4: the index is computed once, off the UI thread — never during composition.
    val index by produceState<ElementIndex?>(initialValue = null, capture) {
        value = withContext(Dispatchers.Default) { ElementIndex(capture) }
    }
    var selected by remember(capture) { mutableStateOf<ElementPath?>(null) }
    var expanded by remember(capture) { mutableStateOf(setOf(ElementPath.root(0))) }
    var testHighlight by remember(capture) { mutableStateOf<TestHighlight?>(null) }
    val searchFocus = remember { FocusRequester() }
    // The tree pane attaches the focus modifier only once it has an index to show, so the request waits for it.
    LaunchedEffect(index) {
        if (index != null) searchFocus.requestFocus()
    }
    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(snapshotBanner(loaded), style = MaterialTheme.typography.labelLarge)
                    Text(
                        loaded.meta.title + (loaded.meta.note.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onClose) { Text("Close snapshot") }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            TreePane(
                index = index,
                expanded = expanded,
                selected = selected,
                onToggleExpanded = { path -> expanded = if (path in expanded) expanded - path else expanded + path },
                onSelect = { path -> selected = path },
                onExpandAll = { expanded = index?.nodes?.filter { it.children.isNotEmpty() }?.map { it.path }?.toSet() ?: expanded },
                onCollapseAll = { expanded = emptySet() },
                onExpandSubtree = { path -> expanded = expanded + (index?.let { subtreeOf(it, path) } ?: emptySet()) },
                onCollapseSubtree = { path -> expanded = expanded - (index?.let { subtreeOf(it, path) } ?: emptySet()) },
                duplicateIds = { node -> index?.hasDuplicateIdentifier(node) == true },
                searchFocusRequester = searchFocus,
                modifier = Modifier.width(320.dp).fillMaxHeight(),
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CanvasPane(
                    capture = capture,
                    index = index,
                    selected = selected,
                    onSelect = { path -> selected = path },
                    testHighlight = testHighlight,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            DetailsPane(
                node = selected?.let { path -> index?.byPath?.get(path) },
                path = selected,
                index = index,
                onTestHighlight = { testHighlight = it },
                width = 320.dp,
            )
        }
    }
}
