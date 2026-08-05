package com.michaeltchuang.walletsdk.ui.liquidStream.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.figma_ic_drop
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.ChatUiMessage
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ChatStack(
    messages: List<ChatUiMessage>,
    modifier: Modifier = Modifier,
) {
    val giftMessages = messages.filter { it.amount != null }
    val latestGiftMessage = giftMessages.lastOrNull()
    val chatMessages = messages.filter { it.amount == null }.takeLast(3)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedContent(
            targetState = latestGiftMessage,
            transitionSpec = {
                (slideInHorizontally(initialOffsetX = { -it }) + fadeIn())
                    .togetherWith(slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
                    .using(SizeTransform(clip = false))
            },
            label = "GiftMessageAnimation",
        ) { gift ->
            if (gift != null) {
                GiftMessageItem(gift)
            } else {
                Spacer(Modifier)
            }
        }

        for (message in chatMessages) {
            ChatMessageItem(message)
        }
    }
}

@Composable
private fun ChatMessageItem(message: ChatUiMessage) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "@${message.sender.uppercase()}",
            color = Color(0xFFB4D2DB).copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp),
        )

        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        brush =
                            Brush.horizontalGradient(
                                colorStops =
                                    arrayOf(
                                        0.00f to Color(0xFFAFEFF5),
                                        0.05f to Color(0xFFAFEFF5),
                                        1.00f to Color(0x00AFEFF5),
                                    ),
                            ),
                    ).padding(start = 2.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .background(Color(0xCC082947), RoundedCornerShape(15.dp))
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Text(
                    text = message.text,
                    color = Color(0xFFD8EAF2),
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

private val GiftGradient =
    Brush.linearGradient(
        colors =
            listOf(
                Color(0xFF31DADA),
                Color(0xFF2D2DF1),
            ),
        start = Offset(0f, 0f),
        end = Offset(20f, 100f),
        tileMode = TileMode.Clamp,
    )

@Composable
private fun GiftMessageItem(message: ChatUiMessage) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFFAFEFF5)),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            modifier =
                Modifier
                    .padding(start = 2.dp)
                    .background(
                        brush =
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xCC2B3BFF), Color(0xCC1E93E0), Color(0xCC1D6F7D)),
                            ),
                        shape = RoundedCornerShape(20.dp),
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
                    Text(
                        text = "@${message.sender.take(8).lowercase()}",
                        color = Color(0xFFE8F4FF),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    brush =
                                        Brush.linearGradient(
                                            colors =
                                                listOf(
                                                    Color(0x4031DADA), // 25% opacity
                                                    Color(0x402D2DF1),
                                                ),
                                            start = Offset.Zero,
                                            end = Offset.Infinite,
                                        ),
                                    shape = RoundedCornerShape(50),
                                ).border(
                                    width = 1.2.dp,
                                    brush = GiftGradient,
                                    shape = RoundedCornerShape(12.dp),
                                ).padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "GIFT SUPERCHAT",
                            color = Color(0xFFEBF9FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }
                Text(
                    text = message.text,
                    color = Color(0xFFF4F8FF),
                    fontSize = 14.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(brush = GiftGradient)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "${message.amount} ${message.asset}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(text = "›", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Preview
@Composable
fun ChatStackPreview() {
    AlgoKitTheme {
        Box(
            modifier =
                Modifier
                    .background(Color(0xFF001423))
                    .padding(20.dp),
        ) {
            ChatStack(
                messages =
                    listOf(
                        ChatUiMessage(
                            sender = "michaeltchuang.algo",
                            text = "This is a preview message",
                            timestamp = 0L,
                        ),
                        ChatUiMessage(
                            sender = "BLOCK_RUNNER",
                            text = "The micro-billing is so smooth here.",
                            timestamp = 0L,
                        ),
                        ChatUiMessage(
                            sender = "gift.algo",
                            text = "Supporting the stream!",
                            timestamp = 0L,
                            amount = "10.0",
                            asset = "USDC",
                        ),
                    ),
            )
        }
    }
}
