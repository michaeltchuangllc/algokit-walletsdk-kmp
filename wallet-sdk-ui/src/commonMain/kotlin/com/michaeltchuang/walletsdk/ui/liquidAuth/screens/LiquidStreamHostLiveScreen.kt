package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.dmsans_bold
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_camera_flip
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_dark_setting
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_eye
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_gift
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_analytics
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_mic
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_minimise
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_send
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_video_camera
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.ColorPalette
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.ConnectedViewersCard
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidStreamHostViewModel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LiquidStreamHostLiveScreen(
    cameraPreview: @Composable (() -> Unit)? = null,
    onSettingsClick: () -> Unit = {},
    onMinimise: () -> Unit = {},
    onWalletClick: () -> Unit = {},
    onCameraClick: () -> Unit = {},
    onMicClick: () -> Unit = {},
    onRotateCamera: () -> Unit = {},
    onStatsClick: () -> Unit = {},
    onStatsModalVisibilityChanged: (Boolean) -> Unit = {},
    onSendClick: () -> Unit = {},
    sessionId: String? = null,
    progressBalanceUsdc: Double? = null,
    remainingBalanceUsdc: Double? = progressBalanceUsdc,
    connectionType: IceConnectionType = IceConnectionType.UNKNOWN,
    currentBlockNumber: Long? = null,
    blockChainLabel: String = "ALGORAND",
    networkLabel: String = "TESTNET",
    balanceCurrencySymbol: String = "¦",
    originUrl: String = "-",
) {
    val viewModel: LiquidStreamHostViewModel = koinViewModel()
    val uiState = viewModel.state.collectAsStateWithLifecycle().value

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is LiquidStreamHostViewModel.ViewEvent.SendMessage -> onSendClick()
                is LiquidStreamHostViewModel.ViewEvent.ShowError -> Unit
            }
        }
    }

    LiquidStreamHostLiveScreenContent(
        cameraPreview = cameraPreview,
        onSettingsClick = {
            viewModel.onSettingsClicked()
            onStatsModalVisibilityChanged(false)
            onSettingsClick()
        },
        onMinimise = onMinimise,
        onWalletClick = onWalletClick,
        onCameraClick = onCameraClick,
        onMicClick = onMicClick,
        onRotateCamera = onRotateCamera,
        onStatsClick = {
            val isStatsVisible = !uiState.isStatsModalVisible
            viewModel.onStatsClicked()
            onStatsModalVisibilityChanged(isStatsVisible)
            onStatsClick()
        },
        onSendClick = viewModel::onSendClicked,
        sessionId = sessionId,
        progressBalanceUsdc = progressBalanceUsdc,
        remainingBalanceUsdc = remainingBalanceUsdc,
        connectionType = connectionType,
        currentBlockNumber = currentBlockNumber,
        blockChainLabel = blockChainLabel,
        networkLabel = networkLabel,
        balanceCurrencySymbol = balanceCurrencySymbol,
        originUrl = originUrl,
        uiState = uiState,
        onTextChanged = viewModel::onMessageChanged,
        onStatsDismissed = {
            viewModel.onStatsDismissed()
            onStatsModalVisibilityChanged(false)
        },
        onStreamCostTabSelected = viewModel::onStreamCostTabSelected,
        onPayoutFrequencyTabSelected = viewModel::onPayoutFrequencyTabSelected,
        onSubsidizeViewerFeesChanged = viewModel::onSubsidizeViewerFeesChanged,
        onSettingsDismissed = viewModel::onSettingsDismissed,
    )
}

