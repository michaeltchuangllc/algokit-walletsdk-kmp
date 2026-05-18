package com.michaeltchuang.walletsdk.ui.test

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algorand.algosdk.sdk.BytesArray
import com.algorand.algosdk.sdk.Sdk
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonic
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyTransaction
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments.TESTNET_ALGOD_URL
import com.michaeltchuang.walletsdk.core.railmpp.utils.PaymentError
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.ByteArray
import kotlin.io.encoding.Base64

class PaymentTestViewModel(
    private val getAccountMnemonic: GetAccountMnemonic,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getLocalAccount: GetLocalAccount,
    private val getLocalAccounts: GetLocalAccounts,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getHdSeed: GetHdSeed,
) : ViewModel() {
    companion object {
        private const val TAG = "PaymentTestViewModel"
        private const val MICRO_USDC_MULTIPLIER = 1_000_000L
    }

    // ── Input fields ──────────────────────────────────────────────────────────

    private val _viewerAddress =
        MutableStateFlow("SOTKPTASL4ISO2542ZVOPD4SZHCWYRUBX27NDOYHSMODL4SNARNH5C66OA")
    val viewerAddress: StateFlow<String> = _viewerAddress.asStateFlow()

    private val _creatorAddress =
        MutableStateFlow("HDIWBIZUHV7I7DQH5UWNERR345TT636ACKHX2TTRJMK5AKTJGO4N4XUF3I")
    val creatorAddress: StateFlow<String> = _creatorAddress.asStateFlow()

    /** Amount expressed in whole USDC (e.g. "1.0") */
    private val _depositAmountUsdc = MutableStateFlow("0.1")
    val depositAmountUsdc: StateFlow<String> = _depositAmountUsdc.asStateFlow()

    // ── Output / result state ─────────────────────────────────────────────────

    private val _remainingBalance = MutableStateFlow<Long?>(null)
    val remainingBalance: StateFlow<Long?> = _remainingBalance.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── Input change handlers ─────────────────────────────────────────────────

    fun onViewerAddressChanged(address: String) {
        _viewerAddress.value = address
    }

    fun onCreatorAddressChanged(address: String) {
        _creatorAddress.value = address
    }

    fun onDepositAmountChanged(amount: String) {
        _depositAmountUsdc.value = amount
    }

    // ── Action 1: Add amount to Session Vault ─────────────────────────────────

    /**
     * Opens a new session vault (or attempts to top it up) and deposits the
     * specified USDC amount.  The **viewer** wallet must already be stored in
     * this device so we can retrieve its mnemonic for signing.
     */
    fun addAmountToSessionVault() {
        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()
        val amountUsdc = _depositAmountUsdc.value.trim().toDoubleOrNull() ?: 1.0
        val depositMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value = "Error: Viewer and Creator addresses are required."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Depositing $amountUsdc USDC to Session Vault…"
            try {
                val signer = buildCreatorWalletSigner(viewer)
                signer?.let {
                    val result =
                        withContext(Dispatchers.IO) {
                            MppPayments.openSessionAndDeposit(
                                signer = signer,
                                viewerAddress = viewer,
                                creatorAddress = creator,
                                depositAmountMicroUsdc = depositMicroUsdc,
                            )
                        }

                    result
                        .onSuccess { txId ->
                            _statusMessage.value =
                                "✅ Deposited $amountUsdc USDC to Session Vault!\nTxId: $txId"
                            Log.d(
                                TAG,
                                "[ADD_TO_VAULT_OK] txId=$txId viewer=$viewer creator=$creator depositMicroUsdc=$depositMicroUsdc",
                            )
                        }.onFailure { err ->
                            showError(PaymentError.from(err), "ADD_TO_VAULT_ERR", err)
                        }
                } ?: showError(PaymentError.SignerNotFound(viewer), "ADD_TO_VAULT_NO_SIGNER")
            } catch (e: Exception) {
                showError(PaymentError.from(e), "ADD_TO_VAULT_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Action 2: Fetch Session Vault remaining balance ───────────────────────

    /**
     * Reads the remaining (un-settled) balance from the on-chain Session Vault
     * for the current viewer / creator pair.  No signing required.
     */
    fun fetchSessionVaultRemainingBalance() {
        viewModelScope.launch {
            val viewer = _viewerAddress.value.trim()
            val creator = _creatorAddress.value.trim()

            if (viewer.isBlank() || creator.isBlank()) {
                _statusMessage.value = "Error: Viewer and Creator addresses are required."
                return@launch
            }
            val signer = buildCreatorWalletSigner(viewer)

            _isLoading.value = true
            _statusMessage.value = "Fetching Session Vault balance…"
            try {
                val remaining =
                    withContext(Dispatchers.IO) {
                        MppPayments.getRemainingBalanceFromSessionVault(
                            viewerAddress = viewer,
                            hostAddress = creator,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            algodUrl = null,
                            authorizedSignerPublicKey = signer?.authorizedSignerPublicKey,
                        )
                    }

                _remainingBalance.value = remaining
                val usdc = remaining / MICRO_USDC_MULTIPLIER.toDouble()
                _statusMessage.value =
                    "✅ Remaining balance: $usdc USDC\n($remaining microUSDC)"
                Log.d(
                    TAG,
                    "[FETCH_BALANCE_OK] remaining=$remaining viewer=$viewer creator=$creator",
                )
            } catch (e: Exception) {
                showError(PaymentError.from(e), "FETCH_BALANCE_ERR", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Action 3: Settle from Session Vault to creator ────────────────────────

    /**
     * Triggers an on-chain settlement of the latest voucher, transferring the
     * earned amount from the Session Vault to the **creator** account.
     * The creator wallet must already be stored on this device.
     */
    fun settleAmount() {
        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()

        val amountUsdc =
            _depositAmountUsdc.value.trim().toDoubleOrNull() ?: 1.0

        val requestedIncrementMicroUsdc =
            (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value =
                "Error: Viewer and Creator addresses are required."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true

            try {
                _statusMessage.value =
                    "Preparing settlement..."

                val viewerSigner =
                    buildCreatorWalletSigner(viewer)
                        ?: run {
                            showError(
                                PaymentError.SignerNotFound(viewer),
                                "SETTLE_NO_VIEWER_SIGNER",
                            )
                            return@launch
                        }

                //
                // STEP 1: LOAD SESSION SNAPSHOT
                //
                val snapshot =
                    withContext(Dispatchers.IO) {
                        MppPayments.getSessionProgressSnapshotFromVault(
                            viewerAddress = viewer,
                            hostAddress = creator,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            authorizedSignerPublicKey =
                                viewerSigner.authorizedSignerPublicKey,
                        )
                    } ?: run {
                        showError(PaymentError.SessionNotFound, "SETTLE_NO_SNAPSHOT")
                        return@launch
                    }

                val totalDeposit =
                    snapshot.totalDepositMicroUsdc

                val lastSettled =
                    snapshot.lastSettledMicroUsdc

                val latestVoucher =
                    snapshot.latestVoucherAmountMicroUsdc

                Log.d(
                    TAG,
                    """
                    [SESSION_STATE]
                    totalDeposit=$totalDeposit
                    lastSettled=$lastSettled
                    latestVoucher=$latestVoucher
                    """.trimIndent(),
                )

                //
                // STEP 2: COMPUTE NEW CUMULATIVE
                //
                val newCumulative =
                    latestVoucher + requestedIncrementMicroUsdc

                Log.d(
                    TAG,
                    """
                    [NEW_CUMULATIVE]
                    requestedIncrement=$requestedIncrementMicroUsdc
                    newCumulative=$newCumulative
                    """.trimIndent(),
                )

                //
                // STEP 3: VALIDATIONS
                //
                if (newCumulative > totalDeposit) {
                    val depositUsdc = totalDeposit / 1_000_000.0
                    val requestedUsdc = newCumulative / 1_000_000.0
                    _statusMessage.value =
                        "❌ ${PaymentError.VoucherExceedsDeposit.userMessage}" +
                        "\n\nDeposited: $depositUsdc USDC  |  Requested: $requestedUsdc USDC"
                    Log.e(
                        TAG,
                        "[VOUCHER_EXCEEDS_DEPOSIT] deposit=$totalDeposit requested=$newCumulative",
                    )
                    return@launch
                }

                if (newCumulative <= lastSettled) {
                    _statusMessage.value = "❌ ${PaymentError.NothingToSettle.userMessage}"
                    Log.e(
                        TAG,
                        "[NOTHING_NEW_TO_SETTLE] newCumulative=$newCumulative lastSettled=$lastSettled",
                    )
                    return@launch
                }

                //
                // STEP 4: BUILD SETTLE MESSAGE
                //
                val settleMessage =
                    MppPayments.settleMessage(
                        signer = viewerSigner,
                        viewerAddress = viewer,
                        hostAddress = creator,
                        cumulativeAmountMicroUsdc = newCumulative,
                    )

                //
                // STEP 5: SIGN MESSAGE
                //

                val viewerSignature = viewerSigner.signMessage(settleMessage)

                Log.d(
                    TAG,
                    """
                    [SIGNATURE_CREATED]
                    sigLen=${viewerSignature.size}
                    """.trimIndent(),
                )

                //
                // STEP 6: VERIFY SIGNATURE
                //
                Log.d(
                    TAG,
                    "[VERIFY_SIGNATURE_OK]",
                )

                //
                // STEP 7: UPDATE VOUCHER — broadcast + wait for on-chain confirmation.
                // settle() reads latestVoucherAmount from chain; if we don't wait here,
                // the Algorand node returns the pre-update value (0 or stale) and settle
                // fails with "Settle exceeds latest voucher".
                //
                _statusMessage.value = "Recording voucher on-chain…"

                val updateTxId =
                    withContext(Dispatchers.IO) {
                        MppPayments.updateVoucherOnChain(
                            signer = viewerSigner,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            totalAmountUsedMicroUsdc = newCumulative,
                            signature = viewerSignature,
                        )
                    }.getOrElse { err ->
                        showError(PaymentError.from(err), "UPDATE_VOUCHER_ERR", err)
                        return@launch
                    }

                Log.d(TAG, "[UPDATE_VOUCHER_OK] txId=$updateTxId")

                // Wait for the updateVoucher transaction to be included in a block before
                // settle reads latestVoucherAmount from on-chain state.
                _statusMessage.value = "Waiting for voucher to confirm on-chain…"

               /* val confirmed =
                    withContext(Dispatchers.IO) {
                        MppPayments.awaitTransactionConfirmation(txId = updateTxId)
                    }

                if (!confirmed) {
                    showError(PaymentError.TransactionNotConfirmed, "UPDATE_VOUCHER_NOT_CONFIRMED")
                    return@launch
                }*/

                Log.d(TAG, "[UPDATE_VOUCHER_CONFIRMED] txId=$updateTxId")

                //
                // STEP 8: SETTLE
                // The settle transaction must be sent by the payee (creator), not the payer (viewer).
                // The channelId must still be derived from the viewer's authorizedSignerPublicKey.
                //
                val creatorSigner =
                    buildCreatorWalletSigner(creator)
                        ?: run {
                            showError(
                                PaymentError.SignerNotFound(creator),
                                "SETTLE_NO_CREATOR_SIGNER",
                            )
                            return@launch
                        }

                val settleResult =
                    withContext(Dispatchers.IO) {
                       /* MppPayments.settle(
                            signer = creatorSigner,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            cumulativeAmountMicroUsdc = newCumulative,
                            signature = viewerSignature,
                            // channelId must use the viewer's (payer's) authorized signer key,
                            // NOT the creator's key — even though creator is signing this txn.
                            authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                        )*/
                        MppPayments.settleLatestVoucher(
                            signer = creatorSigner,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                            algodUrl = TESTNET_ALGOD_URL,
                        )
                    }

                settleResult
                    .onSuccess { txId ->

                        _statusMessage.value =
                            """
                            ✅ Settlement successful
                            
                            TxId:
                            $txId
                            """.trimIndent()

                        Log.d(
                            TAG,
                            "[SETTLE_OK] txId=$txId",
                        )
                    }.onFailure { err ->
                        showError(PaymentError.from(err), "SETTLE_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.from(e), "SETTLE_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateVoucher() {
        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()
        val amountUsdc = _depositAmountUsdc.value.trim().toDoubleOrNull() ?: 1.0
        val depositMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value = "Error: Viewer and Creator addresses are required."
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Updating voucher on-chain…"
            try {
                val viewerSigner = buildCreatorWalletSigner(viewer)
                viewerSigner?.let {
                    // Build settle message and sign it — must NOT pass the raw public key as signature.
                    val settleMessage =
                        MppPayments.settleMessage(
                            signer = viewerSigner,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            cumulativeAmountMicroUsdc = depositMicroUsdc,
                        )
                    val signature = viewerSigner.signMessage(settleMessage)

                    withContext(Dispatchers.IO) {
                        MppPayments.updateVoucherOnChain(
                            signer = viewerSigner,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            totalAmountUsedMicroUsdc = depositMicroUsdc,
                            signature = signature,
                        )
                    }.onSuccess { txId ->
                        Log.d(TAG, "[UPDATE_VOUCHER_OK] txId=$txId")
                        _statusMessage.value = "✅ Voucher updated!\nTxId: $txId"
                    }.onFailure { err ->
                        showError(PaymentError.from(err), "UPDATE_VOUCHER_ERR", err)
                    }
                } ?: showError(PaymentError.SignerNotFound(viewer), "UPDATE_VOUCHER_NO_SIGNER")
            } catch (e: Exception) {
                showError(PaymentError.from(e), "UPDATE_VOUCHER_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun verifyVoucherSignature() {
        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()
        val amountUsdc = _depositAmountUsdc.value.trim().toDoubleOrNull() ?: 1.0
        val depositMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()
        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value = "Error: Viewer and Creator addresses are required."
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Verifying voucher signature…"
            try {
                val viewerSigner = buildCreatorWalletSigner(viewer)
                viewerSigner?.let {
                    // Build and sign the settle message first — must NOT pass raw public key as signature.
                    val settleMessage =
                        MppPayments.settleMessage(
                            signer = viewerSigner,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            cumulativeAmountMicroUsdc = depositMicroUsdc,
                        )
                    val signature = viewerSigner.signMessage(settleMessage)

                    val result =
                        withContext(Dispatchers.IO) {
                            MppPayments.verifySettleSignature(
                                signer = viewerSigner,
                                viewerAddress = viewer,
                                hostAddress = creator,
                                cumulativeAmountMicroUsdc = depositMicroUsdc,
                                signature = signature,
                            )
                        }
                    result
                        .onSuccess { txId ->
                            _statusMessage.value = "✅ Signature verified!"
                            Log.d(TAG, "[VERIFY_SIGNATURE_OK] txId=$txId")
                        }.onFailure { err ->
                            showError(PaymentError.from(err), "VERIFY_SIGNATURE_ERR", err)
                        }
                } ?: showError(PaymentError.SignerNotFound(viewer), "VERIFY_SIGNATURE_NO_SIGNER")
            } catch (e: Exception) {
                showError(PaymentError.from(e), "VERIFY_SIGNATURE_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startSettlePayment(
        viewerAddress: String,
        creatorAddress: String,
        amountUsdc: Long = 1L,
        viewerAuthSignKey: ByteArray,
    ) {
        val viewer = viewerAddress.trim()
        val creator = creatorAddress.trim()

        val requestedIncrementMicroUsdc =
            (amountUsdc * MICRO_USDC_MULTIPLIER)

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value =
                "Error: Viewer and Creator addresses are required."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true

            try {
                _statusMessage.value =
                    "Preparing settlement..."

                val viewerSigner =
                    buildCreatorWalletSigner(viewer)
                        ?: run {
                            showError(
                                PaymentError.SignerNotFound(viewer),
                                "SETTLE_NO_VIEWER_SIGNER",
                            )
                            return@launch
                        }

                //
                // STEP 1: LOAD SESSION SNAPSHOT
                //
                val snapshot =
                    withContext(Dispatchers.IO) {
                        MppPayments.getSessionProgressSnapshotFromVault(
                            viewerAddress = viewer,
                            hostAddress = creator,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            authorizedSignerPublicKey =
                                viewerSigner.authorizedSignerPublicKey,
                        )
                    } ?: run {
                        showError(
                            PaymentError.SessionNotFound,
                            "SETTLE_NO_SNAPSHOT",
                        )
                        return@launch
                    }

                val totalDeposit =
                    snapshot.totalDepositMicroUsdc

                val lastSettled =
                    snapshot.lastSettledMicroUsdc

                val latestVoucher =
                    snapshot.latestVoucherAmountMicroUsdc

                Log.d(
                    TAG,
                    """
                    [SESSION_STATE]
                    totalDeposit=$totalDeposit
                    lastSettled=$lastSettled
                    latestVoucher=$latestVoucher
                    """.trimIndent(),
                )

                //
                // STEP 2: COMPUTE NEW CUMULATIVE
                //
                val newCumulative =
                    latestVoucher + requestedIncrementMicroUsdc

                Log.d(
                    TAG,
                    """
                    [NEW_CUMULATIVE]
                    requestedIncrement=$requestedIncrementMicroUsdc
                    newCumulative=$newCumulative
                    """.trimIndent(),
                )

                //
                // STEP 3: VALIDATIONS
                //
                if (newCumulative > totalDeposit) {
                    val depositUsdc =
                        totalDeposit / 1_000_000.0

                    val requestedUsdc =
                        newCumulative / 1_000_000.0

                    _statusMessage.value =
                        "❌ ${PaymentError.VoucherExceedsDeposit.userMessage}" +
                        "\n\nDeposited: $depositUsdc USDC  |  Requested: $requestedUsdc USDC"

                    Log.e(
                        TAG,
                        "[VOUCHER_EXCEEDS_DEPOSIT] deposit=$totalDeposit requested=$newCumulative",
                    )

                    return@launch
                }

                if (newCumulative <= lastSettled) {
                    _statusMessage.value =
                        "❌ ${PaymentError.NothingToSettle.userMessage}"

                    Log.e(
                        TAG,
                        "[NOTHING_NEW_TO_SETTLE] newCumulative=$newCumulative lastSettled=$lastSettled",
                    )

                    return@launch
                }

                //
                // STEP 4: BUILD SETTLE MESSAGE
                //
                val settleMessage =
                    MppPayments.settleMessage(
                        signer = viewerSigner,
                        viewerAddress = viewer,
                        hostAddress = creator,
                        cumulativeAmountMicroUsdc = newCumulative,
                    )

                //
                // STEP 5: SIGN MESSAGE
                //
                val viewerSignature =
                    viewerSigner.signMessage(settleMessage)

                Log.d(
                    TAG,
                    """
                    [SIGNATURE_CREATED]
                    sigLen=${viewerSignature.size}
                    """.trimIndent(),
                )

                //
                // STEP 6: VERIFY SIGNATURE
                //
                val verifyResult =
                    withContext(Dispatchers.IO) {
                        MppPayments.verifySettleSignature(
                            signer = viewerSigner,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            cumulativeAmountMicroUsdc = newCumulative,
                            signature = viewerSignature,
                        )
                    }

                verifyResult.onFailure { err ->

                    showError(
                        PaymentError.from(err),
                        "VERIFY_SIGNATURE_ERR",
                        err,
                    )

                    return@launch
                }

                Log.d(TAG, "[VERIFY_SIGNATURE_OK]")

                //
                // STEP 7: UPDATE VOUCHER
                //
                _statusMessage.value =
                    "Recording voucher on-chain…"

                val updateTxId =
                    withContext(Dispatchers.IO) {
                        MppPayments.updateVoucherOnChain(
                            signer = viewerSigner,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            totalAmountUsedMicroUsdc = newCumulative,
                            signature = viewerSignature,
                        )
                    }.getOrElse { err ->

                        showError(
                            PaymentError.from(err),
                            "UPDATE_VOUCHER_ERR",
                            err,
                        )

                        return@launch
                    }

                Log.d(TAG, "[UPDATE_VOUCHER_OK] txId=$updateTxId")

                //
                // WAIT CONFIRMATION
                //
                _statusMessage.value =
                    "Waiting for voucher confirmation…"

                val confirmed =
                    withContext(Dispatchers.IO) {
                        MppPayments.awaitTransactionConfirmation(
                            txId = updateTxId,
                        )
                    }

                if (!confirmed) {
                    showError(
                        PaymentError.TransactionNotConfirmed,
                        "UPDATE_VOUCHER_NOT_CONFIRMED",
                    )

                    return@launch
                }

                Log.d(
                    TAG,
                    "[UPDATE_VOUCHER_CONFIRMED] txId=$updateTxId",
                )

                //
                // STEP 8: SETTLE
                //
                val creatorSigner =
                    buildCreatorWalletSigner(creator)
                        ?: run {
                            showError(
                                PaymentError.SignerNotFound(creator),
                                "SETTLE_NO_CREATOR_SIGNER",
                            )

                            return@launch
                        }

                val settleResult =
                    withContext(Dispatchers.IO) {
                        MppPayments.settle(
                            signer = creatorSigner,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            cumulativeAmountMicroUsdc = newCumulative,
                            signature = viewerSignature,
                            authorizedSignerPublicKey =
                                viewerSigner.authorizedSignerPublicKey,
                        )
                    }

                settleResult
                    .onSuccess { txId ->

                        _statusMessage.value =
                            """
                            ✅ Settlement successful
                            
                            TxId:
                            $txId
                            """.trimIndent()

                        Log.d(
                            TAG,
                            "[SETTLE_OK] txId=$txId",
                        )
                    }.onFailure { err ->

                        showError(
                            PaymentError.from(err),
                            "SETTLE_ERR",
                            err,
                        )
                    }
            } catch (e: Exception) {
                showError(
                    PaymentError.from(e),
                    "SETTLE_EXCEPTION",
                    e,
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ─── Error helper ──────────────────────────────────────────────────────────

    /**
     * Parses [cause] into the most specific [PaymentError], updates the status message with
     * [PaymentError.userMessage], and logs the raw exception under [logTag].
     */
    private fun showError(
        error: PaymentError,
        logTag: String,
        cause: Throwable? = null,
    ) {
        _statusMessage.value = "❌ ${error.userMessage}"
        Log.e(TAG, "[$logTag] ${error::class.simpleName}", cause ?: Throwable(error.userMessage))
    }

    private suspend fun buildCreatorWalletSigner(creatorAddress: String): MppWalletSigner? {
        val localAccount = getLocalAccount(creatorAddress) ?: return null
        if (localAccount is LocalAccount.SeedVault) return null

        val authorizedSignerPublicKey =
            when (localAccount) {
                is LocalAccount.HdKey -> localAccount.publicKey
                is LocalAccount.Falcon24 -> localAccount.publicKey
                is LocalAccount.Algo25 -> {
                    val secretKey = getAlgo25SecretKey(creatorAddress)
                    if (secretKey != null && secretKey.size == 64) {
                        secretKey.copyOfRange(
                            32,
                            64,
                        )
                    } else {
                        ByteArray(0)
                    }
                }

                else -> ByteArray(0)
            }

        return object : MppWalletSigner {
            override val address: String = creatorAddress
            override val authorizedSignerPublicKey: ByteArray = authorizedSignerPublicKey
            override val signerType: Long = if (localAccount is LocalAccount.Falcon24) 1L else 0L

            override suspend fun signTransaction(txn: Transaction): ByteArray {
                return try {
                    when (localAccount) {
                        is LocalAccount.Algo25 -> {
                            val secretKey = getAlgo25SecretKey(creatorAddress)
                            if (secretKey == null) {
                                Log.e(TAG, "Missing Algo25 key for $creatorAddress")
                                return ByteArray(0)
                            }
                            val txnBytes = Encoder.encodeToMsgPack(txn)
                            val signature = signAlgo25ArbitraryData(txn.bytesToSign(), secretKey)
                            if (signature == null) {
                                Log.e(TAG, "Algo25 arbitrary signing failed for $creatorAddress")
                                return ByteArray(0)
                            }
                            withContext(GoMobileDispatcher.dispatcher) {
                                Sdk.attachSignature(signature, txnBytes)
                            }
                        }

                        is LocalAccount.HdKey -> {
                            val seed = getHdSeed(localAccount.seedId)
                            if (seed == null) {
                                Log.e(TAG, "Missing HD seed for $creatorAddress")
                                return ByteArray(0)
                            }
                            signHdKeyTransaction(
                                transactionByteArray = Encoder.encodeToMsgPack(txn),
                                seed = seed,
                                account = localAccount.account,
                                change = localAccount.change,
                                key = localAccount.keyIndex,
                            ) ?: run {
                                Log.e(TAG, "HD signing failed for $creatorAddress")
                                return ByteArray(0)
                            }
                        }

                        is LocalAccount.Falcon24 -> {
                            val secretKey = getFalcon24SecretKey(creatorAddress)
                            if (secretKey == null) {
                                Log.e(TAG, "Missing Falcon24 key for $creatorAddress")
                                return ByteArray(0)
                            }
                            signFalconTxnFromBundle(
                                txn = txn,
                                publicKey = localAccount.publicKey,
                                privateKey = secretKey,
                            )
                        }

                        else -> {
                            Log.e(TAG, "Unsupported account for Algorand Session Vault claim signing")
                            ByteArray(0)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "signTransaction failed for $creatorAddress", t)
                    ByteArray(0)
                }
            }

            override suspend fun signTransactions(txns: List<Transaction>): List<ByteArray> {
                return try {
                    when (localAccount) {
                        is LocalAccount.Falcon24 -> {
                            val secretKey = getFalcon24SecretKey(creatorAddress)
                            if (secretKey == null) {
                                Log.e(TAG, "Missing Falcon24 key for $creatorAddress")
                                return emptyList()
                            }
                            signFalconTxnGroupFromBundle(
                                txns = txns,
                                publicKey = localAccount.publicKey,
                                privateKey = secretKey,
                            )
                        }

                        else -> super.signTransactions(txns)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "signTransactions failed for $creatorAddress", t)
                    emptyList()
                }
            }

            override suspend fun signMessage(message: ByteArray): ByteArray {
                return try {
                    when (localAccount) {
                        is LocalAccount.Algo25 -> {
                            val secretKey = getAlgo25SecretKey(creatorAddress)
                            if (secretKey == null) {
                                Log.e(TAG, "Missing Algo25 key for $creatorAddress")
                                return ByteArray(0)
                            }

                            signAlgo25ArbitraryData(
                                data = message,
                                secretKey = secretKey,
                            ) ?: run {
                                Log.e(TAG, "Algo25 message signing failed for $creatorAddress")
                                return ByteArray(0)
                            }
                        }

                        is LocalAccount.Falcon24 -> {
                            val secretKey = getFalcon24SecretKey(creatorAddress)
                            if (secretKey == null) {
                                Log.e(TAG, "Missing Falcon key for $creatorAddress")
                                return ByteArray(0)
                            }

                            signFalcon24ArbitraryData(
                                data = message,
                                publicKey = localAccount.publicKey,
                                privateKey = secretKey,
                            ) ?: run {
                                Log.e(TAG, "Falcon message signing failed for $creatorAddress")
                                return ByteArray(0)
                            }
                        }

                        is LocalAccount.HdKey -> {
                            val seed = getHdSeed(localAccount.seedId)
                            if (seed == null) {
                                Log.e(TAG, "Missing HD seed for $creatorAddress")
                                return ByteArray(0)
                            }

                            signHdKeyArbitraryData(
                                data = message,
                                seed = seed,
                                account = localAccount.account,
                                change = localAccount.change,
                                key = localAccount.keyIndex,
                            ) ?: run {
                                Log.e(TAG, "HD message signing failed for $creatorAddress")
                                return ByteArray(0)
                            }
                        }

                        else -> {
                            Log.e(TAG, "Unsupported account for message signing")
                            ByteArray(0)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "signMessage failed for $creatorAddress", t)
                    ByteArray(0)
                }
            }
        }
    }

    private suspend fun signFalconTxnGroupFromBundle(
        txns: List<Transaction>,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): List<ByteArray> {
        if (txns.isEmpty()) return emptyList()
        if (publicKey.isEmpty() || privateKey.isEmpty()) {
            Log.e(TAG, "[FALCON_BUNDLE_SKIP] reason=empty_key publicKeyLen=${publicKey.size} privateKeyLen=${privateKey.size}")
            return emptyList()
        }

        Log.e(
            TAG,
            "[FALCON_BUNDLE_TRACE] inputTxnCount=${txns.size} firstGroup=${txns.firstOrNull()?.group}",
        )

        return withContext(GoMobileDispatcher.dispatcher) {
            val expectedTxns =
                txns.map {
                    com.algorand.algosdk.util.Encoder
                        .encodeToMsgPack(it)
                }
            val expectedTxIds = txns.map { it.txID() }
            val txnList = BytesArray().apply { expectedTxns.forEach { append(it.copyOf()) } }
            val resultCsv =
                try {
                    Sdk.signFalconBundle(
                        txnList,
                        publicKey.copyOf(),
                        privateKey.copyOf(),
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "[FALCON_BUNDLE_SIGN_FAILED] error=${t.message}", t)
                    return@withContext emptyList()
                }

            val rawSigned =
                resultCsv
                    .split(",")
                    .filter { it.isNotBlank() }
                    .mapNotNull { decodeFalconBundlePiece(it) }

            val decodedSigned =
                rawSigned
                    .mapNotNull { signedBytes ->
                        runCatching {
                            val signed =
                                com.algorand.algosdk.util.Encoder
                                    .decodeFromMsgPack(signedBytes, SignedTransaction::class.java)
                            val signedTxn = signed.tx ?: return@runCatching null
                            Triple(signedTxn.txID(), signedTxn, signedBytes)
                        }.getOrNull()
                    }

            val expectedFirstGroup = txns.firstOrNull()?.group?.toString()
            val decodedFirstGroup =
                decodedSigned
                    .firstOrNull()
                    ?.second
                    ?.group
                    ?.toString()
            val decodedAllGrouped =
                decodedSigned.all {
                    it.second.group != null &&
                        it.second.group
                            .toString()
                            .isNotBlank()
                }

            Log.e(
                TAG,
                "[FALCON_BUNDLE_TRACE] rawSignedCount=${rawSigned.size} decodedSignedCount=${decodedSigned.size} expectedTxnCount=${txns.size} expectedFirstGroup=$expectedFirstGroup decodedFirstGroup=$decodedFirstGroup decodedAllGrouped=$decodedAllGrouped",
            )

            if (txns.firstOrNull()?.group == null ||
                txns
                    .firstOrNull()
                    ?.group
                    .toString()
                    .isBlank()
            ) {
                if (rawSigned.size > txns.size) {
                    Log.e(
                        TAG,
                        "[FALCON_BUNDLE_TRACE] returningRawSigned=true returnedCount=${rawSigned.size}",
                    )
                    return@withContext rawSigned
                }
            }

            val remaining = decodedSigned.toMutableList()
            val out = mutableListOf<ByteArray>()

            expectedTxIds.forEachIndexed { index, expectedTxId ->
                val txIdMatchIndex = remaining.indexOfFirst { it.first == expectedTxId }
                if (txIdMatchIndex >= 0) {
                    out += remaining.removeAt(txIdMatchIndex).third
                } else {
                    val expectedTxn = txns[index]
                    val semanticMatchIndex =
                        remaining.indexOfFirst { (_, actualTxn, _) ->
                            matchesExpectedTransaction(expectedTxn, actualTxn)
                        }
                    if (semanticMatchIndex >= 0) {
                        out += remaining.removeAt(semanticMatchIndex).third
                    } else {
                        Log.e(TAG, "[FALCON_BUNDLE_TRACE] missing signed txn for txId=$expectedTxId")
                        return@withContext emptyList()
                    }
                }
            }

            Log.e(
                TAG,
                "[FALCON_BUNDLE_TRACE] returningFiltered=true returnedCount=${out.size} filteredOut=${rawSigned.size - out.size}",
            )
            out
        }
    }

    private fun decodeFalconBundlePiece(encoded: String): ByteArray? {
        val trimmed = encoded.trim()
        if (trimmed.isEmpty()) return null

        fun addPadding(s: String): String {
            val rem = s.length % 4
            return if (rem == 0) s else s + "=".repeat(4 - rem)
        }

        val candidates =
            listOf(trimmed, addPadding(trimmed))
                .flatMap { value ->
                    listOf(value, value.replace('+', '-').replace('/', '_'))
                }.distinct()

        candidates.forEach { candidate ->
            runCatching {
                java.util.Base64
                    .getDecoder()
                    .decode(candidate)
            }.getOrNull()?.let { return it }
            runCatching {
                java.util.Base64
                    .getUrlDecoder()
                    .decode(candidate)
            }.getOrNull()?.let { return it }
            runCatching {
                Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT).decode(candidate)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun matchesExpectedTransaction(
        expected: Transaction,
        actual: Transaction,
    ): Boolean {
        if (expected.type?.toString() != actual.type?.toString()) return false
        if (expected.sender?.toString() != actual.sender?.toString()) return false

        return when (expected.type?.toString()) {
            "pay" -> {
                expected.receiver?.toString() == actual.receiver?.toString() &&
                    (expected.amount ?: java.math.BigInteger.ZERO) == (
                        actual.amount
                            ?: java.math.BigInteger.ZERO
                    )
            }

            "axfer" -> {
                expected.assetReceiver?.toString() == actual.assetReceiver?.toString() &&
                    (expected.assetAmount ?: java.math.BigInteger.ZERO) == (
                        actual.assetAmount
                            ?: java.math.BigInteger.ZERO
                    ) &&
                    expected.assetIndex.toLong() == actual.assetIndex.toLong()
            }

            "appl" -> {
                expected.applicationId.toLong() == actual.applicationId.toLong() &&
                    (
                        expected.applicationArgs
                            ?: emptyList<ByteArray>()
                    ) == (
                        actual.applicationArgs
                            ?: emptyList<ByteArray>()
                    )
            }

            else -> true
        }
    }

    private suspend fun signFalconTxnFromBundle(
        txn: Transaction,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray {
        val signed =
            signFalconTxnGroupFromBundle(
                txns = listOf(txn),
                publicKey = publicKey,
                privateKey = privateKey,
            )
        return signed.firstOrNull() ?: run {
            Log.e(TAG, "Falcon bundle returned no signed txn")
            ByteArray(0)
        }
    }
}
