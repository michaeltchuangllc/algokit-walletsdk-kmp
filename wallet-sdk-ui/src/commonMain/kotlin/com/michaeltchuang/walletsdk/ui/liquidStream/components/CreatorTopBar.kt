package com.michaeltchuang.walletsdk.ui.liquidStream.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.dmsans_bold
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_dark_setting
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_eye
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_minimise
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_user
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun CreatorTopBar(
    modifier: Modifier = Modifier,
    creatorUsername: String? = "michaeltchuang.algo",
    numbersOfViewers: String? = "1",
    onSettingsClick: () -> Unit,
    onMinimise: () -> Unit,
) {
    Column(modifier = modifier) {
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
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.ic_user),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    }
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
                        text = creatorUsername ?: "michaeltchuang.algo",
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
                            text = " ${numbersOfViewers ?: "0"} of VIEWERS",
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

@Preview
@Composable
fun CreatorTopBarPreview() {
    AlgoKitTheme {
        Box(modifier = Modifier.background(Color.DarkGray).padding(16.dp)) {
            CreatorTopBar(
                onSettingsClick = {},
                onMinimise = {},
            )
        }
    }
}
