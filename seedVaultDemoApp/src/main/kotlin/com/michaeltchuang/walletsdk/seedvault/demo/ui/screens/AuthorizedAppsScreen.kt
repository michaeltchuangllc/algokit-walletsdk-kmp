package com.michaeltchuang.walletsdk.seedvault.demo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.seedvault.demo.R
import com.michaeltchuang.walletsdk.seedvault.demo.data.FakeSeedVaultDataSource
import com.michaeltchuang.walletsdk.seedvault.demo.data.model.AuthorizedApp
import com.michaeltchuang.walletsdk.seedvault.demo.ui.components.SeedVaultBackButton
import com.michaeltchuang.walletsdk.seedvault.demo.ui.theme.SeedVaultTheme

/**
 * Apps allowed to request transactions from a seed, each with a "Revoke" action.
 */
@Composable
fun AuthorizedAppsScreen(
    apps: List<AuthorizedApp>,
    onBack: () -> Unit,
    onRevoke: (AuthorizedApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        SeedVaultBackButton(onClick = onBack)
        Text(
            text = stringResource(R.string.authorized_apps),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp),
        )
        Text(
            text = stringResource(R.string.authorized_apps_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 16.dp),
        )

        if (apps.isEmpty()) {
            Text(
                text = stringResource(R.string.no_authorized_apps),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        apps.forEach { app ->
            AuthorizedAppRow(app = app, onRevoke = { onRevoke(app) })
        }
    }
}

@Composable
private fun AuthorizedAppRow(
    app: AuthorizedApp,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 18.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Placeholder icon: a real implementation would load the app icon from PackageManager
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = app.label.take(1), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onRevoke, enabled = !app.isPrivileged) {
            Text(stringResource(if (app.isPrivileged) R.string.authorized else R.string.revoke))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1615)
@Composable
private fun AuthorizedAppsScreenPreview() {
    SeedVaultTheme {
        AuthorizedAppsScreen(
            apps = FakeSeedVaultDataSource.sampleSeeds.last().authorizedApps,
            onBack = {},
            onRevoke = {},
        )
    }
}
