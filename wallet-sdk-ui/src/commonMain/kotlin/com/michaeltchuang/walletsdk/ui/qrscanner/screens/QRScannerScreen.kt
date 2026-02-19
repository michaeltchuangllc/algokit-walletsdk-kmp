package com.michaeltchuang.walletsdk.ui.qrscanner.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.transaction.signmanager.PendingTransactionRequestManger.storePendingTransactionRequest
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.qrscanner.launchIntentWithUri
import com.michaeltchuang.walletsdk.ui.qrscanner.viewmodels.QRScannerViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import qrscanner.CameraLens
import qrscanner.QrScanner

@Composable
fun QRCodeScannerScreen(
    navController: NavController,
    onQrScanned: (String) -> Unit = {},
    closeSheet: () -> Unit,
    isForWatchAccount: Boolean = false,
) {
    val viewModel: QRScannerViewModel = koinViewModel()
    val hasProcessedResult = remember { mutableStateOf(false) }
    val launchFidoDeepLink = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect {
            when (it) {
                is QRScannerViewModel.ViewEvent.NavigateToRecoveryPhraseScreen -> {
                    navController.navigate(AlgoKitScreens.RECOVER_PHRASE_SCREEN.name + "/?mnemonic=${it.mnemonic}")
                }

                is QRScannerViewModel.ViewEvent.NavigateToTransactionSignatureRequestScreen -> {
                    storePendingTransactionRequest(it.txnDetail)
                    navController.navigate(AlgoKitScreens.TRANSACTION_SIGNATURE_SCREEN.name)
                }

                is QRScannerViewModel.ViewEvent.NavigateToSelectAccountScreen -> {
                    // Navigate to SelectAccountScreen with receiver address, amount, and note
                    navController.navigate(
                        AlgoKitScreens.SELECT_ACCOUNT_SCREEN.name +
                            "?receiver=${it.assetTransfer.receiverAccountAddress}" +
                            "&amount=${it.assetTransfer.amount}" +
                            "&assetId=${it.assetTransfer.assetId}" +
                            "&note=${it.assetTransfer.note ?: ""}",
                    )
                }

                is QRScannerViewModel.ViewEvent.NavigateToAddressScreen -> {
                    if (isForWatchAccount) {
                        // Navigate back to CreateWatchAccountScreen with the scanned address
                        navController.navigate(
                            "${AlgoKitScreens.CREATE_WATCH_ACCOUNT_SCREEN.name}?scannedAddress=${it.accountAddress.address}",
                        )
                    } else {
                        // Handle other address navigation scenarios
                        onQrScanned(it.accountAddress.address)
                    }
                }

                is QRScannerViewModel.ViewEvent.NavigateToFidoDeepLink -> {
                    closeSheet()
                    launchFidoDeepLink.value = it.uri
                }

                is QRScannerViewModel.ViewEvent.NavigateToLiquidAuthScreen -> {
                    // URL encode special characters that confuse navigation parsing
                    // The navigation argument parser uses ? and & to parse, so we must encode these
                    val encodedUri =
                        it.uri
                            .replace("%", "%25") // Must be first to avoid double-encoding!
                            .replace("?", "%3F") // Question mark
                            .replace("&", "%26") // Ampersand
                            .replace("=", "%3D") // Equals
                            .replace("#", "%23") // Hash

                    println("🔗 Navigating to Liquid Auth screen")
                    println("   Original URI: ${it.uri}")
                    println("   Encoded URI: $encodedUri")

                    navController.navigate(AlgoKitScreens.LIQUID_AUTH_SCREEN.name + "?uri=$encodedUri")
                }

                is QRScannerViewModel.ViewEvent.ShowLiquidAuthMainnetNotSupported -> {
                    onQrScanned("Liquid Auth on Mainnet is not supported yet")
                }

                is QRScannerViewModel.ViewEvent.ShowUnrecognizedDeeplink -> {
                    onQrScanned("Unrecognized QR Code")
                }
            }
        }
    }

    ScreenContent(
        navController = navController,
        hasProcessedResult = hasProcessedResult.value,
        onQrCodeScanned = { scannedText ->
            if (!hasProcessedResult.value) {
                viewModel.handleDeeplink(scannedText)
                hasProcessedResult.value = true
            }
        },
        onBackPressed = {
            if (navController.popBackStack().not()) {
                closeSheet()
            }
        },
    )

    launchFidoDeepLink.value?.let { uri ->
        launchIntentWithUri(uri)
        launchFidoDeepLink.value = null
    }
}

@Composable
internal fun ScreenContent(
    navController: NavController,
    hasProcessedResult: Boolean = false,
    onQrCodeScanned: (String) -> Unit = {},
    onBackPressed: () -> Unit = {},
) {
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        QrScanner(
            modifier = Modifier.fillMaxSize(),
            flashlightOn = false,
            cameraLens = CameraLens.Back,
            openImagePicker = false,
            onCompletion = { scannedText ->
                onQrCodeScanned(scannedText)
            },
            imagePickerHandler = {},
            onFailure = {},
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(color = AlgoKitTheme.colors.background),
        ) {
            AlgoKitTopBar(
                title = "Scan QR Code",
                modifier = Modifier.padding(start = 16.dp),
                onClick = onBackPressed,
            )
        }
    }
}

@Preview
@Composable
fun PreviewQRCodeScannerScreen() {
    val navController = rememberNavController()
    QRCodeScannerScreen(navController = navController, closeSheet = {})
}
