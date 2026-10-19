package com.titanicbhai.uiscope

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Temporary shell (plan P0-2). The old UI is quarantined in legacy/ and the new UI is built in Phase 4.
 * The real application shell replaces this in P4-2.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "UIScope") {
        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("UIScope — rebuild in progress")
            }
        }
    }
}
