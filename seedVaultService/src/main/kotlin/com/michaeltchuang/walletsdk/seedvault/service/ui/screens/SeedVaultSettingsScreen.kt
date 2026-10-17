package com.michaeltchuang.walletsdk.seedvault.service.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.seedvault.service.R
import com.michaeltchuang.walletsdk.seedvault.service.domain.model.VaultSeed
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SectionHeader
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SeedChip
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SeedVaultBackButton
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SeedVaultLogo
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SeedVaultWideButton
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SettingsRow
import com.michaeltchuang.walletsdk.seedvault.service.ui.preview.SeedVaultPreviewData
import com.michaeltchuang.walletsdk.seedvault.service.ui.theme.SeedVaultTheme
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.SeedVaultSettingsViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Seed Vault landing / settings screen: the list of seeds, "Create a seed", "Import a seed" and the settings entries.
 */
@Composable
fun SeedVaultSettingsScreen(
    onBack: () -> Unit,
    onSeedClick: (VaultSeed) -> Unit,
    onAddSeed: () -> Unit,
    onImportSeed: () -> Unit,
    onSecurityClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeedVaultSettingsViewModel = koinViewModel(),
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    SeedVaultSettingsContent(
        seeds = (viewState as? SeedVaultSettingsViewModel.ViewState.Content)?.seeds,
        onBack = onBack,
        onSeedClick = onSeedClick,
        onAddSeed = onAddSeed,
        onImportSeed = onImportSeed,
        onSecurityClick = onSecurityClick,
        modifier = modifier,
    )
}

/**
 * @param seeds null while the seeds are still loading
 */
@Composable
private fun SeedVaultSettingsContent(
    seeds: List<VaultSeed>?,
    onBack: () -> Unit,
    onSeedClick: (VaultSeed) -> Unit,
    onAddSeed: () -> Unit,
    onImportSeed: () -> Unit,
    onSecurityClick: () -> Unit,
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

        SeedVaultLogo(modifier = Modifier.align(Alignment.CenterHorizontally))

        Text(
            text = stringResource(R.string.seed_vault_title),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 4.dp, top = 40.dp, bottom = 24.dp),
        )

        SectionHeader(
            text = stringResource(R.string.section_seeds),
            modifier = Modifier.padding(start = 4.dp),
        )
        if (seeds == null) {
            Spacer(modifier = Modifier.height(48.dp))
        } else if (seeds.isEmpty()) {
            Text(
                text = stringResource(R.string.no_seeds),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        } else {
            SeedList(seeds = seeds, onSeedClick = onSeedClick)
        }

        Spacer(modifier = Modifier.height(24.dp))
        SeedVaultWideButton(
            text = stringResource(R.string.create_seed),
            iconRes = R.drawable.ic_add,
            onClick = onAddSeed,
        )
        SeedVaultWideButton(
            text = stringResource(R.string.import_seed),
            iconRes = R.drawable.ic_content_paste,
            onClick = onImportSeed,
            modifier = Modifier.padding(top = 12.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))
        SectionHeader(
            text = stringResource(R.string.section_settings),
            modifier = Modifier.padding(start = 4.dp),
        )
        SettingsRow(
            iconRes = R.drawable.ic_fingerprint,
            title = stringResource(R.string.security_and_fingerprint),
            onClick = onSecurityClick,
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SeedList(
    seeds: List<VaultSeed>,
    onSeedClick: (VaultSeed) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            seeds.forEachIndexed { index, seed ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSeedClick(seed) }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SeedChip()
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = seed.name, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1615)
@Composable
private fun SeedVaultSettingsScreenPreview() {
    SeedVaultTheme {
        SeedVaultSettingsContent(
            seeds = SeedVaultPreviewData.seeds,
            onBack = {},
            onSeedClick = {},
            onAddSeed = {},
            onImportSeed = {},
            onSecurityClick = {},
        )
    }
}
