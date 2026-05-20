package com.michaeltchuang.walletsdk.ui.signing.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.add_note
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.amount
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.asa_balance
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.balance
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.confirm_transaction
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.confirm_transfer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.done
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.enter_your_note
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.fee
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_usdc
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.note
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.to
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.USDC_TESTNET_ID
import com.michaeltchuang.walletsdk.core.foundation.utils.formatAmount
import com.michaeltchuang.walletsdk.core.foundation.utils.toAlgoCurrency
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIconRoundShape
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import com.michaeltchuang.walletsdk.ui.signing.components.PendingTransactionLoaderWidget
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.AssetTransferConfirmViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AssetTransferConfirmScreen(
    navController: NavController,
    senderAddress: String = "",
    receiverAddress: String = "",
    assetId: Long = -7L,
    note: String = "",
    amount: String = "0",
    closeSheet: () -> Unit = {},
) {
    val viewModel: AssetTransferConfirmViewModel = koinViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewState by viewModel.state.collectAsState()
    val events = viewModel.viewEvent.collectAsStateWithLifecycle(initialValue = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var isButtonEnabled by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var dialogMessage by remember { mutableStateOf("") }

    // Reset ViewModel state when entering the screen
    LaunchedEffect(Unit) {
        viewModel.reset()
        viewModel.setup(lifecycle = lifecycleOwner.lifecycle)
        isButtonEnabled = true
    }

    LaunchedEffect(events.value) {
        events.value?.let { event ->
            when (event) {
                is AssetTransferConfirmViewModel.ViewEvent.ShowError -> {
                    scope.launch {
                        snackbarHostState.showSnackbar(event.message)
                    }
                }

                is AssetTransferConfirmViewModel.ViewEvent.TransactionSuccess -> {
                    navController.navigate(
                        AlgoKitScreens.TRANSACTION_SUCCESS_SCREEN.name + "/?transactionId=${event.transactionId}",
                    ) {
                        popUpTo(AlgoKitScreens.ASSET_TRANSFER_SCREEN.name) {
                            inclusive = true
                        }
                    }
                    // Clear resources after successful navigation
                    viewModel.reset()
                }

                is AssetTransferConfirmViewModel.ViewEvent.UnrecognizedAsset -> {
                    viewModel.reset()
                    dialogMessage = event.message
                    showDialog = true
                }

                is AssetTransferConfirmViewModel.ViewEvent.TransactionAlreadyInLedger -> {
                    viewModel.reset()
                    dialogMessage = event.message
                    showDialog = true
                }
            }
        }
    }

    LaunchedEffect(senderAddress, receiverAddress, assetId, amount, note) {
        if (senderAddress.isNotEmpty()) {
            viewModel.setSenderAddress(senderAddress)
        }
        if (receiverAddress.isNotEmpty()) {
            viewModel.setReceiverAddress(receiverAddress)
        }
        if (amount.isNotEmpty()) {
            viewModel.setAmount(amount)
        }
        if (note.isNotEmpty()) {
            viewModel.setNote(note)
        }
        viewModel.setAssetId(assetId)
    }

    ScreenContent(
        navController = navController,
        viewState = viewState,
        snackbarHostState = snackbarHostState,
        isButtonEnabled = isButtonEnabled,
        showDialog = showDialog,
        dialogMessage = dialogMessage,
        onDismissDialog = { showDialog = false },
        onDialogOk = {
            showDialog = false
            closeSheet()
        },
        onSendTransaction = { viewModel.sendTransaction() },
        onSetNote = { viewModel.setNote(it) },
    )
}

@Composable
internal fun ScreenContent(
    navController: NavController,
    viewState: AssetTransferConfirmViewModel.ViewState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    isButtonEnabled: Boolean = true,
    showDialog: Boolean = false,
    dialogMessage: String = "",
    onDismissDialog: () -> Unit = {},
    onDialogOk: () -> Unit = {},
    onSendTransaction: () -> Unit = {},
    onSetNote: (String) -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = viewState) {
            is AssetTransferConfirmViewModel.ViewState.Loading -> {
                // Show loading state
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(color = AlgoKitTheme.colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading...", color = AlgoKitTheme.colors.textMain)
                }
            }

            is AssetTransferConfirmViewModel.ViewState.Confirming -> {
                PendingTransactionLoaderWidget()
            }

            is AssetTransferConfirmViewModel.ViewState.Content -> {
                AssetTransferContent(
                    state = state,
                    navController = navController,
                    onTransactionClick = onSendTransaction,
                    onSetNote = onSetNote,
                    isButtonEnabled = isButtonEnabled,
                )
            }

            is AssetTransferConfirmViewModel.ViewState.Error -> {
                // Show error state
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(color = AlgoKitTheme.colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(state.message, color = AlgoKitTheme.colors.textMain)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
        )

        // Error Dialog (for both unrecognized asset and duplicate transaction)
        if (showDialog) {
            AlertDialog(
                onDismissRequest = onDismissDialog,
                containerColor = AlgoKitTheme.colors.background,
                title = { Text("Transaction Error", color = AlgoKitTheme.colors.textMain) },
                text = { Text(dialogMessage, color = AlgoKitTheme.colors.textMain) },
                confirmButton = {
                    Button(onClick = onDialogOk) {
                        Text("OK", color = AlgoKitTheme.colors.textMain)
                    }
                },
            )
        }
    }
}

@Composable
fun AssetTransferContent(
    state: AssetTransferConfirmViewModel.ViewState.Content,
    navController: NavController,
    onTransactionClick: () -> Unit,
    onSetNote: (String) -> Unit,
    isButtonEnabled: Boolean = true,
) {
    Box(
        modifier =
            Modifier
                .background(color = AlgoKitTheme.colors.background)
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Column {
            AlgoKitTopBar(
                title = localizedStringResource(Res.string.confirm_transaction),
                onClick = { navController.popBackStack() },
            )
            AssetTransferContentItems(
                senderAddress = state.senderAddress,
                receiverAddress = state.receiverAddress,
                amount = state.amount,
                accountBalance = state.accountBalance,
                fee = state.fee,
                note = state.note,
                assetId = state.assetId,
                assetName = state.assetName,
                assetLogoUrl = state.assetLogoUrl,
                assetBalance = state.assetBalance,
                onSetNote = onSetNote,
            )
        }

        AlgoKitPrimaryButton(
            onClick = onTransactionClick,
            text = localizedStringResource(Res.string.confirm_transfer),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            state =
                if (state.isAssetValid &&
                    isButtonEnabled &&
                    assetBalanceStatus(
                        state.assetId,
                        state.assetBalance,
                    )
                ) {
                    AlgoKitButtonState.ENABLED
                } else {
                    AlgoKitButtonState.DISABLED
                },
        )
    }
}

private fun assetBalanceStatus(
    assetId: Long,
    assetBalance: String?,
): Boolean {
    if (assetId == -7L) return true // ALGO is always valid

    // For ASA, check if balance is valid
    if (assetId > 0 && assetBalance.isNullOrEmpty().not()) {
        return true
    }
    return false
}

@Composable
fun AssetTransferContentItems(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    accountBalance: String?,
    fee: String,
    note: String,
    assetId: Long,
    assetName: String,
    assetLogoUrl: String,
    assetBalance: String?,
    onSetNote: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(bottom = 72.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        AssetTransferAmountLabeledText(
            label = localizedStringResource(Res.string.amount),
            value = amount.formatAmount(),
            assetId = assetId,
            assetName = assetName,
            assetLogoUrl = assetLogoUrl,
        )

        AssetTransferDivider()

        AssetTransferAccountLabeledText(
            label = localizedStringResource(Res.string.account),
            value = senderAddress,
        )
        AssetTransferAccountLabeledText(
            label = localizedStringResource(Res.string.to),
            value = receiverAddress,
            isReceiver = true,
        )

        AssetTransferLabeledText(
            label = localizedStringResource(Res.string.fee),
            value = fee.toAlgoCurrency(),
        )
        Spacer(modifier = Modifier.height(8.dp))

        AssetTransferDivider()

        AssetTransferLabeledText(
            label = localizedStringResource(Res.string.balance),
            value =
                accountBalance?.let {
                    try {
                        // Convert from microAlgos to Algos
                        val balanceInMicroAlgos = it.toDoubleOrNull() ?: 0.0
                        val balanceInAlgos = balanceInMicroAlgos / 1_000_000
                        balanceInAlgos.toString().toAlgoCurrency()
                    } catch (e: Exception) {
                        "0.00".toAlgoCurrency()
                    }
                } ?: "Loading...",
        )

        if (assetId > 0) {
            AssetTransferDivider()
            AssetTransferASABalanceRow(
                assetLogoUrl = assetLogoUrl,
                assetBalance = assetBalance,
                assetName = assetName,
                assetId = assetId,
            )
        }
        AssetTransferDivider()
        Spacer(modifier = Modifier.height(16.dp))
        AssetTransferAddNote(note, onSetNote)
    }
}

@Composable
fun AssetTransferDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp),
        thickness = 1.dp,
        color = AlgoKitTheme.colors.layerGray,
    )
}

