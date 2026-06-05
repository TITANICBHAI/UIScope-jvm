package com.titanicbhai.uiscope.codegen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.codegen.AndroidCodegen
import com.titanicbhai.uiscope.codegen.CodeTarget
import com.titanicbhai.uiscope.codegen.PcCodegen
import com.titanicbhai.uiscope.codegen.PcCodeTarget
import com.titanicbhai.uiscope.model.ElementNode
import com.titanicbhai.uiscope.model.InspectionMode
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun CodegenPanel(
    selectedNode: ElementNode?,
    mode: InspectionMode = InspectionMode.ANDROID,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var selectedAndroidTarget by remember { mutableStateOf(CodeTarget.PYTHON_UIAUTOMATOR2) }
    var selectedPcTarget by remember { mutableStateOf(PcCodeTarget.PYTHON_PYWINAUTO) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500L)
            copied = false
        }
    }

    val result = remember(selectedNode, selectedAndroidTarget, selectedPcTarget, mode) {
        selectedNode?.let {
            if (mode == InspectionMode.PC) {
                PcCodegen.generate(it, selectedPcTarget)
            } else {
                AndroidCodegen.generate(it, selectedAndroidTarget)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Code Generator",
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp)
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (mode == InspectionMode.PC) {
                    PcCodeTarget.entries.forEach { target ->
                        val isSelected = target == selectedPcTarget
                        TextButton(
                            onClick = { selectedPcTarget = target; copied = false },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(target.label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                        }
                    }
                } else {
                    CodeTarget.entries.forEach { target ->
                        val isSelected = target == selectedAndroidTarget
                        TextButton(
                            onClick = { selectedAndroidTarget = target; copied = false },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(target.label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                        }
                    }
                }
            }

            result?.let { r ->
                if (r.isFragile) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFFFFF3CD),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚠", fontSize = 12.sp)
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "Fragile selector",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF856404),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                TextButton(
                    onClick = {
                        val sel = StringSelection(r.code)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
                        copied = true
                    },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (copied) "✓ Copied!" else "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) colorScheme.primary else colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }

        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))

        if (selectedNode == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Select an element to generate code",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        } else if (result != null) {
            if (result.isFragile && result.fragilityReason != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFFAEB))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        "⚠ ${result.fragilityReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF856404),
                        fontSize = 10.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Color(0xFF1E1E2E))
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    result.code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    ),
                    color = Color(0xFFCDD6F4)
                )
            }
        }
    }
}
