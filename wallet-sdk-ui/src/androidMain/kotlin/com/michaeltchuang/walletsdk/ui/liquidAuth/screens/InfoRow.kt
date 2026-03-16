package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.R
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme

/**
 * Info Row
 *
 * A reusable component for displaying label-value pairs
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
) {
    val normalizedLabel = label.removeSuffix(":")
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text =
                when (normalizedLabel) {
                    "Session" -> stringResource(R.string.account_details)
                    "Origin" -> stringResource(R.string.origin)
                    "Request ID" -> stringResource(R.string.request_id)
                    else -> normalizedLabel
                },
            style = MaterialTheme.typography.labelSmall,
            color = AlgoKitTheme.colors.textGray,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = AlgoKitTheme.colors.textMain,
            fontWeight = FontWeight.Normal,
        )
    }
}
