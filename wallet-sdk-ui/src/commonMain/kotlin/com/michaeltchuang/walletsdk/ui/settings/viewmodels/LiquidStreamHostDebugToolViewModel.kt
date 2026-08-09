package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultContextUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.settings.domain.DebugAddressHolder
import com.michaeltchuang.walletsdk.utils.DataResource
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

class LiquidStreamHostDebugToolViewModel(
    private val getCurrentBlockUseCase: GetCurrentBlockUseCase,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val getSessionVaultContextUseCase: GetSessionVaultContextUseCase,
    private val mppWalletSignerUseCase: MppWalletSignerUseCase,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<LiquidStreamHostDebugToolViewModel.ViewState> by stateDelegate,
    EventViewModel<LiquidStreamHostDebugToolViewModel.ViewEvent> by eventDelegate {

    init {
        stateDelegate.setDefaultState(ViewState())
        startLivePolling()
        refreshViewerBalances()
        startAutomation()
    }

    private fun startAutomation() {
        viewModelScope.launch {
            delay(2.seconds) // Wait for UI to stabilize
            
            // 1. Initial Deposit to all viewers
            addAmountToAllSessionVaults(1.0)
            
            // 2. Periodic Settle Cycle
            while (true) {
                delay(3.seconds)
                performAutomatedSettlement(incrementUsdc = 0.1)
            }
        }
    }

    private fun startLivePolling() {
        viewModelScope.launch {
            try {
                val network = getCurrentNetworkUseCase().first()
                stateDelegate.updateState { it.copy(liveNetworkLabel = network.displayName.uppercase()) }
            } catch (e: Exception) {
                Napier.e("Failed to fetch initial network", e, tag = "LiquidStreamHostDebugVM")
            }

            while (true) {
                getCurrentBlockUseCase().collect { result ->
                    if (result is DataResource.Success) {
                        stateDelegate.updateState { it.copy(liveBlockNumber = result.data) }
                    }
                }
                delay(3.seconds)
            }
        }
    }

    fun refreshViewerBalances() {
        viewModelScope.launch {
            try {
                val vaultContext = getSessionVaultContextUseCase()
                EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

                val addresses = listOf(
                    DebugAddressHolder.viewerAddress,
                    DebugAddressHolder.viewerAddress2,
                    DebugAddressHolder.viewerAddress3,
                ).filter { it.isNotBlank() }

                val newBalances = mutableMapOf<String, Double>()
                addresses.forEach { address ->
                    try {
                        val remaining = withContext(Dispatchers.Default) {
                            MppPayments.getRemainingBalanceFromSessionVault(address)
                        }
                        newBalances[address] = remaining / 1_000_000.0
                    } catch (e: Exception) {
                        Napier.e("Failed to fetch balance for $address", e, tag = "LiquidStreamHostDebugVM")
                        newBalances[address] = 0.0
                    }
                }
                stateDelegate.updateState { it.copy(viewerBalances = newBalances) }
            } catch (e: Exception) {
                Napier.e("Failed to configure vault for balances", e, tag = "LiquidStreamHostDebugVM")
            }
        }
    }

    private suspend fun performAutomatedSettlement(incrementUsdc: Double) {
        try {
            val vaultContext = getSessionVaultContextUseCase()
            EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

            val creator = DebugAddressHolder.creatorAddress
            val creatorSigner = mppWalletSignerUseCase(creator) ?: return

            val addresses = listOf(
                DebugAddressHolder.viewerAddress,
                DebugAddressHolder.viewerAddress2,
                DebugAddressHolder.viewerAddress3,
            ).filter { it.isNotBlank() }

            val incrementMicroUsdc = (incrementUsdc * 1_000_000).toLong()

            for (viewer in addresses) {
                val viewerSigner = mppWalletSignerUseCase(viewer) ?: continue

                // 1. Resolve Channel Context
                EscrowSessionVaultManagerClient.initializeChannelId(
                    payerAddress = viewer,
                    payeeAddress = creator,
                    authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                )

                val channelId = EscrowSessionVaultManagerClient.channelId ?: continue

                // 2. Fetch Snapshot to determine next cumulative amount
                val snapshot = withContext(Dispatchers.Default) {
                    MppPayments.getSessionProgressSnapshotFromVault()
                } ?: continue

                val newCumulative = snapshot.latestVoucherAmountMicroUsdc + incrementMicroUsdc
                
                // Safety check: don't settle more than deposited
                if (newCumulative > snapshot.totalDepositMicroUsdc) continue

                // 3. Update Voucher On-Chain
                val settleMessage = MppPayments.settleMessage(
                    cumulativeAmountMicroUsdc = newCumulative,
                    channelId = channelId,
                )
                val signature = viewerSigner.signMessage(settleMessage)

                withContext(Dispatchers.Default) {
                    MppPayments.updateVoucherOnChain(
                        signer = viewerSigner,
                        viewerAddress = viewer,
                        totalAmountUsedMicroUsdc = newCumulative,
                        signature = signature,
                    )
                }.onSuccess { txId ->
                    Napier.d("[AUTO_VOUCHER_UPDATE_OK] viewer=$viewer txId=$txId newCumulative=$newCumulative", tag = "LiquidStreamHostDebugVM")
                    
                    // 4. Settle Latest Voucher to Creator ONLY if voucher update succeeded
                    withContext(Dispatchers.Default) {
                        MppPayments.settleLatestVoucher(signer = creatorSigner)
                    }.onSuccess { settleTxId ->
                        Napier.d("[AUTO_SETTLE_OK] viewer=$viewer txId=$settleTxId", tag = "LiquidStreamHostDebugVM")
                    }.onFailure { settleErr ->
                        Napier.e("[AUTO_SETTLE_ERR] viewer=$viewer", settleErr, tag = "LiquidStreamHostDebugVM")
                    }
                }.onFailure { err ->
                    Napier.e("[AUTO_VOUCHER_UPDATE_ERR] viewer=$viewer", err, tag = "LiquidStreamHostDebugVM")
                }
            }
            refreshViewerBalances()
        } catch (e: Exception) {
            Napier.e("Auto-Settle failed", e, tag = "LiquidStreamHostDebugVM")
        }
    }

    fun addAmountToAllSessionVaults(amountUsdc: Double = 1.0) {
        viewModelScope.launch {
            try {
                stateDelegate.updateState { it.copy(isLoading = true) }
                val vaultContext = getSessionVaultContextUseCase()
                EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

                val addresses = listOf(
                    DebugAddressHolder.viewerAddress,
                    DebugAddressHolder.viewerAddress2,
                    DebugAddressHolder.viewerAddress3,
                ).filter { it.isNotBlank() }

                val depositMicroUsdc = (amountUsdc * 1_000_000).toLong()

                for (viewer in addresses) {
                    val signer = mppWalletSignerUseCase(viewer)
                    if (signer != null) {
                        // 1. Check if viewer already has funds
                        val currentBalance = withContext(Dispatchers.Default) {
                            try {
                                MppPayments.getRemainingBalanceFromSessionVault(viewer)
                            } catch (_: Exception) {
                                0L
                            }
                        }

                        if (currentBalance > 0) {
                            Napier.d("[AUTO_DEPOSIT_SKIP] viewer=$viewer balance=$currentBalance", tag = "LiquidStreamHostDebugVM")
                            continue
                        }

                        // 2. Resolve Channel Context
                        EscrowSessionVaultManagerClient.initializeChannelId(
                            payerAddress = viewer,
                            payeeAddress = DebugAddressHolder.creatorAddress,
                            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                        )
                        withContext(Dispatchers.Default) {
                            MppPayments.openSessionAndDeposit(
                                signer = signer,
                                viewerAddress = viewer,
                                depositAmountMicroUsdc = depositMicroUsdc,
                            )
                        }.onSuccess { txId ->
                            eventDelegate.sendEvent(viewModelScope, ViewEvent.ShowStatusMessage("✅ Successfully deposited $amountUsdc USDC to $viewer"))
                            Napier.d("[AUTO_DEPOSIT_OK] viewer=$viewer txId=$txId", tag = "LiquidStreamHostDebugVM")
                        }.onFailure { err ->
                            eventDelegate.sendEvent(viewModelScope, ViewEvent.ShowStatusMessage("❌ Failed to deposit $amountUsdc USDC to $viewer"))
                            Napier.e("[AUTO_DEPOSIT_ERR] viewer=$viewer", err, tag = "LiquidStreamHostDebugVM")
                        }
                    }
                }
                refreshViewerBalances()
            } catch (e: Exception) {
                Napier.e("Batch deposit failed", e, tag = "LiquidStreamHostDebugVM")
                eventDelegate.sendEvent(viewModelScope, ViewEvent.ShowStatusMessage("❌ Batch deposit failed: ${e.message}"))
            } finally {
                stateDelegate.updateState { it.copy(isLoading = false) }
            }
        }
    }



    data class ViewState(
        val liveBlockNumber: Long? = null,
        val liveNetworkLabel: String = "TESTNET",
        val viewerBalances: Map<String, Double> = emptyMap(),
        val isLoading: Boolean = false,
    )

    sealed interface ViewEvent {
        data class ShowStatusMessage(val message: String) : ViewEvent
    }
}
