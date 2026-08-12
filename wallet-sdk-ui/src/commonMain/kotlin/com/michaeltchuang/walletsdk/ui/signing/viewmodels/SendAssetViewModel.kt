package com.michaeltchuang.walletsdk.ui.signing.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.toBigInteger
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetAccountASABalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountAlgoBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMinimumBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetTransactionFeeForAccount
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.ALGO_ID
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val MICRO_ASSET_DIVISOR = 1_000_000.0

class SendAssetViewModel(
    private val getAccountAlgoBalance: GetAccountAlgoBalance,
    private val getAccountASABalance: GetAccountASABalance,
    private val getAccountMinimumBalance: GetAccountMinimumBalance,
    private val getTransactionFeeForAccount: GetTransactionFeeForAccount,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<SendAssetViewModel.ViewState> by stateDelegate,
    EventViewModel<SendAssetViewModel.ViewEvent> by eventDelegate {
    private var algoBalance: BigInteger = BigInteger.ZERO
    private var assetBalance: BigInteger = BigInteger.ZERO
    private var senderAddress: String = ""
    private var assetId: Long = ALGO_ID
    private var activeNetworkUsdcAssetId: Long? = null
    private val algoUsdPrice: Double = 0.199 // Mock price, should come from a price service
    private val maxDecimalPlaces = 6 // Algorand supports 6 decimal places (microAlgos)

    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        updateContentState()
    }

    fun fetchAccountBalance(
        senderAddress: String,
        assetId: Long,
    ) {
        viewModelScope.launch {
            try {
                this@SendAssetViewModel.senderAddress = senderAddress
                this@SendAssetViewModel.assetId = assetId
                activeNetworkUsdcAssetId =
                    AssetConstants.usdcIdForNetwork(getCurrentNetworkUseCase().first().displayName)
                algoBalance = getAccountAlgoBalance(senderAddress) ?: BigInteger.ZERO
                assetBalance =
                    if (assetId == ALGO_ID) {
                        algoBalance
                    } else {
                        getAccountASABalance(senderAddress, assetId) ?: BigInteger.ZERO
                    }
                updateContentState()
            } catch (e: Exception) {
                stateDelegate.updateState { ViewState.Error("Failed to fetch account balance: ${e.message}") }
            }
        }
    }

    private fun updateContentState() {
        val currentState = stateDelegate.state.value
        val currentAmount =
            when (currentState) {
                is ViewState.Content -> currentState.amount
                is ViewState.MinimumBalanceAlert -> currentState.previousAmount
                else -> ""
            }

        val balanceInStandardUnits = assetBalance.toStandardUnitDouble()
        val balanceFormatted = balanceInStandardUnits.toFormattedAmount()
        val balanceUsdValue = "$${(balanceInStandardUnits * algoUsdPrice).toString().take(6)}"

        val amountUsdValue =
            if (currentAmount.isNotEmpty() && currentAmount != "0") {
                val amountDouble = currentAmount.toDoubleOrNull() ?: 0.0
                "$${(amountDouble * algoUsdPrice).toString().take(6)}"
            } else {
                "$0.00"
            }

        stateDelegate.updateState {
            ViewState.Content(
                amount = currentAmount,
                usdValue = amountUsdValue,
                balance = balanceFormatted,
                assetUsdValue = balanceUsdValue,
                isUsdc = isUsdc(assetId),
            )
        }
    }

    fun isUsdc(assetId: Long): Boolean = assetId == activeNetworkUsdcAssetId

    fun onDigitPressed(digit: String) {
        val currentState = stateDelegate.state.value
        if (currentState is ViewState.Content) {
            val currentAmount = currentState.amount
            val newAmount =
                when {
                    digit == "." && currentAmount.isEmpty() -> "0."
                    digit == "." && currentAmount.contains(".") -> return // Already has decimal point
                    currentAmount == "0" && digit != "." -> digit
                    else -> "$currentAmount$digit"
                }

            // Validate the new amount is a valid BigDecimal with max decimal places
            if (isValidAmount(newAmount)) {
                updateAmountAndRefresh(newAmount)
            }
        }
    }

    fun onDeletePressed() {
        val currentState = stateDelegate.state.value
        if (currentState is ViewState.Content) {
            val currentAmount = currentState.amount
            val newAmount =
                if (currentAmount.isNotEmpty()) {
                    currentAmount.dropLast(1)
                } else {
                    ""
                }
            updateAmountAndRefresh(newAmount)
        }
    }

    fun onMaxPressed() {
        viewModelScope.launch {
            try {
                val maxSendable =
                    if (assetId == ALGO_ID) {
                        val minimumBalance = getAccountMinimumBalance(senderAddress) ?: 100000L
                        val transactionFee = getTransactionFeeForAccount(senderAddress)

                        // ALGO max must reserve gas fees and minimum balance.
                        algoBalance - transactionFee.feeInMicroAlgos.toBigInteger() - minimumBalance.toBigInteger()
                    } else {
                        // ASA/USDC max is the full asset holding; ALGO is only needed separately for gas.
                        assetBalance
                    }

                updateAmountAndRefresh(
                    if (maxSendable > BigInteger.ZERO) {
                        maxSendable.toStandardUnitDouble().toFormattedAmount()
                    } else {
                        "0"
                    },
                )
            } catch (e: Exception) {
                // Fallback to 0 if anything goes wrong
                updateAmountAndRefresh("0")
                e.printStackTrace()
            }
        }
    }

    fun onNextPressed() {
        viewModelScope.launch {
            val currentState = stateDelegate.state.value
            if (currentState is ViewState.Content && currentState.amount.isNotEmpty() && currentState.amount != "0") {
                // Get the appropriate fee based on account type
                val transactionFee = getTransactionFeeForAccount(senderAddress)

                if (algoBalance < transactionFee.feeInMicroAlgos.toBigInteger()) {
                    stateDelegate.updateState {
                        ViewState.MinimumBalanceAlert(
                            message = "You need at least ${transactionFee.feeInAlgos} ALGO for gas fees",
                            previousAmount = currentState.amount,
                            previousBalance = currentState.balance,
                            previousAssetUsdValue = currentState.assetUsdValue,
                            previousIsUsdc = currentState.isUsdc,
                        )
                    }
                    return@launch
                }

                try {
                    val amountDouble = currentState.amount.toDouble()
                    val amountInMicroAlgos = (amountDouble * 1_000_000).toLong().toString()
                    eventDelegate.sendEvent(ViewEvent.NavigateNext(amountInMicroAlgos))
                } catch (e: Exception) {
                    stateDelegate.updateState { ViewState.Error("Invalid amount format") }
                }
            }
        }
    }

    fun onMinimumBalanceAlertDismissed() {
        val currentState = stateDelegate.state.value
        if (currentState is ViewState.MinimumBalanceAlert) {
            updateContentState()
        }
    }

    private fun isValidAmount(amount: String): Boolean {
        if (amount.isEmpty()) return true
        if (amount == ".") return false // Just a decimal point is not valid

        return try {
            // Try to parse as BigDecimal
            BigDecimal.parseString(amount)

            // Check decimal places
            if (amount.contains(".")) {
                val decimalPart = amount.substringAfter(".")
                decimalPart.length <= maxDecimalPlaces
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun BigInteger.toStandardUnitDouble(): Double = toString().toDouble() / MICRO_ASSET_DIVISOR

    private fun Double.toFormattedAmount(): String =
        toString()
            .take(8)
            .trimEnd('0')
            .trimEnd('.')

    private fun updateAmountAndRefresh(newAmount: String) {
        val currentState = stateDelegate.state.value
        if (currentState is ViewState.Content) {
            val amountUsdValue =
                if (newAmount.isNotEmpty() && newAmount != "0") {
                    val amountDouble = newAmount.toDoubleOrNull() ?: 0.0
                    "$${(amountDouble * algoUsdPrice).toString().take(6)}"
                } else {
                    "$0.00"
                }

            stateDelegate.updateState {
                currentState.copy(
                    amount = newAmount,
                    usdValue = amountUsdValue,
                )
            }
        }
    }

    sealed interface ViewState {
        data object Loading : ViewState

        data class Content(
            val amount: String,
            val usdValue: String,
            val balance: String?,
            val assetUsdValue: String?,
            val isUsdc: Boolean = false,
            val showUSDAmount: Boolean = false,
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState

        data class MinimumBalanceAlert(
            val message: String,
            val previousAmount: String,
            val previousBalance: String?,
            val previousAssetUsdValue: String?,
            val previousIsUsdc: Boolean,
        ) : ViewState {
            fun toContent(): Content =
                Content(
                    amount = previousAmount,
                    usdValue = "$0.00",
                    balance = previousBalance,
                    assetUsdValue = previousAssetUsdValue,
                    isUsdc = previousIsUsdc,
                    showUSDAmount = false,
                )
        }
    }

    sealed interface ViewEvent {
        data class NavigateNext(
            val amount: String, // Amount in microAlgos
        ) : ViewEvent
    }
}
