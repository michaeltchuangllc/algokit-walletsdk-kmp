package com.michaeltchuang.walletsdk.ui.base.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountMnemonic
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.REPO_URL
import com.michaeltchuang.walletsdk.ui.accountdetails.screens.AccountDetailScreen
import com.michaeltchuang.walletsdk.ui.accountdetails.screens.PassphraseAcknowledgeScreen
import com.michaeltchuang.walletsdk.ui.accountdetails.screens.ShowAddressScreen
import com.michaeltchuang.walletsdk.ui.accountdetails.screens.ViewPassphraseScreen
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.webview.AlgoKitWebViewPlatformScreen
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.LiquidAuthScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.AccountRecoveryTypeSelectionScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.AddressNamingScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.CreateAccountNameScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.CreateWatchAccountScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.Falcon24WalletSelectionScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.ImportSeedVaultAccountsScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.OnboardingAccountTypeScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.OnboardingIntroScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.RecoverAnAccountScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.RecoverRegisteredAccountsScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.RecoveryPhraseScreen
import com.michaeltchuang.walletsdk.ui.onboarding.screens.SelectSeedScreenPlatform
import com.michaeltchuang.walletsdk.ui.qrscanner.screens.QRCodeScannerScreen
import com.michaeltchuang.walletsdk.ui.settings.screens.DeveloperSettingsScreen
import com.michaeltchuang.walletsdk.ui.settings.screens.HdWalletSelectionScreen
import com.michaeltchuang.walletsdk.ui.settings.screens.LanguageScreen
import com.michaeltchuang.walletsdk.ui.settings.screens.NodeSettingsScreen
import com.michaeltchuang.walletsdk.ui.settings.screens.PasskeysScreen
import com.michaeltchuang.walletsdk.ui.settings.screens.EscrowSessionVaultDebugToolScreen
import com.michaeltchuang.walletsdk.ui.settings.screens.SettingsScreen
import com.michaeltchuang.walletsdk.ui.settings.screens.ThemeScreen
import com.michaeltchuang.walletsdk.ui.signing.screens.AddAssetScreen
import com.michaeltchuang.walletsdk.ui.signing.screens.AssetTransferConfirmScreen
import com.michaeltchuang.walletsdk.ui.signing.screens.ConfirmTransactionRequestScreen
import com.michaeltchuang.walletsdk.ui.signing.screens.SelectAccountScreen
import com.michaeltchuang.walletsdk.ui.signing.screens.SelectReceiverScreen
import com.michaeltchuang.walletsdk.ui.signing.screens.SendAlgoScreen
import com.michaeltchuang.walletsdk.ui.signing.screens.TransactingTipsScreen
import com.michaeltchuang.walletsdk.ui.signing.screens.TransactionSuccessScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class AlgoKitEvent {
    ALGO25_ACCOUNT_CREATED,
    CLOSE_BOTTOMSHEET,
    HD_ACCOUNT_CREATED,
}

