package com.michaeltchuang.walletsdk.ui.liquidAuth

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algorand.algosdk.account.Account
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.fasterxml.uuid.Generators
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonic
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyData
import com.michaeltchuang.walletsdk.core.foundation.utils.date.TimeProvider
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.AssertionApi
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.AddNewPasskey
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.SetPasskeyLastUsedTime
import foundation.algorand.auth.fido2.AttestationApi
import foundation.algorand.crypto.EncoderType
import foundation.algorand.crypto.avm.KeyPairs
import foundation.algorand.provider.Message
import foundation.algorand.provider.avm.models.RequestMessage
import foundation.algorand.provider.avm.models.ResponseMessage
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.KeyPair
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Demo View Model
 *
 * Minimal state to handle FIDO2 PublicKeyCredentials and Proof of Knowledge
 * Now includes AVMProvider logic directly
 */
class AnswerViewModel(
    private val addNewPasskey: AddNewPasskey,
    private val passkeyRepository: PasskeyRepository,
    private val setPasskeyLastUsedTime: SetPasskeyLastUsedTime,
    private val getAccountMnemonic: GetAccountMnemonic,
    private val timeProvider: TimeProvider,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getLocalAccount: GetLocalAccount,
    private val getSeed: GetHdSeed,
) : ViewModel() {
    companion object {
        private const val TAG = "AnswerViewModel"
    }

    // ==================== API Instances ====================
    private var attestationApi: AttestationApi? = null
    private var assertionApi: AssertionApi? = null

    // User Agent for API requests
    val userAgent: String by lazy {
        val applicationId = "com.michaeltchuang.walletsdk.demo"
        val versionName = "1.0"
        "$applicationId/$versionName (Android ${Build.VERSION.RELEASE}; ${Build.MODEL}; ${Build.BRAND})"
    }

    /**
     * Initialize API instances with the Activity's httpClient
     * This ensures cookies are properly maintained
     */
    fun initializeApis(
        attestationApi: AttestationApi,
        assertionApi: AssertionApi,
    ) {
        this.attestationApi = attestationApi
        this.assertionApi = assertionApi
    }

    // ==================== StateFlow ====================
    private val _session = MutableStateFlow("Logged Out")
    val session: StateFlow<String> = _session

    fun setSession(cookie: String?) {
        if (cookie !== null) {
            _session.value = cookie
        }
    }

    private val _message = MutableStateFlow<AuthMessage?>(null)
    val message: StateFlow<AuthMessage?> = _message

    fun setMessage(msg: AuthMessage?) {
        _message.value = msg
    }

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    fun setCount(i: Int) {
        _count.value = i
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setError(errorMessage: String?) {
        _error.value = errorMessage
    }

    fun clearError() {
        _error.value = null
    }

    // ==================== AVMProvider Logic ====================
    // Datachannel Provider/Handler
    private val uuidGenerator = Generators.timeBasedEpochRandomGenerator()

    // Must be unique to this provider
    private val providerId = uuidGenerator.generate().toString()

    // Account address for the provider - exposed as StateFlow for UI
    private val _accountAddress = MutableStateFlow("")
    val accountAddress: StateFlow<String> = _accountAddress

    // Encoder for message processing
    private val encoder =
        foundation.algorand.crypto.avm
            .Encoder()

    // KeyPair for signing
    private var keyPair: KeyPair? = null

    fun getProviderId(): String = providerId

    fun setAccountAddress(address: String) {
        _accountAddress.value = address
    }

    fun setKeyPair(keyPair: KeyPair) {
        this.keyPair = keyPair
    }

    // ==================== Credential Methods ====================
    suspend fun saveCredential(
        context: Context,
        account: String,
        credential: PublicKeyCredential,
        response: String,
    ) {
        val requestOption = PublicKeyCredentialCreationOptions(response)
        addNewPasskey(
            algoAddress = account,
            requestOptions = requestOption,
            credId = credential.rawId!!,
        )
    }

    suspend fun getCredentialId(origin: String): String? {
        val credentialId = passkeyRepository.getCredentialIdBySiteId(origin)
        return credentialId
    }

    suspend fun getCredentialIdByAlgoAddress(algoAddress: String): String? {
        val credentialId = passkeyRepository.getCredentialIdByAlgoAddress(algoAddress)
        return credentialId
    }

    suspend fun deleteCredentialByAlgoAddress(algoAddress: String) {
        val credentialId = passkeyRepository.getCredentialIdByAlgoAddress(algoAddress)
        if (credentialId != null) {
            Log.d(TAG, "Deleting credential: $credentialId for address: $algoAddress")
            passkeyRepository.removePasskeyByCredentialId(credentialId)
        } else {
            Log.w(TAG, "No credential found to delete for address: $algoAddress")
        }
    }

    fun getCredentialMessage(
        account: String,
        credential: PublicKeyCredential,
    ): JSONObject {
        val credMessage = JSONObject()
        credMessage.put("address", account)
        credMessage.put("device", Build.MODEL)
        credMessage.put("origin", message.value!!.origin)
        credMessage.put("id", credential.id)
        credMessage.put("prevCounter", count.value!!)
        credMessage.put("type", "credential")
        return credMessage
    }

    suspend fun getMnemonic(address: String): String? {
        var mnemonicValue: String? = null
        getAccountMnemonic(address).use(
            onSuccess = { mnemonic ->
                mnemonicValue = mnemonic.words.joinToString(" ")
            },
            onFailed = { _, _ -> return@use null },
        )
        return mnemonicValue
    }

    /**
     * Sign FIDO2 Challenge
     *
     * Signs the FIDO2 challenge with the Algorand account
     * This is used in the liquid FIDO2 extension
     */
    suspend fun signFido2Challenge(
        challenge: ByteArray,
        address: String,
    ): ByteArray? {
        val localAccount = getLocalAccount(address) ?: return null

        return when (localAccount) {
            is LocalAccount.Algo25 -> {
                val mnemonic = getMnemonic(address) ?: return null
                // Use KeyPairs.rawSignBytes for AVM-compatible signing (same as AnswerActivity)
                val keyPair = KeyPairs.getKeyPair(mnemonic)
                KeyPairs.rawSignBytes(challenge, keyPair.private)
            }
            is LocalAccount.HdKey -> {
                val seed = getSeed(localAccount.seedId) ?: return null
                // Use signHdKeyData for AVM-compatible signing without prefix
                signHdKeyData(challenge, seed, localAccount.account, localAccount.change, localAccount.keyIndex)
            }
            is LocalAccount.Falcon24 -> {
                // Falcon24 uses a different signing approach
                val privateKey = getFalcon24SecretKey(address) ?: return null
                signFalcon24ArbitraryData(challenge, localAccount.publicKey, privateKey)
            }
            else -> null
        }
    }

    /**
     * Get Account Type String for FIDO2
     *
     * Returns the appropriate type string for liquidExtJSON based on the account type
     * - "falcon-1024" for Falcon24 accounts
     * - "algorand" for all other account types (Algo25, HdKey, etc.)
     */
    suspend fun getAccountTypeForFido2(address: String): String {
        val localAccount = getLocalAccount(address)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> "falcon-1024"
            else -> "algorand"
        }
    }

    suspend fun getAccountPublicKey(address: String): ByteArray {
        val localAccount = getLocalAccount(address)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> localAccount.publicKey
            is LocalAccount.HdKey -> localAccount.publicKey
            is LocalAccount.Algo25 -> Account(getMnemonic(localAccount.algoAddress)).ed25519PublicKey.bytes
            else -> ByteArray(0)
        }
    }

    // ==================== AVMProvider Message Handling ====================

    /**
     * Decode Unsigned Transaction
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeUnsignedTransaction(unsignedTxn: String): Transaction? =
        Encoder.decodeFromMsgPack(Base64.decode(unsignedTxn), Transaction::class.java)

    /**xxx
     * Handle Messages from DataChannel
     *
     * Processes incoming messages and handles transaction signing requests
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun handleMessages(
        msgStr: String,
        onSignTransaction: (SignTransactionsParams, Message) -> Unit,
    ) {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "📨 RECEIVED MESSAGE FROM DATACHANNEL")
            Log.d(TAG, "Message length: ${msgStr.length}")
            Log.d(TAG, "========================================")

            val message = Message(Base64.UrlSafe.decode(msgStr), EncoderType.CBOR)
            val request = encoder.decode<RequestMessage>(message.data, message.encoding)

            Log.d(TAG, "Message decoded - Reference: ${request.reference}")
            Log.d(TAG, "Request ID: ${request.id}")

            if (request.reference == "arc0027:sign_transactions:request") {
                Log.d(TAG, "✅ Transaction signing request detected")
                viewModelScope.launch {
                    val params =
                        encoder.decode<SignTransactionsParams>(
                            encoder.encode(request.params, EncoderType.NONE),
                            EncoderType.NONE,
                        )
                    Log.d(TAG, "Decoded ${params.txns.size} transaction(s) from request")
                    Log.d(TAG, "Provider ID: ${params.providerId}")

                    // Callback to handle the transaction signing
                    onSignTransaction(params, message)
                }
            } else {
                Log.w(TAG, "⚠️ Unknown request reference: ${request.reference}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "❌ Error handling message: $e")
            e.printStackTrace()
        }
    }

    /**
     * Handle Message
     *
     * Processes incoming messages and returns appropriate response
     */
    fun handleMessage(message: Message): Any {
        val decoded = encoder.decode<RequestMessage>(message.data, message.encoding)
        when (decoded.reference) {
            "arc0027:sign_transactions:request" -> {
                val params =
                    encoder.decode<SignTransactionsParams>(
                        encoder.encode(decoded.params, EncoderType.NONE),
                        EncoderType.NONE,
                    )
                // Note: processSignTransactions is now suspend, but handleMessage is not
                // This will need to be called from a coroutine context
                val result =
                    kotlinx.coroutines.runBlocking {
                        processSignTransactions(params)
                    }
                return ResponseMessage(
                    id = uuidGenerator.generate().toString(),
                    reference = "arc0027:sign_transactions:response",
                    requestId = decoded.id,
                    result = result,
                )
            }

            else -> {
                throw IllegalArgumentException("Invalid reference: ${decoded.reference}")
            }
        }
    }

    /**
     * Process ARC27 Sign Transactions Requests
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun processSignTransactions(params: SignTransactionsParams): SignTransactionsResult {
        Log.d(TAG, "========================================")
        Log.d(TAG, "📝 PROCESSING SIGN TRANSACTIONS")
        Log.d(TAG, "Number of transactions: ${params.txns.size}")
        Log.d(TAG, "Provider ID: $providerId")
        Log.d(TAG, "Account Address: ${_accountAddress.value}")
        Log.d(TAG, "========================================")
        require(params.validate())

        val currentAccountAddress = _accountAddress.value

        val signedTxns = mutableListOf<String>()
        // val txnIds = mutableListOf<String>()
        params.txns.forEachIndexed { index, txn ->
            Log.d(TAG, "Signing transaction ${index + 1}/${params.txns.size}")
            val transactionBytes =
                Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(txn.txn!!)
            val unsignedTransaction = decodeUnsignedTransaction(Base64.encode(transactionBytes))
            // val inst = decodeUnsignedTransaction(Base64.encode(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(txn.txn!!)))
            // Sign the transaction using the secret key
            when (val it = getLocalAccount(currentAccountAddress)) {
                is LocalAccount.Algo25 -> {
                    // Get the secret key using the provided function
                    val secretKey = getAlgo25SecretKey.invoke(currentAccountAddress)
                    Log.d("AnswerViewModel", "Algo25 secret key: ${secretKey?.toHexString()}")

                    if (secretKey == null) {
                        throw IllegalArgumentException("Secret key not found for address: $currentAccountAddress")
                    }
                    // Return just the signature (not the full signed transaction)
                    val keyPair = KeyPairs.getKeyPair(getMnemonic(accountAddress.value)!!)
                    val signature = KeyPairs.rawSignBytes(unsignedTransaction!!.bytesToSign(), keyPair.private)
                    signedTxns.add(Base64.UrlSafe.encode(signature!!))
                }
                is LocalAccount.Falcon24 -> {
                    // Falcon24 needs custom format: msgpack-encoded { "txn": <transaction>, "sig": <signature> }
                    Log.d(TAG, "Signing with Falcon24...")
                    val privateKey = getFalcon24SecretKey(currentAccountAddress)
                    val signedGroupBytes =
                        signFalcon24ArbitraryData(unsignedTransaction!!.bytesToSign(), it.publicKey, privateKey!!)!!

                   /* Log.d(TAG, "Falcon24 signature length: ${signedGroupBytes.size} bytes")
                    Log.d(TAG, "Transaction bytes length: ${transactionBytes.size} bytes")

                    val base64SignedGroup = Base64.UrlSafe.encode(signedGroupBytes)*/
                    val base64SignedGroup = Base64.UrlSafe.encode(signedGroupBytes)
                    signedTxns.add(base64SignedGroup)
                }

