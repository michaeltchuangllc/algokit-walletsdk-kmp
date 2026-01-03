package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Connection Status Card
 *
 * Displays the current connection status with appropriate styling and messages
 */
@Composable
fun ConnectionStatusCard(
    isConnected: Boolean,
    isWaiting: Boolean,
    isConnecting: Boolean,
    hasError: Boolean,
    errorMessage: String?,
    session: String,
    origin: String?,
    requestId: String?,
    accountAddress: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        hasError -> MaterialTheme.colorScheme.errorContainer
                        isWaiting -> MaterialTheme.colorScheme.surfaceVariant
                        isConnecting -> MaterialTheme.colorScheme.tertiaryContainer
                        isConnected -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Status Icon and Loader
            if (hasError) {
                ErrorState(errorMessage, session)
            } else if (isWaiting) {
                WaitingState()
            } else if (isConnecting) {
                ConnectingState(origin, requestId)
            } else if (isConnected) {
                ConnectedState(origin, requestId, session)
            } else {
                DisconnectedState(session)
            }
        }
    }
}

@Composable
private fun ErrorState(errorMessage: String?, session: String) {
    // Error state - show error icon and message
    Text(
        text = "⚠",
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        text = "Connection Failed",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
    if (errorMessage != null) {
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
        )
    }
    Text(
        text = "Session: $session",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
    )
}

@Composable
private fun WaitingState() {
    // Waiting for connection - show loader
    CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = 4.dp,
    )
    Text(
        text = "Waiting for connection...",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "Scan a QR code or use deep link to connect",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ConnectingState(origin: String?, requestId: String?) {
    // Connecting - show loader with connection info
    CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.tertiary,
        strokeWidth = 4.dp,
    )
    Text(
        text = "Connecting...",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    Text(
        text = "Establishing secure connection",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )

    // Show connection details while connecting
    Spacer(modifier = Modifier.height(8.dp))

    if (origin != null) {
        InfoRow(label = "Origin", value = origin)
    }
    if (requestId != null) {
        InfoRow(label = "Request ID", value = requestId)
    }
}

@Composable
private fun ConnectedState(origin: String?, requestId: String?, session: String) {
    // Connected - show success icon
    Text(
        text = "✓",
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = "Connected",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    // Connection Details
    Spacer(modifier = Modifier.height(8.dp))

    if (origin != null) {
        InfoRow(label = "Origin", value = origin)
    }
    if (requestId != null) {
        InfoRow(label = "Request ID", value = requestId)
    }
    InfoRow(label = "Session", value = session)
}

@Composable
private fun DisconnectedState(session: String) {
    // Disconnected state (no error, but not connected)
    Text(
        text = "⚠",
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        text = "Disconnected",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
    Text(
        text = "Session: $session",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
    )
}
