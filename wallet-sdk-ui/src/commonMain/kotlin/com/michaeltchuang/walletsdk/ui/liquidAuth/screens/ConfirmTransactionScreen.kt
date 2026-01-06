package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.balance
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.close_popup
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.confirm_transfer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.fee
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.origin
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.provider
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.session
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.core.foundation.utils.toAlgoCurrency
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIconRoundShape
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ConfirmTransferScreen(
    provider: String,
    origin: String,
    session: String,
    fee: String,
    accountBalance: String,
    address: String,
    onTransactionClick: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .background(color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f))
                .fillMaxSize()
                .padding(16.dp), contentAlignment = Alignment.Center
    ) {
        Column(
            modifier =
                Modifier
                    .background(color = AlgoKitTheme.colors.background)
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.close_popup)
                    )
                }
            }
            ConfirmTransferContentItems(
                senderAddress = address,
                accountBalance = accountBalance,
                fee = fee,
                provider = provider,
                origin = origin,
                session = session
            )

            AlgoKitPrimaryButton(
                onClick = onTransactionClick,
                text = localizedStringResource(Res.string.confirm_transfer),
                modifier =
                    Modifier.fillMaxWidth(),
            )
        }

    }
}

@Composable
fun ConfirmTransferContentItems(
    senderAddress: String,
    accountBalance: String,
    fee: String,
    provider: String,
    origin: String,
    session: String
) {
    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(bottom = 72.dp),
    ) {

        ConfirmTransferAccountLabeledText(
            label = localizedStringResource(Res.string.account),
            value = senderAddress,
        )

        ConfirmTransferLabeledText(
            label = stringResource(Res.string.origin),
            value = origin
        )
        ConfirmTransferLabeledText(
            label = stringResource(Res.string.provider),
            value = provider
        )

        ConfirmTransferLabeledText(
            label = stringResource(Res.string.session),
            value = session
        )

        ConfirmTransferLabeledText(
            label = localizedStringResource(Res.string.fee),
            value = fee.toAlgoCurrency()
        )

        Spacer(modifier = Modifier.height(8.dp))

        ConfirmTransferLabeledText(
            label = localizedStringResource(Res.string.balance),
            value =
                accountBalance.let {
                    try {
                        // Convert from microAlgos to Algos
                        val balanceInMicroAlgos = it.toDoubleOrNull() ?: 0.0
                        val balanceInAlgos = balanceInMicroAlgos / 1_000_000
                        balanceInAlgos.toString().toAlgoCurrency()
                    } catch (e: Exception) {
                        "0.00".toAlgoCurrency()
                    }
                },
        )
    }
}


@Composable
fun ConfirmTransferAccountLabeledText(
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
fun ConfirmTransferLabeledText(
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

@Preview
@Composable
fun ConfirmTransferScreenPreview() {
    AlgoKitTheme {
        ConfirmTransferScreen(
            provider = "Provider",
            origin = "Origin",
            session = "Session",
            fee = "0.001",
            accountBalance = "1000000",
            address = "XWJQV6G54JU62F7ZL7K5HJXH3Z3WZ44Q4ZMZ5W4",
            onTransactionClick = {},
            onClose = {}, 
        )
    }
}