@Composable
fun AssetTransferASABalanceRow(
    assetLogoUrl: String,
    assetBalance: String?,
    assetName: String,
    assetId: Long,
) {
    Row(
        modifier = Modifier.padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(.25f),
            text = localizedStringResource(Res.string.asa_balance),
            color = AlgoKitTheme.colors.textGray,
            style = typography.body.regular.sansMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            SetAssetLogo(assetLogoUrl, assetId)
            Text(
                text = assetBalance?.let { "${it.formatAmount()} $assetName" } ?: "Not Opted In",
                fontSize = 16.sp,
                color = AlgoKitTheme.colors.textMain,
                style = typography.body.regular.sansMedium,
            )
        }
    }
}

@Composable
fun AssetTransferAmountLabeledText(
    label: String,
    value: String,
    assetId: Long = -7L,
    assetName: String = "",
    assetLogoUrl: String = "",
) {
    val displayAssetName = if (assetId != -7L && assetName.isNotEmpty()) assetName else "ALGO"
    val amountValue = if (assetId != -7L) value else value.toAlgoCurrency()

    Row(
        modifier = Modifier.padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(.25f),
            text = label,
            color = AlgoKitTheme.colors.textGray,
            style = typography.body.regular.sansMedium,
        )
        if (assetId != -7L) {
            SetAssetLogo(assetLogoUrl, assetId)
        }
        Text(
            text = "$amountValue $displayAssetName",
            fontSize = 18.sp,
            color = AlgoKitTheme.colors.textMain,
            style = typography.body.regular.sansMedium,
        )
    }
}

