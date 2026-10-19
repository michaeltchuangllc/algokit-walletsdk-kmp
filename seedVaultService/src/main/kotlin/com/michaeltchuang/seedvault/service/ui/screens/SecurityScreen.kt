package com.michaeltchuang.seedvault.service.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.michaeltchuang.seedvault.service.R
import com.michaeltchuang.seedvault.service.data.auth.UserAuthenticators
import com.michaeltchuang.seedvault.service.ui.auth.rememberRequireAuth
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultBackButton
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultPrimaryButton
import com.michaeltchuang.seedvault.service.ui.components.SeedVaultWideButton
import org.koin.compose.koinInject

/**
 * Shows whether the user can be verified on this device (Trusty applet or the system fingerprint /
 * screen lock) and lets them try it.
 */
@Composable
internal fun SecurityScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val authenticators: UserAuthenticators = koinInject()
    val available by produceState<Boolean?>(null) { value = authenticators.isAvailable() }
    val verifiedMessage = stringResource(R.string.security_test_ok)
    val requireAuth = rememberRequireAuth(title = stringResource(R.string.security_test_title))

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        SeedVaultBackButton(onClick = onBack)
        Text(
            text = stringResource(R.string.security_title),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 16.dp),
        )
        Text(
            text = stringResource(R.string.security_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        available?.let { ready ->
            Text(
                text = stringResource(if (ready) R.string.security_status_ready else R.string.security_status_missing),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 24.dp),
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        SeedVaultPrimaryButton(
            text = stringResource(R.string.security_test),
            enabled = available == true,
            onClick = { requireAuth { Toast.makeText(context, verifiedMessage, Toast.LENGTH_SHORT).show() } },
        )
        SeedVaultWideButton(
            text = stringResource(R.string.security_open_settings),
            onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
