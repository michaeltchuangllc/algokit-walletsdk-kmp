package com.michaeltchuang.walletsdk.ui.signing.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.add_note
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.amount
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.balance
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.confirm_transaction
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.confirm_transfer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.done
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.enter_your_note
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.fee
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DividerDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.core.foundation.utils.formatAmount
import com.michaeltchuang.walletsdk.core.foundation.utils.toAlgoCurrency
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
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
    note: String = "",
    amount: String = "0",
) {
    val viewModel: AssetTransferConfirmViewModel = koinViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewState by viewModel.state.collectAsState()
    val events = viewModel.viewEvent.collectAsStateWithLifecycle(initialValue = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Reset ViewModel state when entering the screen
    LaunchedEffect(Unit) {
        viewModel.reset()
        viewModel.setup(lifecycle = lifecycleOwner.lifecycle)
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
                }
            }
        }
    }

    LaunchedEffect(senderAddress, receiverAddress, amount, note) {
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
    }

    ScreenContent(
        navController = navController,
        viewState = viewState,
        snackbarHostState = snackbarHostState,
        onSendTransaction = { viewModel.sendTransaction() },
        onSetNote = { viewModel.setNote(it) },
    )
}

@Composable
internal fun ScreenContent(
    navController: NavController,
    viewState: AssetTransferConfirmViewModel.ViewState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
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
    }
}

@Composable
fun AssetTransferContent(
    state: AssetTransferConfirmViewModel.ViewState.Content,
    navController: NavController,
    onTransactionClick: () -> Unit,
    onSetNote: (String) -> Unit,
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
        )
    }
}

@Composable
fun AssetTransferContentItems(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    accountBalance: String?,
    fee: String,
    note: String,
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

        AssetTransferLabeledText(label = localizedStringResource(Res.string.fee), value = fee.toAlgoCurrency())
        Spacer(modifier = Modifier.height(8.dp))
        AssetTransferDivider()

//        AssetTransferLabeledText(label = "Current", value = "10.00".toAlgoCurrency())
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
        AssetTransferDivider()

        AssetTransferAddNote(note, onSetNote)
    }
}

@Composable
fun AssetTransferDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp),
        thickness = DividerDefaults.Thickness,
        color = AlgoKitTheme.colors.layerGray,
    )
}

@Composable
fun AssetTransferAmountLabeledText(
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
        Column {
            Text(
                text = "$value ALGO",
                fontSize = 18.sp,
                color = AlgoKitTheme.colors.textMain,
                style = typography.body.regular.sansMedium,
            )

//            Text(
//                text = "\u00A6 $value",
//                color = AlgoKitTheme.colors.textGray,
//                style = typography.body.regular.sansMedium,
//            )
        }
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

@Preview
@Composable
fun PreviewAssetTransferScreen() {
    AlgoKitTheme {
        AssetTransferConfirmScreen(
            navController = androidx.navigation.compose.rememberNavController(),
        )
    }
}