@Composable
fun AssetTransferAccountLabeledText(
    label: String,
    value: String,
    isReceiver: Boolean = false,
) {
    Row(
        modifier = Modifier.padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(.20f),
            text = label,
            color = AlgoKitTheme.colors.textGray,
            style = typography.body.regular.sansMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlgoKitIconRoundShape(
                imageVector = vectorResource(Res.drawable.ic_wallet),
                contentDescription = "Wallet Icon",
                backgroundColor = if (isReceiver) AlgoKitTheme.colors.layerGrayLighter else AlgoKitTheme.colors.wallet4,
            )

            if (isReceiver) {
                Text(
                    modifier = Modifier.padding(start = 16.dp),
                    text = value,
                    color = AlgoKitTheme.colors.textMain,
                    style = typography.body.regular.sansMedium,
                )
            } else {
                Text(
                    modifier = Modifier.padding(start = 16.dp),
                    text = value.toShortenedAddress(),
                    color = AlgoKitTheme.colors.textMain,
                    style = typography.body.regular.sansMedium,
                )
            }
        }
    }
}

@Composable
fun AssetTransferLabeledText(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.padding(vertical = 16.dp)) {
        Text(
            modifier = Modifier.fillMaxWidth(.25f),
            text = label,
            color = AlgoKitTheme.colors.textGray,
            style = typography.body.regular.sansMedium,
        )
        Text(
            text = value,
            color = AlgoKitTheme.colors.textMain,
            style = typography.body.regular.sansMedium,
        )
    }
}

