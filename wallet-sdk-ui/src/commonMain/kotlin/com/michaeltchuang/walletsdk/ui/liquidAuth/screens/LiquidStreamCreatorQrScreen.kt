package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.figma_ic_lock
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_copy
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_dark_setting
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_liquid_qr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.ColorPalette
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import qrgenerator.qrkitpainter.rememberQrKitPainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidStreamCreatorQrScreen(
    cameraPreview: @Composable (() -> Unit)?,
    onEndStream: () -> Unit,
) {
    val requestId = "C4FEC83F-8C43-401B-A739-77C87F83835B"
    val qrPainter = rememberQrKitPainter(data = "https://liquid-auth.example/connect/$requestId")
    var showViewerBottomSheet by remember { mutableStateOf(false) }
    val viewerBottomSheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ColorPalette.White)
                .verticalScroll(rememberScrollState()),
    ) {
        HeaderRow()
        Spacer(modifier = Modifier.height(20.dp))
        StatusBanner()
        Spacer(modifier = Modifier.height(15.dp))
        CreatorQrCard(
            qrPainter = qrPainter,
            requestId = requestId,
            onGenerateNewQrCode = { showViewerBottomSheet = true },
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showViewerBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showViewerBottomSheet = false },
            sheetState = viewerBottomSheetState,
            dragHandle = null,
            containerColor = Color.Transparent,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Scaffold(
                modifier = Modifier.fillMaxHeight(0.9f),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    LiquidStreamViewerScreen(
                        onMinimize = { showViewerBottomSheet = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .height(45.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .border(1.dp, ColorPalette.RichNavy900Alpha10, RoundedCornerShape(100.dp))
                    .padding(start = 7.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(ColorPalette.CoolGray150),
            )
            Text(
                text = "algo_rhytem",
                color = ColorPalette.RichNavy900,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            )
            Text(
                text = "⌄",
                color = ColorPalette.ElectricBlue600,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeaderSquareButton(
                background = ColorPalette.ElectricBlue600Alpha10,
                icon = Res.drawable.ic_liquid_qr,
                tint = ColorPalette.ElectricBlue600,
            )
            HeaderSquareButton(
                background = ColorPalette.CoolGray150,
                icon = Res.drawable.ic_dark_setting,
                tint = ColorPalette.RichNavy900,
            )
        }
    }
}

@Composable
private fun HeaderSquareButton(
    background: Color,
    icon: org.jetbrains.compose.resources.DrawableResource,
    tint: Color,
) {
    Box(
        modifier =
            Modifier
                .size(45.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            vectorResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun StatusBanner() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(ColorPalette.SignalRed500Alpha10)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(ColorPalette.SignalRed500),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "Status:",
            color = ColorPalette.SignalRed500,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Waiting for client to scan QR code...",
            color = ColorPalette.SignalRed500,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun CreatorQrCard(
    qrPainter: androidx.compose.ui.graphics.painter.Painter,
    requestId: String,
    onGenerateNewQrCode: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(ColorPalette.Aqua200)
                .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                vectorResource(Res.drawable.figma_ic_lock),
                contentDescription = null,
                tint = ColorPalette.ElectricBlue600,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Secured by Algorand Testnet",
                color = ColorPalette.RichNavy900,
                fontSize = 12.sp,
                letterSpacing = (-0.2).sp,
            )
        }

        Text(
            text = "Scan this QR code\nwith your device to connect:",
            color = ColorPalette.RichNavy900,
            fontSize = 32.sp / 2f,
            lineHeight = 19.2.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val qrContainerSize = (maxWidth * 0.78f).coerceIn(190.dp, 320.dp)
            val qrPadding = (qrContainerSize * 0.07f).coerceAtLeast(12.dp)

            Box(
                modifier =
                    Modifier
                        .size(qrContainerSize)
                        .clip(RoundedCornerShape(22.3.dp))
                        .background(Color.White)
                        .padding(qrPadding),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = qrPainter,
                    contentDescription = "Creator QR Code",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Text(
            text = "REQUEST ID:",
            color = ColorPalette.RichNavy900,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
        )

        Row(
            modifier =
                Modifier
                    .height(26.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(100.dp))
                    .border(1.dp, ColorPalette.ElectricBlue600, RoundedCornerShape(100.dp))
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = requestId,
                color = ColorPalette.ElectricBlue600,
                fontSize = 12.sp,
                lineHeight = 14.4.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                vectorResource(Res.drawable.ic_copy),
                contentDescription = null,
                tint = ColorPalette.ElectricBlue600,
                modifier = Modifier.size(16.dp),
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFFE4E9EB)),
        )

        Button(
            onClick = onGenerateNewQrCode,
            shape = RoundedCornerShape(20.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = ColorPalette.ElectricBlue600,
                    contentColor = Color.White,
                ),
            modifier = Modifier.fillMaxWidth().height(55.dp),
        ) {
            Icon(
                vectorResource(Res.drawable.ic_liquid_qr),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Generate New QR Code",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
    }
}

@Preview
@Composable
private fun LiquidStreamCreatorQrScreenPreview() {
    AlgoKitTheme {
        LiquidStreamCreatorQrScreen(
            cameraPreview = null,
            onEndStream = {},
        )
    }
}
