package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.runtime.Composable

/**
 * Platform-specific overlay that renders the AnswerScreen (Liquid Auth client) directly
 * in Compose instead of launching a separate Activity.
 *
 * Consumers should place this at the root of their Compose UI (e.g. alongside
 * LiquidAuthMiniPlayerOverlay) so it can survive bottom-sheet dismissal and
 * navigation changes.
 */
@Composable
expect fun AnswerScreenOverlay()
