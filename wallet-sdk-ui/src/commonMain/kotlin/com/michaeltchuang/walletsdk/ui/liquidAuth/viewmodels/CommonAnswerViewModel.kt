package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountAlgoBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyData
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.utils.DataResource
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

open class CommonAnswerViewModel(
    private val getCurrentBlockUseCase: GetCurrentBlockUseCase,
    protected val getAccountAlgoBalance: GetAccountAlgoBalance,
    protected val getLocalAccount: GetLocalAccount,
    protected val getLocalAccounts: GetLocalAccounts,
    protected val getAlgo25SecretKey: GetAlgo25SecretKey,
    protected val getFalcon24SecretKey: GetFalcon24SecretKey,
    protected val getSeed: GetHdSeed,
) : LiquidAuthViewerStateHolder() {
    companion object {
        private const val TAG = "CommonAnswerViewModel"
    }

    // ── Current block number ───────────────────────────────────────────────────

    private val _currentBlockNumber = MutableStateFlow<Long?>(null)
    val currentBlockNumber: StateFlow<Long?> = _currentBlockNumber

    private var blockNumberPollingJob: Job? = null

    /**
     * Optional iOS-style stream-timeout callback.
     * Set this from the overlay to be notified when [onStreamTimeout] fires.
     * Android overrides [onStreamTimeout] directly; iOS uses this lambda hook.
     */
    var onTimeout: (() -> Unit)? = null

    override fun onStreamTimeout(reason: String) {
        onTimeout?.invoke()
    }

    // ── Balance ────────────────────────────────────────────────────────────────

    fun fetchAccountBalance() {
        viewModelScope.launch {
            try {
                val balance = getAccountAlgoBalance(accountAddress.value)
                setAccountBalance(balance?.toString())
                println("$TAG: fetched balance=${balance?.toString() ?: "0"}")
            } catch (e: Exception) {
                println("$TAG: exception fetching balance: ${e.message}")
            }
        }
    }

    // ── Account helpers ────────────────────────────────────────────────────────

    suspend fun getAvailableAccountAddresses(): List<String> = getLocalAccounts().map { it.address }.distinct()

    suspend fun resolveLocalAccount(address: String): LocalAccount? = getLocalAccount(address)

    suspend fun resolveAlgo25SecretKey(address: String): ByteArray? = getAlgo25SecretKey(address)

    suspend fun resolveFalcon24SecretKey(address: String): ByteArray? = getFalcon24SecretKey(address)

    suspend fun resolveSeed(seedId: Int): ByteArray? = getSeed(seedId)

    suspend fun isSeedVaultAccount(address: String): Boolean = getLocalAccount(address) is LocalAccount.SeedVault

    suspend fun getFee(): String {
        val localAccount = getLocalAccount(accountAddress.value)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> "0.004"
            else -> "0.001"
        }
    }

    suspend fun getAccountTypeForFido2(address: String): String {
        val localAccount = getLocalAccount(address)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> "falcon-1024"
            is LocalAccount.SeedVault -> "solana"
            else -> "algorand"
        }
    }

    suspend fun getAccountPublicKey(address: String): ByteArray {
        val localAccount = getLocalAccount(address)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> localAccount.publicKey
            is LocalAccount.HdKey -> localAccount.publicKey
            is LocalAccount.Algo25 -> {
                val secretKey = getAlgo25SecretKey(address)
                if (secretKey != null && secretKey.size == 64) {
                    // Last 32 bytes of the 64-byte expanded key are the public key.
                    secretKey.copyOfRange(32, 64)
                } else {
                    ByteArray(0)
                }
            }
            is LocalAccount.SeedVault -> {
                val decoded = decodeBase58(localAccount.publicKey)
                if (decoded == null || decoded.size != 32) {
                    Napier.e(
                        tag = TAG,
                        message = "Invalid SeedVault public key for address=${localAccount.address}, decodedLength=${decoded?.size}",
                    )
                    ByteArray(0)
                } else {
                    decoded
                }
            }
            else -> ByteArray(0)
        }
    }

    // ── FIDO-2 signing ──────────────────────────────────���──────────────────────

    /**
     * Signs a FIDO-2 challenge with the key material stored for [address].
     *
     * Delegates to the platform's expect/actual signing functions, so this works on
     * both Android and iOS without any platform-specific code here.
     */
    suspend fun signFido2Challenge(
        challenge: ByteArray,
        address: String,
    ): ByteArray? {
        println("$TAG: signFido2Challenge called for address=$address")
        val localAccount =
            getLocalAccount(address) ?: run {
                println("$TAG: getLocalAccount returned null for $address")
                return null
            }
        println("$TAG: localAccount type=${localAccount::class.simpleName}")

        return when (localAccount) {
            is LocalAccount.Algo25 -> {
                val secretKey =
                    getAlgo25SecretKey(address) ?: run {
                        println("$TAG: getAlgo25SecretKey returned null")
                        return null
                    }
                val result = signAlgo25ArbitraryData(challenge, secretKey)
                println("$TAG: signAlgo25ArbitraryData result=${result != null}")
                result
            }

            is LocalAccount.HdKey -> {
                val seed = getSeed(localAccount.seedId) ?: return null
                signHdKeyData(
                    data = challenge,
                    seed = seed,
                    account = localAccount.account,
                    change = localAccount.change,
                    key = localAccount.keyIndex,
                )
            }

            is LocalAccount.Falcon24 -> {
                val privateKey = getFalcon24SecretKey(address) ?: return null
                if (challenge.isEmpty() || localAccount.publicKey.isEmpty() || privateKey.isEmpty()) {
                    println("$TAG: signFido2Challenge skipped — empty input for Falcon24")
                    return null
                }
                try {
                    signFalcon24ArbitraryData(challenge, localAccount.publicKey, privateKey)
                } catch (t: Throwable) {
                    println("$TAG: signFalcon24ArbitraryData threw: ${t.message}")
                    null
                }
            }

            is LocalAccount.SeedVault -> {
                println("$TAG: SeedVault account — FIDO2 signing not supported")
                null
            }

            else -> null
        }
    }

    // ── Block number polling ───────────────────────────────────────────────────

    fun startRealtimeBlockNumberUpdates() {
        if (blockNumberPollingJob?.isActive == true) return
        blockNumberPollingJob =
            viewModelScope.launch {
                while (true) {
                    getCurrentBlockUseCase().collect { result ->
                        when (result) {
                            is DataResource.Success -> _currentBlockNumber.value = result.data
                            is DataResource.Error,
                            is DataResource.Loading,
                            -> Unit
                        }
                    }
                    delay(1000)
                }
            }
    }

    fun stopRealtimeBlockNumberUpdates() {
        blockNumberPollingJob?.cancel()
        blockNumberPollingJob = null
    }
}
