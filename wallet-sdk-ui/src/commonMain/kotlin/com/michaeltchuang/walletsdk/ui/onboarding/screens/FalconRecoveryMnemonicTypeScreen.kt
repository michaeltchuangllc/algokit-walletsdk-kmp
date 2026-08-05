package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.falcon25_recovery_description
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.falcon25_recovery_title
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.legacy_text
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_falcon24_description
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_falcon24_title
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.recovery_phrase_24_words
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.recovery_phrase_25_words
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.select_your_recovery_phrase_type
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.MnemonicTypeCard
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitHighlightedGrayText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitTitleText
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource

@Composable
fun FalconRecoveryMnemonicTypeScreen(navController: NavController) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(AlgoKitTheme.colors.background),
    ) {
        AlgoKitTopBar(
            modifier =
                Modifier
                    .padding(horizontal = 24.dp)
                    .wrapContentSize(),
            onClick = { navController.popBackStack() },
        )

        AlgoKitTitleText(
            modifier = Modifier.padding(horizontal = 24.dp),
            text = localizedStringResource(Res.string.select_your_recovery_phrase_type),
        )
        Spacer(Modifier.height(24.dp))

        MnemonicTypeCard(
            title = localizedStringResource(Res.string.falcon25_recovery_title),
            description = localizedStringResource(Res.string.falcon25_recovery_description),
            footer = localizedStringResource(Res.string.recovery_phrase_25_words),
            onClick = {
                navController.navigate(AlgoKitScreens.RECOVER_PHRASE_SCREEN.name + "/falcon25")
            },
        )
        Spacer(Modifier.height(24.dp))

        MnemonicTypeCard(
            title = localizedStringResource(Res.string.mnemonic_type_falcon24_title),
            description = localizedStringResource(Res.string.mnemonic_type_falcon24_description),
            footer = localizedStringResource(Res.string.recovery_phrase_24_words),
            highlightContent = {
                AlgoKitHighlightedGrayText(
                    text = localizedStringResource(Res.string.legacy_text),
                )
            },
            onClick = {
                navController.navigate(AlgoKitScreens.RECOVER_PHRASE_SCREEN.name + "/falcon24")
            },
        )
    }
}
