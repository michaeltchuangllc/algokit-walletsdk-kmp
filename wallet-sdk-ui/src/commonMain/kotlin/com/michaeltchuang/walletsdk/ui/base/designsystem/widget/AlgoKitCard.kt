package com.michaeltchuang.walletsdk.ui.base.designsystem.widget

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_algo25_description
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_algo25_footer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mnemonic_type_algo25_title
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.recommended
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitBodyText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitHighlightedGrayText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitLinkText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitTitleText
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun MnemonicTypeCard(
    title: String,
    description: String,
    footer: String,
    highlightContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 24.dp,
                ),
        onClick = onClick,
        shape = CardDefaults.outlinedShape,
        border = CardDefaults.outlinedCardBorder(enabled = true),
        colors = CardDefaults.outlinedCardColors(containerColor = AlgoKitTheme.colors.background),
    ) {
        Column(Modifier.padding(all = 20.dp)) {
            Row {
                AlgoKitTitleText(text = title)
                highlightContent?.let {
                    Spacer(Modifier.width(8.dp))
                    it()
                }
            }

            Row(
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlgoKitBodyText(modifier = Modifier.weight(1f), text = description)
                Box(
                    modifier =
                        Modifier
                            .padding(start = 10.dp)
                            .size(40.dp)
                            .clip(shape = CircleShape)
                            .background(color = AlgoKitTheme.colors.layerGrayLighter),
                ) {
                    Icon(
                        modifier = Modifier.align(Alignment.Center),
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        tint = AlgoKitTheme.colors.textGrayLighter,
                        contentDescription = "Right Arrow",
                    )
                }
            }
            AlgoKitLinkText(text = footer)
        }
    }
}

@Preview
@Composable
fun PeraCardPreview() {
    MnemonicTypeCard(
        title = localizedStringResource(Res.string.mnemonic_type_algo25_title),
        description = localizedStringResource(Res.string.mnemonic_type_algo25_description),
        footer = localizedStringResource(Res.string.mnemonic_type_algo25_footer),
        highlightContent = {
            AlgoKitHighlightedGrayText(
                text = localizedStringResource(Res.string.recommended),
            )
        },
        onClick = { },
    )
}
