package com.hybridmesh.relay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RelayDarkColorScheme = darkColorScheme(
    primary = RelayAccent,
    onPrimary = RelayBackground,

    secondary = RelayAccentMuted,
    onSecondary = RelayTextPrimary,

    background = RelayBackground,
    onBackground = RelayTextPrimary,

    surface = RelaySurface,
    onSurface = RelayTextPrimary,

    surfaceVariant = RelaySurfaceElevated,
    onSurfaceVariant = RelayTextSecondary,

    outline = RelayBorder
)

@Composable
fun HybridMeshRelayTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RelayDarkColorScheme,
        typography = RelayTypography,
        content = content
    )
}