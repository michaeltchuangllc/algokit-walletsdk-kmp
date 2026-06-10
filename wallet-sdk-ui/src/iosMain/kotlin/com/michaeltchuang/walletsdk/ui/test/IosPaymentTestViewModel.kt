package com.michaeltchuang.walletsdk.ui.test

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25Transaction
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24GroupBundle
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24Transaction
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyTransaction
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments.TESTNET_ALGOD_URL
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS ViewModel for the Escrow Session Vault Debug Tool Screen.
 *
 * Provides the same operations as the Android [PaymentTestViewModel]:
 * 1. Deposit (open/top-up) a Session Vault.
 * 2. Fetch the remaining balance.
 * 3. Update a voucher on-chain.
 * 4. Verify a voucher signature.
 * 5. Settle the latest voucher.
 *
 * Signing uses the iOS-native expect/actual functions which delegate to the Swift bridge.
 */
class IosPaymentTestViewModel(
    private val getLocalAccount: GetLocalAccount,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getHdSeed: GetHdSeed,
) : ViewModel() {
    companion object {
        private const val TAG = "IosPaymentTestViewModel"
        private const val MICRO_USDC_MULTIPLIER = 1_000_000L
    }

    // ── Input fields ──────────────────────────────────────────────────────────

    private val _viewerAddress =
        MutableStateFlow(
            "2MFPDQMIMIYS6CCIRMNWB6IACQL6VCFRZE7STJBP4W5Q3FLHUJHIVP3FLY",
        )
    val viewerAddress: StateFlow<String> = _viewerAddress.asStateFlow()

    private val _creatorAddress =
        MutableStateFlow(
            "EBRI466FDKE2LKEPUDAYTIRZZ7LLKT7YMZ7TG37II6CCOAJK44SKXY7EHI",
        )
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
                val signer = buildWalletSigner(viewer)
                if (signer != null) {
                    val result =
                        withContext(Dispatchers.Default) {
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
                            Napier.d("[ADD_TO_VAULT_OK] txId=$txId", tag = TAG)
                        }.onFailure { err ->
                            showError("ADD_TO_VAULT_ERR", err)
                        }
                } else {
                    showError("ADD_TO_VAULT_NO_SIGNER", RuntimeException("Signer not found for $viewer"))
                }
            } catch (e: Exception) {
                showError("ADD_TO_VAULT_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Action 2: Fetch Session Vault remaining balance ───────────────────────

    fun fetchSessionVaultRemainingBalance() {
        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value = "Error: Viewer and Creator addresses are required."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Fetching Session Vault balance…"
            try {
                val signer = buildWalletSigner(viewer)
                val remaining =
                    withContext(Dispatchers.Default) {
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
                _statusMessage.value = "✅ Remaining balance: $usdc USDC\n($remaining microUSDC)"
                Napier.d("[FETCH_BALANCE_OK] remaining=$remaining", tag = TAG)
            } catch (e: Exception) {
                showError("FETCH_BALANCE_ERR", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Action 3: Update Voucher ──────────────────────────────────────────────

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
                val viewerSigner = buildWalletSigner(viewer)
                if (viewerSigner != null) {
                    val settleMessage =
                        MppPayments.settleMessage(
                            signer = viewerSigner,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            cumulativeAmountMicroUsdc = depositMicroUsdc,
                        )
                    val signature = viewerSigner.signMessage(settleMessage)

                    withContext(Dispatchers.Default) {
                        MppPayments.updateVoucherOnChain(
                            signer = viewerSigner,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            totalAmountUsedMicroUsdc = depositMicroUsdc,
                            signature = signature,
                        )
                    }.onSuccess { txId ->
                        Napier.d("[UPDATE_VOUCHER_OK] txId=$txId", tag = TAG)
                        _statusMessage.value = "✅ Voucher updated!\nTxId: $txId"
                    }.onFailure { err ->
                        showError("UPDATE_VOUCHER_ERR", err)
                    }
                } else {
                    showError("UPDATE_VOUCHER_NO_SIGNER", RuntimeException("Signer not found for $viewer"))
                }
            } catch (e: Exception) {
                showError("UPDATE_VOUCHER_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Action 4: Verify Voucher Signature ────────────────────────────────────

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
                val viewerSigner = buildWalletSigner(viewer)
                if (viewerSigner != null) {
                    val settleMessage =
                        MppPayments.settleMessage(
                            signer = viewerSigner,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            cumulativeAmountMicroUsdc = depositMicroUsdc,
                        )
                    val signature = viewerSigner.signMessage(settleMessage)

                    val result =
                        withContext(Dispatchers.Default) {
                            MppPayments.verifySettleSignature(
                                signer = viewerSigner,
                                viewerAddress = viewer,
                                hostAddress = creator,
                                cumulativeAmountMicroUsdc = depositMicroUsdc,
                                signature = signature,
                            )
                        }
                    result
                        .onSuccess {
                            _statusMessage.value = "✅ Signature verified!"
                            Napier.d("[VERIFY_SIGNATURE_OK]", tag = TAG)
                        }.onFailure { err ->
                            showError("VERIFY_SIGNATURE_ERR", err)
                        }
                } else {
                    showError("VERIFY_SIGNATURE_NO_SIGNER", RuntimeException("Signer not found for $viewer"))
                }
            } catch (e: Exception) {
                showError("VERIFY_SIGNATURE_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Action 5: Settle Amount ───────────────────────────────────────────────

    fun settleAmount() {
        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()
        val amountUsdc = _depositAmountUsdc.value.trim().toDoubleOrNull() ?: 1.0
        val requestedIncrementMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value = "Error: Viewer and Creator addresses are required."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                _statusMessage.value = "Preparing settlement..."

                val viewerSigner =
                    buildWalletSigner(viewer) ?: run {
                        showError("SETTLE_NO_VIEWER_SIGNER", RuntimeException("Signer not found for $viewer"))
                        return@launch
                    }

                // STEP 1: LOAD SESSION SNAPSHOT
                val snapshot =
                    withContext(Dispatchers.Default) {
                        MppPayments.getSessionProgressSnapshotFromVault(
                            viewerAddress = viewer,
                            hostAddress = creator,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                        )
                    } ?: run {
                        showError("SETTLE_NO_SNAPSHOT", RuntimeException("Session not found for viewer=$viewer creator=$creator"))
                        return@launch
                    }

                val totalDeposit = snapshot.totalDepositMicroUsdc
                val lastSettled = snapshot.lastSettledMicroUsdc
                val latestVoucher = snapshot.latestVoucherAmountMicroUsdc

                Napier.d(
                    "[SESSION_STATE] totalDeposit=$totalDeposit lastSettled=$lastSettled latestVoucher=$latestVoucher",
                    tag = TAG,
                )

                // STEP 2: COMPUTE NEW CUMULATIVE
                val newCumulative = latestVoucher + requestedIncrementMicroUsdc

                // STEP 3: VALIDATIONS
                if (newCumulative > totalDeposit) {
                    val depositUsdc = totalDeposit / 1_000_000.0
                    val requestedUsdc = newCumulative / 1_000_000.0
                    _statusMessage.value =
                        "❌ Voucher amount exceeds deposit." +
                        "\n\nDeposited: $depositUsdc USDC  |  Requested: $requestedUsdc USDC"
                    return@launch
                }

                if (newCumulative <= lastSettled) {
                    _statusMessage.value = "❌ Nothing new to settle."
                    return@launch
                }

                // STEP 4: BUILD SETTLE MESSAGE
                val settleMessage =
                    MppPayments.settleMessage(
                        signer = viewerSigner,
                        viewerAddress = viewer,
                        hostAddress = creator,
                        cumulativeAmountMicroUsdc = newCumulative,
                    )

                // STEP 5: SIGN MESSAGE
                val viewerSignature = viewerSigner.signMessage(settleMessage)
                Napier.d("[SIGNATURE_CREATED] sigLen=${viewerSignature.size}", tag = TAG)

                // STEP 6: RECORD VOUCHER ON-CHAIN
                _statusMessage.value = "Recording voucher on-chain…"
                val updateTxId =
                    withContext(Dispatchers.Default) {
                        MppPayments.updateVoucherOnChain(
                            signer = viewerSigner,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            totalAmountUsedMicroUsdc = newCumulative,
                            signature = viewerSignature,
                        )
                    }.getOrElse { err ->
                        showError("UPDATE_VOUCHER_ERR", err)
                        return@launch
                    }
                Napier.d("[UPDATE_VOUCHER_OK] txId=$updateTxId", tag = TAG)

                // STEP 7: SETTLE
                val creatorSigner =
                    buildWalletSigner(creator) ?: run {
                        showError("SETTLE_NO_CREATOR_SIGNER", RuntimeException("Signer not found for $creator"))
                        return@launch
                    }

                _statusMessage.value = "Settling to creator…"
                val settleResult =
                    withContext(Dispatchers.Default) {
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
                            "✅ Settlement successful\n\nTxId:\n$txId"
                        Napier.d("[SETTLE_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        showError("SETTLE_ERR", err)
                    }
            } catch (e: Exception) {
                showError("SETTLE_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun showError(
        logTag: String,
        cause: Throwable?,
    ) {
        val message = cause?.message ?: "Unknown error"
        _statusMessage.value = "❌ $message"
        Napier.e("[$logTag] $message", cause, tag = TAG)
    }

    /**
     * Builds a platform-agnostic [MppWalletSigner] for [address] using the iOS
     * signing bridges.  Returns null if the account is not found or not signable.
     */
    private suspend fun buildWalletSigner(address: String): MppWalletSigner? {
        val localAccount = getLocalAccount(address) ?: return null
        if (localAccount is LocalAccount.SeedVault) return null

        val authorizedSignerPublicKey: ByteArray =
            when (localAccount) {
                is LocalAccount.HdKey -> localAccount.publicKey
                is LocalAccount.Falcon24 -> localAccount.publicKey
                is LocalAccount.Algo25 -> {
                    val secretKey = getAlgo25SecretKey(address)
                    if (secretKey != null && secretKey.size == 64) {
                        secretKey.copyOfRange(32, 64)
                    } else {
                        ByteArray(0)
                    }
                }
                else -> ByteArray(0)
            }

        val signerType: Long = if (localAccount is LocalAccount.Falcon24) 1L else 0L

        return object : MppWalletSigner {
            override val address: String = address
            override val authorizedSignerPublicKey: ByteArray = authorizedSignerPublicKey
            override val signerType: Long = signerType

            override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray {
                return try {
                    when (localAccount) {
                        is LocalAccount.Algo25 -> {
                            val secretKey = getAlgo25SecretKey(address)
                            if (secretKey == null) {
                                Napier.e("Missing Algo25 key for $address", tag = TAG)
                                return ByteArray(0)
                            }
                            signAlgo25Transaction(
                                secretKey = secretKey,
                                transactionByteArray = txnMsgpack,
                            )
                        }
                        is LocalAccount.HdKey -> {
                            val seed = getHdSeed(localAccount.seedId)
                            if (seed == null) {
                                Napier.e("Missing HD seed for $address", tag = TAG)
                                return ByteArray(0)
                            }
                            signHdKeyTransaction(
                                transactionByteArray = txnMsgpack,
                                seed = seed,
                                account = localAccount.account,
                                change = localAccount.change,
                                key = localAccount.keyIndex,
                            ) ?: run {
                                Napier.e("HD signing failed for $address", tag = TAG)
                                ByteArray(0)
                            }
                        }
                        is LocalAccount.Falcon24 -> {
                            val secretKey = getFalcon24SecretKey(address)
                            if (secretKey == null) {
                                Napier.e("Missing Falcon24 key for $address", tag = TAG)
                                return ByteArray(0)
                            }
                            signFalcon24Transaction(
                                transactionByteArray = txnMsgpack,
                                publicKey = localAccount.publicKey,
                                privateKey = secretKey,
                            ) ?: run {
                                Napier.e("Falcon24 signing failed for $address", tag = TAG)
                                ByteArray(0)
                            }
                        }
                        else -> {
                            Napier.e("Unsupported account type for $address", tag = TAG)
                            ByteArray(0)
                        }
                    }
                } catch (t: Throwable) {
                    Napier.e("signTransactionBytes failed for $address: ${t.message}", t, tag = TAG)
                    ByteArray(0)
                }
            }

            /**
             * For Falcon24 signers with multiple transactions: use bundle signing so the Go SDK
             * can assign a single group ID and prepend the dummy transactions needed to satisfy
             * the AVM LogicSig verification budget (pool = 1000 bytes × txn count).
             * Without this, a 2-txn group only has a 2000-byte pool but two Falcon LogicSigs
             * total ~6073 bytes → Algorand rejects with "more than the available pool" error.
             */
            override suspend fun signTransactionsBytes(txnsMsgpack: List<ByteArray>): List<ByteArray> {
                if (localAccount !is LocalAccount.Falcon24 || txnsMsgpack.size <= 1) {
                    return super.signTransactionsBytes(txnsMsgpack)
                }
                return try {
                    val secretKey = getFalcon24SecretKey(address)
                    if (secretKey == null) {
                        Napier.e("Missing Falcon24 key for group bundle signing: $address", tag = TAG)
                        return txnsMsgpack.map { ByteArray(0) }
                    }
                    val result =
                        signFalcon24GroupBundle(
                            txnsByteArrays = txnsMsgpack,
                            publicKey = localAccount.publicKey,
                            privateKey = secretKey,
                        )
                    if (result.isEmpty()) {
                        Napier.e("Falcon24 group bundle returned empty for $address", tag = TAG)
                        txnsMsgpack.map { ByteArray(0) }
                    } else {
                        Napier.d("Falcon24 group bundle signed: ${result.size} txns (includes dummies)", tag = TAG)
                        result
                    }
                } catch (t: Throwable) {
                    Napier.e("signTransactionsBytes (Falcon bundle) failed: ${t.message}", t, tag = TAG)
                    txnsMsgpack.map { ByteArray(0) }
                }
            }

            override suspend fun signMessage(message: ByteArray): ByteArray {
                return try {
                    when (localAccount) {
                        is LocalAccount.Algo25 -> {
                            val secretKey = getAlgo25SecretKey(address)
                            if (secretKey == null) {
                                Napier.e("Missing Algo25 key for $address", tag = TAG)
                                return ByteArray(0)
                            }
                            signAlgo25ArbitraryData(data = message, secretKey = secretKey) ?: run {
                                Napier.e("Algo25 message signing failed for $address", tag = TAG)
                                ByteArray(0)
                            }
                        }
                        is LocalAccount.HdKey -> {
                            val seed = getHdSeed(localAccount.seedId)
                            if (seed == null) {
                                Napier.e("Missing HD seed for $address", tag = TAG)
                                return ByteArray(0)
                            }
                            signHdKeyArbitraryData(
                                data = message,
                                seed = seed,
                                account = localAccount.account,
                                change = localAccount.change,
                                key = localAccount.keyIndex,
                            ) ?: run {
                                Napier.e("HD message signing failed for $address", tag = TAG)
                                ByteArray(0)
                            }
                        }
                        is LocalAccount.Falcon24 -> {
                            val secretKey = getFalcon24SecretKey(address)
                            if (secretKey == null) {
                                Napier.e("Missing Falcon24 key for $address", tag = TAG)
                                return ByteArray(0)
                            }
                            signFalcon24ArbitraryData(
                                data = message,
                                publicKey = localAccount.publicKey,
                                privateKey = secretKey,
                            ) ?: run {
                                Napier.e("Falcon24 message signing failed for $address", tag = TAG)
                                ByteArray(0)
                            }
                        }
                        else -> {
                            Napier.e("Unsupported account type for message signing: $address", tag = TAG)
                            ByteArray(0)
                        }
                    }
                } catch (t: Throwable) {
                    Napier.e("signMessage failed for $address: ${t.message}", t, tag = TAG)
                    ByteArray(0)
                }
            }
        }
    }
}
