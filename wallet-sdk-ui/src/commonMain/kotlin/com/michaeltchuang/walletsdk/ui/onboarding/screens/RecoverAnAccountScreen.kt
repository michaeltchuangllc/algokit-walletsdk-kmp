package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.bottom_sheet_mnemonic_type_title
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.legacy_text
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_algo25_description
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_algo25_footer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_algo25_title
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_falcon24_description
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_falcon24_footer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_falcon24_title
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_universal_description
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_universal_footer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_universal_title
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.new_text
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
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.PeraCard
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitHighlightedGrayText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitHighlightedGreenText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitTitleText
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun RecoverAnAccountScreen(
    navController: NavController,
    showSnackbar: (message: String, isError: Boolean) -> Unit,
) {
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
            text = localizedStringResource(Res.string.bottom_sheet_mnemonic_type_title),
        )
        Spacer(Modifier.height(24.dp))
        PeraCard(
            title = localizedStringResource(Res.string.mnemonic_type_falcon24_title),
            description = localizedStringResource(Res.string.mnemonic_type_falcon24_description),
            footer = localizedStringResource(Res.string.mnemonic_type_falcon24_footer),
            highlightContent = {
                AlgoKitHighlightedGreenText(
                    text = localizedStringResource(Res.string.new_text),
                )
            },
            onClick = {
                navController.navigate(AlgoKitScreens.RECOVER_PHRASE_SCREEN.name + "/falcon24")
            },
        )

        PeraCard(
            title = localizedStringResource(Res.string.mnemonic_type_algo25_title),
            description = localizedStringResource(Res.string.mnemonic_type_algo25_description),
            footer = localizedStringResource(Res.string.mnemonic_type_algo25_footer),
            highlightContent = {
                AlgoKitHighlightedGrayText(
                    text = localizedStringResource(Res.string.legacy_text),
                )
            },
            onClick = {
                navController.navigate(AlgoKitScreens.RECOVER_PHRASE_SCREEN.name + "/algo25")
            },
        )
        Spacer(Modifier.height(24.dp))
        PeraCard(
            title = localizedStringResource(Res.string.mnemonic_type_universal_title),
            description = localizedStringResource(Res.string.mnemonic_type_universal_description),
            footer = localizedStringResource(Res.string.mnemonic_type_universal_footer),
            highlightContent = {
                AlgoKitHighlightedGrayText(
                    text = localizedStringResource(Res.string.legacy_text),
                )
            },
            onClick = {
                showSnackbar(WalletSdkConstants.FEATURE_NOT_SUPPORTED_YET, false)
            },
        )
    }
}

@Preview
@Composable
fun RecoverAnAccountScreenPreview() {
    AlgoKitTheme {
        RecoverAnAccountScreen(
            navController = rememberNavController(),
            showSnackbar = { message, isError ->
                println("Snackbar: $message, isError: $isError")
            },
        )
    }
}
