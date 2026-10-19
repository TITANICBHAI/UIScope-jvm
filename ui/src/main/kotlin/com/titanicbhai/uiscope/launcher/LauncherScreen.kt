package com.titanicbhai.uiscope.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanicbhai.uiscope.model.InspectionMode

@Composable
fun LauncherScreen(
    onModeSelected: (InspectionMode) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onHistory) {
                Text("📋 History", style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onSettings) {
                Text("⚙ Settings", style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(48.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "UIScope",
                    style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                    color = colorScheme.primary
                )
                Text(
                    text = "See what your UI is made of.",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top
            ) {
                ModeCard(
                    icon = "🖥️",
                    title = "Inspect This PC",
                    description = "Point at any window on this computer.\nSee its full element tree.",
                    note = "No setup required.\nWorks with any app.",
                    onClick = { onModeSelected(InspectionMode.PC) }
                )
                ModeCard(
                    icon = "📱",
                    title = "Inspect Android",
                    description = "Connect your Android phone via USB\nor WiFi. Inspect any app on it.",
                    note = "Requires: Developer Mode enabled\n+ USB or ADB IP",
                    onClick = { onModeSelected(InspectionMode.ANDROID) }
                )
            }
        }
    }
}

@Composable
private fun ModeCard(
    icon: String,
    title: String,
    description: String,
    note: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        color = if (isHovered) colorScheme.primaryContainer else colorScheme.surfaceVariant,
        tonalElevation = if (isHovered) 8.dp else 2.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = icon, fontSize = 52.sp)

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (isHovered) colorScheme.onPrimaryContainer else colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isHovered) colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                else colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            HorizontalDivider(
                color = (if (isHovered) colorScheme.onPrimaryContainer else colorScheme.outline)
                    .copy(alpha = 0.3f)
            )

            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = if (isHovered) colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                else colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}
