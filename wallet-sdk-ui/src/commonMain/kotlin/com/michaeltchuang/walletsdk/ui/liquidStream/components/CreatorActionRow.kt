package com.michaeltchuang.walletsdk.ui.liquidStream.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_analytics
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_camera_flip
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_mic
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_mic_off
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_video_camera
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_video_camera_off
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.ColorPalette
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

@Composable
fun CreatorActionRow(
    onWalletClick: () -> Unit,
    onCameraClick: () -> Unit,
    onMicClick: () -> Unit,
    onRotateCamera: () -> Unit,
    onStatsClick: () -> Unit,
    isMicMuted: Boolean = false,
    isCameraEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
                InnerActionButton(
                    icon = if (isCameraEnabled) Res.drawable.ic_video_camera else Res.drawable.ic_video_camera_off,
                    onClick = onCameraClick,
                    backgroundColor = if (isCameraEnabled) Color(0xFFE6E8FF) else Color(0xFFFF3B30),
                    iconTint = if (isCameraEnabled) Color(0xFF2D2DF1) else Color.White,
                )
                InnerActionButton(
                    icon = if (isMicMuted) Res.drawable.ic_mic_off else Res.drawable.ic_mic,
                    onClick = onMicClick,
                    backgroundColor = if (isMicMuted) Color(0xFFFF3B30) else Color(0xFFE6E8FF),
                    iconTint = if (isMicMuted) Color.White else Color(0xFF2D2DF1),
                )
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
