package com.michaeltchuang.walletsdk.demo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme

/**
 * iOS stub for DiscoverScreen.
 *
 * The Payment Test screen relies on Algorand SDK signing APIs that are
 * only available on Android.  iOS support can be added in a future iteration.
 */
@Composable
actual fun PaymentTestScreen() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AlgoKitTheme.colors.background)
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Feature Not Available",
            style = MaterialTheme.typography.headlineMedium,
            color = AlgoKitTheme.colors.textMain,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "The Payment Test screen is currently only supported on Android.",
            style = MaterialTheme.typography.bodyLarge,
            color = AlgoKitTheme.colors.textGray,
            textAlign = TextAlign.Center,
        )
    }
}
