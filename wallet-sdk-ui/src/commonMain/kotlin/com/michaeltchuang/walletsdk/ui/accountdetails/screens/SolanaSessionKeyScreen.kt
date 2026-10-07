package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaLinkedSigner
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaLinkedSignerSource
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaSignerBalance
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.SolanaSessionKeyViewModel
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitSecondaryButton
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SolanaSessionKeyScreen(
    address: String,
    onBack: () -> Unit,
    showSnackBar: (String) -> Unit,
) {
    val viewModel: SolanaSessionKeyViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(address) { viewModel.load(address) }
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is SolanaSessionKeyViewModel.ViewEvent.Message -> showSnackBar(event.text)
            }
        }
    }

    SolanaSessionKeyContent(
        state = state,
        onBack = onBack,
        showSnackBar = showSnackBar,
        onRegenerate = viewModel::regenerate,
        onRefreshBalance = viewModel::refreshBalance,
        onSweep = viewModel::sweep,
    )
}

@Composable
private fun SolanaSessionKeyContent(
    state: SolanaSessionKeyViewModel.ViewState,
    onBack: () -> Unit,
    showSnackBar: (String) -> Unit,
    onRegenerate: () -> Unit,
    onRefreshBalance: () -> Unit,
    onSweep: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AlgoKitTheme.colors.background),
    ) {
        AlgoKitTopBar(
            title = "Session signing key",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onClick = onBack,
        )
        SolanaSessionKeyBody(
            state = state,
            showSnackBar = showSnackBar,
            onRegenerate = onRegenerate,
            onRefreshBalance = onRefreshBalance,
            onSweep = onSweep,
        )
    }
}

