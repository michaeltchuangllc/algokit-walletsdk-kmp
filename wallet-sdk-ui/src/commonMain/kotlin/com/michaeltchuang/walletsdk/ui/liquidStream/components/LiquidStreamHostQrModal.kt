package com.michaeltchuang.walletsdk.ui.liquidStream.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.close_popup
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.copied_to_clipboard
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.copy_session_id
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.dmsans_bold
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.figma_ic_lock
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_copy
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_cross
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.invite_viewers
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.scan_qr_to_join_stream
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.secured_by_algorand_testnet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.session_id_label
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.stream_is_live
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.stream_qr_code
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.ColorPalette
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.vectorResource
import qrgenerator.qrkitpainter.rememberQrKitPainter

@Composable
fun LiquidStreamHostQrModal(
    requestId: String,
    qrUrl: String,
    securedViaLabel: String = "",
    onDismiss: () -> Unit,
) {
    val qrPainter = rememberQrKitPainter(data = qrUrl)
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }
    val colors = AlgoKitTheme.colors

    Box(modifier = Modifier.fillMaxSize()) {
        // Semi-transparent dark overlay backdrop
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.streamHostOverlay)
                    .clickable { onDismiss() },
        )

        // Modal Card
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .fillMaxWidth()
                    .widthIn(max = 380.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.streamHostCardBackground)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 22.dp)
                        .clickable(enabled = false) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top Row: Status Indicator & Close Icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(ColorPalette.Turquoise600),
                        )
                        Text(
                            text = localizedStringResource(Res.string.stream_is_live),
                            color = ColorPalette.SlateBlue400,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                    }

                    Icon(
                        vectorResource(Res.drawable.ic_cross),
                        contentDescription = localizedStringResource(Res.string.close_popup),
                        tint = ColorPalette.SlateBlue400,
                        modifier =
                            Modifier
                                .size(24.dp)
                                .clickable { onDismiss() },
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Title
                Text(
                    text = localizedStringResource(Res.string.invite_viewers),
                    style =
                        TextStyle(
                            fontSize = 24.sp,
                            lineHeight = 28.8.sp,
                            fontFamily = FontFamily(Font(Res.font.dmsans_bold, FontWeight.W700)),
                            fontWeight = FontWeight.W700,
                            color = ColorPalette.RichNavy900,
                            textAlign = TextAlign.Center,
                        ),
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Subtitle
                Text(
                    text = localizedStringResource(Res.string.scan_qr_to_join_stream),
                    style =
                        TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 19.2.sp,
                            fontFamily = FontFamily(Font(Res.font.dmsans_bold, FontWeight.W700)),
                            fontWeight = FontWeight.W700,
                            color = ColorPalette.RichNavy900,
                            textAlign = TextAlign.Center,
                        ),
                )

                Spacer(modifier = Modifier.height(20.dp))

                // QR Code Display Card
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val qrContainerSize = (maxWidth * 0.82f).coerceIn(200.dp, 280.dp)
                    val qrPadding = (qrContainerSize * 0.08f).coerceAtLeast(14.dp)

                    Box(
                        modifier =
                            Modifier
                                .size(qrContainerSize)
                                .clip(RoundedCornerShape(26.dp))
                                .background(Color.White)
                                .padding(qrPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = qrPainter,
                            contentDescription = localizedStringResource(Res.string.stream_qr_code),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // SESSION ID Label
                Text(
                    text = localizedStringResource(Res.string.session_id_label),
                    color = ColorPalette.RichNavy900,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // SESSION ID Pill Box
                Row(
                    modifier =
                        Modifier
                            .height(38.dp)
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .border(1.5.dp, ColorPalette.ElectricBlue600, CircleShape)
                            .clickable {
                                clipboardManager.setText(AnnotatedString(requestId))
                                isCopied = true
                            }
                            .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (isCopied) localizedStringResource(Res.string.copied_to_clipboard) else requestId,
                        color = ColorPalette.ElectricBlue600,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    Icon(
                        vectorResource(Res.drawable.ic_copy),
                        contentDescription = localizedStringResource(Res.string.copy_session_id),
                        tint = ColorPalette.ElectricBlue600,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Security Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        vectorResource(Res.drawable.figma_ic_lock),
                        contentDescription = null,
                        tint = ColorPalette.RichNavy900,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = securedViaLabel.ifBlank { localizedStringResource(Res.string.secured_by_algorand_testnet) },
                            color = ColorPalette.RichNavy900,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}


@PreviewLightDark
@Composable
private fun LiquidStreamHostQrModalPreview() {
    AlgoKitTheme {
        val reqId = "C4FEC83F-8C43-401B-A739-77C87F83835B"
        LiquidStreamHostQrModal(
            requestId = reqId,
            qrUrl = "https://liquid-auth.example/connect/$reqId",
            onDismiss = {},
        )
    }
}

