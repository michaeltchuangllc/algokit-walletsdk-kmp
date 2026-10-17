package com.michaeltchuang.seedvault.service.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.seedvault.service.R
import com.michaeltchuang.seedvault.service.ui.components.SecureScreen
import com.michaeltchuang.seedvault.service.ui.components.SeedPhraseWarning
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultBackButton
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultPrimaryButton
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultWideButton
import com.michaeltchuang.seedvault.service.ui.preview.SeedVaultPreviewData
import com.michaeltchuang.seedvault.service.ui.theme.SeedVaultTheme
import com.michaeltchuang.seedvault.service.ui.viewmodel.ImportSeedViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Imports an existing 25-word Falcon-1024 seed phrase, typed in word by word or pasted from the
 * clipboard.
 */
@Composable
fun ImportSeedScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportSeedViewModel = koinViewModel(),
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            val message =
                when (event) {
                    ImportSeedViewModel.ViewEvent.SeedSaved -> {
                        onBack()
                        return@collect
                    }
                    ImportSeedViewModel.ViewEvent.InvalidSeedPhrase -> R.string.invalid_seed_phrase
                    ImportSeedViewModel.ViewEvent.InvalidClipboard -> R.string.invalid_clipboard
                    ImportSeedViewModel.ViewEvent.ShowError -> R.string.generic_error
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    ImportSeedContent(
        words = viewState.words,
        canImport = viewState.canImport,
        onBack = onBack,
        onWordChange = viewModel::onWordChange,
        onPaste = { clipboardManager.getText()?.text?.let(viewModel::onPaste) },
        onImport = viewModel::onImport,
        modifier = modifier,
    )
}

@Composable
private fun ImportSeedContent(
    words: List<String>,
    canImport: Boolean,
    onBack: () -> Unit,
    onWordChange: (index: Int, value: String) -> Unit,
    onPaste: () -> Unit,
    onImport: () -> Unit,
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
                text = stringResource(R.string.import_seed_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp),
            )
            Text(
                text = stringResource(R.string.import_seed_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 24.dp),
            )
            SeedPhraseWarning(text = stringResource(R.string.seed_phrase_warning))
            Spacer(modifier = Modifier.height(16.dp))
            SeedVaultWideButton(
                text = stringResource(R.string.paste_from_clipboard),
                iconRes = R.drawable.ic_content_paste,
                onClick = onPaste,
            )
            Spacer(modifier = Modifier.height(16.dp))
            SeedPhraseInputGrid(words = words, onWordChange = onWordChange)
            Spacer(modifier = Modifier.height(16.dp))
        }

        SeedVaultPrimaryButton(
            text = stringResource(R.string.import_seed_button),
            iconRes = R.drawable.ic_check,
            enabled = canImport,
            onClick = onImport,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

/** Editable version of the numbered seed phrase grid: 1-13 in the left column, 14-25 on the right. */
@Composable
private fun SeedPhraseInputGrid(
    words: List<String>,
    onWordChange: (index: Int, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = (words.size + 1) / 2
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeedWordField(
                    index = row,
                    word = words[row],
                    onWordChange = { onWordChange(row, it) },
                    modifier = Modifier.weight(1f),
                )
                val right = row + rows
                if (right < words.size) {
                    SeedWordField(
                        index = right,
                        word = words[right],
                        onWordChange = { onWordChange(right, it) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SeedWordField(
    index: Int,
    word: String,
    onWordChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.width(24.dp),
        )
        BasicTextField(
            value = word,
            onValueChange = onWordChange,
            singleLine = true,
            textStyle =
                MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
            // Password keyboard turns off suggestions and keeps the words out of the keyboard's dictionary
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = if (index == LAST_WORD_INDEX) ImeAction.Done else ImeAction.Next,
                ),
            modifier = Modifier.weight(1f),
        )
    }
}

private const val LAST_WORD_INDEX = 24

@Preview(showBackground = true, backgroundColor = 0xFF0D1615)
@Composable
private fun ImportSeedScreenPreview() {
    SeedVaultTheme {
        ImportSeedContent(
            words = SeedVaultPreviewData.seedPhrase,
            canImport = true,
            onBack = {},
            onWordChange = { _, _ -> },
            onPaste = {},
            onImport = {},
        )
    }
}