@Composable
private fun LiquidStreamHostLiveScreenContent(
    cameraPreview: @Composable (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onMinimise: () -> Unit,
    onWalletClick: () -> Unit,
    onCameraClick: () -> Unit,
    onMicClick: () -> Unit,
    onRotateCamera: () -> Unit,
    onStatsClick: () -> Unit,
    onSendClick: () -> Unit,
    sessionId: String?,
    progressBalanceUsdc: Double?,
    remainingBalanceUsdc: Double?,
    connectionType: IceConnectionType,
    currentBlockNumber: Long?,
    blockChainLabel: String,
    networkLabel: String,
    balanceCurrencySymbol: String,
    originUrl: String,
    uiState: LiquidStreamHostViewModel.UiState,
    onTextChanged: (String) -> Unit,
    onStatsDismissed: () -> Unit,
    onStreamCostTabSelected: (String) -> Unit,
    onPayoutFrequencyTabSelected: (String) -> Unit,
    onSubsidizeViewerFeesChanged: (Boolean) -> Unit,
    onSettingsDismissed: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF76818D)),
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
                            colors = listOf(Color(0x008A9AA9), Color(0x9F23384E), Color(0xD010263D)),
                        ),
                    ),
        )

        Box(
            modifier =
                Modifier
                    .size(width = 260.dp, height = 102.dp)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 256.dp)
                    .clip(RoundedCornerShape(topStart = 120.dp, topEnd = 120.dp))
                    .background(Color(0x12FFFFFF)),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 0.dp)
                    .imePadding(),
        ) {
            CreatorTopBar(
                onSettingsClick = onSettingsClick,
                onMinimise = onMinimise,
            )
            Spacer(Modifier.weight(1f))
            // CreatorChatStack()
            Spacer(Modifier.height(18.dp))
            CreatorActionRow(
                onWalletClick = onWalletClick,
                onCameraClick = onCameraClick,
                onMicClick = onMicClick,
                onRotateCamera = onRotateCamera,
                onStatsClick = onStatsClick,
            )
            Spacer(Modifier.height(18.dp))
            CreatorComposer(
                text = uiState.message,
                onTextChanged = onTextChanged,
                onSendClick = onSendClick,
            )
            Spacer(Modifier.height(20.dp))
            HomeIndicator()
            Spacer(Modifier.height(4.dp))
        }

        if (uiState.isStatsModalVisible) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x4D001423))
                        .clickable { onStatsDismissed() },
            )

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier.padding(bottom = 250.dp),
                ) {
                    ConnectedViewersCard(
                        sessionId = sessionId ?: "session-pending",
                        remainingBalanceUSDC = remainingBalanceUsdc,
                        progressBalanceUSDC = progressBalanceUsdc,
                        connectionType = connectionType,
                        currentBlockNumber = currentBlockNumber,
                        networkLabel = networkLabel,
                        originUrl = originUrl,
                    )
                }
            }
        }

        if (uiState.isSettingsModalVisible) {
            StreamHostSettingsSheet(
                selectedStreamCostTabId = uiState.selectedStreamCostTabId,
                selectedPayoutFrequencyTabId = uiState.selectedPayoutFrequencyTabId,
                subsidizeViewerFeesEnabled = uiState.subsidizeViewerFeesEnabled,
                realTimeRate = uiState.realTimeRate,
                streamRevenue = uiState.streamRevenue,
                securedViaLabel = uiState.securedViaLabel,
                blockNumberLabel = uiState.blockNumberLabel,
                onStreamCostTabSelected = onStreamCostTabSelected,
                onPayoutFrequencyTabSelected = onPayoutFrequencyTabSelected,
                onSubsidizeViewerFeesChanged = onSubsidizeViewerFeesChanged,
                onDismiss = onSettingsDismissed,
            )
        }
    }
}

@Composable
private fun CreatorTopBar(
    onSettingsClick: () -> Unit,
    onMinimise: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(2.dp, Color(0xFF35D3EF), CircleShape)
                                .background(Color(0x33FFFFFF)),
                    )
                    Box(
                        modifier =
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2D2DF1))
                                .border(2.dp, Color.White, CircleShape),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "michaeltchuang.algo",
                        style =
                            TextStyle(
                                fontSize = 18.sp,
                                lineHeight = 28.8.sp,
                                fontFamily = FontFamily(Font(Res.font.dmsans_bold, FontWeight.Bold)),
                                fontWeight = FontWeight.W700,
                                color = Color.White,
                            ),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            vectorResource(Res.drawable.ic_eye),
                            contentDescription = null,
                            tint = Color(0xFFAFEFF5),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "# of VIEWERS",
                            color = Color(0xFFBFD4DD),
                            fontSize = 14.sp / 1.2f,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TopSquareIconButton(icon = Res.drawable.ic_dark_setting, onClick = onSettingsClick)
                TopSquareIconButton(icon = Res.drawable.ic_minimise, onClick = onMinimise)
            }
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
private fun CreatorChatStack() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "@BLOCK_RUNNER",
            color = Color(0xFFB9D9E1),
            fontSize = 12.sp,
            letterSpacing = 1.2.sp,
        )
        // CreatorMessageBubble(text = "The micro-billing is so smooth here.")
        Spacer(Modifier.height(2.dp))
        Text(
            text = "@STREAM_HOPPER",
            color = Color(0xFFB9D9E1),
            fontSize = 12.sp,
            letterSpacing = 1.2.sp,
        )
        CreatorMessageBubble(
            text = "I've missed your streams! I've been busy with work, but I'm so glad to be back. Keep up the amazing content!",
            maxLines = 3,
        )
    }
}

