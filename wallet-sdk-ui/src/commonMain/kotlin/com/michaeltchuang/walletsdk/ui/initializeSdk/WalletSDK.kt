package com.michaeltchuang.walletsdk.ui.initializeSdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetAccountASABalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.NameRegistrationUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetBasicAccountInformationUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetSolanaBalancesUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.SyncSolanaAccountsFromSeedVaultUseCase
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.base.di.walletSdkUiModules
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitEvent
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.base.navigation.OnBoardingBottomSheet
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform.getKoin

/**
 * State holder for the AlgoKit Wallet bottom sheet.
 *
 * This class manages the visibility and configuration of the wallet bottom sheet.
 */
@Stable
class WalletBottomSheetState {
    var isVisible by mutableStateOf(false)
        private set

    var initialScreen by mutableStateOf<AlgoKitScreens?>(null)
        private set

    var address by mutableStateOf<String?>(null)
        private set

    /**
     * Show the wallet bottom sheet.
     *
     * @param initialScreen Optional specific screen to open directly (e.g., AlgoKitScreens.QR_CODE_SCANNER_SCREEN)
     * @param address Optional account address to view details for (required if initialScreen is ACCOUNT_STATUS_SCREEN)
     */
    fun show(
        initialScreen: AlgoKitScreens? = null,
        address: String? = null,
    ) {
        this.initialScreen = initialScreen
        this.address = address
        isVisible = true
    }

    /**
     * Hide the wallet bottom sheet.
     */
    fun hide() {
        isVisible = false
        initialScreen = null
        address = null
    }

    companion object {
        val Saver: Saver<WalletBottomSheetState, Boolean> =
            Saver(
                save = { it.isVisible },
                restore = { WalletBottomSheetState().apply { isVisible = it } },
            )
    }
}

/**
 * Remember a [WalletBottomSheetState] across recompositions.
 */
@Composable
fun rememberWalletSDKBottomSheetState(): WalletBottomSheetState =
    rememberSaveable(saver = WalletBottomSheetState.Saver) {
        WalletBottomSheetState()
    }

/**
 * WalletSDK initialization object.
 *
 * This object provides a centralized way to initialize all Koin modules
 * from both wallet-sdk-core and wallet-sdk-ui modules, and provides
 * a clean API for account and network management operations.
 */
object WalletSDK {
    private val nameRegistrationUseCase: NameRegistrationUseCase
        get() = getKoin().get()

    private val getBasicAccountInformationUseCase: GetBasicAccountInformationUseCase
        get() = getKoin().get()

    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase
        get() = getKoin().get()

    private val getSolanaBalancesUseCase: GetSolanaBalancesUseCase
        get() = getKoin().get()

    private val getAccountASABalanceUseCase: GetAccountASABalance
        get() = getKoin().get()

    private val syncSolanaAccountsFromSeedVaultUseCase: SyncSolanaAccountsFromSeedVaultUseCase
        get() = getKoin().get()

    /**
     * Note: WalletSDK.initialize() must be called BEFORE this composable is rendered:
     * - Android: In AndroidApp.onCreate()
     * - iOS: In MainViewController()
     */
    fun initialize(
        context: Any? = null,
        enableLogging: Boolean = false,
    ) {
        val modules = walletSdkUiModules

        // Check if Koin is already started
        val isKoinStarted = runCatching { getKoin() }.isSuccess

        if (isKoinStarted) {
            // Koin is already running, just load our modules
            loadKoinModules(modules)
        } else {
            // Koin not started yet, start it with our configuration
            startKoin {
                // Apply platform-specific configuration
                if (context != null) {
                    platformConfiguration(context, enableLogging)
                }

                // walletSdkUiModules already includes walletSdkCoreModules
                modules(modules)
            }
        }
    }

    suspend fun getAccountsWithBalances(): List<AccountLite> {
        val accounts = nameRegistrationUseCase.getAccountLite()
        return coroutineScope {
            accounts
                .map { account ->
                    async {
                        val accountInfo = getBasicAccountInformationUseCase(account.address)
                        account.copy(balance = accountInfo?.amount ?: "0")
                    }
                }.awaitAll()
        }
    }

    suspend fun deleteAccount(address: String) {
        nameRegistrationUseCase.deleteAccount(address)
    }

    suspend fun getSolanaBalances(addresses: List<String>): Map<String, String?> =
        getSolanaBalancesUseCase(addresses)

    suspend fun getSolanaUsdcBalances(addresses: List<String>): Map<String, String?> =
        getSolanaBalancesUseCase
            .getUsdcBalances(addresses)
            .mapValues { (_, balance) -> balance?.toString() }

    suspend fun getAccountASABalance(
        address: String,
        assetId: Long,
    ): String? = getAccountASABalanceUseCase(address, assetId)?.toString()

    suspend fun syncSolanaAccountsFromSeedVault() {
        syncSolanaAccountsFromSeedVaultUseCase()
    }

    fun getCurrentNetwork(): Flow<AlgorandNetwork> = getCurrentNetworkUseCase()

    /**
     * Composable function to show the AlgoKit wallet bottom sheet.
     *
     * @param state The state of the bottom sheet
     * @param onAccountDeleted Callback for when an account is deleted
     * @param onDismiss Callback for when the bottom sheet is dismissed
     * @param onAccountCreated Callback for when an account is created
     *
     * This composable will show the bottom sheet when the state is visible, and handle
     * the events for account deletion and creation.functions
     */
    @Composable
    fun ShowAlgoKitWalletBottomSheet(
        state: WalletBottomSheetState,
        onAccountDeleted: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onAccountCreated: () -> Unit = {},
    ) {
        // Get current account count
        val accountCount = remember { mutableStateOf(0) }

        // Fetch account count when sheet becomes visible
        LaunchedEffect(state.isVisible) {
            if (state.isVisible) {
                accountCount.value = nameRegistrationUseCase.getAccountLite().size
            }
        }

        if (state.isVisible) {
            OnBoardingBottomSheet(
                accounts = accountCount.value,
                initialScreen = state.initialScreen,
                address = state.address,
                onAccountDeleted = {
                    onAccountDeleted()
                    state.hide()
                    onDismiss()
                },
                onAlgoKitEvent = { event ->
                    when (event) {
                        AlgoKitEvent.CLOSE_BOTTOMSHEET -> {
                            state.hide()
                            onDismiss()
                        }

                        AlgoKitEvent.ALGO25_ACCOUNT_CREATED,
                        AlgoKitEvent.HD_ACCOUNT_CREATED,
                            -> {
                            onAccountCreated()
                            state.hide()
                            onDismiss()
                        }
                    }
                },
            )
        }
    }
}
