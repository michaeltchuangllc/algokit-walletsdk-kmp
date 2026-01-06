package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.R
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel

@Composable
fun AnswerScreen(viewModel: AnswerViewModel) {
    // Collect StateFlow values as Compose state
    val session by viewModel.session.collectAsState()
    val message by viewModel.authMessage.collectAsState()
    val accountAddress by viewModel.accountAddress.collectAsState()
    val errorMessage by viewModel.error.collectAsState()

    // Determine connection status
    val isWaiting = message == null && errorMessage == null
    val hasError = errorMessage != null
    val isConnected = message != null && session != "Logged Out" && !hasError
    val isConnecting = message != null && session == "Logged Out" && !hasError

    ScreenContentAnswer(
        isConnected = isConnected,
        isWaiting = isWaiting,
        isConnecting = isConnecting,
        hasError = hasError,
        errorMessage = errorMessage,
        session = session,
        origin = message?.origin,
        requestId = message?.requestId,
        accountAddress = accountAddress,
    )
}

@Composable
fun ScreenContentAnswer(
    isConnected: Boolean,
    isWaiting: Boolean,
    isConnecting: Boolean,
    hasError: Boolean,
    errorMessage: String?,
    session: String,
    origin: String?,
    requestId: String?,
    accountAddress: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Header
            Text(
                text = stringResource(R.string.liquid_auth_header),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Connection Status Card
            ConnectionStatusCard(
                isConnected = isConnected,
                isWaiting = isWaiting,
                isConnecting = isConnecting,
                hasError = hasError,
                errorMessage = errorMessage,
                session = session,
                origin = origin,
                requestId = requestId,
                accountAddress = accountAddress,
            )

            // Account Info Card (if account address exists)
            if (accountAddress.isNotEmpty()) {
                AccountInfoCard(accountAddress = accountAddress)
            }
        }
    }
}
