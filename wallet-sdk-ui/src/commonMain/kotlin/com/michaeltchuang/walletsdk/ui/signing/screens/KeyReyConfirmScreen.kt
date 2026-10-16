package com.michaeltchuang.walletsdk.ui.signing.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.add_note
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.confirm_transaction
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.done
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.enter_your_note
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.key_reg_transaction_title
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.note
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.deeplink.model.KeyRegTransactionDetail
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import com.michaeltchuang.walletsdk.ui.signing.components.PendingTransactionLoaderWidget
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.KeyRegConfirmViewModel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ConfirmTransactionRequestScreen(
    navController: NavController,
    viewModel: KeyRegConfirmViewModel = koinViewModel(),
    showSnackBar: (message: String, isError: Boolean) -> Unit,
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    val transactionDetail = remember { viewModel.getPendingTransactionRequest() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        viewModel.setup(lifecycle = lifecycleOwner.lifecycle)
    }
    LaunchedEffect(transactionDetail) {
        viewModel.calculateMinimumFee(transactionDetail)
    }
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is KeyRegConfirmViewModel.ViewEvent.SendSignedTransactionFailed -> {
                    showSnackBar(event.error, true)
                }
                is KeyRegConfirmViewModel.ViewEvent.SendSignedTransactionSuccess -> {
                    navController.navigate(AlgoKitScreens.TRANSACTION_SUCCESS_SCREEN.name + "/?transactionId=${event.transactionId}") {
                        popUpTo(AlgoKitScreens.TRANSACTION_SIGNATURE_SCREEN.name) {
                            inclusive = true
                        }
                    }
                }
            }
        }
    }

    ScreenContent(
        viewState = viewState,
        onConfirm = { viewModel.confirmTransaction() },
        onBack = { navController.popBackStack() },
        transactionDetail = transactionDetail,
    )
}

@Composable
fun ScreenContent(
    viewState: KeyRegConfirmViewModel.ViewState,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    transactionDetail: KeyRegTransactionDetail?,
) {
    when (viewState) {
        is KeyRegConfirmViewModel.ViewState.Content -> {
            Content(
                onConfirm = onConfirm,
                onBack = onBack,
                viewState = viewState,
                transactionDetail = transactionDetail,
            )
        }

        is KeyRegConfirmViewModel.ViewState.Loading -> {
            PendingTransactionLoaderWidget()
        }
    }
}

@Composable
fun Content(
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    viewState: KeyRegConfirmViewModel.ViewState.Content,
    transactionDetail: KeyRegTransactionDetail?,
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
                title = localizedStringResource(Res.string.key_reg_transaction_title),
                onClick = onBack,
            )
            transactionDetail?.let {
                ContentItems(
                    transactionDetail,
                    viewState.minimumFee,
                )
            }
        }

        AlgoKitPrimaryButton(
            onClick = onConfirm,
            text = localizedStringResource(Res.string.confirm_transaction),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
        )
    }
}

@Composable
fun ContentItems(
    txnDetail: KeyRegTransactionDetail?,
    minimumFee: String,
) {
    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(bottom = 72.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Address Section
        LabeledText(
            label = "Address",
            value = txnDetail?.address ?: "Unknown",
        )

        // Fee
        LabeledText(label = "Fee", value = ("\u00A6") + minimumFee)

        // Type
        LabeledText(label = "Type", value = txnDetail?.type ?: "Unknown")

        Divider()

        LabeledText(label = "Vote Key", value = txnDetail?.voteKey ?: "")
        LabeledText(label = "Selection Key", value = txnDetail?.selectionPublicKey ?: "")
        LabeledText(label = "State Proof Key", value = txnDetail?.sprfkey ?: "")
        LabeledText(label = "Valid First Round", value = txnDetail?.voteFirstRound ?: "")
        LabeledText(label = "Valid Last Round", value = txnDetail?.voteLastRound ?: "")
        LabeledText(label = "Vote Key Dilution", value = txnDetail?.voteKeyDilution ?: "")
        Spacer(modifier = Modifier.height(8.dp))
        Divider()
        AddNote(txnDetail)
    }
}

@Composable
fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp),
        thickness = DividerDefaults.Thickness,
        color = AlgoKitTheme.colors.layerGray,
    )
}

@Composable
fun LabeledText(
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
fun AddNote(txnDetail: KeyRegTransactionDetail?) {
    var isAddNoteEnabled by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    Column {
        if (isAddNoteEnabled) {
            AddNoteTextField(noteText, {
                noteText = it
            }, {
                noteText = ""
            }, {
                txnDetail?.copy(note = noteText)
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
                LabeledText(stringResource(Res.string.note), "")
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
fun AddNoteTextField(
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

        // Bottom Line
        HorizontalDivider(
            thickness = 1.dp,
            color = Color.Gray,
        )
    }
}

@Preview
@Composable
fun PreviewTransactionDetailsScreen() {
    AlgoKitTheme {
        ConfirmTransactionRequestScreen(
            navController = rememberNavController(),
            viewModel = koinViewModel(),
            showSnackBar = { message, isError ->
                println("Snackbar: $message")
            },
        )
    }
}
