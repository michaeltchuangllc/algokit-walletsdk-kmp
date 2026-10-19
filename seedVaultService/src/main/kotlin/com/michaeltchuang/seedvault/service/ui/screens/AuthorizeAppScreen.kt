package com.michaeltchuang.seedvault.service.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.seedvault.service.R
import com.michaeltchuang.seedvault.service.ui.auth.rememberRequireAuth
import com.michaeltchuang.seedvault.service.ui.components.SeedChip
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultPrimaryButton
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultWideButton
import com.michaeltchuang.seedvault.service.ui.preview.SeedVaultPreviewData
import com.michaeltchuang.seedvault.service.ui.theme.SeedVaultTheme
import com.michaeltchuang.seedvault.service.ui.viewmodel.AuthorizeAppViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * "Allow this app to use your seeds?" screen shown to an app that started the authorization
 * activity. The app's identity comes from the system, not from anything the app sent.
 */
@Composable
fun AuthorizeAppScreen(
    appPackageName: String,
    appLabel: String,
    onAllowed: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthorizeAppViewModel = koinViewModel { parametersOf(appPackageName, appLabel) },
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requireAuth = rememberRequireAuth(title = stringResource(R.string.authorize_auth_title, appLabel))

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                AuthorizeAppViewModel.ViewEvent.Authorized -> onAllowed()
                AuthorizeAppViewModel.ViewEvent.ShowError ->
                    Toast.makeText(context, R.string.generic_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val content = viewState as? AuthorizeAppViewModel.ViewState.Content ?: return
    AuthorizeAppContent(
        content = content,
        onToggleSeed = viewModel::onToggleSeed,
        onAllow = { requireAuth(viewModel::onAllow) },
        onDeny = onDeny,
        modifier = modifier,
    )
}

@Composable
private fun AuthorizeAppContent(
    content: AuthorizeAppViewModel.ViewState.Content,
    onToggleSeed: (Long) -> Unit,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.authorize_title, content.appLabel),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 4.dp, top = 32.dp),
            )
            Text(
                text = stringResource(R.string.authorize_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 24.dp),
            )
            if (content.seeds.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_seeds),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        content.seeds.forEach { choice ->
                            SeedChoiceRow(
                                choice = choice,
                                isSelected = choice.seed.id in content.selectedSeedIds,
                                onClick = { onToggleSeed(choice.seed.id) },
                            )
                        }
                    }
                }
            }
        }

        SeedVaultPrimaryButton(
            text = stringResource(R.string.authorize_allow),
            iconRes = R.drawable.ic_check,
            enabled = content.canAllow,
            onClick = onAllow,
            modifier = Modifier.padding(top = 16.dp),
        )
        SeedVaultWideButton(
            text = stringResource(R.string.cancel),
            onClick = onDeny,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun SeedChoiceRow(
    choice: AuthorizeAppViewModel.SeedChoice,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !choice.isAuthorized, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeedChip()
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = choice.seed.name, style = MaterialTheme.typography.titleLarge)
            if (choice.isAuthorized) {
                Text(
                    text = stringResource(R.string.authorized),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Checkbox(checked = isSelected || choice.isAuthorized, onCheckedChange = null, enabled = !choice.isAuthorized)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1615)
@Composable
private fun AuthorizeAppScreenPreview() {
    SeedVaultTheme {
        AuthorizeAppContent(
            content =
                AuthorizeAppViewModel.ViewState.Content(
                    appLabel = "Wallet Service Demo",
                    seeds = SeedVaultPreviewData.seeds.map { AuthorizeAppViewModel.SeedChoice(it, isAuthorized = it.id == 1L) },
                    selectedSeedIds = setOf(2L),
                ),
            onToggleSeed = {},
            onAllow = {},
            onDeny = {},
        )
    }
}
