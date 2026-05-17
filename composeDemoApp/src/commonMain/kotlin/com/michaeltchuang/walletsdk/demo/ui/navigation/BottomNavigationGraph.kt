package com.michaeltchuang.walletsdk.demo.ui.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.michaeltchuang.walletsdk.demo.ui.screens.AccountListScreen
import com.michaeltchuang.walletsdk.demo.ui.screens.BroadcastScreen
import com.michaeltchuang.walletsdk.demo.ui.screens.PaymentTestScreen
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackBarLayout
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackbarViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.StreamHostUiMode
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI

@OptIn(KoinExperimentalAPI::class)
fun NavGraphBuilder.getBottomNavigationGraph(
    navController: NavController,
    snackbarHostState: SnackbarHostState,
    streamHostUiModeState: MutableState<StreamHostUiMode>,
    miniPlayerCameraPreviewState: MutableState<(@Composable () -> Unit)?>,
    miniPlayerOnCloseActionState: MutableState<(() -> Unit)?>,
) {
    composable<Accounts> {
        val backStackEntry = remember(it) { navController.getBackStackEntry<Accounts>() }
        val sharedViewModel: SnackbarViewModel = koinViewModel(viewModelStoreOwner = backStackEntry)
        AccountListScreen(
            tag = backStackEntry.toRoute<Accounts>().details.name,
            navController = navController,
            snackbarViewModel = sharedViewModel,
        )
        SnackBarLayout(sharedViewModel, snackbarHostState)
    }
    composable<Broadcast> {
        val backStackEntry = remember(it) { navController.getBackStackEntry<Broadcast>() }
        val sharedViewModel: SnackbarViewModel = koinViewModel(viewModelStoreOwner = backStackEntry)
        BroadcastScreen(
            tag = backStackEntry.toRoute<Broadcast>().details.name,
            navController = navController,
            snackbarViewModel = sharedViewModel,
            streamHostUiModeState = streamHostUiModeState,
            miniPlayerCameraPreviewState = miniPlayerCameraPreviewState,
            miniPlayerOnCloseActionState = miniPlayerOnCloseActionState,
        )
        SnackBarLayout(sharedViewModel, snackbarHostState)
    }
    composable<Discover> {
       /* val backStackEntry = remember(it) { navController.getBackStackEntry<Discover>() }
        val sharedViewModel: SnackbarViewModel = koinViewModel(viewModelStoreOwner = backStackEntry)
        DiscoverScreen(
            tag = backStackEntry.toRoute<Discover>().details.name,
            navController = navController,
            snackbarViewModel = sharedViewModel,
        )
        SnackBarLayout(sharedViewModel, snackbarHostState)*/
        PaymentTestScreen()
    }
}
