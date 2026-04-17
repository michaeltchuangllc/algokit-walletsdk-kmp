package com.michaeltchuang.walletsdk.demo.ui.components

import algokit_walletsdk_kmp.composedemoapp.generated.resources.Res
import algokit_walletsdk_kmp.composedemoapp.generated.resources.account_type_algo25
import algokit_walletsdk_kmp.composedemoapp.generated.resources.account_type_falcon24
import algokit_walletsdk_kmp.composedemoapp.generated.resources.account_type_hd
import algokit_walletsdk_kmp.composedemoapp.generated.resources.account_type_ledger
import algokit_walletsdk_kmp.composedemoapp.generated.resources.account_type_seedvault
import algokit_walletsdk_kmp.composedemoapp.generated.resources.account_type_watch
import algokit_walletsdk_kmp.composedemoapp.generated.resources.ic_hd_wallet
import algokit_walletsdk_kmp.composedemoapp.generated.resources.ic_solana_sign
import algokit_walletsdk_kmp.composedemoapp.generated.resources.ic_usdc
import algokit_walletsdk_kmp.composedemoapp.generated.resources.ic_wallet
import algokit_walletsdk_kmp.composedemoapp.generated.resources.wallet_icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.foundation.utils.formatAmount
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIconRoundShape
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun AccountItem(
    account: AccountLite,
    onAccountItemClick: (address: String) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clickable(onClick = {
                    onAccountItemClick(account.address)
                }),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlgoKitIconRoundShape(
                modifier = Modifier,
                imageVector = vectorResource(getWalletIcon(account.registrationType)),
                contentDescription = localizedStringResource(Res.string.wallet_icon),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = account.customName.ifEmpty { account.address.toShortenedAddress() },
                    style = typography.body.large.sansMedium,
                )
                Text(
                    text = localizedStringResource(getAccountTypeResource(account.registrationType)),
                    style = typography.footnote.mono,
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_usdc),
                        contentDescription = "USDC",
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = account.usdcBalance?.formatAmount(false) ?: "N/A",
                        fontSize = 16.sp,
                        style = typography.footnote.sansMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (account.registrationType is AccountRegistrationType.SeedVault) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_solana_sign),
                            contentDescription = "Solana",
                            modifier = Modifier.size(14.dp),
                            tint = Color.Unspecified,
                        )
                    }
                    Text(
                        text = "${getBalancePrefix(account.registrationType)}${account.balance?.formatAmount() ?: "0.00"}",
                        fontSize = 16.sp,
                        style = typography.footnote.sansMedium,
                    )
                }
            }
        }
    }
}

fun getWalletIcon(localAccount: AccountRegistrationType): DrawableResource =
    when (localAccount) {
        is AccountRegistrationType.HdKey -> {
            Res.drawable.ic_hd_wallet
        }

        is AccountRegistrationType.Algo25 -> {
            Res.drawable.ic_wallet
        }

        else -> {
            Res.drawable.ic_wallet
        }
    }

fun getAccountTypeResource(localAccount: AccountRegistrationType): StringResource =
    when (localAccount) {
        is AccountRegistrationType.HdKey -> Res.string.account_type_hd
        is AccountRegistrationType.Algo25 -> Res.string.account_type_algo25
        is AccountRegistrationType.Falcon24 -> Res.string.account_type_falcon24
        is AccountRegistrationType.NoAuth -> Res.string.account_type_watch
        is AccountRegistrationType.LedgerBle -> Res.string.account_type_ledger
        is AccountRegistrationType.SeedVault -> Res.string.account_type_seedvault
    }

private fun getBalancePrefix(registrationType: AccountRegistrationType): String =
    when (registrationType) {
        is AccountRegistrationType.SeedVault -> ""
        else -> "\u00A6"
    }

@Preview()
@Composable
fun AccountItemPreview() {
    // Create a sample account for preview
    val sampleAccount =
        AccountLite(
            address = "ASDFGHJKLASDFGHJKL",
            customName = "Sample Account",
            balance = "5000000000", // 5000 Algos in microAlgos
            registrationType = AccountRegistrationType.Algo25,
            usdcBalance = "5000000000"
        )

    AlgoKitTheme {
        AccountItem(
            account = sampleAccount,
            onAccountItemClick = { /* Preview - no action */ },
        )
    }
}
