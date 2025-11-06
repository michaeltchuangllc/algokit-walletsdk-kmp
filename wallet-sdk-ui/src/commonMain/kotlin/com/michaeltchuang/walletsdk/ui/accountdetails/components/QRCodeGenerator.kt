package com.michaeltchuang.walletsdk.ui.accountdetails.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import qrgenerator.qrkitpainter.rememberQrKitPainter

@Composable
fun QRCodeDisplay(
    data: String,
    size: Dp = 232.dp,
    modifier: Modifier = Modifier,
    onQRCodeGenerated: ((ImageBitmap?) -> Unit)? = null
) {
    val qrPainter = rememberQrKitPainter(data = data)

    LaunchedEffect(qrPainter, data) {
        if (data.isNotBlank()) {
            onQRCodeGenerated?.invoke(null)
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (data.isNotBlank()) {
            Image(
                painter = qrPainter,
                contentDescription = "QR Code for $data",
                modifier = Modifier.size(size - 16.dp)
            )
        } else {
            // Fallback when data is empty
            Text(
                text = "No Data\nProvided",
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}