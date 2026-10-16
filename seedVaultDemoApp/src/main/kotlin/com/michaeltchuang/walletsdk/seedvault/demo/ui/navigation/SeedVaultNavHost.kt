package com.michaeltchuang.walletsdk.seedvault.demo.ui.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.michaeltchuang.walletsdk.seedvault.demo.R
import com.michaeltchuang.walletsdk.seedvault.demo.ui.screens.AuthorizedAppsScreen
import com.michaeltchuang.walletsdk.seedvault.demo.ui.screens.SeedDetailsScreen
import com.michaeltchuang.walletsdk.seedvault.demo.ui.screens.SeedVaultSettingsScreen
import com.michaeltchuang.walletsdk.seedvault.demo.ui.viewmodel.SeedVaultViewModel
import kotlinx.serialization.Serializable

@Serializable
data object SeedVaultSettings

@Serializable
data class SeedDetails(
    val seedId: Long,
)

@Serializable
data class AuthorizedApps(
    val seedId: Long,
)

@Composable
fun SeedVaultNavHost(
    viewModel: SeedVaultViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Entries that have no screen yet (security, backup, seed phrase) only show a toast
    val showPlaceholder = {
        Toast.makeText(context, R.string.placeholder_not_built, Toast.LENGTH_SHORT).show()
    }

    NavHost(
        navController = navController,
        startDestination = SeedVaultSettings,
        modifier = modifier,
    ) {
        composable<SeedVaultSettings> {
            SeedVaultSettingsScreen(
                seeds = uiState.seeds,
                onBack = onExit,
                onSeedClick = { seed -> navController.navigate(SeedDetails(seed.id)) },
                onAddSeed = viewModel::addSeed,
                onSecurityClick = showPlaceholder,
            )
        }
        composable<SeedDetails> { entry ->
            val seedId = entry.toRoute<SeedDetails>().seedId
            val seed = uiState.seed(seedId) ?: return@composable
            SeedDetailsScreen(
                seed = seed,
                onBack = { navController.navigateUp() },
                onRename = { name -> viewModel.renameSeed(seedId, name) },
                onBackupClick = showPlaceholder,
                onViewSeedPhraseClick = showPlaceholder,
                onAuthorizedAppsClick = { navController.navigate(AuthorizedApps(seedId)) },
                onDelete = {
                    viewModel.deleteSeed(seedId)
                    navController.navigateUp()
                },
            )
        }
        composable<AuthorizedApps> { entry ->
            val seedId = entry.toRoute<AuthorizedApps>().seedId
            val seed = uiState.seed(seedId) ?: return@composable
            AuthorizedAppsScreen(
                apps = seed.authorizedApps,
                onBack = { navController.navigateUp() },
                onRevoke = { app -> viewModel.revokeApp(seedId, app.packageName) },
            )
        }
    }
}
