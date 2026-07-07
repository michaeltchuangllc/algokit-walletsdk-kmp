package com.michaeltchuang.walletsdk.demo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.ColorPalette
import com.michaeltchuang.walletsdk.ui.settings.screens.networkNodeSettings
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun NetworkStatusBar(modifier: Modifier = Modifier) {
    val currentNetwork by networkNodeSettings.collectAsState()

    currentNetwork?.let { network ->
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .background(
                        when (network) {
                            AlgorandNetwork.TESTNET -> ColorPalette.Purple100
                            AlgorandNetwork.MAINNET -> ColorPalette.Purple100
                        },
                    ).padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text =
                    when (network) {
                        AlgorandNetwork.TESTNET -> "Testnet"
                        AlgorandNetwork.MAINNET -> "Mainnet"
                    },
                color =
                    when (network) {
                        AlgorandNetwork.TESTNET -> AlgoKitTheme.colors.testnetText
                        AlgorandNetwork.MAINNET -> AlgoKitTheme.colors.testnetText
                    },
                style = AlgoKitTheme.typography.footnote.sansMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview
@Composable
fun NetworkStatusBarPreview() {
    AlgoKitTheme {
        NetworkStatusBar()
    }
}
