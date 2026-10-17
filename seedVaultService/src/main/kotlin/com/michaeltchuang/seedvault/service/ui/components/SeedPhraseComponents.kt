package com.michaeltchuang.seedvault.service.ui.components

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.michaeltchuang.seedvault.service.R
import com.michaeltchuang.seedvault.service.ui.preview.SeedVaultPreviewData
import com.michaeltchuang.seedvault.service.ui.theme.SeedVaultTheme

/**
 * Keeps the seed phrase out of screenshots, screen recordings and the recents thumbnail while
 * the calling screen is shown.
 */
@Composable
fun SecureScreen() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * Seed phrase words in two numbered columns, read top to bottom (1-13 left, 14-25 right).
 * Shows a spinner until the words are loaded.
 */
@Composable
fun SeedPhraseGrid(
    words: List<String>,
    modifier: Modifier = Modifier,
) {
    if (words.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
        }
        return
    }

    val rows = (words.size + 1) / 2
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeedWord(index = row, word = words[row], modifier = Modifier.weight(1f))
                val right = row + rows
                if (right < words.size) {
                    SeedWord(index = right, word = words[right], modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SeedWord(
    index: Int,
    word: String,
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
        Text(
            text = word,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Red tinted callout used above the seed phrase.
 */
@Composable
fun SeedPhraseWarning(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Icon in a round chip with a teal glow behind it, the hero of the seed phrase screens.
 */
@Composable
fun GlowingIcon(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(136.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            listOf(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f), Color.Transparent),
                        ),
                    ),
        )
        Box(
            modifier =
                Modifier
                    .size(80.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1615)
@Composable
private fun SeedPhraseGridPreview() {
    SeedVaultTheme {
        SeedPhraseGrid(words = SeedVaultPreviewData.seedPhrase, modifier = Modifier.padding(16.dp))
    }
}
