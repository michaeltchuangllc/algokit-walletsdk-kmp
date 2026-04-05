/*
package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_camera_flip
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_creator_eye
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_cross
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_dark_setting
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_every_block
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_free
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_gift
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_heart
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_info
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_liquid_qr
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_passkey
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_qr_scan
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_signal
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_star
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_usdc
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.pera_icon_3d
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.LiquidRingToggle
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.LiquidSegmentedTabs
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.SegmentedTabItem
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidStreamHostViewModel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

object Variables {
    val AquaAqua50: Color = Color(0x80B9EFEF)
    val WhiteWhite20: Color = Color(0x33FFFFFF)
}


private val streamCostTabs =
    listOf(
        SegmentedTabItem(
            id = LiquidStreamHostViewModel.STREAM_COST_FREE_TAB_ID,
            icon = Res.drawable.ic_free,
            title = "FREE",
        ),
        SegmentedTabItem(
            id = LiquidStreamHostViewModel.STREAM_COST_PAID_TAB_ID,
            icon = Res.drawable.ic_usdc,
            title = "8 micro-USDC",
        ),
    )

private val payoutFrequencyTabs =
    listOf(
        SegmentedTabItem(
            id = LiquidStreamHostViewModel.PAYOUT_EVERY_BLOCK_TAB_ID,
            icon = Res.drawable.ic_every_block,
            title = "Every Block",
            subtitle = "(~2.8 secs)",
        ),
        SegmentedTabItem(
            id = LiquidStreamHostViewModel.PAYOUT_EVERY_256_BLOCKS_TAB_ID,
            icon = Res.drawable.ic_every_block,
            title = "Every 256 Blocks",
            subtitle = "(~12 mins)",
        ),
    )

@Composable
fun LiquidStreamHostScreen(
    cameraPreview: @Composable (() -> Unit)? = null,
    onEndStream: () -> Unit,
) {
    val viewModel: LiquidStreamHostViewModel = koinViewModel()
    val uiState by viewModel.state.collectAsState()

    LiquidAuthCreatorScreenContent(
        uiState = uiState,
        cameraPreview = cameraPreview,
        onEndStream = onEndStream,
        onStreamCostTabSelected = viewModel::onStreamCostTabSelected,
        onPayoutFrequencyTabSelected = viewModel::onPayoutFrequencyTabSelected,
        onSubsidizeViewerFeesChanged = viewModel::onSubsidizeViewerFeesChanged,
    )
}

@Composable
private fun LiquidAuthCreatorScreenContent(
    uiState: LiquidStreamHostViewModel.UiState,
    cameraPreview: @Composable (() -> Unit)? = null,
    onEndStream: () -> Unit,
    onStreamCostTabSelected: (String) -> Unit,
    onPayoutFrequencyTabSelected: (String) -> Unit,
    onSubsidizeViewerFeesChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.White),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        HeaderRow(accountDisplayName = uiState.accountDisplayName)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            StreamInformationCard(uiState = uiState, cameraPreview = cameraPreview)
            MetricsBlock(uiState = uiState)
            StreamCostBlock(
                uiState = uiState,
                onStreamCostTabSelected = onStreamCostTabSelected,
                onSubsidizeViewerFeesChanged = onSubsidizeViewerFeesChanged,
            )
            PayoutFrequencyBlock(
                uiState = uiState,
                onPayoutFrequencyTabSelected = onPayoutFrequencyTabSelected,
            )
            EndStreamButtons(onEndStream)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HeaderRow(accountDisplayName: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            modifier = Modifier.border(1.dp, Color(0xFFE5E7EA), RoundedCornerShape(18.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFFE8EBEF)))
                Text(
                    accountDisplayName,
                    color = Color(0xFF0B2239),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    vectorResource(Res.drawable.ic_passkey),
                    null,
                    tint = Color(0xFF2A34F7),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeaderIcon(Res.drawable.ic_camera_flip, Color(0xFFD6D9F8), Color(0xFF2D3DFF))
            HeaderIcon(Res.drawable.ic_dark_setting, Color(0xFFE1E4E7), Color(0xFF0B2239))
            HeaderIcon(Res.drawable.ic_cross, Color(0xFFE1E4E7), Color(0xFF0B2239))
        }
    }
}

@Composable
private fun HeaderIcon(icon: DrawableResource, bg: Color, tint: Color) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            vectorResource(icon),
            null, tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun StreamInformationCard(
    uiState: LiquidStreamHostViewModel.UiState,
    cameraPreview: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier.fillMaxWidth().height(577.dp).clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF7C8690))
    ) {
        val isInPreview = LocalInspectionMode.current
        if (isInPreview) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF7C8690)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.pera_icon_3d),
                    contentDescription = "stream_preview",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(52.dp),
                )
            }
        } else {
            cameraPreview?.invoke()
        }
        Box(
            Modifier.fillMaxWidth().height(290.dp).align(Alignment.BottomCenter).background(
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0.0f to Color(0x002D2DF1),
                            0.7f to Color(0xB32D2DF1),
                        ),
                ),
            ),
        )

        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Badge(icon = Res.drawable.ic_signal, qualityLabel = uiState.qualityLabel)
                EyeBadge(icon = Res.drawable.ic_creator_eye, viewerCount = uiState.viewerCount)
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFD4D8DE))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(uiState.creatorName, color = Color.White, fontSize = 14.sp)
                        Text(uiState.creatorHandle, color = Color(0xFFBFC7FF), fontSize = 10.sp)
                    }
                }

                Text(
                    uiState.title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 30.sp,
                )

                Box(
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = Color(0xFF31DADA),
                            shape = RoundedCornerShape(size = 20.dp),
                        )
                        .width(266.99686.dp)
                        .height(65.99611.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0x31DADA),
                                    Color(0x2D2DF1),
                                ),
                            ),
                            shape = RoundedCornerShape(size = 20.dp),
                        )
                        .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xFF46D9E6),
                                            Color(0xFF2B86F3),
                                            Color(0xFF2A34F7),
                                        ),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                vectorResource(Res.drawable.ic_gift),
                                null,
                                tint = Color(0xFFF0FBFF),
                                modifier = Modifier.size(15.dp),
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                uiState.latestGiftTitle,
                                color = Color(0xFFACE9EE),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.8.sp,
                            )
                            Text(
                                uiState.latestGiftMessage,
                                color = Color(0xFFEFF8FF),
                                fontSize = 20.sp / 1.5f,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CounterPill(uiState.likeCount, Res.drawable.ic_heart, Color(0xFFFC3D58))
                    CounterPill(uiState.infoCount, Res.drawable.ic_info, Color(0xFF2D3DFF))
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0x66A8C6FF)), contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            vectorResource(Res.drawable.ic_qr_scan),
                            null,
                            tint = Color(0xFFE7F1FF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CounterPill(value: String, icon: DrawableResource, iconColor: Color) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1F2F8E)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(vectorResource(icon), null, tint = iconColor, modifier = Modifier.size(12.dp))
            Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Badge(
    icon: DrawableResource,
    qualityLabel: String,
) {
    Row(
        modifier =
            Modifier
                .border(
                    width = 1.dp,
                    color = Variables.AquaAqua50,
                    shape = RoundedCornerShape(size = 32.dp),
                )
                .width(107.dp)
                .height(40.dp)
                .padding(
                    start = 12.dp, top = 8.dp, end = 12.dp,
                    bottom = 8.dp
                ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            vectorResource(icon),
            null,
            tint = Color(0xFFBAF0F1),
            modifier = Modifier.size(16.dp),
        )
        Text(qualityLabel, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun EyeBadge(
    icon: DrawableResource,
    viewerCount: String,
) {
    Row(
        modifier =
            Modifier
                .border(
                    width = 1.dp,
                    color = Variables.WhiteWhite20,
                    shape = RoundedCornerShape(size = 32.dp),
                )
                .width(93.dp)
                .height(40.dp)
                .background(
                    color = Variables.WhiteWhite20,
                    shape = RoundedCornerShape(size = 32.dp),
                )
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            vectorResource(icon),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(viewerCount, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun MetricsBlock(uiState: LiquidStreamHostViewModel.UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement =
                Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "REAL-TIME RATE", color = Color(0xFF8792A0),
                    fontSize = 12.sp
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        uiState.streamRate,
                        fontSize = 42.sp / 1.5f,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF061D35),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "USDC/BLOCK",
                        fontSize = 12.sp,
                        color = Color(0xFF273CFF),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("STREAM REVENUE", color = Color(0xFF8792A0), fontSize = 12.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        uiState.streamRevenue,
                        fontSize = 42.sp / 1.5f,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF061D35),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "USDC",
                        fontSize = 12.sp,
                        color = Color(0xFF273CFF),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFDDE2E7)))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    vectorResource(Res.drawable.ic_passkey),
                    null,
                    tint = Color(0xFFAEE8E7),
                    modifier = Modifier.size(12.dp)
                )
                Text(uiState.securedVia, fontSize = 12.sp, color = Color(0xFF243C56))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFAEE8E7)))
                Text(
                    uiState.blockNumberLabel,
                    fontSize = 12.sp,
                    color = Color(0xFF243C56),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun StreamCostBlock(
    uiState: LiquidStreamHostViewModel.UiState,
    onStreamCostTabSelected: (String) -> Unit,
    onSubsidizeViewerFeesChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                "Stream Cost",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF071F37)
            )
            Text(
                "(PER BLOCK)",
                fontSize = 12.sp,
                color = Color(0xFF8792A0),
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }

        val streamCostTabsWithValue =
            streamCostTabs.map { tab ->
                if (tab.id == LiquidStreamHostViewModel.STREAM_COST_PAID_TAB_ID) {
                    tab.copy(title = uiState.streamCostPaidTitle)
                } else {
                    tab
                }
            }

        LiquidSegmentedTabs(
            tabs = streamCostTabsWithValue,
            selectedTabId = uiState.selectedStreamCostTabId,
            onTabSelected = onStreamCostTabSelected,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                vectorResource(Res.drawable.ic_info),
                null,
                tint = Color(0xFFAEE8E7),
                modifier = Modifier.size(12.dp)
            )
            Text(
                uiState.streamCostInfoText,
                fontSize = 12.sp,
                color = Color(0xFF243C56),
            )
        }

        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFAEE8E7)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LiquidRingToggle(
                        checked = uiState.subsidizeViewerFeesEnabled,
                        onCheckedChange = onSubsidizeViewerFeesChanged,
                    )
                    Text(
                        "Subsidize viewer transaction fees",
                        fontSize = 16.sp,
                        color = Color(0xFF09243D),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "The streamer covers 0.001 ALGO gas fee,\nso viewers can enjoy streams without\nworrying about costs.",
                    fontSize = 12.sp,
                    color = Color(0xFF45606F),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun PayoutFrequencyBlock(
    uiState: LiquidStreamHostViewModel.UiState,
    onPayoutFrequencyTabSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                "Payout Frequency",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF071F37)
            )
            Text(
                "(PER REVENUE BATCH)",
                fontSize = 12.sp,
                color = Color(0xFF8792A0),
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }

        LiquidSegmentedTabs(
            tabs = payoutFrequencyTabs,
            selectedTabId = uiState.selectedPayoutFrequencyTabId,
            onTabSelected = onPayoutFrequencyTabSelected,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
        )

        Row {
            Icon(
                vectorResource(Res.drawable.ic_star),
                null,
                tint = Color(0xFFAEE8E7),
                modifier = Modifier.size(12.dp)
            )
            Text(
                uiState.payoutInfoText,
                fontSize = 12.sp,
                color = Color(0xFF243C56),
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun EndStreamButtons(onEndStream: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(55.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFE4E6EC)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                vectorResource(Res.drawable.ic_liquid_qr),
                null,
                tint = Color(0xFF2D3DFF),
                modifier = Modifier.size(20.dp)
            )
        }
        Button(
            onClick = onEndStream,
            modifier = Modifier.weight(1f).height(55.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2E34F7),
                contentColor = Color.White
            ),
        ) {
            Box(
                Modifier.size(14.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFF53C57))
            )
            Spacer(Modifier.width(10.dp))
            Text("End Stream", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Preview()
@Composable
private fun LiquidAuthCreatorScreenPreview() {
    AlgoKitTheme {
        LiquidAuthCreatorScreenContent(
            uiState = LiquidStreamHostViewModel.mockUiState(),
            onEndStream = {},
            onStreamCostTabSelected = {},
            onPayoutFrequencyTabSelected = {},
            onSubsidizeViewerFeesChanged = {},
        )
    }
}
*/
