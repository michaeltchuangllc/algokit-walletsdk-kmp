package com.michaeltchuang.walletsdk.demo.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.demo.ui.components.ConnectionStatusBar
import com.michaeltchuang.walletsdk.demo.ui.components.NetworkStatusBar
import com.michaeltchuang.walletsdk.demo.ui.viewmodel.AppViewModel
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.AnswerScreenOverlay
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.LiquidAuthMiniPlayerOverlay
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.StreamHostUiMode
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AppNavigation() {
    koinViewModel<AppViewModel>()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val isBottomSheetVisible = remember { mutableStateOf(false) }
    val streamHostUiModeState = remember { mutableStateOf(StreamHostUiMode.Hidden) }
    val miniPlayerCameraPreviewState = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val miniPlayerOnCloseActionState = remember { mutableStateOf<(() -> Unit)?>(null) }
    val currentRoute =
        navController
            .currentBackStackEntryAsState()
            .value
            ?.destination
            ?.route
    val showTopBarActions = currentRoute != Broadcast::class.qualifiedName

    Box(
        modifier =
            Modifier
                .fillMaxSize(),
    ) {
        Scaffold(
            modifier =
                Modifier
                    .background(color = AlgoKitTheme.colors.background)
                    .fillMaxSize(),
            topBar = {
                Column {
                    TopBar(showActions = showTopBarActions)
                    NetworkStatusBar()
                    ConnectionStatusBar()
                }
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState, modifier = Modifier)
            },
            bottomBar = {
                AppNavigationBar(navController) {
                    isBottomSheetVisible.value = true
                }
            },
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = Accounts,
                modifier = Modifier.padding(paddingValues = paddingValues),
            ) {
                getBottomNavigationGraph(
                    navController = navController,
                    snackbarHostState = snackbarHostState,
                    streamHostUiModeState = streamHostUiModeState,
                    miniPlayerCameraPreviewState = miniPlayerCameraPreviewState,
                    miniPlayerOnCloseActionState = miniPlayerOnCloseActionState,
                )
            }
        }

        LiquidAuthMiniPlayerOverlay(
            streamHostUiModeState = streamHostUiModeState,
            miniPlayerCameraPreviewState = miniPlayerCameraPreviewState,
            onClose = {
                miniPlayerOnCloseActionState.value?.invoke() ?: run {
                    streamHostUiModeState.value = StreamHostUiMode.Hidden
                }
            },
        )

        AnswerScreenOverlay()
    }
}
