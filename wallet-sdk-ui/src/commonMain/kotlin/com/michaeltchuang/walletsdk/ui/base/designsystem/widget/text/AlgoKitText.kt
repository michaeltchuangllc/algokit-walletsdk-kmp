package com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.error
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_error
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun AlgoKitHeadlineText(
    modifier: Modifier = Modifier,
    text: String,
) {
    Text(
        modifier = modifier,
        text = text,
        style = AlgoKitTheme.typography.title.regular.sansMedium,
        color = AlgoKitTheme.colors.textMain,
    )
}

@Composable
fun AlgoKitTitleText(
    modifier: Modifier = Modifier,
    text: String,
    color: Color = AlgoKitTheme.colors.textMain,
) {
    Text(
        modifier = modifier,
        text = text,
        style = AlgoKitTheme.typography.body.large.sansMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun AlgoKitBodyText(
    modifier: Modifier = Modifier,
    text: String,
    textAlign: TextAlign = TextAlign.Left,
    color: Color = AlgoKitTheme.colors.textGray,
) {
    Text(
        modifier = modifier,
        text = text,
        style = AlgoKitTheme.typography.body.regular.sansMedium,
        color = color,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

@Composable
fun AlgoKitBodyText(
    modifier: Modifier = Modifier,
    text: AnnotatedString,
    onTextLayout: (TextLayoutResult) -> Unit,
) {
    Text(
        modifier = modifier,
        text = text,
        style = AlgoKitTheme.typography.body.regular.sansMedium,
        color = AlgoKitTheme.colors.textGray,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = onTextLayout,
    )
}

@Composable
fun AlgoKitLinkText(
    modifier: Modifier = Modifier,
    text: String,
) {
    Text(
        modifier = modifier,
        text = text,
        style = AlgoKitTheme.typography.body.regular.sansMedium,
        color = AlgoKitTheme.colors.linkPrimary,
    )
}

@Composable
fun AlgoKitScrimText(
    modifier: Modifier = Modifier,
    text: String,
) {
    Text(
        modifier = modifier,
        text = text,
        style = AlgoKitTheme.typography.body.regular.sansMedium,
        color = AlgoKitTheme.colors.linkPrimary,
    )
}

@Composable
fun AlgoKitFootnoteText(
    modifier: Modifier = Modifier,
    text: String,
) {
    Text(
        modifier = modifier,
        text = text,
        style = AlgoKitTheme.typography.body.regular.sansMedium,
        color = AlgoKitTheme.colors.linkPrimary,
    )
}

@Composable
fun AlgoKitWarningText(
    modifier: Modifier = Modifier,
    text: String,
) {
    Row(modifier = modifier) {
        Image(
            modifier = Modifier.align(alignment = Alignment.CenterVertically),
            painter = painterResource(Res.drawable.ic_error),
            colorFilter = ColorFilter.tint(color = AlgoKitTheme.colors.negative),
            contentDescription = localizedStringResource(Res.string.error),
        )
        Text(
            modifier = Modifier.padding(start = 12.dp),
            text = text,
            style = AlgoKitTheme.typography.body.regular.sansMedium,
            color = AlgoKitTheme.colors.negative,
        )
    }
}

@Composable
fun AlgoKitHighlightedText(
    modifier: Modifier = Modifier,
    text: String,
    textColor: Color,
    backgroundColor: Color,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(color = backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            modifier =
                Modifier.padding(
                    horizontal = 8.dp,
                    vertical = 4.dp,
                ),
            text = text,
            style = AlgoKitTheme.typography.caption.sansMedium,
            color = textColor,
        )
    }
}

@Composable
fun AlgoKitHighlightedGreenText(
    modifier: Modifier = Modifier,
    text: String,
) {
    AlgoKitHighlightedText(
        modifier = modifier,
        text = text,
        textColor = AlgoKitTheme.colors.wallet4Icon,
        backgroundColor = AlgoKitTheme.colors.wallet4,
    )
}

@Composable
fun AlgoKitHighlightedGrayText(
    modifier: Modifier = Modifier,
    text: String,
) {
    AlgoKitHighlightedText(
        modifier = modifier,
        text = text,
        textColor = AlgoKitTheme.colors.textGray,
        backgroundColor = AlgoKitTheme.colors.textGrayLighter,
    )
}
