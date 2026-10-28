package com.nexus.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Minimal Material3 wrapper. No custom color/typography scheme in Phase 1A.
 */
@Composable
fun NexusAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
