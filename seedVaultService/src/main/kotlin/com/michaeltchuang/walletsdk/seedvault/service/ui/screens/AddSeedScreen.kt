package com.michaeltchuang.walletsdk.seedvault.service.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.seedvault.service.R
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SecureScreen
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SeedPhraseGrid
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SeedPhraseWarning
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SeedVaultBackButton
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SeedVaultPrimaryButton
import com.michaeltchuang.walletsdk.seedvault.service.ui.components.SeedVaultWideButton
import com.michaeltchuang.walletsdk.seedvault.service.ui.preview.SeedVaultPreviewData
import com.michaeltchuang.walletsdk.seedvault.service.ui.theme.SeedVaultTheme
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.AddSeedViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Shows a newly generated 25-word Falcon-1024 seed phrase. The seed is only stored once the user
 * says they have written it down (backed up) or chooses to back it up later.
 */
@Composable
fun AddSeedScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddSeedViewModel = koinViewModel(),
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                AddSeedViewModel.ViewEvent.SeedSaved -> onBack()
                AddSeedViewModel.ViewEvent.ShowError ->
                    Toast.makeText(context, R.string.generic_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val content = viewState as? AddSeedViewModel.ViewState.Content
    AddSeedContent(
        seedPhrase = content?.seedPhrase.orEmpty(),
        isSaving = content?.isSaving == true,
        onBack = onBack,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}

@Composable
private fun AddSeedContent(
    seedPhrase: List<String>,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSave: (isBackedUp: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureScreen()
    val canSave = seedPhrase.isNotEmpty() && !isSaving

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            SeedVaultBackButton(onClick = onBack)
            Text(
                text = stringResource(R.string.new_seed_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp),
            )
            Text(
                text = stringResource(R.string.new_seed_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 24.dp),
            )
            SeedPhraseWarning(text = stringResource(R.string.seed_phrase_warning))
            Spacer(modifier = Modifier.height(16.dp))
            SeedPhraseGrid(words = seedPhrase)
            Spacer(modifier = Modifier.height(16.dp))
        }

        SeedVaultPrimaryButton(
            text = stringResource(R.string.written_it_down),
            iconRes = R.drawable.ic_check,
            enabled = canSave,
            onClick = { onSave(true) },
            modifier = Modifier.padding(top = 16.dp),
        )
        SeedVaultWideButton(
            text = stringResource(R.string.back_up_later),
            onClick = { if (canSave) onSave(false) },
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1615)
@Composable
private fun AddSeedScreenPreview() {
    SeedVaultTheme {
        AddSeedContent(
            seedPhrase = SeedVaultPreviewData.seedPhrase,
            isSaving = false,
            onBack = {},
            onSave = {},
        )
    }
}