//                is LocalAccount.Falcon24 -> {
//                    // Falcon24 needs custom format: msgpack-encoded { "txn": <transaction>, "sig": <signature> }
//                    Log.d(TAG, "Signing with Falcon24...")
//                    val privateKey = getFalcon24SecretKey(currentAccountAddress)
//                    val signature =
//                        signFalcon24ArbitraryData(unsignedTransaction!!.bytesToSign(), it.publicKey, privateKey!!)!!
//
//                    Log.d(TAG, "Falcon24 signature length: ${signature.size} bytes")
//                    Log.d(TAG, "Transaction bytes length: ${transactionBytes.size} bytes")
//
//                    // Create the custom signed transaction format for Falcon24
//                    val signedTxnMap = mapOf(
//                        "txn" to transactionBytes,  // Original transaction bytes
//                        "sig" to signature           // Falcon signature
//                    )
//
//                    // Encode to msgpack
//                    val signedTxnBytes = Encoder.encodeToMsgPack(signedTxnMap)
//                    Log.d(TAG, "Falcon24 signed transaction (msgpack) length: ${signedTxnBytes.size} bytes")
//                    signedTxns.add(Base64.UrlSafe.encode(signedTxnBytes))
//                }

                is LocalAccount.HdKey -> {
                    // Return just the signature (not the full signed transaction)
                    val signature =
                        signHdKeyData(
                            data = unsignedTransaction!!.bytesToSign(),
                            seed = getSeed(it.seedId)!!,
                            account = it.account,
                            change = it.change,
                            key = it.keyIndex,
                        )!!

                    signedTxns.add(Base64.UrlSafe.encode(signature))
                }

                is LocalAccount.LedgerBle -> TODO()
                is LocalAccount.NoAuth -> TODO()
                null -> TODO()
            }

            // txnIds.add(unsignedTransaction!!.txID())
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "✅ TRANSACTION SIGNING COMPLETE")
        Log.d(TAG, "Successfully signed ${signedTxns.size} transaction(s)")
        Log.d(TAG, "========================================")

        // Create the response payload
        return SignTransactionsResult(providerId, signedTxns)
    }

    /**
     * Get KeyPair from Mnemonic
     *
     * Generates a KeyPair from the provided mnemonic
     */
    fun getKeyPairFromMnemonic(mnemonic: String): KeyPair = KeyPairs.getKeyPair(mnemonic)

    // ==================== Liquid Auth API Methods ====================

    /**
     * Extension function to convert OkHttp Call to suspend function
     */
    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancel()
            }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resume(response)
                    }
                },
            )
        }

    /**
     * Post Attestation Options
     *
     * Retrieves PublicKeyCredentialCreationOptions from the FIDO2 server
     */
    suspend fun fetchAttestationOptions(
        origin: String,
        userAgent: String,
        options: JSONObject = JSONObject(),
    ): Response = attestationApi!!.postAttestationOptions(origin, userAgent, options).await()

    /**
     * Post Attestation Result
     *
     * Submits the PublicKeyCredential to the FIDO2 server after registration
     */
    suspend fun submitAttestationResult(
        origin: String,
        userAgent: String,
        credential: PublicKeyCredential,
        liquidExt: JSONObject? = null,
    ): Response = attestationApi!!.postAttestationResult(origin, userAgent, credential, liquidExt).await()

    /**
     * Post Assertion Options
     *
     * Retrieves PublicKeyCredentialRequestOptions from the FIDO2 server
     */
    suspend fun fetchAssertionOptions(
        origin: String,
        userAgent: String,
        credentialId: String,
        liquidExt: Boolean? = true,
    ): Response = assertionApi!!.postAssertionOptions(origin, userAgent, credentialId, liquidExt).await()

    /**
     * Post Assertion Result
     *
     * Submits the PublicKeyCredential to the FIDO2 server after authentication
     */
    suspend fun submitAssertionResult(
        origin: String,
        userAgent: String,
        credential: PublicKeyCredential,
        liquidExt: JSONObject?,
    ): Response = assertionApi!!.postAssertionResult(origin, userAgent, credential, liquidExt).await()
}
