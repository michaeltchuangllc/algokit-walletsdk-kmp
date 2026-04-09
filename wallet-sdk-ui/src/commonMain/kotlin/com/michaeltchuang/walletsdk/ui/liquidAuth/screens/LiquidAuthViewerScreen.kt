package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.figma_ic_drop
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.figma_ic_lock
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_dark_setting
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_eye
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_gift
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_minimise
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_send
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.ConnectedViewersCard
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.displayName
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthViewerViewModel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LiquidAuthViewerScreen(
    sessionId: String = "",
    connectionType: IceConnectionType = IceConnectionType.UNKNOWN,
    cameraPreview: @Composable (() -> Unit)? = null,
    onMinimize: () -> Unit = {},
    onSendClick: () -> Unit = {},
    viewerAddress: String = "-",
    originUrl: String = "-",
    networkLabel: String = "TESTNET",
    currentBlockNumber: Long? = null,
    balanceAlgos: Double = 0.0,
) {
    val viewModel: LiquidAuthViewerViewModel = koinViewModel()
    val uiState = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is LiquidAuthViewerViewModel.ViewEvent.SendMessage -> onSendClick()
                is LiquidAuthViewerViewModel.ViewEvent.ShowError -> Unit
            }
        }
    }

    LiquidAuthViewerScreenContent(
        sessionId = sessionId,
        connectionType = connectionType,
        cameraPreview = cameraPreview,
        onMinimize = onMinimize,
        viewerAddress = viewerAddress,
        originUrl = originUrl,
        networkLabel = networkLabel,
        currentBlockNumber = currentBlockNumber,
        balanceAlgos = balanceAlgos,
        uiState = uiState,
        onSettingsClick = viewModel::onSettingsClicked,
        onAnalyticsClick = viewModel::onAnalyticsClicked,
        onAnalyticsDismissed = viewModel::onAnalyticsDismissed,
        onViewerSettingsDismissed = viewModel::onViewerSettingsDismissed,
        onPayoutFrequencyTabSelected = viewModel::onPayoutFrequencyTabSelected,
        onWillingToBeRelayerChanged = viewModel::onWillingToBeRelayerChanged,
        onMessageChanged = viewModel::onMessageChanged,
        onSendClick = viewModel::onSendClicked,
    )
}

@Composable
private fun LiquidAuthViewerScreenContent(
    sessionId: String,
    connectionType: IceConnectionType,
    cameraPreview: @Composable (() -> Unit)?,
    onMinimize: () -> Unit,
    viewerAddress: String,
    originUrl: String,
    networkLabel: String,
    currentBlockNumber: Long?,
    balanceAlgos: Double,
    uiState: LiquidAuthViewerViewModel.UiState,
    onSettingsClick: () -> Unit,
    onAnalyticsClick: () -> Unit,
    onAnalyticsDismissed: () -> Unit,
    onViewerSettingsDismissed: () -> Unit,
    onPayoutFrequencyTabSelected: (String) -> Unit,
    onWillingToBeRelayerChanged: (Boolean) -> Unit,
    onMessageChanged: (String) -> Unit,
    onSendClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF788592)),
    ) {
        if (cameraPreview != null) {
            cameraPreview()
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(520.dp)
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0f to Color(0x00001423),
                                    0.58f to Color(0xB2001423),
                                    1f to Color(0xD9001423),
                                ),
                        ),
                    ),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                    .navigationBarsPadding()
                    .imePadding(),
        ) {
            Spacer(Modifier.height(14.dp))
            Header(
                onSettingsClick = onSettingsClick,
                onMinimize = onMinimize,
            )
            Spacer(Modifier.height(20.dp))
            // GiftTickerCard()
            Spacer(Modifier.weight(1f))
            // ChatStack()
            Spacer(Modifier.height(16.dp))
            FloatingButtons(onAnalyticsClick = onAnalyticsClick)
            Spacer(Modifier.height(20.dp))
            StreamStatusRow(
                sessionId = sessionId,
                connectionType = connectionType,
            )
            Spacer(Modifier.height(12.dp))
            ChatComposer(
                message = uiState.message,
                onMessageChanged = onMessageChanged,
                onSendClick = onSendClick,
            )
            Spacer(Modifier.height(12.dp))
        }

        if (uiState.showAnalyticsModal) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x4D001423))
                        .clickable { onAnalyticsDismissed() },
            )

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier.padding(bottom = 250.dp),
                ) {
                    ConnectedViewersCard(
                        sessionId = sessionId.ifBlank { "session-pending" },
                        balanceAlgos = balanceAlgos,
                        connectionType = connectionType,
                        currentBlockNumber = currentBlockNumber,
                        networkLabel = networkLabel,
                        originUrl = originUrl,
                        viewerAddress = viewerAddress,
                    )
                }
            }
        }

        if (uiState.showViewerSettingsSheet) {
            StreamViewerSettingsSheet(
                selectedPayoutFrequencyTabId = uiState.selectedPayoutFrequencyTabId,
                willingToBeRelayerEnabled = uiState.willingToBeRelayerEnabled,
                realTimeRate = uiState.realTimeRate,
                streamRevenue = uiState.streamRevenue,
                securedViaLabel = uiState.securedViaLabel,
                blockNumberLabel = uiState.blockNumberLabel,
                onPayoutFrequencyTabSelected = onPayoutFrequencyTabSelected,
                onWillingToBeRelayerChanged = onWillingToBeRelayerChanged,
                onDismiss = onViewerSettingsDismissed,
            )
        }
    }
}

