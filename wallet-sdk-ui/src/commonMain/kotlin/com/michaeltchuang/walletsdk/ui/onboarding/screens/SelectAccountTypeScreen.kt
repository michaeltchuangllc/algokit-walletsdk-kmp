package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_algo_sign
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_solana_sign
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.core.foundation.utils.Chain
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.GroupChoiceWidget
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import org.jetbrains.compose.resources.vectorResource

@Composable
fun SelectAccountTypeScreen(navController: NavController) {
    SelectAccountTypeScreenContent(
        onBackClick = { navController.popBackStack() },
        onAlgorandClick = {
            navController.navigate("${AlgoKitScreens.SELECT_SEED_SCREEN.name}?accountType=${Chain.ALGORAND.name}")
        },
        onSolanaClick = {
            navController.navigate("${AlgoKitScreens.SELECT_SEED_SCREEN.name}?accountType=${Chain.SOLANA.name}")
        },
    )
}

@Composable
fun SelectAccountTypeScreenContent(
    onBackClick: () -> Unit,
    onAlgorandClick: () -> Unit,
    onSolanaClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color = AlgoKitTheme.colors.background)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
    ) {
        AlgoKitTopBar(
            onClick = onBackClick,
            title = "Select Account Type",
        )

        GroupChoiceWidget(
            title = "Algorand",
            description = "Import Algorand account from Seed Vault",
            iconContentDescription = "Algorand",
            icon = vectorResource(Res.drawable.ic_algo_sign),
            onClick = onAlgorandClick,
        )
        GroupChoiceWidget(
            title = "Solana",
            description = "Import Solana account from Seed Vault",
            iconContentDescription = "Solana",
            icon = vectorResource(Res.drawable.ic_solana_sign),
            onClick = onSolanaClick,
        )
    }
}

@Preview
@Composable
fun SelectAccountTypeScreenPreview() {
    AlgoKitTheme {
        SelectAccountTypeScreenContent(
            onBackClick = {},
            onAlgorandClick = {},
            onSolanaClick = {},
        )
    }
}
