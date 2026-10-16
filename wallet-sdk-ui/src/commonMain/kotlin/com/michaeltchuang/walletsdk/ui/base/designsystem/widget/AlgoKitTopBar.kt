package com.michaeltchuang.walletsdk.ui.base.designsystem.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun AlgoKitTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    showInfoIcon: Boolean = false,
    onInfoClick: () -> Unit = {},
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .height(50.dp),
    ) {
        IconButton(
            onClick = { onClick() },
            modifier =
                Modifier
                    .size(48.dp)
                    .align(alignment = Alignment.CenterVertically),
        ) {
            Icon(
                tint = AlgoKitTheme.colors.textMain,
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }
        title?.let {
            Text(
                text = title,
                modifier =
                    modifier
                        .padding(start = 16.dp)
                        .align(alignment = Alignment.CenterVertically),
                color = AlgoKitTheme.colors.textMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (showInfoIcon) {
            IconButton(
                onClick = { onInfoClick() },
                modifier =
                    Modifier
                        .size(24.dp)
                        .align(alignment = Alignment.CenterVertically),
            ) {
                Icon(
                    tint = AlgoKitTheme.colors.textMain,
                    imageVector = Icons.Default.Info,
                    contentDescription = "Info",
                )
            }
        }
    }
}

@Preview
@Composable
fun AlgoKitTopBarPreview() {
    AlgoKitTopBar(
        title = "Settings",
        onClick = { },
    )
}

@Preview
@Composable
fun AlgoKitTopBarWithoutTitlePreview() {
    AlgoKitTopBar(
        onClick = { },
    )
}

@Preview
@Composable
fun AlgoKitTopBarWithInfoIconPreview() {
    AlgoKitTopBar(
        title = "Create Watch Account",
        onClick = { },
        showInfoIcon = true,
        onInfoClick = { },
    )
}
