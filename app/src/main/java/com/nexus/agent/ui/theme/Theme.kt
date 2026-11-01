package com.nexus.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = NexusPrimary,
    onPrimary = NexusOnPrimary,
    primaryContainer = NexusPrimaryContainer,
    onPrimaryContainer = NexusOnPrimaryContainer,
    secondary = NexusSecondary,
    onSecondary = NexusOnSecondary,
    secondaryContainer = NexusSecondaryContainer,
    onSecondaryContainer = NexusOnSecondaryContainer,
    tertiary = NexusTertiary,
    background = NexusBackground,
    onBackground = NexusOnSurface,
    surface = NexusSurface,
    onSurface = NexusOnSurface,
    surfaceVariant = NexusSurfaceVariant,
    onSurfaceVariant = NexusOnSurfaceVariant,
    outline = NexusOutline
)

private val DarkColors = darkColorScheme(
    primary = NexusPrimaryDark,
    onPrimary = NexusOnPrimaryDark,
    primaryContainer = NexusPrimaryContainerDark,
    onPrimaryContainer = NexusOnPrimaryContainerDark,
    secondary = NexusSecondaryDark,
    onSecondary = NexusOnSecondaryDark,
    secondaryContainer = NexusSecondaryContainerDark,
    onSecondaryContainer = NexusOnSecondaryContainerDark,
    tertiary = NexusTertiary,
    background = NexusBackgroundDark,
    onBackground = NexusOnSurfaceDark,
    surface = NexusSurfaceDark,
    onSurface = NexusOnSurfaceDark,
    surfaceVariant = NexusSurfaceVariantDark,
    onSurfaceVariant = NexusOnSurfaceVariantDark,
    outline = NexusOutlineDark
)

/**
 * NEXUS Material3 theme. Follows the system light/dark setting by default; pass
 * [darkTheme] explicitly to force either mode.
 */
@Composable
fun NexusAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
