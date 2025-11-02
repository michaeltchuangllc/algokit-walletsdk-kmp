package com.michaeltchuang.walletsdk.ui.accountdetails.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.next
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.final_class.webview_multiplatform_mobile.webview.WebViewPlatform
import com.final_class.webview_multiplatform_mobile.webview.controller.rememberWebViewController
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun AccountDetailWebviewItem(
    icon: DrawableResource,
    title: String,
    url: String,
) {
    val webViewController by rememberWebViewController()
    WebViewPlatform(webViewController = webViewController)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { webViewController.open(url) }
                .padding(vertical = 12.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = title,
            tint = AlgoKitTheme.colors.textMain,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            color = AlgoKitTheme.colors.textMain,
            modifier = Modifier.weight(1f),
            style = AlgoKitTheme.typography.body.regular.sansMedium,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            tint = AlgoKitTheme.colors.textMain,
            contentDescription = localizedStringResource(Res.string.next),
        )
    }
}
