package com.michaeltchuang.seedvault.service.ui.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.michaeltchuang.seedvault.service.R
import com.michaeltchuang.seedvault.service.ui.components.SecureScreen
import com.michaeltchuang.seedvault.service.ui.screens.AddSeedScreen
import com.michaeltchuang.seedvault.service.ui.screens.AuthorizeAppScreen
import com.michaeltchuang.seedvault.service.ui.screens.AuthorizedAppsScreen
import com.michaeltchuang.seedvault.service.ui.screens.ConfirmSignScreen
import com.michaeltchuang.seedvault.service.ui.screens.ImportSeedScreen
import com.michaeltchuang.seedvault.service.ui.screens.RevealSeedPhraseScreen
import com.michaeltchuang.seedvault.service.ui.screens.SecurityScreen
import com.michaeltchuang.seedvault.service.ui.screens.SeedDetailsScreen
import com.michaeltchuang.seedvault.service.ui.screens.SeedPhraseScreen
import com.michaeltchuang.seedvault.service.ui.screens.SeedVaultSettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object SeedVaultSettings

@Serializable
data object AddSeed

@Serializable
data object ImportSeed

@Serializable
data class SeedDetails(
    val seedId: Long,
)

@Serializable
data class AuthorizedApps(
    val seedId: Long,
)

@Serializable
data class RevealSeedPhrase(
    val seedId: Long,
)

@Serializable
data class SeedPhrase(
    val seedId: Long,
)

@Serializable
data object Security

/** Start destination when an app asked to be authorized (AUTHORIZE_APP intent). */
@Serializable
data class AuthorizeApp(
    val appPackageName: String,
    val appLabel: String,
)

/** Start destination when the service needs the user to approve a signature. */
@Serializable
data class ConfirmSign(
    val requestId: String,
)

/**
 * Every screen gets its own ViewModel scoped to its back stack entry, so the seed phrase screens
 * drop the words from memory as soon as they are popped.
 */
@Composable
fun SeedVaultNavHost(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    startDestination: Any = SeedVaultSettings,
    onFlowFinished: (approved: Boolean) -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    // Entries that have no screen yet (security, backup) only show a toast
    val showPlaceholder = {
        Toast.makeText(context, R.string.placeholder_not_built, Toast.LENGTH_SHORT).show()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<SeedVaultSettings> {
            SeedVaultSettingsScreen(
                onBack = onExit,
                onSeedClick = { seed -> navController.navigate(SeedDetails(seed.id)) },
                onAddSeed = { navController.navigate(AddSeed) },
                onImportSeed = { navController.navigate(ImportSeed) },
                onSecurityClick = { navController.navigate(Security) },
            )
        }
        composable<Security> {
            SecurityScreen(onBack = { navController.navigateUp() })
        }
        composable<AuthorizeApp> { entry ->
            val route = entry.toRoute<AuthorizeApp>()
            SecureScreen()
            AuthorizeAppScreen(
                appPackageName = route.appPackageName,
                appLabel = route.appLabel,
                onAllowed = { onFlowFinished(true) },
                onDeny = { onFlowFinished(false) },
            )
        }
        composable<ConfirmSign> { entry ->
            SecureScreen()
            ConfirmSignScreen(
                requestId = entry.toRoute<ConfirmSign>().requestId,
                onGone = onFlowFinished,
            )
        }
        composable<AddSeed> {
            AddSeedScreen(onBack = { navController.navigateUp() })
        }
        composable<ImportSeed> {
            ImportSeedScreen(onBack = { navController.navigateUp() })
        }
        composable<SeedDetails> { entry ->
            val seedId = entry.toRoute<SeedDetails>().seedId
            SeedDetailsScreen(
                seedId = seedId,
                onBack = { navController.navigateUp() },
                onBackupClick = showPlaceholder,
                onViewSeedPhraseClick = { navController.navigate(RevealSeedPhrase(seedId)) },
                onAuthorizedAppsClick = { navController.navigate(AuthorizedApps(seedId)) },
            )
        }
        composable<AuthorizedApps> { entry ->
            AuthorizedAppsScreen(
                seedId = entry.toRoute<AuthorizedApps>().seedId,
                onBack = { navController.navigateUp() },
            )
        }
        composable<RevealSeedPhrase> { entry ->
            val seedId = entry.toRoute<RevealSeedPhrase>().seedId
            RevealSeedPhraseScreen(
                onBack = { navController.navigateUp() },
                onReveal = {
                    // Skip the warning when going back from the seed phrase
                    navController.navigate(SeedPhrase(seedId)) {
                        popUpTo<RevealSeedPhrase> { inclusive = true }
                    }
                },
            )
        }
        composable<SeedPhrase> { entry ->
            SeedPhraseScreen(
                seedId = entry.toRoute<SeedPhrase>().seedId,
                onBack = { navController.navigateUp() },
            )
        }
    }
}
