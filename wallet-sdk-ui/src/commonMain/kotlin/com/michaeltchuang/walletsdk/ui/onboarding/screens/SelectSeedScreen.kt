package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.SelectSeedViewModel
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SelectSeedScreen(
    navController: NavController,
    viewModel: SelectSeedViewModel = koinViewModel(),
    permissionGranted: Boolean = false,
    onRequestPermission: () -> Unit = {},
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()

    // Show permission UI if permission not granted and we're in Idle/Error state
    val showPermissionUI =
        !permissionGranted &&
            (
                viewState is SelectSeedViewModel.ViewState.Idle ||
                    viewState is SelectSeedViewModel.ViewState.Error
            )

    // Auto-load seeds when permission is granted and we're in Idle state
    LaunchedEffect(permissionGranted, viewState) {
        if (permissionGranted && viewState is SelectSeedViewModel.ViewState.Idle) {
            viewModel.loadSeeds()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is SelectSeedViewModel.ViewEvent.SeedSelected -> {
                    navController.navigate(
                        "${AlgoKitScreens.IMPORT_SEED_VAULT_ACCOUNTS_SCREEN.name}?selectedSeedIds=${event.seed.id}",
                    )
                }
                is SelectSeedViewModel.ViewEvent.AllSeedsConnected -> {
                    // Handle all seeds connected
                }
                is SelectSeedViewModel.ViewEvent.Error -> {
                    // Show error snackbar
                }
                is SelectSeedViewModel.ViewEvent.SeedAuthorized -> {
                    // Seed was authorized, reload seeds
                    viewModel.loadSeeds()
                }
                is SelectSeedViewModel.ViewEvent.RequestAuthorizeSeed -> {
                    // Platform-specific implementation handles this
                }
            }
        }
    }

    SelectSeedScreenContent(
        navController = navController,
        viewState = viewState,
        permissionGranted = !showPermissionUI,
        onSeedSelected = { viewModel.onSeedSelected(it) },
        onAllSeedsConnected = { viewModel.onAllSeedsConnected() },
        onRetry = {
            if (showPermissionUI) {
                onRequestPermission()
            } else {
                viewModel.loadSeeds()
            }
        },
        onRequestPermission = onRequestPermission,
        onAuthorizeSeed = { viewModel.authorizeNewSeed() },
    )
}

@Composable
fun SelectSeedScreenContent(
    navController: NavController,
    viewState: SelectSeedViewModel.ViewState,
    permissionGranted: Boolean = true,
    onSeedSelected: (SelectSeedViewModel.SeedItem) -> Unit = {},
    onAllSeedsConnected: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRequestPermission: () -> Unit = {},
    onAuthorizeSeed: () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AlgoKitTheme.colors.background)
                .padding(horizontal = 24.dp),
    ) {
        AlgoKitTopBar(
            title = "Select Seed",
            onClick = { navController.popBackStack() },
        )

        when (viewState) {
            is SelectSeedViewModel.ViewState.Content -> {
                if (!permissionGranted) {
                    PermissionRequiredState(onRequestPermission = onRequestPermission)
                } else {
                    ContentState(
                        seeds = viewState.seeds,
                        hasMoreSeedsAvailable = viewState.hasMoreSeedsAvailable,
                        onSeedSelected = onSeedSelected,
                        onAllSeedsConnected = onAllSeedsConnected,
                        onAuthorizeSeed = onAuthorizeSeed,
                    )
                }
            }

            is SelectSeedViewModel.ViewState.NoAuthorizedSeeds -> {
                if (!permissionGranted) {
                    PermissionRequiredState(onRequestPermission = onRequestPermission)
                } else {
                    NoAuthorizedSeedsState(
                        message = viewState.message,
                        canAuthorize = viewState.canAuthorize,
                        onAuthorizeSeed = onAuthorizeSeed,
                        onRetry = onRetry,
                    )
                }
            }

            is SelectSeedViewModel.ViewState.Error -> {
                if (!permissionGranted) {
                    PermissionRequiredState(onRequestPermission = onRequestPermission)
                } else {
                    ErrorState(
                        message = viewState.message,
                        onRetry = onRetry,
                    )
                }
            }

            is SelectSeedViewModel.ViewState.Idle -> {
                if (!permissionGranted) {
                    PermissionRequiredState(onRequestPermission = onRequestPermission)
                }
            }
            is SelectSeedViewModel.ViewState.Loading -> {
                if (!permissionGranted) {
                    PermissionRequiredState(onRequestPermission = onRequestPermission)
                } else {
                    // Show loading state
                    LoadingState()
                }
            }
        }
    }
}

@Composable
private fun ContentState(
    seeds: List<SelectSeedViewModel.SeedItem>,
    hasMoreSeedsAvailable: Boolean,
    onSeedSelected: (SelectSeedViewModel.SeedItem) -> Unit,
    onAllSeedsConnected: () -> Unit,
    onAuthorizeSeed: () -> Unit,
) {
    // Seed list
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(seeds) { seed ->
            SeedCard(
                seed = seed,
                onClick = { onSeedSelected(seed) },
            )
        }

        // Show "Authorize more seeds" option if there are more seeds available
        if (hasMoreSeedsAvailable) {
            item {
                Spacer(Modifier.height(8.dp))
                AuthorizeMoreSeedsCard(onClick = onAuthorizeSeed)
            }
        }
    }
}

