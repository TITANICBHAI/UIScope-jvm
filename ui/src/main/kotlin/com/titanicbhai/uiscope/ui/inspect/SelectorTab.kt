package com.titanicbhai.uiscope.ui.inspect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.titanicbhai.uiscope.codegen.CodeGenerator
import com.titanicbhai.uiscope.codegen.CodeResult
import com.titanicbhai.uiscope.codegen.CodeTarget
import com.titanicbhai.uiscope.model.ElementIndex
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.ElementPath
import com.titanicbhai.uiscope.selector.SelectorEngine
import com.titanicbhai.uiscope.selector.SelectorEvaluator
import com.titanicbhai.uiscope.selector.Strategy
import com.titanicbhai.uiscope.ui.theme.UiScopeTokens

/** The elements a *Test selector* run matched, and whether the result is exactly one (green on the canvas) or not (red). */
data class TestHighlight(val paths: Set<ElementPath>, val unique: Boolean)

private fun prettyStrategy(strategy: Strategy): String = strategy.name.lowercase().replace('_', ' ')

/**
 * The Selector tab (plan P4-6, 4.6): the verdict card (one sentence + tier chip + specific warnings), the ranked candidates
 * with the engine's own match counts, *Test selector* (evaluates the active selector and hands the matches to the canvas -
 * green when exactly one, red otherwise), and the code block with the target dropdown and Copy code / Copy selector.
 * The best candidate is preselected; clicking a row makes it active.
 */
@Composable
fun SelectorTab(
    node: ElementNode,
    index: ElementIndex,
    onTestHighlight: (TestHighlight?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = remember(node) { SelectorEngine.suggest(index, node) }
    var active by remember(node) { mutableStateOf(report.best) }
    val targets = remember(index) { CodeTarget.forCapture(index.capture.target) }
    var target by remember(index) { mutableStateOf(targets.first()) }
    var targetsOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxSize()) {
        // Verdict card.
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(UiScopeTokens.spaceM)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(report.verdict.tier.label) })
                    Text(report.verdict.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = UiScopeTokens.spaceM))
                }
                report.warnings.forEach { warning ->
                    Text(warning.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Candidates.
        Text("Candidates", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(UiScopeTokens.spaceM))
        LazyColumn(Modifier.weight(1f)) {
            items(report.candidates) { candidate ->
                val isActive = candidate == active
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { active = candidate }
                        .padding(horizontal = UiScopeTokens.spaceM, vertical = UiScopeTokens.spaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(prettyStrategy(candidate.strategy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(candidate.expression, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("matches ${candidate.matches}", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(UiScopeTokens.spaceS))
                    Text(candidate.tier.label, style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = { clipboard.setText(AnnotatedString(candidate.expression)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy selector expression")
                    }
                }
            }
        }
        // Test selector + code.
        Column(Modifier.fillMaxWidth().padding(UiScopeTokens.spaceM)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                Button(onClick = {
                    val matched = SelectorEvaluator.evaluate(index, active.selector)
                    onTestHighlight(TestHighlight(matched.map { it.path }.toSet(), matched.size == 1))
                }) { Text("Test selector") }
                Box {
                    OutlinedButton(onClick = { targetsOpen = true }) { Text(target.displayName, maxLines = 1) }
                    DropdownMenu(expanded = targetsOpen, onDismissRequest = { targetsOpen = false }) {
                        for (t in targets) {
                            DropdownMenuItem(text = { Text(t.displayName) }, onClick = {
                                target = t
                                targetsOpen = false
                            })
                        }
                    }
                }
            }
            val result = remember(active, target, index) { CodeGenerator.generate(target, active, index.capture.target) }
            when (result) {
                is CodeResult.Code -> {
                    Box(Modifier.fillMaxWidth().height(140.dp).verticalScroll(rememberScrollState()).padding(vertical = UiScopeTokens.spaceS)) {
                        Text(result.code, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(result.selectorText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(UiScopeTokens.spaceS)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(result.code)) }) { Text("Copy code") }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(result.selectorText)) }) { Text("Copy selector") }
                    }
                }
                is CodeResult.NotAvailable -> Text(result.reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = UiScopeTokens.spaceS))
            }
        }
    }
}
