package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_secure_backup
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.i_want_to_import_algorand
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.i_want_to_recover
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.i_want_to_recover_an
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.i_want_to_recover_qr
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.i_want_to_recover_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.i_want_to_restore_my
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_backup
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_global
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_key
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_ledger
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_qr
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.import_a_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.import_an_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.import_from_pera_web
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.key
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ledger
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.pair_ledger_device
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.qr_code
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.recover_a_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.recover_an_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.recover_an_account_with_qr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.GroupChoiceWidget
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.Resource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun AccountRecoveryTypeSelectionScreen(
    navController: NavController,
    onClick: (message: String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(color = AlgoKitTheme.colors.background)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
    ) {
        AlgoKitTopBar(
            modifier = Modifier.padding(horizontal = 24.dp),
            onClick = { navController.popBackStack() },
        )
        TitleWidget()
        Spacer(modifier = Modifier.height(30.dp))
        RecoverAnAccountWidget(navController = navController)
        RecoverAnAccountWithQRWidget(navController)
        // PairLedgerDeviceWidget(onClick)
        // ImportPeraWebWidget(onClick)
        // AlgorandSecureBackupWidget(onClick)
    }
}

@Composable
private fun TitleWidget(isOnHdWallet: Boolean = false) {
    val titleRes =
        if (isOnHdWallet) {
            Res.string.import_a_wallet
        } else {
            Res.string.import_an_account
        }

    Text(
        modifier = Modifier.padding(horizontal = 24.dp),
        style = typography.title.regular.sansMedium,
        color = AlgoKitTheme.colors.textMain,
        text = localizedStringResource(titleRes),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoverAnAccountWidget(
    isOnHdWallet: Boolean = false,
    navController: NavController,
) {
    val titleRes: Resource
    val descriptionRes: Resource
    if (isOnHdWallet) {
        titleRes = Res.string.recover_a_wallet
        descriptionRes = Res.string.i_want_to_recover_wallet
    } else {
        titleRes = Res.string.recover_an_account
        descriptionRes = Res.string.i_want_to_recover
    }

    GroupChoiceWidget(
        title = localizedStringResource(titleRes),
        description = localizedStringResource(descriptionRes),
        icon = vectorResource(Res.drawable.ic_key),
        iconContentDescription = localizedStringResource(Res.string.key),
        onClick = { navController.navigate(AlgoKitScreens.RECOVER_AN_ACCOUNT_SCREEN.name) },
    )
}

@Composable
private fun RecoverAnAccountWithQRWidget(navController: NavController) {
    GroupChoiceWidget(
        title = localizedStringResource(Res.string.recover_an_account_with_qr),
        description = localizedStringResource(Res.string.i_want_to_recover_qr),
        icon = vectorResource(Res.drawable.ic_qr),
        iconContentDescription = localizedStringResource(Res.string.qr_code),
        onClick = { navController.navigate(AlgoKitScreens.QR_CODE_SCANNER_SCREEN.name) },
    )
}

@Composable
private fun PairLedgerDeviceWidget(onClick: (message: String) -> Unit) {
    GroupChoiceWidget(
        title = localizedStringResource(Res.string.pair_ledger_device),
        description = localizedStringResource(Res.string.i_want_to_recover_an),
        iconContentDescription = localizedStringResource(Res.string.ledger),
        icon = vectorResource(Res.drawable.ic_ledger),
        onClick = { onClick(WalletSdkConstants.FEATURE_NOT_SUPPORTED_YET) },
    )
}

@Composable
private fun ImportPeraWebWidget(onClick: (message: String) -> Unit) {
    GroupChoiceWidget(
        title = localizedStringResource(Res.string.import_from_pera_web),
        description = localizedStringResource(Res.string.i_want_to_import_algorand),
        iconContentDescription = localizedStringResource(Res.string.import_from_pera_web),
        icon = vectorResource(Res.drawable.ic_global),
        onClick = { onClick(WalletSdkConstants.FEATURE_NOT_SUPPORTED_YET) },
    )
}

@Composable
private fun AlgorandSecureBackupWidget(onClick: (message: String) -> Unit) {
    GroupChoiceWidget(
        title = localizedStringResource(Res.string.algorand_secure_backup),
        description = localizedStringResource(Res.string.i_want_to_restore_my),
        iconContentDescription = localizedStringResource(Res.string.i_want_to_restore_my),
        icon = vectorResource(Res.drawable.ic_backup),
        onClick = { onClick(WalletSdkConstants.FEATURE_NOT_SUPPORTED_YET) },
    )
}

@Preview
@Composable
fun AccountRecoveryTypeSelectionScreenPreview() {
    AlgoKitTheme {
        AccountRecoveryTypeSelectionScreen(rememberNavController()) {}
    }
}
