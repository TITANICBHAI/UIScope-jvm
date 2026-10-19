package com.titanicbhai.uiscope.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(
    adbAvailable: Boolean,
    onContinue: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val osName = System.getProperty("os.name").orEmpty()
    val isWindows = osName.contains("Windows", ignoreCase = true)
    val isMac = osName.contains("Mac", ignoreCase = true)

    Box(
        modifier = Modifier.fillMaxSize().background(colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(520.dp),
            shape = RoundedCornerShape(16.dp),
            color = colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Welcome to UIScope",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = colorScheme.onSurface
                    )
                    Text(
                        "Let's check your system before you start. You can proceed regardless.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = colorScheme.outline)

                CheckRow(
                    isOk = adbAvailable,
                    label = "Android Debug Bridge (ADB)",
                    okText = "Found on PATH — Android inspection ready.",
                    warnText = "Not found on PATH. You can download it later via Settings. PC mode works without ADB."
                )

                CheckRow(
                    isOk = true,
                    label = "PC Accessibility API",
                    okText = when {
                        isWindows -> "Windows UI Automation is available — no extra permission needed for most apps."
                        isMac -> "Grant access in: System Settings → Privacy & Security → Accessibility"
                        else -> "Requires AT-SPI2 (pre-installed on GNOME/KDE). Run: sudo apt install at-spi2-core"
                    },
                    warnText = ""
                )

                HorizontalDivider(color = colorScheme.outline)

                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Get Started", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    isOk: Boolean,
    label: String,
    okText: String,
    warnText: String
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = if (isOk) "✓" else "⚠",
            color = if (isOk) colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 2.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onSurface
            )
            Text(
                text = if (isOk) okText else warnText,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified
            )
        }
    }
}