@Composable
fun AssetTransferAddNote(
    note: String,
    onSetNote: (String) -> Unit,
) {
    var isAddNoteEnabled by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf(note) }
    Column {
        if (isAddNoteEnabled) {
            AssetTransferAddNoteTextField(noteText, {
                noteText = it
            }, {
                noteText = ""
            }, {
                onSetNote(noteText)
                isAddNoteEnabled = false
            })
        } else {
            Row(
                modifier =
                    Modifier.clickable(onClick = {
                        isAddNoteEnabled = true
                    }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(.25f),
                    text = localizedStringResource(Res.string.note),
                    color = AlgoKitTheme.colors.textGray,
                    style = typography.body.regular.sansMedium,
                )
                if (noteText.isNotEmpty()) {
                    Text(
                        style = typography.body.regular.sansMedium,
                        text = noteText,
                        color = AlgoKitTheme.colors.textMain,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = localizedStringResource(Res.string.add_note),
                        tint = AlgoKitTheme.colors.textMain,
                    )
                    Text(
                        style = typography.body.large.sansMedium,
                        text = localizedStringResource(Res.string.add_note),
                        color = AlgoKitTheme.colors.textMain,
                    )
                }
            }
        }
    }
}

@Composable
fun AssetTransferAddNoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onClearClick: () -> Unit,
    onDoneClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                style = typography.body.regular.sansMedium,
                color = AlgoKitTheme.colors.textMain,
                text = localizedStringResource(Res.string.enter_your_note),
            )

            Text(
                modifier = Modifier.clickable(onClick = onDoneClick),
                style = typography.body.regular.sansMedium,
                color = AlgoKitTheme.colors.textMain,
                text = localizedStringResource(Res.string.done),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                singleLine = true,
                textStyle =
                    LocalTextStyle.current.copy(
                        color = AlgoKitTheme.colors.textMain,
                        fontSize = 16.sp,
                    ),
            )
            IconButton(onClick = onClearClick) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear",
                    tint = Color.Gray,
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Color.Gray,
        )
    }
}

@Composable
private fun SetAssetLogo(assetLogoUrl: String, assetId: Long) {
    Box(modifier = Modifier.padding(end = 5.dp), contentAlignment = Alignment.Center) {
        if (assetLogoUrl.isBlank() && assetId == USDC_TESTNET_ID) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_usdc),
                contentDescription = "USDC",
                modifier = Modifier.size(16.dp),
            )
        } else {
            AsyncImage(
                model = assetLogoUrl,
                contentDescription = "Asset Logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Preview
@Composable
fun PreviewAssetTransferScreen() {
    AlgoKitTheme {
        ScreenContent(
            navController = androidx.navigation.compose.rememberNavController(),
            viewState =
                AssetTransferConfirmViewModel.ViewState.Content(
                    senderAddress = "L4HCUPPXMVJMHZPJSCC7FJV3BP66OKKKNQ5FH4U7RZMZZGOV3R2QM3YSSM",
                    receiverAddress = "X3B5KQQGQ5XJFGWDKLJ4J7Q2Z4H3YKJ5L6Q2X4D7F8G9H0J1K2L3M4N5O",
                    amount = "100",
                    accountBalance = "599000",
                    note = "Test note",
                    fee = "0.001",
                    assetId = 10458941L,
                    assetName = "USDC",
                    assetLogoUrl = "https://algorand-wallet-mainnet.b-cdn.net/media/usd-coin-usdc-logo.png",
                    assetBalance = "0",
                    isAssetValid = true,
                ),
        )
    }
}
