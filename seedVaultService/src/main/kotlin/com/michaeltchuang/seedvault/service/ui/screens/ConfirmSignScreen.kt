package com.michaeltchuang.seedvault.service.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.michaeltchuang.seedvault.service.R
import com.michaeltchuang.seedvault.service.domain.model.VaultSeed
import com.michaeltchuang.seedvault.service.domain.usecase.GetSeed
import com.michaeltchuang.seedvault.service.remote.SignApprovalCoordinator
import com.michaeltchuang.seedvault.service.remote.SignApprovalRequest
import com.michaeltchuang.seedvault.service.ui.auth.rememberRequireAuth
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultPrimaryButton
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultWideButton
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

/**
 * Shows what an app wants signed and asks the user to verify themselves (fingerprint or screen
 * lock) before the signature is made. Leaving the screen any other way counts as a no.
 *
 * @param onGone called when the request is settled, with whether the user approved, or when it no
 * longer exists
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSignScreen(
    requestId: String,
    onGone: (approved: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordinator: SignApprovalCoordinator = koinInject()
    // Kept, since the coordinator forgets a request once it is settled
    val request = remember(requestId) { coordinator.request(requestId) }
    val currentOnGone by rememberUpdatedState(onGone)

    if (request == null) {
        LaunchedEffect(Unit) { currentOnGone(false) }
        return
    }
    LaunchedEffect(request) {
        currentOnGone(request.decision.await())
    }
    DisposableEffect(request) {
        onDispose { coordinator.resolve(requestId, false) }
    }
    // Only a seed the app is authorized for can be signed with, anything else is a no
    val getSeed: GetSeed = koinInject()
    val seed by produceState<SeedLookup>(SeedLookup.Loading, request) {
        val found = getSeed(request.operation.seedId).first()
        value = SeedLookup.Done(found?.takeIf { seed -> seed.authorizedApps.any { it.packageName == request.callerPackage } })
    }
    val lookup = seed
    if (lookup is SeedLookup.Done && lookup.seed == null) {
        LaunchedEffect(Unit) { coordinator.resolve(requestId, false) }
    }
    if (lookup !is SeedLookup.Done || lookup.seed == null) return
    // The sheet is what the user sees on top of the calling app, dismissing it is a no
    ModalBottomSheet(
        onDismissRequest = { coordinator.resolve(requestId, false) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        ConfirmSignContent(
            request = request,
            seedName = lookup.seed.name,
            onApproved = { coordinator.resolve(requestId, true) },
            onDeny = { coordinator.resolve(requestId, false) },
            modifier = modifier,
        )
    }
}

private sealed interface SeedLookup {
    data object Loading : SeedLookup

    data class Done(
        val seed: VaultSeed?,
    ) : SeedLookup
}

@Composable
private fun ConfirmSignContent(
    request: SignApprovalRequest,
    seedName: String,
    onApproved: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requireAuth =
        rememberRequireAuth(
            title = stringResource(R.string.confirm_sign_auth_title),
            subtitle = request.description,
        )
    Column(modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.confirm_sign_title, request.appLabel),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = request.description, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.confirm_sign_seed, seedName),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(modifier = Modifier.height(32.dp))
        SeedVaultPrimaryButton(
            text = stringResource(R.string.confirm_sign_approve),
            onClick = { requireAuth(onApproved) },
        )
        SeedVaultWideButton(
            text = stringResource(R.string.confirm_sign_deny),
            onClick = onDeny,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
