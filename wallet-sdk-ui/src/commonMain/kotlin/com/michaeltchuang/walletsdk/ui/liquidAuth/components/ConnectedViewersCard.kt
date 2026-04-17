package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitDarkColor
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitLightColor
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalCustomColors
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalThemeIsDark
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.displayName
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.round

@Composable
internal fun ConnectedViewersCard(
    sessionId: String,
    balanceUSDC: Double,
    connectionType: IceConnectionType,
    currentBlockNumber: Long? = null,
    blockChainLabel: String = "ALGORAND",
    networkLabel: String = "TESTNET",
    balanceCurrencySymbol: String = "¦",
    originUrl: String = "-",
    viewerAddress: String? = null,
) {
    val colors = AlgoKitTheme.colors
    val isDarkTheme = LocalThemeIsDark.current.value
    val balanceText = (round(balanceUSDC * 100) / 100).toString()
    val streamCost = if (connectionType == IceConnectionType.RELAY) "0.5" else "0.1"
    val progress = (balanceUSDC / 1.0).coerceIn(0.0, 1.0).toFloat()
    val shortSessionId = if (sessionId.length > 28) "${sessionId.take(28)}..." else sessionId
    val originDisplay =
        originUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .ifBlank { "-" }
    val badgeText =
        buildString {
            append("WEBRTC")
            currentBlockNumber?.let { append(" • ALGORAND $networkLabel #$it") }
        }
    val backgroundGradient =
        if (isDarkTheme) {
            Brush.horizontalGradient(
                colors = listOf(Color(0xFF0E2B45), Color(0xFF102338), Color(0xFF0A1C2D)),
            )
        } else {
            Brush.horizontalGradient(
                colors = listOf(Color(0xFFF7FBFF), Color(0xFFF1F7FD), Color(0xFFEAF3FB)),
            )
        }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(backgroundGradient)
                    .border(1.dp, colors.streamHostSheetBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                MetricBlock(
                    label = "SESSION VAULT",
                    value = balanceText,
                    unit = "USDC",
                    alignEnd = false,
                )
                MetricBlock(
                    label = "STREAM COST",
                    value = streamCost,
                    unit = "USDC/BLOCK+GAS",
                    alignEnd = true,
                )
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(colors.streamHostDivider),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(progress)
                            .height(7.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF2F46FF), Color(0xFF2CC8CB)),
                                ),
                            ),
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(20.dp),
                        ).border(1.dp, colors.streamHostSheetBorder, RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(colors.streamHostAccent))
                Text(
                    text = "  $badgeText",
                    color = colors.streamHostCaption,
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(modifier = Modifier.weight(1f).height(1.dp).background(colors.streamHostDivider))
                Text(
                    text = "michaeltchuang.algo",
                    color = colors.streamHostTitle,
                    fontSize = 25.sp / 2f,
                    fontWeight = FontWeight.Bold,
                )
                Box(modifier = Modifier.weight(1f).height(1.dp).background(colors.streamHostDivider))
            }

            MetaRow(label = "ORIGIN:", value = originDisplay)
            MetaRow(label = "TYPE:", value = connectionType.displayName().uppercase())
            MetaRow(label = "REQUEST ID:", value = shortSessionId)
            viewerAddress?.takeIf { it.isNotBlank() }?.let {
                MetaRow(label = "ACCOUNT:", value = it.toShortenedAddress())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                NavButton(text = "‹")
                Text(
                    text = "1 OF 1 STREAMS",
                    color = colors.streamHostCaption,
                    fontSize = 20.sp / 2f,
                    letterSpacing = 1.sp,
                )
                NavButton(text = "›")
            }
        }
    }
}

@Composable
private fun MetricBlock(
    label: String,
    value: String,
    unit: String,
    alignEnd: Boolean,
) {
    val colors = AlgoKitTheme.colors
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(
            text = label,
            color = colors.streamHostMetricLabel,
            fontSize = 21.sp / 2f,
            letterSpacing = 1.sp,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = value,
                color = colors.streamHostTitle,
                fontSize = 66.sp / 2f,
                fontWeight = FontWeight.Bold,
                lineHeight = 1.sp,
            )
            Text(
                text = unit,
                color = colors.streamHostBodyText,
                fontSize = 30.sp / 2f,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MetaRow(
    label: String,
    value: String,
) {
    val colors = AlgoKitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.streamHostCaption,
            fontSize = 21.sp / 2f,
            letterSpacing = 0.5.sp,
        )
        Text(
            text = value,
            color = colors.streamHostBodyText,
            fontSize = 24.sp / 2f,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
    }
}

@Composable
private fun NavButton(text: String) {
    val colors = AlgoKitTheme.colors
    Box(
        modifier =
            Modifier
                .size(
                    width = 42.dp,
                    height = 38.dp,
                ).clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.streamHostSheetBorder, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.streamHostTitle,
            fontSize = 24.sp / 2f,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Preview
@Composable
private fun ConnectedViewersCardLightPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(false),
            LocalCustomColors provides AlgoKitLightColor,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().background(AlgoKitTheme.colors.background).padding(vertical = 16.dp),
            ) {
                ConnectedViewersCard(
                    sessionId = "019d1234-1a42-7dd7-9474-222b83739bac",
                    balanceUSDC = round(0.2 * 100) / 100,
                    connectionType = IceConnectionType.RELAY,
                    currentBlockNumber = null,
                    networkLabel = "TESTNET",
                )
            }
        }
    }
}

@Preview
@Composable
private fun ConnectedViewersCardDarkPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(true),
            LocalCustomColors provides AlgoKitDarkColor,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().background(AlgoKitTheme.colors.background).padding(vertical = 16.dp),
            ) {
                ConnectedViewersCard(
                    sessionId = "session-1234567890",
                    balanceUSDC = round(0.2 * 100) / 100,
                    connectionType = IceConnectionType.STUN,
                    currentBlockNumber = null,
                    networkLabel = "MAINNET",
                )
            }
        }
    }
}
