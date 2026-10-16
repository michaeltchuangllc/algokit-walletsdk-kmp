package com.michaeltchuang.walletsdk.ui.onboarding.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_ledger_old_export
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_phone_new
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIconRoundShapeBig
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitTitleText
import com.michaeltchuang.walletsdk.ui.base.utils.LottieJsonAnimations
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.vectorResource

@Composable
fun AnimationLoader(
    modifier: Modifier = Modifier,
    start: ImageVector,
    lottie: LottieCompositionSpec,
    end: ImageVector,
    description: String,
) {
    val preloaderLottieComposition by rememberLottieComposition(lottie)
    val preloaderProgress by animateLottieCompositionAsState(
        preloaderLottieComposition,
        iterations = Compottie.IterateForever,
        isPlaying = true,
    )
    Column(modifier = modifier) {
        Row(modifier = Modifier.align(alignment = Alignment.CenterHorizontally)) {
            AlgoKitIconRoundShapeBig(
                imageVector = start,
                contentDescription = "start side of the animation",
            )
            Image(
                painter =
                    rememberLottiePainter(
                        composition = preloaderLottieComposition,
                        progress = { preloaderProgress },
                    ),
                contentDescription = null,
                modifier =
                    Modifier
                        .width(120.dp)
                        .align(alignment = Alignment.CenterVertically),
            )
            AlgoKitIconRoundShapeBig(
                imageVector = end,
                contentDescription = "end side of the animation",
            )
        }
        AlgoKitTitleText(
            modifier =
                Modifier
                    .padding(top = 40.dp)
                    .align(alignment = Alignment.CenterHorizontally),
            text = description,
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
fun AnimationLoaderPreview() {
    var animationData by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        animationData = LottieJsonAnimations.TRANSACTION_LOADING
    }

    AlgoKitTheme {
        AnimationLoader(
            start = vectorResource(Res.drawable.ic_ledger_old_export),
            end = vectorResource(Res.drawable.ic_phone_new),
            lottie = LottieCompositionSpec.JsonString(animationData),
            description = "Searching your accounts",
        )
    }
}
