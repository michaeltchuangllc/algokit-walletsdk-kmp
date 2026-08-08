package com.michaeltchuang.walletsdk.demo.ui.components

import algokit_walletsdk_kmp.shareddemoapp.generated.resources.Res
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalResourceApi::class)
@Composable
fun LottieConfetti(modifier: Modifier = Modifier) {
    var animationData by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        animationData = Res.readBytes("files/confetti_animation.json").decodeToString()
    }

    val composition by rememberLottieComposition(
        LottieCompositionSpec.JsonString(animationData),
    )
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = 1,
        speed = 1.5f,
    )
    Image(
        painter =
            rememberLottiePainter(
                composition = composition,
                progress = { progress },
            ),
        contentDescription = null,
        modifier = modifier,
    )
}