enum class AlgoKitScreens {
    ACCOUNT_RECOVERY_TYPE_SCREEN,
    CREATE_ACCOUNT_NAME,
    CREATE_WATCH_ACCOUNT_SCREEN,
    ON_BOARDING_ACCOUNT_TYPE_SCREEN,
    HD_WALLET_SELECTION_SCREEN,
    INITIAL_REGISTER_INTRO_SCREEN,
    QR_CODE_SCANNER_SCREEN,
    QR_CODE_SCREEN,
    RECOVER_AN_ACCOUNT_SCREEN,
    RECOVER_PHRASE_SCREEN,
    RECOVER_REGISTERED_ACCOUNTS_SCREEN,
    SETTINGS_SCREEN,
    THEME_SCREEN,
    LANGUAGE_SCREEN,
    TRANSACTION_SIGNATURE_SCREEN,
    TRANSACTION_SUCCESS_SCREEN,
    WEBVIEW_PLATFORM_SCREEN,
    DEVELOPER_SETTINGS_SCREEN,
    ACCOUNT_STATUS_SCREEN,
    PASS_PHRASE_ACKNOWLEDGE_SCREEN,
    VIEW_PASSPHRASE_SCREEN,
    NODE_SETTINGS_SCREEN,
    FALCON24_WALLET_SELECTION_SCREEN,
    ASSET_TRANSFER_SCREEN,
    SELECT_ACCOUNT_SCREEN,
    SEND_ALGO_SCREEN,
    SELECT_RECEIVER_SCREEN,
    TRANSACTING_TIPS_SCREEN,
    ADDRESS_NAMING_SCREEN,
    PASSKEYS_SCREEN,
    LIQUID_AUTH_SCREEN,
    ADD_ASSET_SCREEN,
    SELECT_SEED_SCREEN,
    IMPORT_SEED_VAULT_ACCOUNTS_SCREEN,
    DEVELOPER_TEST_SCREEN,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnBoardingBottomSheet(
    accounts: Int,
    initialScreen: AlgoKitScreens? = null,
    address: String? = null,
    onAccountDeleted: () -> Unit,
    onAlgoKitEvent: (event: AlgoKitEvent) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = {
            scope.launch {
                scope
                    .async {
                        sheetState.hide()
                    }.await()
                onAlgoKitEvent(AlgoKitEvent.CLOSE_BOTTOMSHEET)
            }
        },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = AlgoKitTheme.colors.background,
        contentColor = AlgoKitTheme.colors.textMain,
    ) {
        NavigationBottomSheetNavHost(
            startDestination = startDestination(accounts, initialScreen),
            address = address,
            onAccountDeleted = {
                onAccountDeleted()
            },
            closeSheet = {
                scope.launch {
                    scope
                        .async {
                            sheetState.hide()
                        }.await()
                    onAlgoKitEvent(AlgoKitEvent.CLOSE_BOTTOMSHEET)
                }
            },
        ) {
            onAlgoKitEvent(AlgoKitEvent.ALGO25_ACCOUNT_CREATED)
        }
    }
}

