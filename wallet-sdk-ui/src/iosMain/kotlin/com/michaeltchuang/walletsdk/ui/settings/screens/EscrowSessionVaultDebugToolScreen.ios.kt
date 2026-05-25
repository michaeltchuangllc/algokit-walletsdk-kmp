package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme

/**
 * iOS-specific Escrow Session Vault Debug Tool Screen
 *
 * The escrow session vault debug tool is currently only supported on Android.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun EscrowSessionVaultDebugToolScreen(navController: Any) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = AlgoKitTheme.colors.background,
                        titleContentColor = AlgoKitTheme.colors.textMain,
                    ),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(AlgoKitTheme.colors.background)
                    .padding(padding)
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Feature Not Available",
                style = MaterialTheme.typography.headlineMedium,
                color = AlgoKitTheme.colors.textMain,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "The escrow session vault debug tool is currently only supported on Android.",
                style = MaterialTheme.typography.bodyLarge,
                color = AlgoKitTheme.colors.textGray,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "iOS support is not available right now.",
                style = MaterialTheme.typography.bodyMedium,
                color = AlgoKitTheme.colors.textGray,
                textAlign = TextAlign.Center,
            )
        }
    }
}
