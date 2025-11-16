package com.michaeltchuang.walletsdk.ui.signing.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.michaeltchuang.walletsdk.ui.base.utils.LottieJsonAnimations
import io.github.alexzhirkevich.compottie.LottieAnimation
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalResourceApi::class)
@Composable
fun LottieConfetti(modifier: Modifier = Modifier) {
    var animationData by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        animationData = LottieJsonAnimations.TRANSACTION_LOADING
    }

    val composition by rememberLottieComposition(
        LottieCompositionSpec.JsonString(animationData),
    )
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = 100,
        speed = 1.5f,
    )
    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = modifier,
    )
}