@Composable
fun NavigationBottomSheetNavHost(
    startDestination: String = AlgoKitScreens.ON_BOARDING_ACCOUNT_TYPE_SCREEN.name,
    address: String?,
    closeSheet: () -> Unit,
    onAccountDeleted: () -> Unit,
    onFinish: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val navController = rememberNavController()

    Scaffold(
        modifier = Modifier.fillMaxHeight(.9f),
        containerColor = AlgoKitTheme.colors.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 100.dp),
                snackbar = { data ->
                    val isError = data.visuals.actionLabel == "ERROR"
                    val isSuccess = data.visuals.actionLabel == "SUCCESS"

                    Snackbar(
                        snackbarData = data,
                        containerColor =
                            when {
                                isError -> AlgoKitTheme.colors.snackbarError
                                isSuccess -> AlgoKitTheme.colors.snackbarSuccess
                                else -> AlgoKitTheme.colors.snackbarInfo
                            },
                        contentColor =
                            when {
                                isError -> AlgoKitTheme.colors.snackbarErrorText
                                isSuccess -> AlgoKitTheme.colors.snackbarSuccessText
                                else -> AlgoKitTheme.colors.snackbarInfoText
                            },
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(color = AlgoKitTheme.colors.background)
                    .padding(0.dp),
        ) {
            NavHost(
                navController,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                startDestination = startDestination,
            ) {
                composable(route = AlgoKitScreens.INITIAL_REGISTER_INTRO_SCREEN.name) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(color = AlgoKitTheme.colors.background)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        OnboardingIntroScreen(navController)
                    }
                }
                composable(AlgoKitScreens.ON_BOARDING_ACCOUNT_TYPE_SCREEN.name) {
                    OnboardingAccountTypeScreen(navController) { message ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                }
                composable(AlgoKitScreens.CREATE_ACCOUNT_NAME.name) {
                    CreateAccountNameScreen(
                        navController = navController,
                        showSnackBar = { message ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        onFinish = {
                            onFinish()
                        },
                    )
                }
                composable(AlgoKitScreens.HD_WALLET_SELECTION_SCREEN.name) {
                    HdWalletSelectionScreen(navController = navController)
                }
                composable(
                    route = AlgoKitScreens.CREATE_WATCH_ACCOUNT_SCREEN.name + "?scannedAddress={scannedAddress}",
                    arguments =
                        listOf(
                            navArgument("scannedAddress") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                ) { backStackEntry ->
                    val scannedAddress = backStackEntry.arguments?.getString("scannedAddress")
                    CreateWatchAccountScreen(
                        navController = navController,
                        showSnackBar = { message ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        scannedAddress = scannedAddress,
                    )
                }
                composable(AlgoKitScreens.FALCON24_WALLET_SELECTION_SCREEN.name) {
                    Falcon24WalletSelectionScreen(navController = navController)
                }
                composable(AlgoKitScreens.ACCOUNT_RECOVERY_TYPE_SCREEN.name) {
                    AccountRecoveryTypeSelectionScreen(navController = navController) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(it)
                        }
                    }
                }
                composable(
                    route = AlgoKitScreens.QR_CODE_SCANNER_SCREEN.name + "?isForWatchAccount={isForWatchAccount}",
                    arguments =
                        listOf(
                            navArgument("isForWatchAccount") {
                                type = NavType.BoolType
                                defaultValue = false
                            },
                        ),
                ) { backStackEntry ->
                    val isForWatchAccount =
                        backStackEntry.arguments?.getBoolean("isForWatchAccount") ?: false
                    QRCodeScannerScreen(
                        navController = navController,
                        onQrScanned = {
                            coroutineScope.launch { snackbarHostState.showSnackbar(it) }
                        },
                        closeSheet = { closeSheet() },
                        isForWatchAccount = isForWatchAccount,
                    )
                }
                composable(route = AlgoKitScreens.QR_CODE_SCREEN.name) {
                    address?.let { addr ->
                        ShowAddressScreen(
                            navController = navController,
                            address = addr,
                            showSnackBar = { message ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                        )
                    }
                }
                composable(
                    route = AlgoKitScreens.RECOVER_PHRASE_SCREEN.name + "/{accountType}?mnemonic={mnemonic}",
                    arguments =
                        listOf(
                            navArgument("accountType") {
                                type = NavType.StringType
                            },
                            navArgument("mnemonic") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                ) { backStackEntry ->
                    val accountTypeString =
                        backStackEntry.arguments?.getString("accountType", "falcon24")
                    val scannedMnemonic = backStackEntry.arguments?.getString("mnemonic", "") ?: ""

                    val accountType =
                        when {
                            scannedMnemonic.isNotEmpty() -> {
                                val wordCount = scannedMnemonic.trim().split("\\s+".toRegex()).size
                                when (wordCount) {
                                    25 -> AccountMnemonic.AccountType.Algo25
                                    else -> AccountMnemonic.AccountType.Falcon24 // 24 words default
                                }
                            }

                            accountTypeString == "algo25" -> AccountMnemonic.AccountType.Algo25
                            accountTypeString == "hdkey" -> AccountMnemonic.AccountType.HdKey
                            else -> AccountMnemonic.AccountType.Falcon24
                        }

                    RecoveryPhraseScreen(
                        navController = navController,
                        accountType = accountType,
                        mnemonicString = scannedMnemonic,
                    ) { message ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                }
                composable(route = AlgoKitScreens.RECOVER_AN_ACCOUNT_SCREEN.name) {
                    RecoverAnAccountScreen(navController = navController) { message, isError ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = message,
                                actionLabel = if (isError) "ERROR" else null,
                            )
                        }
                    }
                }
                composable(route = AlgoKitScreens.WEBVIEW_PLATFORM_SCREEN.name) {
                    AlgoKitWebViewPlatformScreen(url = REPO_URL)
                }
                composable(route = AlgoKitScreens.TRANSACTION_SIGNATURE_SCREEN.name) {
                    ConfirmTransactionRequestScreen(navController = navController) { message, isError ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = message,
                                actionLabel = if (isError) "ERROR" else null,
                            )
                        }
                    }
                }
                composable(
                    route = AlgoKitScreens.TRANSACTION_SUCCESS_SCREEN.name + "/?transactionId={transactionId}",
                    arguments =
                        listOf(
                            navArgument("transactionId") {
                                type = NavType.StringType
                            },
                        ),
                ) {
                    val transactionId = it.arguments?.getString("transactionId")
                    transactionId?.let {
                        TransactionSuccessScreen(transactionId = it) {
                            closeSheet()
                        }
                    }
                }
                composable(route = AlgoKitScreens.INITIAL_REGISTER_INTRO_SCREEN.name) {
                    OnboardingIntroScreen(navController)
                }
                composable(route = AlgoKitScreens.SETTINGS_SCREEN.name) {
                    SettingsScreen(navController)
                }
                composable(route = AlgoKitScreens.THEME_SCREEN.name) {
                    ThemeScreen(navController)
                }
                composable(route = AlgoKitScreens.LANGUAGE_SCREEN.name) {
                    LanguageScreen(navController)
                }
                composable(route = AlgoKitScreens.NODE_SETTINGS_SCREEN.name) {
                    NodeSettingsScreen(navController)
                }
                composable(route = AlgoKitScreens.DEVELOPER_SETTINGS_SCREEN.name) {
                    DeveloperSettingsScreen(navController) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(it)
                        }
                    }
                }
                composable(route = AlgoKitScreens.ACCOUNT_STATUS_SCREEN.name) {
                    address?.let { it1 ->
                        AccountDetailScreen(navController, it1, showSnackBar = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(it)
                            }
                        }) {
                            onAccountDeleted()
                        }
                    }
                }
                composable(route = AlgoKitScreens.PASS_PHRASE_ACKNOWLEDGE_SCREEN.name) {
                    address?.let {
                        PassphraseAcknowledgeScreen(navController, it)
                    }
                }
                composable(route = AlgoKitScreens.VIEW_PASSPHRASE_SCREEN.name) {
                    address?.let { it1 ->
                        ViewPassphraseScreen(navController, it1)
                    }
                }
                composable(
                    route =
                        AlgoKitScreens.ASSET_TRANSFER_SCREEN.name +
                            "?sender={sender}&receiver={receiver}&assetId={assetId}&amount={amount}&note={note}",
                    arguments =
                        listOf(
                            navArgument("sender") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                            navArgument("receiver") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                            navArgument("note") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                            navArgument("amount") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = "0" // in microalgos big integer
                            },
                            navArgument("assetId") {
                                type = NavType.LongType
                                nullable = false
                                defaultValue = -7L
                            },
                        ),
                ) { backStackEntry ->
                    val sender = backStackEntry.arguments?.getString("sender") ?: ""
                    val receiver = backStackEntry.arguments?.getString("receiver") ?: ""
                    val assetId = backStackEntry.arguments?.getLong("assetId") ?: -7L
                    val amount = backStackEntry.arguments?.getString("amount") ?: "0.00"
                    val note = backStackEntry.arguments?.getString("note") ?: ""
                    AssetTransferConfirmScreen(
                        navController = navController,
                        senderAddress = sender,
                        receiverAddress = receiver,
                        assetId = assetId,
                        note = note,
                        amount = amount,
                        closeSheet = closeSheet,
                    )
                }
                composable(
                    route =
                        AlgoKitScreens.SELECT_ACCOUNT_SCREEN.name +
                            "?assetId={assetId}&receiver={receiver}&amount={amount}&note={note}",
                    arguments =
                        listOf(
                            navArgument("assetId") {
                                type = NavType.LongType
                                nullable = false
                                defaultValue = -7L
                            },
                            navArgument("receiver") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                            navArgument("amount") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = "0" // in microalgos big integer
                            },
                            navArgument("note") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                        ),
                ) { backStackEntry ->
                    val assetId = backStackEntry.arguments?.getLong("assetId") ?: 0L
                    val receiver = backStackEntry.arguments?.getString("receiver") ?: ""
                    val amount = backStackEntry.arguments?.getString("amount") ?: "0.00"
                    val note = backStackEntry.arguments?.getString("note") ?: ""

                    SelectAccountScreen(
                        navController = navController,
                        assetId = assetId,
                        receiverAddress = receiver,
                        amount = amount,
                        note = note,
                    )
                }
                composable(
                    route = AlgoKitScreens.SEND_ALGO_SCREEN.name + "?sender={sender}&receiver={receiver}&amount={amount}&note={note}",
                    arguments =
                        listOf(
                            navArgument("sender") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                            navArgument("receiver") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                            navArgument("amount") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                            navArgument("note") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                        ),
                ) { backStackEntry ->
                    val sender = backStackEntry.arguments?.getString("sender") ?: ""

                    SendAlgoScreen(
                        navController = navController,
                        senderAddress = sender,
                    )
                }
                composable(
                    route = AlgoKitScreens.SELECT_RECEIVER_SCREEN.name + "?sender={sender}&amount={amount}&note={note}&assetId={assetId}",
                    arguments =
                        listOf(
                            navArgument("sender") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                            navArgument("amount") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = "0" // in microalgos big integer
                            },
                            navArgument("note") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                            navArgument("assetId") {
                                type = NavType.LongType
                                nullable = false
                                defaultValue = -7L
                            },
                        ),
                ) { backStackEntry ->
                    val sender = backStackEntry.arguments?.getString("sender") ?: ""
                    val amount = backStackEntry.arguments?.getString("amount") ?: "0.00"
                    val note = backStackEntry.arguments?.getString("note") ?: ""
                    val assetId = backStackEntry.arguments?.getLong("assetId") ?: -7L
                    SelectReceiverScreen(
                        navController = navController,
                        amount = amount,
                        note = note,
                        senderAddress = sender,
                        assetId = assetId,
                    )
                }
                composable(route = AlgoKitScreens.TRANSACTING_TIPS_SCREEN.name) {
                    TransactingTipsScreen(
                        onUnderstandClick = {
                            navController.popBackStack()
                        },
                    )
                }
                composable(route = AlgoKitScreens.RECOVER_REGISTERED_ACCOUNTS_SCREEN.name) {
                    RecoverRegisteredAccountsScreen(
                        navController = navController,
                        showSnackBar = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(it)
                            }
                        },
                        allAccountImported = onFinish,
                    )
                }

                composable(
                    route = AlgoKitScreens.ADDRESS_NAMING_SCREEN.name + "?address={address}",
                    arguments =
                        listOf(
                            navArgument("address") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                        ),
                ) { backStackEntry ->
                    val address = backStackEntry.arguments?.getString("address") ?: ""
                    AddressNamingScreen(
                        navController,
                        address,
                        showSnackBar = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(it)
                            }
                        },
                        onFinish,
                    )
                }
                composable(route = AlgoKitScreens.PASSKEYS_SCREEN.name) {
                    PasskeysScreen {
                        navController.popBackStack()
                    }
                }
                composable(
                    route = AlgoKitScreens.LIQUID_AUTH_SCREEN.name + "?uri={uri}",
                    arguments =
                        listOf(
                            navArgument("uri") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                ) { backStackEntry ->
                    val uri = backStackEntry.arguments?.getString("uri")
                    LiquidAuthScreen(
                        navController = navController,
                        uri = uri,
                        showSnackBar = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(it)
                            }
                        },
                        closeSheet = { closeSheet() },
                    )
                }
                composable(
                    route = AlgoKitScreens.ADD_ASSET_SCREEN.name + "?assetId={assetId}&accountAddress={accountAddress}",
                    arguments =
                        listOf(
                            navArgument("assetId") {
                                type = NavType.LongType
                                nullable = false
                                defaultValue = 0L
                            },
                            navArgument("accountAddress") {
                                type = NavType.StringType
                                nullable = false
                                defaultValue = ""
                            },
                        ),
                ) { backStackEntry ->
                    val assetId = backStackEntry.arguments?.getLong("assetId") ?: 0L
                    val accountAddress = backStackEntry.arguments?.getString("accountAddress") ?: ""
                    AddAssetScreen(
                        navController = navController,
                        assetId = assetId,
                        accountAddress = accountAddress,
                    )
                }
                composable(route = AlgoKitScreens.SELECT_SEED_SCREEN.name) {
                    SelectSeedScreenPlatform(navController = navController)
                }
                composable(
                    route = AlgoKitScreens.IMPORT_SEED_VAULT_ACCOUNTS_SCREEN.name + "?selectedSeedIds={selectedSeedIds}",
                    arguments =
                        listOf(
                            navArgument("selectedSeedIds") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                ) { backStackEntry ->
                    val selectedSeedIdsRaw = backStackEntry.arguments?.getString("selectedSeedIds")
                    val selectedSeedIds =
                        selectedSeedIdsRaw
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.toSet()
                            .orEmpty()
                    ImportSeedVaultAccountsScreen(
                        navController = navController,
                        selectedSeedIds = selectedSeedIds,
                        showSnackBar = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(it)
                            }
                        },
                        onAccountsImported = onFinish,
                    )
                }
                composable(route = AlgoKitScreens.DEVELOPER_TEST_SCREEN.name) {
                    EscrowSessionVaultDebugToolScreen(navController)
                }
            }
        }
    }
}

fun startDestination(
    accounts: Int,
    initialScreen: AlgoKitScreens? = null,
): String =
    when {
        initialScreen != null -> initialScreen.name
        accounts == 0 -> AlgoKitScreens.INITIAL_REGISTER_INTRO_SCREEN.name
        else -> AlgoKitScreens.ON_BOARDING_ACCOUNT_TYPE_SCREEN.name
    }
