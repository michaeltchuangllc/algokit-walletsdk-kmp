package com.michaeltchuang.walletsdk.ui.settings.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.app_preferences
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.developer_settings
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.get_help
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_code
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_feedback
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_localization
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_moon
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_text_document
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.localization_settings
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.next
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.privacy_policy
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.settings
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.support
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.terms_and_services
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.theme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.final_class.webview_multiplatform_mobile.webview.WebViewPlatform
import com.final_class.webview_multiplatform_mobile.webview.controller.rememberWebViewController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.ui.BuildInfo
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.isDebugBuild
import com.michaeltchuang.walletsdk.ui.settings.components.SettingsItem
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun SettingsScreen(navController: NavController) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color = AlgoKitTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings),
            modifier =
                Modifier
                    .padding(vertical = 16.dp),
            color = AlgoKitTheme.colors.textMain,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Section: App Preferences
        Text(
            text = stringResource(Res.string.app_preferences),
            color = AlgoKitTheme.colors.textMain,
            style = AlgoKitTheme.typography.body.regular.sansMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        SettingsItem(
            icon = Res.drawable.ic_moon,
            title = stringResource(Res.string.theme),
        ) {
            navController.navigate(AlgoKitScreens.THEME_SCREEN.name)
        }
        SettingsItem(
            icon = Res.drawable.ic_localization,
            title = stringResource(Res.string.localization_settings),
        ) {
            navController.navigate(AlgoKitScreens.LANGUAGE_SCREEN.name)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Section: Support
        Text(
            text = stringResource(Res.string.support),
            color = AlgoKitTheme.colors.textMain,
            style = AlgoKitTheme.typography.body.regular.sansMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        SettingsWebviewItem(
            Res.drawable.ic_feedback,
            stringResource(Res.string.get_help),
            WalletSdkConstants.SUPPORT_URL,
        )
        SettingsWebviewItem(
            Res.drawable.ic_text_document,
            stringResource(Res.string.privacy_policy),
            WalletSdkConstants.PRIVACY_POLICY_URL,
        )
        SettingsWebviewItem(
            Res.drawable.ic_text_document,
            stringResource(Res.string.terms_and_services),
            WalletSdkConstants.TERMS_AND_SERVICES_URL,
        )
        SettingsItem(
            Res.drawable.ic_code,
            stringResource(Res.string.developer_settings),
        ) {
            navController.navigate(AlgoKitScreens.DEVELOPER_SETTINGS_SCREEN.name)
        }

        Spacer(modifier = Modifier.height(32.dp))

        DisplayVersionInfo()
    }
}

@Composable
fun DisplayVersionInfo() {
    val debugLabel = if (isDebugBuild()) "Debug" else ""
    val versionText =
        buildString {
            append("AlgoKit Wallet SDK")
            append("\nv${BuildInfo.VERSION_NAME}")
            if (BuildInfo.GIT_HASH.isNotEmpty()) {
                append("\n(${BuildInfo.GIT_HASH})")
            }
            if (debugLabel.isNotEmpty()) {
                append("\n$debugLabel Build")
            }
        }
    Text(
        text = versionText,
        color = AlgoKitTheme.colors.textMain,
        style = AlgoKitTheme.typography.footnote.sansMedium,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
fun SettingsWebviewItem(
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
            contentDescription = stringResource(Res.string.next),
        )
    }
}

@Preview
@Composable
fun SettingsScreenPreview() {
    AlgoKitTheme {
        SettingsScreen(navController = rememberNavController())
    }
}
