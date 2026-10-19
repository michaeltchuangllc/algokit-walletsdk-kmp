package com.michaeltchuang.seedvault.service.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.seedvault.service.R
import com.michaeltchuang.seedvault.service.ui.auth.rememberRequireAuth
import com.michaeltchuang.seedvault.service.ui.components.GlowingIcon
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultBackButton
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultPrimaryButton
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultWideButton
import com.michaeltchuang.seedvault.service.ui.theme.SeedVaultTheme
import com.michaeltchuang.seedvault.service.ui.viewmodel.Acknowledgement
import com.michaeltchuang.seedvault.service.ui.viewmodel.RevealSeedPhraseViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Warning shown before a seed phrase is revealed. Every statement has to be ticked first.
 */
@Composable
fun RevealSeedPhraseScreen(
    onBack: () -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RevealSeedPhraseViewModel = koinViewModel(),
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    val requireAuth = rememberRequireAuth(title = stringResource(R.string.reveal_auth_title))
    RevealSeedPhraseContent(
        viewState = viewState,
        onAcknowledgementChanged = viewModel::onAcknowledgementChanged,
        onBack = onBack,
        onReveal = { requireAuth(onReveal) },
        modifier = modifier,
    )
}

@Composable
private fun RevealSeedPhraseContent(
    viewState: RevealSeedPhraseViewModel.ViewState,
    onAcknowledgementChanged: (Acknowledgement, Boolean) -> Unit,
    onBack: () -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SeedVaultBackButton(onClick = onBack, modifier = Modifier.align(Alignment.Start))
            GlowingIcon(iconRes = R.drawable.ic_lock)
            Text(
                text = stringResource(R.string.reveal_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.reveal_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            )
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Acknowledgement.entries.forEachIndexed { index, acknowledgement ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                        AcknowledgementRow(
                            textRes = acknowledgement.textRes,
                            checked = acknowledgement in viewState.checked,
                            onCheckedChange = { isChecked -> onAcknowledgementChanged(acknowledgement, isChecked) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        SeedVaultPrimaryButton(
            text = stringResource(R.string.reveal_seed_phrase),
            iconRes = R.drawable.ic_visibility,
            enabled = viewState.canReveal,
            onClick = onReveal,
            modifier = Modifier.padding(top = 8.dp),
        )
        SeedVaultWideButton(
            text = stringResource(R.string.cancel),
            onClick = onBack,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

private val Acknowledgement.textRes: Int
    @StringRes get() =
        when (this) {
            Acknowledgement.NOBODY_CAN_SEE_SCREEN -> R.string.reveal_ack_screen
            Acknowledgement.SHARING_LOSES_FUNDS -> R.string.reveal_ack_sharing
            Acknowledgement.LOSING_PHRASE_LOSES_FUNDS -> R.string.reveal_ack_losing
            Acknowledgement.NOBODY_WILL_ASK -> R.string.reveal_ack_never_ask
        }

@Composable
private fun AcknowledgementRow(
    @StringRes textRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(checked = checked)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyLarge,
            color = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckCircle(checked: Boolean) {
    val fill by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.tertiary else Color.Transparent,
        label = "checkFill",
    )
    val border by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
        label = "checkBorder",
    )
    Box(
        modifier =
            Modifier
                .size(28.dp)
                .background(fill, CircleShape)
                .border(2.dp, border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = checked, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1615)
@Composable
private fun RevealSeedPhraseScreenPreview() {
    SeedVaultTheme {
        RevealSeedPhraseContent(
            viewState = RevealSeedPhraseViewModel.ViewState(checked = setOf(Acknowledgement.NOBODY_CAN_SEE_SCREEN)),
            onAcknowledgementChanged = { _, _ -> },
            onBack = {},
            onReveal = {},
        )
    }
}
