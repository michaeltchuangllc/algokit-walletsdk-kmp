package com.michaeltchuang.walletsdk.demo.ui.screens

import algokit_walletsdk_kmp.composedemoapp.generated.resources.Res
import algokit_walletsdk_kmp.composedemoapp.generated.resources.no_account_list
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.demo.ui.components.AccountItem
import com.michaeltchuang.walletsdk.demo.ui.components.LottieConfetti
import com.michaeltchuang.walletsdk.demo.ui.navigation.ACTIONS
import com.michaeltchuang.walletsdk.demo.ui.viewmodel.AccountListViewModel
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackbarViewModel
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitEvent
import com.michaeltchuang.walletsdk.ui.base.navigation.OnBoardingBottomSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private const val CONFETTI_DURATION = 5000L
private val FAB_PADDING = 32.dp
private const val TAG = "AccountListScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountListScreen(
    navController: NavController,
    snackbarViewModel: SnackbarViewModel,
    tag: String,
) {
    val viewModel: AccountListViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val events = viewModel.viewEvent.collectAsStateWithLifecycle(initialValue = null)
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showConfetti by remember { mutableStateOf(false) }
    var qrScanClick by rememberSaveable { mutableStateOf(false) }
    var settingsClick by rememberSaveable { mutableStateOf(false) }
    var onAccountItemClick by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    // val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Pull to refresh state
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ACTIONS.qrClickEvent.collect {
            qrScanClick = it
            settingsClick = false
            onAccountItemClick = false
            showSheet = it
        }
    }
    LaunchedEffect(Unit) {
        ACTIONS.settingsClickEvent.collect {
            qrScanClick = false
            onAccountItemClick = false
            settingsClick = it
            showSheet = it
        }
    }
    LaunchedEffect(Unit) {
        viewModel.fetchAccounts()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    settingsClick = false
                    qrScanClick = false
                    onAccountItemClick = false
                    showSheet = true
                },
                modifier = Modifier.padding(end = FAB_PADDING, bottom = FAB_PADDING),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Account")
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            modifier = Modifier.fillMaxSize(),
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    viewModel.fetchAccounts()
                    // Add a small delay to ensure smooth animation
                    delay(500)
                    isRefreshing = false
                }
            },
        ) {
            AccountListContent(
                state = state,
                padding = padding,
                onAccountItemClick = { it ->
                    scope.launch {
                        address = it
                        onAccountItemClick = true
                        showSheet = true
                    }
                },
            )
        }
    }

    OnBoardingBottomSheet(
        showSheet = showSheet,
        accounts = viewModel.accountLite.size,
        launchQRScanScreen = qrScanClick,
        launchSettingsScreen = settingsClick,
        launchAccountStatusScreen = onAccountItemClick,
        address = address,
        onAccountDeleted = {
            showSheet = false
            viewModel.fetchAccounts()
        },
    ) { event ->
        handleBottomSheetEvent(
            event = event,
            onCloseSheet = {
                showSheet = false
                // Refresh accounts to get latest data whenever bottom sheet closes
                scope.launch {
                    viewModel.fetchAccounts()
                }
            },
            onAccountCreated = {
                showConfetti = true
                showSheet = false
                scope.launch {
                    viewModel.fetchAccounts()
                }
            },
        )
    }

    if (showConfetti) {
        ConfettiEffect(
            onComplete = { showConfetti = false },
        )
    }
}

@Composable
private fun AccountListContent(
    state: AccountListViewModel.AccountsState,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onAccountItemClick: (String) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize(),
    ) {
        when (state) {
            AccountListViewModel.AccountsState.Idle -> {
                CenteredMessage("Ready to load accounts...")
            }

            AccountListViewModel.AccountsState.Loading -> {
                CenteredLoader()
            }

            is AccountListViewModel.AccountsState.Content -> {
                AccountsList(
                    accounts = state.accounts,
                    onAccountItemClick = onAccountItemClick,
                )
            }

            is AccountListViewModel.AccountsState.Error -> {
                print(state.message)
                CenteredMessage(
                    text = "Error: ${state.message}",
                    color = Color.Red,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    color: Color = Color.Unspecified,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun CenteredLoader() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AccountsList(
    accounts: List<AccountLite>,
    onAccountItemClick: (String) -> Unit,
) {
    if (accounts.isEmpty()) {
        CenteredMessage(stringResource(Res.string.no_account_list))
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                accounts,
            ) { account ->
                AccountItem(account) { address ->
                    onAccountItemClick(address)
                }
                Log.d(TAG, "Total accounts: ${accounts.size}")
            }
        }
    }
}

@Composable
private fun ConfettiEffect(onComplete: () -> Unit) {
    LottieConfetti(
        modifier =
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f),
    )

    LaunchedEffect(Unit) {
        delay(CONFETTI_DURATION)
        onComplete()
    }
}

private fun handleBottomSheetEvent(
    event: AlgoKitEvent,
    onCloseSheet: () -> Unit,
    onAccountCreated: () -> Unit,
) {
    when (event) {
        AlgoKitEvent.CLOSE_BOTTOMSHEET -> {
            onCloseSheet()
        }

        AlgoKitEvent.ALGO25_ACCOUNT_CREATED,
        AlgoKitEvent.HD_ACCOUNT_CREATED,
        -> {
            onAccountCreated()
        }
    }
}
