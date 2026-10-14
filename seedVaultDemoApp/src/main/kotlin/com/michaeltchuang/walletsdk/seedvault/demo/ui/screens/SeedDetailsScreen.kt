package com.michaeltchuang.walletsdk.seedvault.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.seedvault.demo.R
import com.michaeltchuang.walletsdk.seedvault.demo.data.FakeSeedVaultDataSource
import com.michaeltchuang.walletsdk.seedvault.demo.data.model.VaultSeed
import com.michaeltchuang.walletsdk.seedvault.demo.ui.components.SectionHeader
import com.michaeltchuang.walletsdk.seedvault.demo.ui.components.SeedVaultBackButton
import com.michaeltchuang.walletsdk.seedvault.demo.ui.components.SeedVaultWideButton
import com.michaeltchuang.walletsdk.seedvault.demo.ui.components.SettingsRow
import com.michaeltchuang.walletsdk.seedvault.demo.ui.theme.SeedVaultTheme

/**
 * Details of a single seed: rename it, backup state, seed phrase and authorized apps entries.
 */
@Composable
fun SeedDetailsScreen(
    seed: VaultSeed,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onBackupClick: () -> Unit,
    onViewSeedPhraseClick: () -> Unit,
    onAuthorizedAppsClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            SeedVaultBackButton(onClick = onBack)
            Text(
                text = seed.name,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 24.dp),
            )

            SectionHeader(
                text = stringResource(R.string.section_manage),
                modifier = Modifier.padding(start = 4.dp),
            )
            SeedNameCard(name = seed.name, onEditClick = { showRenameDialog = true })

            Spacer(modifier = Modifier.height(40.dp))
            SeedPhraseSection(
                seed = seed,
                onBackupClick = onBackupClick,
                onViewSeedPhraseClick = onViewSeedPhraseClick,
                onAuthorizedAppsClick = onAuthorizedAppsClick,
            )
        }

        SeedVaultWideButton(
            text = stringResource(R.string.delete_seed),
            onClick = onDelete,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }

    if (showRenameDialog) {
        RenameSeedDialog(
            currentName = seed.name,
            onConfirm = { name ->
                onRename(name)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
}

@Composable
private fun SeedPhraseSection(
    seed: VaultSeed,
    onBackupClick: () -> Unit,
    onViewSeedPhraseClick: () -> Unit,
    onAuthorizedAppsClick: () -> Unit,
) {
    SectionHeader(
        text = stringResource(R.string.section_seed_phrase),
        modifier = Modifier.padding(start = 4.dp),
    )
    SettingsRow(
        iconRes = if (seed.isBackedUp) R.drawable.ic_check_circle else R.drawable.ic_error,
        iconTint = if (seed.isBackedUp) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        title = stringResource(R.string.backup),
        subtitle = stringResource(if (seed.isBackedUp) R.string.backup_done else R.string.backup_none),
        onClick = onBackupClick,
    )
    SettingsRow(
        iconRes = R.drawable.ic_edit_note,
        iconTint = MaterialTheme.colorScheme.outline,
        title = stringResource(R.string.view_seed_phrase),
        onClick = onViewSeedPhraseClick,
    )
    SettingsRow(
        iconRes = R.drawable.ic_settings,
        iconTint = MaterialTheme.colorScheme.outline,
        title = stringResource(R.string.authorized_apps),
        subtitle =
            pluralStringResource(
                R.plurals.authorized_apps_count,
                seed.authorizedApps.size,
                seed.authorizedApps.size,
            ),
        onClick = onAuthorizedAppsClick,
    )
}

@Composable
private fun SeedNameCard(
    name: String,
    onEditClick: () -> Unit,
) {
    Surface(
        onClick = onEditClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.seed_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Icon(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = stringResource(R.string.edit_seed_name),
            )
        }
    }
}

@Composable
private fun RenameSeedDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_seed_name)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.seed_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1615)
@Composable
private fun SeedDetailsScreenPreview() {
    SeedVaultTheme {
        SeedDetailsScreen(
            seed = FakeSeedVaultDataSource.sampleSeeds.last(),
            onBack = {},
            onRename = {},
            onBackupClick = {},
            onViewSeedPhraseClick = {},
            onAuthorizedAppsClick = {},
            onDelete = {},
        )
    }
}