@Composable
private fun SolanaSessionKeyBody(
    state: SolanaSessionKeyViewModel.ViewState,
    showSnackBar: (String) -> Unit,
    onRegenerate: () -> Unit,
    onRefreshBalance: () -> Unit,
    onSweep: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        BodyText(
            "Seed Vault keys never leave secure hardware, so this session key handles Liquid Stream " +
                "settlement for you — no Seed Vault prompt for every payment.",
        )
        Spacer(Modifier.height(16.dp))

        when {
            state.isLoading -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AlgoKitTheme.colors.positive)
                }
            }

            state.linkedSigner != null -> {
                SessionKeySection(
                    signer = state.linkedSigner,
                    balance = state.balance,
                    isBalanceLoading = state.isBalanceLoading,
                    isBusy = state.isBusy,
                    showSnackBar = showSnackBar,
                    onRefreshBalance = onRefreshBalance,
                    onSweep = onSweep,
                    onRegenerate = onRegenerate,
                )
            }

            // Fallback only: the key is created when the account is added.
            else -> {
                SectionCard {
                    SectionTitle("No session key yet")
                    BodyText("The session key couldn't be created. Try again.")
                    Spacer(Modifier.height(12.dp))
                    AlgoKitPrimaryButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRegenerate,
                        text = "Generate session key",
                        state = if (state.isBusy) AlgoKitButtonState.PROGRESS else AlgoKitButtonState.ENABLED,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SessionKeySection(
    signer: SolanaLinkedSigner,
    balance: SolanaSignerBalance?,
    isBalanceLoading: Boolean,
    isBusy: Boolean,
    showSnackBar: (String) -> Unit,
    onRefreshBalance: () -> Unit,
    onSweep: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var confirmRegenerate by remember { mutableStateOf(false) }
    val hasFunds = balance?.hasFunds == true
    val hasStrayUsdc = (balance?.usdcBaseUnits ?: 0L) > 0L
    val needsSol = balance != null && !balance.canSweep

    SectionCard {
        SectionTitle("Voucher signing is on")
        BodyText("Generated on this device", fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        BodyText("Session key address (tap to copy)", fontSize = 12.sp)
        Text(
            text = signer.signerAddress,
            modifier =
                Modifier.clickable {
                    clipboard.setText(AnnotatedString(signer.signerAddress))
                    showSnackBar("Session key address copied")
                },
            color = AlgoKitTheme.colors.textMain,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BodyText("SOL balance", fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (isBalanceLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = AlgoKitTheme.colors.positive,
                )
            } else {
                Text(
                    text = "Refresh",
                    modifier = Modifier.clickable(onClick = onRefreshBalance),
                    color = AlgoKitTheme.colors.positive,
                    fontSize = 13.sp,
                )
            }
        }
        BodyText("${formatUnits(balance?.lamports, 9)} SOL", color = AlgoKitTheme.colors.textMain)
        if (needsSol) {
            BodyText(
                "Receiving Liquid Stream payments? Send a little SOL to this address so it can pay settlement fees.",
                color = AlgoKitTheme.colors.textMain,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        BodyText(
            "When you pay: it only signs vouchers. No SOL needed — the receiver pays the settlement fees.",
            fontSize = 12.sp,
        )
        BodyText(
            "When you receive: it settles vouchers and pays the small network fees, so keep a little SOL on it. " +
                "Settled USDC goes to your Seed Vault account.",
            fontSize = 12.sp,
        )
        BodyText("It never needs USDC.", fontSize = 12.sp)
    }

    // Safety net: the key doesn't need USDC, so surface any that was sent to it by mistake.
    if (hasStrayUsdc) {
        Spacer(Modifier.height(16.dp))
        SectionCard {
            SectionTitle("USDC on session key")
            BodyText("${formatUnits(balance?.usdcBaseUnits, 6)} USDC", color = AlgoKitTheme.colors.textMain)
            Spacer(Modifier.height(8.dp))
            BodyText(
                "This key doesn't need USDC. Returning it sends all funds on the key, including SOL, " +
                    "back to your Seed Vault — you'll need to add SOL again afterwards.",
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            AlgoKitSecondaryButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSweep,
                text = "Return funds to Seed Vault",
                state = if (isBusy) AlgoKitButtonState.PROGRESS else AlgoKitButtonState.ENABLED,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    AlgoKitSecondaryButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = { confirmRegenerate = true },
        text = "Regenerate session key",
        state = if (isBusy) AlgoKitButtonState.PROGRESS else AlgoKitButtonState.ENABLED,
    )

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Regenerate session key?") },
            text = {
                Text(
                    "The current key will be replaced and permanently deleted from this device. " +
                        "Open Liquid Stream sessions authorized for the old key will need a Seed Vault " +
                        "approval to switch to the new one." +
                        (
                            if (hasFunds) {
                                " Funds on the current key will be returned to your Seed Vault first."
                            } else {
                                ""
                            }
                        ) +
                        " If you receive Liquid Stream payments, the new key will need a little SOL for settlement fees.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegenerate = false
                    onRegenerate()
                }) {
                    Text(text = "Regenerate", color = AlgoKitTheme.colors.negative)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, AlgoKitTheme.colors.layerGrayLighter, RoundedCornerShape(12.dp))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = AlgoKitTheme.colors.textMain,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun BodyText(
    text: String,
    color: Color = AlgoKitTheme.colors.textGray,
    fontSize: TextUnit = 14.sp,
    modifier: Modifier = Modifier,
) {
    Text(text = text, modifier = modifier, color = color, fontSize = fontSize)
}

/** Formats integer base units with [decimals] places, trimming trailing zeros. */
internal fun formatUnits(
    baseUnits: Long?,
    decimals: Int,
): String {
    if (baseUnits == null) return "—"
    val raw = baseUnits.toString().padStart(decimals + 1, '0')
    val whole = raw.dropLast(decimals)
    val fraction = raw.takeLast(decimals).trimEnd('0')
    return if (fraction.isEmpty()) whole else "$whole.$fraction"
}

@Preview
@Composable
private fun SolanaSessionKeyPreview() {
    AlgoKitTheme {
        SolanaSessionKeyContent(
            state =
                SolanaSessionKeyViewModel.ViewState(
                    isLoading = false,
                    linkedSigner =
                        SolanaLinkedSigner(
                            ownerAddress = "Owner1111111111111111111111111111111111111",
                            signerAddress = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin",
                            source = SolanaLinkedSignerSource.GENERATED,
                            derivationPath = null,
                            createdAtMs = 0,
                        ),
                    balance = SolanaSignerBalance(lamports = 12_500_000, usdcBaseUnits = 4_250_000),
                ),
            onBack = {},
            showSnackBar = {},
            onRegenerate = {},
            onRefreshBalance = {},
            onSweep = {},
        )
    }
}
