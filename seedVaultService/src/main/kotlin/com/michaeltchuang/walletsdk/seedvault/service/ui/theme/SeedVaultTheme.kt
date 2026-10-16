package com.michaeltchuang.walletsdk.seedvault.service.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

// Dark palette sampled from the Solana Seed Vault reference screenshots in issue #423
private const val BACKGROUND = 0xFF0D1615
private const val CARD = 0xFF262B2B
private const val CHIP = 0xFF414B4A
private const val SECONDARY_TEXT = 0xFFA8B6B8
private const val DIM_ICON = 0xFF5A6463
private const val DIVIDER = 0xFF3A4140
private const val VAULT_TEAL = 0xFF35B6B4
private const val WARNING_RED = 0xFFFF4141

private val seedVaultColorScheme =
    darkColorScheme(
        primary = Color.White,
        onPrimary = Color(BACKGROUND),
        tertiary = Color(VAULT_TEAL),
        background = Color(BACKGROUND),
        onBackground = Color.White,
        surface = Color(BACKGROUND),
        onSurface = Color.White,
        onSurfaceVariant = Color(SECONDARY_TEXT),
        surfaceContainer = Color(CARD),
        surfaceContainerHigh = Color(CHIP),
        outline = Color(DIM_ICON),
        outlineVariant = Color(DIVIDER),
        error = Color(WARNING_RED),
    )

// Row titles in the reference are a bit smaller than the Material default titleLarge (22sp)
private val seedVaultTypography =
    Typography().run {
        copy(titleLarge = titleLarge.copy(fontSize = 19.sp, lineHeight = 26.sp))
    }

@Composable
fun SeedVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = seedVaultColorScheme,
        typography = seedVaultTypography,
        content = content,
    )
}