@Composable
private fun StreamStatusRow(
    sessionId: String,
    connectionType: IceConnectionType,
) {
    val trimmedSession = if (sessionId.isNotBlank()) "${sessionId.take(8)}..." else "-"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Session: $trimmedSession",
            color = Color(0xFFB8CDD7),
            fontSize = 12.sp,
        )
        Text(
            text = "Network: ${connectionType.displayName()}",
            color = Color(0xFFB8CDD7),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Header(
    onSettingsClick: () -> Unit,
    onMinimize: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier =
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .border(1.dp, Color(0xFF3FD2EF), CircleShape)
                            .background(Color(0x29FFFFFF)),
                )
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E34F7))
                            .border(1.dp, Color.White, CircleShape),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "michaeltchuang.algo",
                    color = Color.White,
                    fontSize = 20.sp / 1.5f,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        vectorResource(Res.drawable.ic_eye),
                        contentDescription = null,
                        tint = Color(0xFF99EFF2),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "1,284 VIEWERS",
                        color = Color(0xFFB8CDD7),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TopSquareIconButton(icon = Res.drawable.ic_dark_setting, onClick = onSettingsClick)
            TopSquareIconButton(icon = Res.drawable.ic_minimise, onClick = onMinimize)
        }
    }
}

@Composable
private fun TopSquareIconButton(
    icon: DrawableResource,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(45.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x2EFFFFFF))
                .border(1.dp, Color(0x35FFFFFF), RoundedCornerShape(16.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            vectorResource(icon),
            contentDescription = null,
            tint = Color(0xFFB9EFEF),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun GiftTickerCard() {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x9047E0E8), RoundedCornerShape(20.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xCC13889A), Color(0xCC2B3CFF)),
                    ),
                ).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF3CD2E4), Color(0xFF2A34F7)),
                        ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                vectorResource(Res.drawable.ic_gift),
                contentDescription = null,
                tint = Color(0xFFEFFFFF),
                modifier = Modifier.size(16.dp),
            )
        }
        Column {
            Text(text = "@CYQUEEN SENT", color = Color(0xFFACEBF1), fontSize = 10.sp, letterSpacing = 0.7.sp)
            Text(text = "0.888 USDC", color = Color.White, fontSize = 28.sp / 1.5f)
        }
    }
}

@Composable
private fun ChatStack() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.Transparent,
            modifier =
                Modifier.background(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xCC2B3BFF), Color(0xCC1E93E0), Color(0xCC1D6F7D)),
                        ),
                    shape = RoundedCornerShape(18.dp),
                ),
        ) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        vectorResource(Res.drawable.figma_ic_drop),
                        contentDescription = null,
                        tint = Color(0xFF2ED8EA),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("@cyqueen", color = Color(0xFFE8F4FF), fontSize = 18.sp / 1.5f)
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x3398EDF0))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "GIFT SUPERCHAT",
                            color = Color(0xFFEBF9FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        )
                    }
                }
                Text(
                    text = "This drop is legendary! Algos well spent",
                    color = Color(0xFFF4F8FF),
                    fontSize = 14.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x4DE9FCFF))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text("0.888 USDC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(text = "›", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(text = "@BLOCK_RUNNER", color = Color(0xFFB4D2DB), fontSize = 14.sp / 1.4f, letterSpacing = 1.sp)

        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC082947))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Text(
                text = "The micro-billing is so smooth here.",
                color = Color(0xFFD8EAF2),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "SYSTEM", color = Color(0x778DC1D0), fontSize = 14.sp / 1.4f, letterSpacing = 1.sp)
            Text(text = "Viewer sent [Cyber Rose] gift", color = Color(0x819EB0BD), fontSize = 28.sp / 1.9f)
        }
    }
}

