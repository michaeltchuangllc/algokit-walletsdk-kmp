package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import android.content.Context
import android.content.ServiceConnection
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewModelScope
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonic
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.utils.date.TimeProvider
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService
import com.michaeltchuang.walletsdk.core.railmpp.core.PAYMENT_CHANNEL_LABEL
import com.michaeltchuang.walletsdk.core.railmpp.core.WebRtcDataChannel
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.LogAppSignatureUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ManageSignalServiceUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ProcessSignTransactionsUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ProvideHttpClientUseCase
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.AddNewPasskey
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.SetPasskeyLastUsedTime
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.AssertionIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.AttestationIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAttestationResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.PrepareAuthenticationUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.ProcessBiometricTransactionSigningUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.RegisterPasskeyUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage
import foundation.algorand.provider.Message
import foundation.algorand.provider.avm.models.SignTransactionsParams
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.DataChannel

actual class LiquidAuthPlatformServices(
    val addNewPasskey: AddNewPasskey,
    val passkeyRepository: PasskeyRepository,
    val setPasskeyLastUsedTime: SetPasskeyLastUsedTime,
    val getAccountMnemonic: GetAccountMnemonic,
    val timeProvider: TimeProvider,
    val processBiometricTransactionSigningUseCase: ProcessBiometricTransactionSigningUseCase,
    val registerPasskeyUseCase: RegisterPasskeyUseCase,
    val prepareAuthenticationUseCase: PrepareAuthenticationUseCase,
    val manageSignalServiceUseCase: ManageSignalServiceUseCase,
    val processSignTransactionsUseCase: ProcessSignTransactionsUseCase,
    val attestationIntentLauncherUseCase: AttestationIntentLauncherUseCase,
    val assertionIntentLauncherUseCase: AssertionIntentLauncherUseCase,
    val eventDelegate: EventDelegate<AnswerViewModel.ViewEvent>,
    val logAppSignatureUseCase: LogAppSignatureUseCase,
    val providerHttpClientUseCase: ProvideHttpClientUseCase,
) {
    companion object {
        private const val TAG = "LiquidAuthPlatformServices"
    }

    private var signalServiceConnection: ServiceConnection? = null
    private val _signalService = MutableStateFlow<SignalService?>(null)
    val signalService = _signalService

    val viewEvent get() = eventDelegate.viewEvent

    fun getProvideHttpClient(): OkHttpClient = providerHttpClientUseCase.invoke()

    fun logAppSignature(context: Context) {
        logAppSignatureUseCase(context, context.javaClass.simpleName)
    }

    fun bindSignalService(context: Context) {
        signalServiceConnection = manageSignalServiceUseCase(context) { _signalService.value = it }
    }

    fun unbindSignalService(
        context: Context,
        viewModel: AnswerViewModel,
    ) {
        viewModel.stopMppPaymentViewer()
        _signalService.value?.stop()
        signalServiceConnection?.let { manageSignalServiceUseCase.unbind(context, it) }
        signalServiceConnection = null
        _signalService.value = null
    }

    fun stopSignalService() {
        _signalService.value?.stop()
    }

    fun isHostPeerConnectionReady(service: SignalService?): Boolean = service?.peerConnection != null

    fun getOrCreateHostPaymentDataChannel(service: SignalService?): DataChannel? {
        if (!isHostPeerConnectionReady(service)) return null
        return service?.getDataChannel(PAYMENT_CHANNEL_LABEL) ?: service?.createDataChannel(PAYMENT_CHANNEL_LABEL)
    }

    fun createHostPaymentWebRtcDataChannel(dataChannel: DataChannel): WebRtcDataChannel = WebRtcDataChannel(dataChannel)

    fun sendHostMessage(
        service: SignalService?,
        message: String,
    ) {
        service?.send(message)
    }

    fun hostDataChannelState(service: SignalService?): String? = service?.dataChannel?.state()?.toString()

    fun isHostConnected(service: SignalService?): Boolean = hostDataChannelState(service) == "OPEN"

    fun onStreamTimeout(
        viewModel: AnswerViewModel,
        reason: String,
    ) {
        stopSignalService()
        viewModel.viewModelScope.launch {
            eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowToast(reason))
            eventDelegate.sendEvent(AnswerViewModel.ViewEvent.StreamDisconnected(reason))
        }
    }

    suspend fun saveCredential(
        account: String,
        credential: PublicKeyCredential,
        response: String,
    ) {
        val requestOption = PublicKeyCredentialCreationOptions(response)
        val credentialId = credential.rawId ?: return
        addNewPasskey(
            address = account,
            requestOptions = requestOption,
            credId = credentialId,
        )
        Napier.d(tag = TAG, message = "Credential saved to local storage")
        eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowToast("Credential saved to local storage"))
    }

    suspend fun getCredentialIdByAccountAddress(accountAddress: String): String? =
        passkeyRepository.getCredentialIdByAddress(accountAddress)

    suspend fun deleteCredentialByAccountAddress(accountAddress: String) {
        val credentialId = passkeyRepository.getCredentialIdByAddress(accountAddress)
        if (credentialId != null) {
            Napier.d(tag = TAG, message = "Deleting credential: $credentialId for address: $accountAddress")
            passkeyRepository.removePasskeyByCredentialId(credentialId)
        } else {
            Napier.w(tag = TAG, message = "No credential found to delete for address: $accountAddress")
        }
    }

    suspend fun getMnemonic(address: String): String? {
        var mnemonicValue: String? = null
        getAccountMnemonic(address).use(
            onSuccess = { mnemonic -> mnemonicValue = mnemonic.words.joinToString(" ") },
            onFailed = { _, _ -> return@use null },
        )
        return mnemonicValue
    }

    suspend fun processBiometricTransactionSigning(
        viewModel: AnswerViewModel,
        activity: FragmentActivity,
        params: SignTransactionsParams,
        message: Message,
    ) {
        when (
            val result =
                processBiometricTransactionSigningUseCase(
                    activity = activity,
                    viewModel = viewModel,
                    params = params,
                    message = message,
                )
        ) {
            is ProcessBiometricTransactionSigningUseCase.Result.Success -> {
                eventDelegate.sendEvent(
                    AnswerViewModel.ViewEvent.TransactionSigned(result.resultMessage, result.signResult),
                )
            }

            is ProcessBiometricTransactionSigningUseCase.Result.Cancelled ->
                eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowToast(result.reason))

            is ProcessBiometricTransactionSigningUseCase.Result.Error ->
                eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowError(result.message))
        }
    }

    suspend fun preparePasskeyRegistration(
        viewModel: AnswerViewModel,
        authMessage: AuthMessage,
        accountAddress: String,
        options: JSONObject = JSONObject(),
        onSessionUpdate: (String?) -> Unit = {},
    ): RegisterPasskeyUseCase.Result {
        val result =
            registerPasskeyUseCase(
                authMessage = authMessage,
                algoAddress = accountAddress,
                viewModel = viewModel,
                options = options,
                onSessionUpdate = onSessionUpdate,
            )
        when (result) {
            is RegisterPasskeyUseCase.Result.Success -> { /* NOOP: navigation handled by caller */ }
            is RegisterPasskeyUseCase.Result.Error ->
                eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowError(result.message))
        }
        return result
    }

    fun registerPasskey(
        viewModel: AnswerViewModel,
        authMessage: AuthMessage,
        accountAddress: String,
        options: JSONObject = JSONObject(),
    ) {
        viewModel.viewModelScope.launch {
            val result =
                preparePasskeyRegistration(
                    viewModel = viewModel,
                    authMessage = authMessage,
                    accountAddress = accountAddress,
                    options = options,
                    onSessionUpdate = { sessionId -> sessionId?.let { viewModel.setSession(it) } },
                )
            when (result) {
                is RegisterPasskeyUseCase.Result.Success -> {
                    viewModel.setAttestationApiResponse(result.attestationApiResponse)
                    eventDelegate.sendEvent(
                        AnswerViewModel.ViewEvent.RegistrationSuccess(result.pubKeyCredentialCreationOptions, accountAddress),
                    )
                }

                is RegisterPasskeyUseCase.Result.Error ->
                    eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowError(result.message))
            }
        }
    }

    suspend fun prepareAuthentication(
        viewModel: AnswerViewModel,
        authMessage: AuthMessage,
        credentialId: String,
        onSessionUpdate: (String?) -> Unit = {},
        onCredentialNotFound: () -> Unit = {},
    ): PrepareAuthenticationUseCase.Result =
        prepareAuthenticationUseCase(
            authMessage = authMessage,
            credentialId = credentialId,
            viewModel = viewModel,
            onSessionUpdate = onSessionUpdate,
            onCredentialNotFound = onCredentialNotFound,
        )

    fun authenticate(
        viewModel: AnswerViewModel,
        authMessage: AuthMessage,
        credentialId: String,
        setSession: ((String?) -> Unit)? = null,
        onCredentialNotFound: (() -> Unit)? = null,
    ) {
        viewModel.viewModelScope.launch {
            val result =
                prepareAuthentication(
                    viewModel = viewModel,
                    authMessage = authMessage,
                    credentialId = credentialId,
                    onSessionUpdate = { sessionId -> setSession?.invoke(sessionId) },
                    onCredentialNotFound = { onCredentialNotFound?.invoke() },
                )
            when (result) {
                is PrepareAuthenticationUseCase.Result.Success -> {
                    eventDelegate.sendEvent(
                        AnswerViewModel.ViewEvent.AuthenticationSuccess(result.publicKeyCredentialRequestOptions, credentialId),
                    )
                    setPasskeyLastUsedTime(credentialId, timeProvider.getCurrentTimeMillis())
                }

                is PrepareAuthenticationUseCase.Result.CredentialNotFound ->
                    eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowError(result.message))

                is PrepareAuthenticationUseCase.Result.Error ->
                    eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowError(result.message))
            }
        }
    }

    fun handleAssertionResultFromLauncher(
        viewModel: AnswerViewModel,
        result: HandleAssertionResultUseCase.Result,
    ) {
        viewModel.viewModelScope.launch {
            when (result) {
                is HandleAssertionResultUseCase.Result.Success -> {
                    eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowToast("Authentication Successful!"))
                    eventDelegate.sendEvent(AnswerViewModel.ViewEvent.AssertionSuccess(result.credential))
                }

                is HandleAssertionResultUseCase.Result.Cancelled ->
                    eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowToast(result.message))

                is HandleAssertionResultUseCase.Result.Error ->
                    eventDelegate.sendEvent(AnswerViewModel.ViewEvent.ShowError(result.message))
            }
        }
    }

    fun handleAttestationResultFromLauncher(
        viewModel: AnswerViewModel,
        result: HandleAttestationResultUseCase.Result,
        accountAddress: String?,
    ) {
        when (result) {
            is HandleAttestationResultUseCase.Result.Success -> {
                val apiResponse = viewModel.getAttestationApiResponse()
                if (accountAddress != null && apiResponse != null) {
                    viewModel.viewModelScope.launch {
                        saveCredential(accountAddress, result.credential, apiResponse)
                        eventDelegate.sendEvent(AnswerViewModel.ViewEvent.AttestationSuccess(result.credential))
                    }
                } else {
                    viewModel.viewModelScope.launch {
                        eventDelegate.sendEvent(
                            AnswerViewModel.ViewEvent.AttestationError("Missing account or API response for credential save"),
                        )
                    }
                }
            }

            is HandleAttestationResultUseCase.Result.Cancelled ->
                viewModel.viewModelScope.launch { eventDelegate.sendEvent(AnswerViewModel.ViewEvent.AttestationCancelled) }

            is HandleAttestationResultUseCase.Result.Error ->
                viewModel.viewModelScope.launch {
                    eventDelegate.sendEvent(AnswerViewModel.ViewEvent.AttestationError(result.message))
                }
        }
    }
}
