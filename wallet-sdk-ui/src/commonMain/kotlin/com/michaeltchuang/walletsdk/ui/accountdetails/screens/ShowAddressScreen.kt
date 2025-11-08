package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.address_copied_to_clipboard
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.copy_address
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_copy
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.qr_code
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.accountdetails.components.QRCodeDisplay
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.QRCodeViewModel
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ShowAddressScreen(
    navController: NavController,
    address: String,
    showSnackBar: (String) -> Unit,
) {
    val viewModel: QRCodeViewModel = koinViewModel()
    val viewState by viewModel.state.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    // Pre-calculate the message to avoid composable invocation in onClick
    val copiedMessage = localizedStringResource(Res.string.address_copied_to_clipboard)

    // Initialize with address
    LaunchedEffect(address) {
        viewModel.setAddress(address)
    }

    // Handle ViewEvents
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is QRCodeViewModel.ViewEvent.AddressCopied -> {
                    clipboardManager.setText(AnnotatedString(address))
                    showSnackBar(event.message)
                }

                is QRCodeViewModel.ViewEvent.AddressShared -> {}

                is QRCodeViewModel.ViewEvent.Error -> {
                    showSnackBar(event.message)
                }
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color = AlgoKitTheme.colors.background),
    ) {
        // Top Bar with Back Button
        AlgoKitTopBar(
            title = localizedStringResource(Res.string.qr_code),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onClick = {
                navController.popBackStack()
            },
        )

        // Scrollable Content
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // QR Code Card
            Card(
                modifier =
                    Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(16.dp)),
                colors =
                    CardDefaults.cardColors(
                        containerColor = Color.White,
                    ),
                elevation =
                    CardDefaults.cardElevation(
                        defaultElevation = 8.dp,
                    ),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val state = viewState) {
                        is QRCodeViewModel.ViewState.Content -> {
                            QRCodeDisplay(
                                data = state.address,
                                modifier = Modifier.size(232.dp),
                            )
                        }

                        else -> {
                            // Loading or error state placeholder
                            Box(
                                modifier =
                                    Modifier
                                        .size(232.dp)
                                        .background(
                                            color = AlgoKitTheme.colors.layerGrayLighter,
                                            shape = RoundedCornerShape(8.dp),
                                        ),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Address display
            when (val state = viewState) {
                is QRCodeViewModel.ViewState.Content -> {
                    Text(
                        text = state.displayAddress,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        color = AlgoKitTheme.colors.textMain,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = state.address,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        color = AlgoKitTheme.colors.textGray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                else -> {
                    // Placeholder for loading state
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 16.dp)
                                .background(
                                    color = AlgoKitTheme.colors.layerGrayLighter,
                                    shape = RoundedCornerShape(4.dp),
                                ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Action Buttons - Copy Address
            AlgoKitPrimaryButton(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                onClick = {
                    viewModel.copyAddress(copiedMessage)
                },
                text = localizedStringResource(Res.string.copy_address),
                leftIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_copy),
                        contentDescription = localizedStringResource(Res.string.copy_address),
                        modifier = Modifier.size(20.dp),
                    )
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

         /*   // Share Address Button
            AlgoKitSecondaryButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                onClick = {
                    when (val state = viewState) {
                        is QRCodeViewModel.ViewState.Content -> {
                            viewModel.shareAddress(state.address)
                        }

                        else -> {
                            // Handle other states if needed
                        }
                    }
                },
                text = localizedStringResource(Res.string.share_address),
                leftIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_send),
                        contentDescription = localizedStringResource(Res.string.share_address),
                        modifier = Modifier.size(20.dp)
                    )
                }
            )*/

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview
@Composable
fun QRCodeScreenPreview() {
    AlgoKitTheme {
        ShowAddressScreen(
            navController = rememberNavController(),
            address = "MCRT347GYFXVLIQBCEBTEQJO6S5KFYRG2TC5CLXBHGGVNXHONP5RA7FWRLM",
            showSnackBar = {},
        )
    }
}