@Composable
private fun FloatingButtons(onAnalyticsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(66.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC052440))
                    .border(1.dp, Color(0x403EE6EA), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFB9EFEF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    vectorResource(Res.drawable.ic_wallet),
                    contentDescription = null,
                    tint = Color(0xFF0C2A48),
                    modifier = Modifier.size(20.dp),
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 5.dp, bottom = 5.dp)
                            .size(15.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2D2DF1)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .size(66.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC052440))
                    .border(1.dp, Color(0x403EE6EA), RoundedCornerShape(20.dp))
                    .clickable(onClick = onAnalyticsClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFB9EFEF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    vectorResource(Res.drawable.figma_ic_lock),
                    contentDescription = null,
                    tint = Color(0xFF0C2A48),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StreamActions(
    onStopStreaming: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF53C57))
                    .clickable(onClick = onStopStreaming),
            contentAlignment = Alignment.Center,
        ) {
            Text("Stop Streaming", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x66001423))
                    .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(12.dp))
                    .clickable(onClick = onDisconnect),
            contentAlignment = Alignment.Center,
        ) {
            Text("Disconnect Client", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChatComposer(
    message: String,
    onMessageChanged: (String) -> Unit,
    onSendClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(70.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0x668A9AAC))
                .border(1.dp, Color(0x40D7E6EE), RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF3CD2E4), Color(0xFF2A34F7)),
                        ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                vectorResource(Res.drawable.ic_gift),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        BasicTextField(
            value = message,
            onValueChange = onMessageChanged,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle =
                TextStyle(
                    color = Color(0xEDE4EEF6),
                    fontSize = 30.sp / 1.9f,
                ),
            decorationBox = { innerTextField ->
                Box {
                    if (message.isEmpty()) {
                        Text(
                            text = "Say something...",
                            color = Color(0xCFE4EEF6),
                            fontSize = 30.sp / 1.9f,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Box(
            modifier =
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color(0x66D4E6EE), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("☺", color = Color(0xBFE0EFF5), fontSize = 14.sp)
        }
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2D2DF1))
                    .clickable(onClick = onSendClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                vectorResource(Res.drawable.ic_send),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun HomeIndicator() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .width(136.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x66B7C7D2)),
        )
    }
}

@Preview
@Composable
private fun LiquidAuthViewerScreenPreview() {
    AlgoKitTheme {
        var uiState by remember { mutableStateOf(LiquidAuthViewerViewModel.UiState()) }
        LiquidAuthViewerScreenContent(
            sessionId = "session-preview-id",
            connectionType = IceConnectionType.UNKNOWN,
            cameraPreview = null,
            onMinimize = {},
            viewerAddress = "ABCDE...XYZ",
            originUrl = "https://example.app",
            networkLabel = "TESTNET",
            currentBlockNumber = 38291041L,
            balanceAlgos = 12.34,
            uiState = uiState,
            onSettingsClick = { uiState = uiState.copy(showViewerSettingsSheet = true, showAnalyticsModal = false) },
            onAnalyticsClick = { uiState = uiState.copy(showAnalyticsModal = !uiState.showAnalyticsModal) },
            onAnalyticsDismissed = { uiState = uiState.copy(showAnalyticsModal = false) },
            onViewerSettingsDismissed = { uiState = uiState.copy(showViewerSettingsSheet = false) },
            onPayoutFrequencyTabSelected = { tabId -> uiState = uiState.copy(selectedPayoutFrequencyTabId = tabId) },
            onWillingToBeRelayerChanged = { enabled -> uiState = uiState.copy(willingToBeRelayerEnabled = enabled) },
            onMessageChanged = { message -> uiState = uiState.copy(message = message) },
            onSendClick = { uiState = uiState.copy(message = "") },
        )
    }
}
