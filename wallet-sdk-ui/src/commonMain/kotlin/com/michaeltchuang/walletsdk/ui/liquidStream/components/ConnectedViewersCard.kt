package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.core.foundation.utils.LiquidStreamConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitDarkColor
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitLightColor
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalCustomColors
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalThemeIsDark
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.displayName
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.round

data class ConnectedViewerInfo(
    val sessionId: String,
    val remainingBalanceUSDC: Double?,
    val progressBalanceUSDC: Double?,
    val progressCapacityUSDC: Double? = null,
    val revenueCapacityUSDC: Double? = null,
    val connectionType: IceConnectionType,
    val currentBlockNumber: Long? = null,
    val networkLabel: String = "TESTNET",
    val originUrl: String = "-",
    val viewerAddress: String? = null,
    val lastSettledUSDC: Double? = null,
    val startRound: Long? = null,
)

@Composable
internal fun ConnectedViewersCard(viewers: List<ConnectedViewerInfo>) {
    if (viewers.isEmpty()) return

    val colors = AlgoKitTheme.colors
    val isDarkTheme = LocalThemeIsDark.current.value
    val pagerState = rememberPagerState(pageCount = { viewers.size })
    val coroutineScope = rememberCoroutineScope()

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
                    .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) { page ->
                val viewer = viewers[page]
                ConnectedViewerContent(viewer)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                NavButton(
                    text = "‹",
                    onClick = {
                        if (pagerState.currentPage > 0) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        }
                    },
                )
                Text(
                    text = "${pagerState.currentPage + 1} OF ${viewers.size} STREAMS",
                    color = colors.streamHostCaption,
                    fontSize = 20.sp / 2f,
                    letterSpacing = 1.sp,
                )
                NavButton(
                    text = "›",
                    onClick = {
                        if (pagerState.currentPage < viewers.size - 1) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectedViewerContent(viewer: ConnectedViewerInfo) {
    val colors = AlgoKitTheme.colors
    val balanceText = viewer.remainingBalanceUSDC?.let { (round(it * 100) / 100).toString() } ?: "N/A"
    val streamCost =
        if (viewer.connectionType == IceConnectionType.RELAY) {
            "0.5"
        } else {
            microUsdcToUsdcDisplay(LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC)
        }
    val progress =
        if (viewer.progressBalanceUSDC != null) {
            val capacity = (viewer.progressCapacityUSDC ?: viewer.remainingBalanceUSDC ?: 0.0).coerceAtLeast(0.0)
            if (capacity > 0.0) {
                (viewer.progressBalanceUSDC / capacity).coerceIn(0.0, 1.0).toFloat()
            } else {
                0f
            }
        } else {
            0f
        }
    val shortSessionId = if (viewer.sessionId.length > 28) "${viewer.sessionId.take(28)}..." else viewer.sessionId
    val originDisplay =
        viewer.originUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .ifBlank { "-" }
    val badgeText =
        buildString {
            append("WEBRTC")
            viewer.currentBlockNumber?.let { append(" • ALGORAND ${viewer.networkLabel} #$it") }
        }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
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
                text = viewer.viewerAddress.toShortenedAddress(),
                color = colors.streamHostTitle,
                fontSize = 25.sp / 2f,
                fontWeight = FontWeight.Bold,
            )
            Box(modifier = Modifier.weight(1f).height(1.dp).background(colors.streamHostDivider))
        }

        MetaRow(label = "ORIGIN:", value = originDisplay)
        MetaRow(label = "TYPE:", value = viewer.connectionType.displayName().uppercase())
        MetaRow(label = "REQUEST ID:", value = shortSessionId)
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
            fontSize = 12.sp,
            lineHeight = 14.4.sp,
            textAlign = TextAlign.Right,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Normal,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = value,
                color = colors.streamHostTitle,
                fontSize = 28.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = unit,
                color = colors.streamHostBodyText,
                fontSize = 12.sp,
                lineHeight = 14.4.sp,
                fontWeight = FontWeight.Bold,
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
private fun microUsdcToUsdcDisplay(microUsdc: Long): String {
    val usdc = microUsdc / 1_000_000.0
    val rounded = round(usdc * 100) / 100
    return rounded.toString()
}

@Composable
private fun NavButton(
    text: String,
    onClick: () -> Unit,
) {
    val colors = AlgoKitTheme.colors
    Box(
        modifier =
            Modifier
                .size(
                    width = 42.dp,
                    height = 38.dp,
                ).clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.streamHostSheetBorder, RoundedCornerShape(12.dp))
                .clickable { onClick() },
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
                    viewers =
                        listOf(
                            ConnectedViewerInfo(
                                sessionId = "019d1234-1a42-7dd7-9474-222b83739bac",
                                remainingBalanceUSDC = 8.88,
                                connectionType = IceConnectionType.RELAY,
                                currentBlockNumber = null,
                                networkLabel = "TESTNET",
                                progressBalanceUSDC = 0.2,
                                originUrl = "michaeltchuang.ngrok.dev",
                                viewerAddress = "6Z4BAS2WIVUXW4DLEVTTQHFRUMGQZZFZQ4OTIUUZCOGIJH3MEPJHMAYX3U",
                            ),
                            ConnectedViewerInfo(
                                sessionId = "session-2",
                                remainingBalanceUSDC = 5.0,
                                connectionType = IceConnectionType.STUN,
                                currentBlockNumber = 12345L,
                                networkLabel = "MAINNET",
                                progressBalanceUSDC = 0.5,
                                originUrl = "another-origin.com",
                                viewerAddress = "6Z4BAS2WIVUXW4DLEVTTQHFRUMGQZZFZQ4OTIUUZCOGIJH3MEPJHMAYX3U",
                            ),
                        ),
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
                    viewers =
                        listOf(
                            ConnectedViewerInfo(
                                sessionId = "session-1234567890",
                                remainingBalanceUSDC = 8.88,
                                connectionType = IceConnectionType.STUN,
                                currentBlockNumber = null,
                                networkLabel = "MAINNET",
                                progressBalanceUSDC = 0.2,
                                originUrl = "michaeltchuang.ngrok.dev",
                                viewerAddress = "6Z4BAS2WIVUXW4DLEVTTQHFRUMGQZZFZQ4OTIUUZCOGIJH3MEPJHMAYX3U",
                            ),
                        ),
                )
            }
        }
    }
}
