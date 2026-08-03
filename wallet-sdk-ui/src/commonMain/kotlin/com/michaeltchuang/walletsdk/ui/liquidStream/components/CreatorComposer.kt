package com.michaeltchuang.walletsdk.ui.liquidStream.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_gift
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_send
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.vectorResource

@Composable
fun CreatorComposer(
    text: String,
    onTextChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
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
