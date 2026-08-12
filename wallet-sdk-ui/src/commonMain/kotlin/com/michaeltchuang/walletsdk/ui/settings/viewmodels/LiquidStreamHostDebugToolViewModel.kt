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
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewerInfo
import com.michaeltchuang.walletsdk.ui.settings.domain.DebugAddressHolder
import com.michaeltchuang.walletsdk.utils.DataResource
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val applicationScope: CoroutineScope,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<LiquidStreamHostDebugToolViewModel.ViewState> by stateDelegate,
    EventViewModel<LiquidStreamHostDebugToolViewModel.ViewEvent> by eventDelegate {

    private val viewerChannelIds = mutableMapOf<String, ByteArray>()
    private val authorizedSignerViewers = mutableSetOf<String>()
    private var balanceRefreshJob: Job? = null

    init {
        stateDelegate.setDefaultState(ViewState())
        startLivePolling()
        refreshViewerBalances()
        startAutomation()
    }

    private fun getOrInitChannelId(viewerAddress: String, signer: com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner): ByteArray {
        viewerChannelIds[viewerAddress]?.let { return it }
        val derived = EscrowSessionVaultManagerClient.deriveChannelId(
            payerAddress = viewerAddress,
            payeeAddress = DebugAddressHolder.creatorAddress,
            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
        )
        viewerChannelIds[viewerAddress] = derived
        return derived
    }

    private fun startAutomation() {
        viewModelScope.launch {
            delay(2.seconds) // Wait for UI to stabilize
            
            // 1. Initial deposit must finish before voucher settlement begins.
            addAmountToAllSessionVaults(1.0).join()

            // 2. Periodic Settle Cycle
            while (true) {
                delay(8.seconds)
                performAutomatedSettlement(incrementUsdc = 0.1)
            }
        }
    }

    private fun startLivePolling() {
        viewModelScope.launch {
            try {
                val network = getCurrentNetworkUseCase().first()
                stateDelegate.updateState {
                    it.copy(
                        liveNetworkLabel = network.displayName.uppercase(),
                        viewers = buildViewersList(it.viewerBalances, it.liveBlockNumber, network.displayName.uppercase()),
                    )
                }
            } catch (e: Exception) {
                Napier.e("Failed to fetch initial network", e, tag = "LiquidStreamHostDebugVM")
            }

            while (true) {
                getCurrentBlockUseCase().collect { result ->
                    if (result is DataResource.Success) {
                        stateDelegate.updateState {
                            it.copy(
                                liveBlockNumber = result.data,
                                viewers = buildViewersList(it.viewerBalances, result.data, it.liveNetworkLabel),
                            )
                        }
                    }
                }
                delay(3.seconds)
            }
        }
    }

    fun refreshViewerBalances() {
        if (balanceRefreshJob?.isActive == true) return
        balanceRefreshJob = viewModelScope.launch {
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
                        val signer = mppWalletSignerUseCase(address)
                        val channelId = if (signer != null) getOrInitChannelId(address, signer) else null
                        val remaining = withContext(Dispatchers.Default) {
                            MppPayments.getRemainingBalanceFromSessionVault(address, channelId)
                        }
                        newBalances[address] = remaining / 1_000_000.0
                    } catch (e: Exception) {
                        Napier.e("Failed to fetch balance for $address", e, tag = "LiquidStreamHostDebugVM")
                        newBalances[address] = 0.0
                    }
                }
                stateDelegate.updateState {
                    it.copy(
                        viewerBalances = newBalances,
                        viewers = buildViewersList(newBalances, it.liveBlockNumber, it.liveNetworkLabel),
                    )
                }
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
                val channelId = getOrInitChannelId(viewer, viewerSigner)
                if (viewer !in authorizedSignerViewers) {
                    val authorizationResult =
                        withContext(Dispatchers.Default) {
                            MppPayments.setAuthorizedSignerForSession(
                                signer = viewerSigner,
                                viewerAddress = viewer,
                                authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                                channelId = channelId,
                            )
                        }
                    if (authorizationResult.isFailure) {
                        Napier.e(
                            "[AUTO_SET_AUTH_SIGNER_ERR] viewer=$viewer",
                            authorizationResult.exceptionOrNull(),
                            tag = "LiquidStreamHostDebugVM",
                        )
                        continue
                    }
                    authorizedSignerViewers += viewer
                }

                // 2. Fetch Snapshot to determine next cumulative amount
                val snapshot = withContext(Dispatchers.Default) {
                    MppPayments.getSessionProgressSnapshotFromVault(channelId)
                } ?: continue

                val newCumulative = snapshot.latestVoucherAmountMicroUsdc + incrementMicroUsdc
                
                // Safety check: don't settle more than deposited
                if (newCumulative > snapshot.totalDepositMicroUsdc) continue

                // 3. Settle the signed voucher directly. This records the voucher and pays the creator in one call.
                val settleMessage = MppPayments.settleMessage(
                    cumulativeAmountMicroUsdc = newCumulative,
                    channelId = channelId,
                )
                val signature = viewerSigner.signMessage(settleMessage)

                withContext(Dispatchers.Default) {
                    MppPayments.settle(
                        signer = creatorSigner,
                        cumulativeAmountMicroUsdc = newCumulative,
                        signature = signature,
                        channelId = channelId,
                    )
                }.onSuccess { txId ->
                    Napier.d("[AUTO_SETTLE_OK] viewer=$viewer txId=$txId newCumulative=$newCumulative", tag = "LiquidStreamHostDebugVM")
                }.onFailure { err ->
                    Napier.e("[AUTO_SETTLE_ERR] viewer=$viewer", err, tag = "LiquidStreamHostDebugVM")
                }
            }
            refreshViewerBalances()
        } catch (e: Exception) {
            Napier.e("Auto-Settle failed", e, tag = "LiquidStreamHostDebugVM")
        }
    }

    private fun addAmountToAllSessionVaults(amountUsdc: Double = 1.0): Job =
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
                        val channelId = getOrInitChannelId(viewer, signer)

                        // 1. Check if viewer already has funds
                        val currentBalance = withContext(Dispatchers.Default) {
                            try {
                                MppPayments.getRemainingBalanceFromSessionVault(viewer, channelId)
                            } catch (_: Exception) {
                                0L
                            }
                        }

                        if (currentBalance > 0) {
                            Napier.d("[AUTO_DEPOSIT_SKIP] viewer=$viewer balance=$currentBalance", tag = "LiquidStreamHostDebugVM")
                            continue
                        }

                        val depositResult =
                            withContext(Dispatchers.Default) {
                                MppPayments.openSessionAndDeposit(
                                    signer = signer,
                                    viewerAddress = viewer,
                                    depositAmountMicroUsdc = depositMicroUsdc,
                                    channelId = channelId,
                                )
                            }
                        depositResult.onSuccess { txId ->
                            val authorizationResult =
                                withContext(Dispatchers.Default) {
                                    MppPayments.setAuthorizedSignerForSession(
                                        signer = signer,
                                        viewerAddress = viewer,
                                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                        channelId = channelId,
                                    )
                                }
                            authorizationResult.onSuccess {
                                eventDelegate.sendEvent(
                                    viewModelScope,
                                    ViewEvent.ShowStatusMessage("✅ Successfully deposited $amountUsdc USDC to $viewer"),
                                )
                                Napier.d("[AUTO_DEPOSIT_OK] viewer=$viewer txId=$txId", tag = "LiquidStreamHostDebugVM")
                            }.onFailure { err ->
                                eventDelegate.sendEvent(
                                    viewModelScope,
                                    ViewEvent.ShowStatusMessage("❌ Failed to register signer for $viewer"),
                                )
                                Napier.e("[AUTO_SET_AUTH_SIGNER_ERR] viewer=$viewer", err, tag = "LiquidStreamHostDebugVM")
                            }
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

    fun closeAllSessions() {
        applicationScope.launch {
            try {
                val vaultContext = getSessionVaultContextUseCase()
                EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)

                val addresses = listOf(
                    DebugAddressHolder.viewerAddress,
                    DebugAddressHolder.viewerAddress2,
                    DebugAddressHolder.viewerAddress3,
                ).filter { it.isNotBlank() }
                val signer = mppWalletSignerUseCase(DebugAddressHolder.creatorAddress)
                for (viewer in addresses) {

                    if (signer != null) {
                        val channelId = getOrInitChannelId(viewer, signer)

                        // 2. Close Session Vault
                        withContext(Dispatchers.Default) {
                            MppPayments.closeSessionVault(signer = signer, channelId = channelId)
                        }.onSuccess { txId ->
                            Napier.d("[AUTO_CLOSE_OK] viewer=$viewer txId=$txId", tag = "LiquidStreamHostDebugVM")
                        }.onFailure { err ->
                            Napier.e("[AUTO_CLOSE_ERR] viewer=$viewer", err, tag = "LiquidStreamHostDebugVM")
                        }
                    }
                }
                refreshViewerBalances()
            } catch (e: Exception) {
                Napier.e("Close sessions failed", e, tag = "LiquidStreamHostDebugVM")
            }
        }
    }

    private fun channelIdDisplayFor(viewerAddress: String): String =
        viewerChannelIds[viewerAddress]?.joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        } ?: "channel-pending"

    private fun buildViewersList(
        balances: Map<String, Double>,
        blockNumber: Long?,
        networkLabel: String,
    ): List<ConnectedViewerInfo> {
        return listOf(
            ConnectedViewerInfo(
                sessionId = channelIdDisplayFor(DebugAddressHolder.viewerAddress),
                remainingBalanceUSDC = balances[DebugAddressHolder.viewerAddress] ?: 0.0,
                progressBalanceUSDC = balances[DebugAddressHolder.viewerAddress] ?: 0.0,
                progressCapacityUSDC = 1.0,
                connectionType = IceConnectionType.LOCAL,
                currentBlockNumber = blockNumber,
                networkLabel = networkLabel,
                originUrl = "https://liquid-auth-api.pg.nodely.dev/",
                viewerAddress = DebugAddressHolder.viewerAddress,
            ),
            ConnectedViewerInfo(
                sessionId = channelIdDisplayFor(DebugAddressHolder.viewerAddress2),
                remainingBalanceUSDC = balances[DebugAddressHolder.viewerAddress2] ?: 0.0,
                progressBalanceUSDC = balances[DebugAddressHolder.viewerAddress2] ?: 0.0,
                progressCapacityUSDC = 1.0,
                connectionType = IceConnectionType.LOCAL,
                currentBlockNumber = blockNumber,
                networkLabel = networkLabel,
                originUrl = "https://liquid-auth-api.pg.nodely.dev/",
                viewerAddress = DebugAddressHolder.viewerAddress2,
            ),
            ConnectedViewerInfo(
                sessionId = channelIdDisplayFor(DebugAddressHolder.viewerAddress3),
                remainingBalanceUSDC = balances[DebugAddressHolder.viewerAddress3] ?: 0.0,
                progressBalanceUSDC = balances[DebugAddressHolder.viewerAddress3] ?: 0.0,
                progressCapacityUSDC = 1.0,
                connectionType = IceConnectionType.LOCAL,
                currentBlockNumber = blockNumber,
                networkLabel = networkLabel,
                originUrl = "https://viewer-3.app",
                viewerAddress = DebugAddressHolder.viewerAddress3,
            ),
        ).filter { !it.viewerAddress.isNullOrBlank() }
    }


    data class ViewState(
        val liveBlockNumber: Long? = null,
        val liveNetworkLabel: String = "TESTNET",
        val viewerBalances: Map<String, Double> = emptyMap(),
        val viewers: List<ConnectedViewerInfo> = emptyList(),
        val isLoading: Boolean = false,
    )

    sealed interface ViewEvent {
        data class ShowStatusMessage(val message: String) : ViewEvent
    }
}
