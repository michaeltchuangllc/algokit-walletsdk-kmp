package com.michaeltchuang.walletsdk.demo.ui.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.michaeltchuang.walletsdk.demo.ui.screens.AccountListScreen
import com.michaeltchuang.walletsdk.demo.ui.screens.BroadcastScreen
import com.michaeltchuang.walletsdk.demo.ui.screens.DiscoverScreen
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackBarLayout
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackbarViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI

@OptIn(KoinExperimentalAPI::class)
fun NavGraphBuilder.getBottomNavigationGraph(
    navController: NavController,
    snackbarHostState: SnackbarHostState,
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
        )
        SnackBarLayout(sharedViewModel, snackbarHostState)
    }
    composable<Discover> {
        val backStackEntry = remember(it) { navController.getBackStackEntry<Discover>() }
        val sharedViewModel: SnackbarViewModel = koinViewModel(viewModelStoreOwner = backStackEntry)
        DiscoverScreen(
            tag = backStackEntry.toRoute<Discover>().details.name,
            navController = navController,
            snackbarViewModel = sharedViewModel,
        )
        SnackBarLayout(sharedViewModel, snackbarHostState)
    }
}
