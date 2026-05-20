package com.michaeltchuang.walletsdk.ui.settings.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.discover
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.REPO_URL
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.webview.AlgoKitWebViewScreen
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource

@Composable
fun DiscoverScreen(
    navController: NavController,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier =
            Modifier
                .fillMaxSize()
                .background(AlgoKitTheme.colors.background),
    ) {
        AlgoKitTopBar(
            modifier = Modifier.padding(horizontal = 16.dp),
            title = localizedStringResource(Res.string.discover),
            onClick = { navController.popBackStack() },
        )
        AlgoKitWebViewScreen(
            modifier = Modifier.fillMaxSize(),
            REPO_URL,
        )
    }
}