@Composable
private fun CreatorMessageBubble(
    text: String,
    maxLines: Int = Int.MAX_VALUE,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color(0x66AEEFF2), RoundedCornerShape(18.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xCC123651), Color(0xCC102F49)),
                    ),
                ).padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFFE0EFF5),
            fontSize = 15.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CreatorActionRow(
    onWalletClick: () -> Unit,
    onCameraClick: () -> Unit,
    onMicClick: () -> Unit,
    onRotateCamera: () -> Unit,
    onStatsClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OuterActionContainer {
            InnerActionButton(
                icon = Res.drawable.ic_wallet,
                onClick = onWalletClick,
                backgroundColor = Color(0xFFAEEFF2),
                iconTint = Color(0xFF0B203B),
                showPlusBadge = true,
            )
        }

        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xCC082947))
                    .border(1.dp, Color(0x403EE6EA), RoundedCornerShape(24.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InnerActionButton(icon = Res.drawable.ic_video_camera, onClick = onCameraClick, backgroundColor = Color(0xFFE6E8FF))
                InnerActionButton(icon = Res.drawable.ic_mic, onClick = onMicClick, backgroundColor = Color(0xFFE6E8FF))
                InnerActionButton(icon = Res.drawable.ic_camera_flip, onClick = onRotateCamera, backgroundColor = Color(0xFFE6E8FF))
            }
        }

        OuterActionContainer {
            InnerActionButton(
                icon = Res.drawable.ic_analytics,
                onClick = onStatsClick,
                backgroundColor = Color(0xFFAEEFF2),
                iconTint = Color(0xFF0B203B),
            )
        }
    }
}

@Composable
private fun OuterActionContainer(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(67.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xCC082947))
                .border(1.dp, Color(0x40D7E6EE), RoundedCornerShape(22.dp))
                .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun InnerActionButton(
    icon: DrawableResource,
    onClick: () -> Unit,
    backgroundColor: Color = ColorPalette.Turquoise600,
    iconTint: Color = Color(0xFF2D2DF1),
    showPlusBadge: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .size(45.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(color = backgroundColor)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            vectorResource(icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        if (showPlusBadge) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 4.dp, bottom = 4.dp)
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2D2DF1)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "+", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CreatorComposer(
    text: String,
    onTextChanged: (String) -> Unit,
    onSendClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0x668A9AAC))
                .border(1.dp, Color(0x40D7E6EE), RoundedCornerShape(24.dp))
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(52.dp)
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
                modifier = Modifier.size(16.dp),
            )
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(color = Color(0xEDE4EEF6), fontSize = 34.sp / 1.9f),
            decorationBox = { innerTextField ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = "Say something...",
                            color = Color(0xCFE4EEF6),
                            fontSize = 34.sp / 1.9f,
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
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2D2DF1))
                    .clickable(onClick = onSendClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                vectorResource(Res.drawable.ic_send),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
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
                    .width(140.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x66B7C7D2)),
        )
    }
}

@Preview
@Composable
private fun LiquidStreamHostLiveScreenPreview() {
    AlgoKitTheme {
        var uiState by remember { mutableStateOf(LiquidStreamHostViewModel.UiState()) }
        LiquidStreamHostLiveScreenContent(
            cameraPreview = null,
            onSettingsClick = { uiState = uiState.copy(isSettingsModalVisible = true, isStatsModalVisible = false) },
            onMinimise = {},
            onWalletClick = {},
            onCameraClick = {},
            onMicClick = {},
            onRotateCamera = {},
            onStatsClick = { uiState = uiState.copy(isStatsModalVisible = !uiState.isStatsModalVisible) },
            onSendClick = { uiState = uiState.copy(message = "") },
            sessionId = "session-preview-id",
            progressBalanceUsdc = 11.9,
            remainingBalanceUsdc = 12.0,
            connectionType = IceConnectionType.UNKNOWN,
            currentBlockNumber = 38291041L,
            blockChainLabel = "ALGORAND",
            networkLabel = "TESTNET",
            balanceCurrencySymbol = "A",
            originUrl = "https://example.app",
            uiState = uiState,
            onTextChanged = { message -> uiState = uiState.copy(message = message) },
            onStatsDismissed = { uiState = uiState.copy(isStatsModalVisible = false) },
            onStreamCostTabSelected = { tabId -> uiState = uiState.copy(selectedStreamCostTabId = tabId) },
            onPayoutFrequencyTabSelected = { tabId -> uiState = uiState.copy(selectedPayoutFrequencyTabId = tabId) },
            onSubsidizeViewerFeesChanged = { enabled -> uiState = uiState.copy(subsidizeViewerFeesEnabled = enabled) },
            onSettingsDismissed = { uiState = uiState.copy(isSettingsModalVisible = false) },
        )
    }
}
