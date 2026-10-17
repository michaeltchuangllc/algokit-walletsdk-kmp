package com.michaeltchuang.seedvault.service.ui.screens

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
import com.michaeltchuang.seedvault.service.R
import com.michaeltchuang.seedvault.service.ui.components.SecureScreen
import com.michaeltchuang.seedvault.service.ui.components.SeedPhraseGrid
import com.michaeltchuang.seedvault.service.ui.components.SeedPhraseWarning
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultBackButton
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultPrimaryButton
import com.michaeltchuang.seedvault.service.ui.preview.SeedVaultPreviewData
import com.michaeltchuang.seedvault.service.ui.theme.SeedVaultTheme
import com.michaeltchuang.seedvault.service.ui.viewmodel.SeedPhraseViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The 25-word seed phrase of an existing seed, shown after [RevealSeedPhraseScreen].
 */
@Composable
fun SeedPhraseScreen(
    seedId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeedPhraseViewModel = koinViewModel { parametersOf(seedId) },
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                SeedPhraseViewModel.ViewEvent.ShowError -> {
                    Toast.makeText(context, R.string.generic_error, Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }
        }
    }

    val content = viewState as? SeedPhraseViewModel.ViewState.Content
    SeedPhraseContent(
        seedName = content?.seedName.orEmpty(),
        seedPhrase = content?.seedPhrase.orEmpty(),
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun SeedPhraseContent(
    seedName: String,
    seedPhrase: List<String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureScreen()

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            SeedVaultBackButton(onClick = onBack)
            Text(
                text = stringResource(R.string.seed_phrase_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp),
            )
            Text(
                text = stringResource(R.string.seed_phrase_subtitle, seedName),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 24.dp),
            )
            SeedPhraseWarning(text = stringResource(R.string.seed_phrase_warning))
            Spacer(modifier = Modifier.height(16.dp))
            SeedPhraseGrid(words = seedPhrase)
            Spacer(modifier = Modifier.height(16.dp))
        }

        SeedVaultPrimaryButton(
            text = stringResource(R.string.done),
            onClick = onBack,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1615)
@Composable
private fun SeedPhraseScreenPreview() {
    SeedVaultTheme {
        SeedPhraseContent(
            seedName = SeedVaultPreviewData.seeds.first().name,
            seedPhrase = SeedVaultPreviewData.seedPhrase,
            onBack = {},
        )
    }
}
