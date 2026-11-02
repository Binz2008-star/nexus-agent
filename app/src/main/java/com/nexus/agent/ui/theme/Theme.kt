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
    onTertiary = NexusOnTertiary,
    tertiaryContainer = NexusTertiaryContainer,
    onTertiaryContainer = NexusOnTertiaryContainer,
    background = NexusBackground,
    onBackground = NexusOnSurface,
    surface = NexusSurface,
    onSurface = NexusOnSurface,
    surfaceVariant = NexusSurfaceVariant,
    onSurfaceVariant = NexusOnSurfaceVariant,
    surfaceContainer = NexusSurfaceContainer,
    outline = NexusOutline,
    outlineVariant = NexusOutlineVariant,
    error = NexusError,
    onError = NexusOnError,
    errorContainer = NexusErrorContainer,
    onErrorContainer = NexusOnErrorContainer
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
    tertiary = NexusTertiaryDark,
    onTertiary = NexusOnTertiaryDark,
    tertiaryContainer = NexusTertiaryContainerDark,
    onTertiaryContainer = NexusOnTertiaryContainerDark,
    background = NexusBackgroundDark,
    onBackground = NexusOnSurfaceDark,
    surface = NexusSurfaceDark,
    onSurface = NexusOnSurfaceDark,
    surfaceVariant = NexusSurfaceVariantDark,
    onSurfaceVariant = NexusOnSurfaceVariantDark,
    surfaceContainer = NexusSurfaceContainerDark,
    outline = NexusOutlineDark,
    outlineVariant = NexusOutlineVariantDark,
    error = NexusErrorDark,
    onError = NexusOnErrorDark,
    errorContainer = NexusErrorContainerDark,
    onErrorContainer = NexusOnErrorContainerDark
)

/**
 * NEXUS X Premium Material 3 Theme.
 * Follows system light/dark. Full color roles, typography, and shapes.
 */
@Composable
fun NexusAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NexusTypography,
        shapes = NexusShapes,
        content = content
    )
}
