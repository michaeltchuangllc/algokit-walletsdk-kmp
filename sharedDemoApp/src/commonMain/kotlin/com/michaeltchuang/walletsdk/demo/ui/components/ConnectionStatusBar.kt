package com.michaeltchuang.walletsdk.demo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.ColorPalette
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.ConnectionStatusState
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.SESSION_LOGGED_OUT

@Composable
fun ConnectionStatusBar(modifier: Modifier = Modifier) {
    val state = ConnectionStatusState

    if (!state.isVisible) return

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(ColorPalette.Gray700)
                .clickable { state.isExpanded = !state.isExpanded }
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    when {
                        state.session == SESSION_LOGGED_OUT -> {
                            "Connecting to LiquidAuth"
                        }
                        state.session.isBlank() -> {
                            "Waiting for session"
                        }
                        else -> {
                            "Connected to ${state.session}"
                        }
                    },
                color = ColorPalette.Turquoise500,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.session.isNotBlank() && state.session != SESSION_LOGGED_OUT) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Disconnect",
                        tint = Color.Red,
                        modifier = Modifier.clickable { state.onDisconnect?.invoke() },
                    )
                }
                Icon(
                    imageVector = if (state.isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (state.isExpanded) "Collapse" else "Expand",
                    tint = ColorPalette.Turquoise500,
                )
            }
        }

        AnimatedVisibility(
            visible = state.isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.origin.isNotBlank()) {
                    InfoRow(
                        label = "Origin",
                        value = state.origin,
                    )
                }
                if (state.requestId.isNotBlank()) {
                    InfoRow(
                        label = "Request ID",
                        value = state.requestId,
                    )
                }
                if (state.accountAddress.isNotBlank()) {
                    InfoRow(
                        label = "Account",
                        value = state.accountAddress.toShortenedAddress(),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ColorPalette.Gray400,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = AlgoKitTheme.colors.textMain,
            fontWeight = FontWeight.Normal,
        )
    }
}