@Composable
private fun SeedCard(
    seed: SelectSeedViewModel.SeedItem,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder(enabled = true),
        colors = CardDefaults.outlinedCardColors(containerColor = AlgoKitTheme.colors.backgroundSecondary),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left icon
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AlgoKitTheme.colors.layerGray),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_wallet),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = AlgoKitTheme.colors.textMain,
                )
            }

            Spacer(Modifier.width(16.dp))

            // Seed name
            Text(
                text = seed.name,
                style = AlgoKitTheme.typography.body.large.sansMedium,
                color = AlgoKitTheme.colors.textMain,
                modifier = Modifier.weight(1f),
            )

            // Right arrow
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Select",
                tint = AlgoKitTheme.colors.textGray,
            )
        }
    }
}

@Composable
private fun AuthorizeMoreSeedsCard(onClick: () -> Unit) {
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder(enabled = true),
        colors = CardDefaults.outlinedCardColors(containerColor = AlgoKitTheme.colors.backgroundSecondary),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left icon (plus icon)
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AlgoKitTheme.colors.layerGray),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    style = AlgoKitTheme.typography.body.large.sansMedium,
                    color = AlgoKitTheme.colors.textMain,
                )
            }

            Spacer(Modifier.width(16.dp))

            // Text
            Text(
                text = "Authorize another seed",
                style = AlgoKitTheme.typography.body.large.sansMedium,
                color = AlgoKitTheme.colors.textMain,
                modifier = Modifier.weight(1f),
            )

            // Right arrow
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Authorize",
                tint = AlgoKitTheme.colors.textGray,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Loading seeds...",
            style = AlgoKitTheme.typography.body.regular.sans,
            color = AlgoKitTheme.colors.textGray,
        )
    }
}

@Composable
private fun PermissionRequiredState(onRequestPermission: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Seed Vault permission required",
            style = AlgoKitTheme.typography.body.large.sansMedium,
            color = AlgoKitTheme.colors.textMain,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "This app needs permission to access your Seed Vault to import Solana accounts.",
            style = AlgoKitTheme.typography.body.regular.sans,
            color = AlgoKitTheme.colors.textGray,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.height(24.dp))

        OutlinedCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onRequestPermission() }
                    .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder(enabled = true),
            colors = CardDefaults.outlinedCardColors(containerColor = AlgoKitTheme.colors.background),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Grant Permission",
                    style = AlgoKitTheme.typography.body.large.sansMedium,
                    color = AlgoKitTheme.colors.textMain,
                )
            }
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = AlgoKitTheme.typography.body.regular.sans,
            color = AlgoKitTheme.colors.textGray,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.height(24.dp))

        OutlinedCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onRetry() }
                    .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder(enabled = true),
            colors = CardDefaults.outlinedCardColors(containerColor = AlgoKitTheme.colors.background),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Retry",
                    style = AlgoKitTheme.typography.body.large.sansMedium,
                    color = AlgoKitTheme.colors.textMain,
                )
            }
        }
    }
}

@Composable
private fun NoAuthorizedSeedsState(
    message: String,
    canAuthorize: Boolean,
    onAuthorizeSeed: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = AlgoKitTheme.typography.body.regular.sans,
            color = AlgoKitTheme.colors.textGray,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.height(24.dp))

        if (canAuthorize) {
            // Show "Authorize Seed" button
            OutlinedCard(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAuthorizeSeed() }
                        .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder(enabled = true),
                colors = CardDefaults.outlinedCardColors(containerColor = AlgoKitTheme.colors.background),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Authorize Seed",
                        style = AlgoKitTheme.typography.body.large.sansMedium,
                        color = AlgoKitTheme.colors.textMain,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // Always show "Retry" button
        OutlinedCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onRetry() }
                    .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder(enabled = true),
            colors = CardDefaults.outlinedCardColors(containerColor = AlgoKitTheme.colors.background),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Refresh",
                    style = AlgoKitTheme.typography.body.large.sansMedium,
                    color = AlgoKitTheme.colors.textMain,
                )
            }
        }
    }
}

@Preview
@Composable
fun SelectSeedScreenPreview() {
    AlgoKitTheme {
        SelectSeedScreenContent(
            navController = rememberNavController(),
            viewState =
                SelectSeedViewModel.ViewState.Content(
                    seeds =
                        listOf(
                            SelectSeedViewModel.SeedItem("1", "Seeker Seed 1"),
                            SelectSeedViewModel.SeedItem("2", "Seeker Seed 2"),
                        ),
                    hasMoreSeedsAvailable = true,
                ),
        )
    }
}

@Preview
@Composable
fun NoAuthorizedSeedsScreenPreview() {
    AlgoKitTheme {
        SelectSeedScreenContent(
            navController = rememberNavController(),
            viewState =
                SelectSeedViewModel.ViewState.NoAuthorizedSeeds(
                    message = "No authorized seeds found. Please authorize a seed from the Seed Vault.",
                    canAuthorize = true,
                ),
        )
    }
}

/**
 * Platform-specific SelectSeedScreen that handles runtime permissions.
 * On Android, this automatically requests Seed Vault permission.
 * On iOS, this just shows the regular SelectSeedScreen (Seed Vault is Android-only).
 */
@Composable
expect fun SelectSeedScreenPlatform(navController: NavController)
