package com.michaeltchuang.walletsdk.ui.signing.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.sending_the_transaction
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.your_transaction_is_processed_algorand
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun PendingTransactionLoaderWidget() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AlgoKitTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .background(Color(0xFF2A2A1E), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                LottieConfetti()
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = localizedStringResource(Res.string.sending_the_transaction),
                color = AlgoKitTheme.colors.textMain,
                style = typography.body.large.sansMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = localizedStringResource(Res.string.your_transaction_is_processed_algorand),
                color = AlgoKitTheme.colors.textGray,
                style = typography.body.regular.sansMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview
@Composable
fun SendingTransactionLoadingPreview() {
    PendingTransactionLoaderWidget()
}
