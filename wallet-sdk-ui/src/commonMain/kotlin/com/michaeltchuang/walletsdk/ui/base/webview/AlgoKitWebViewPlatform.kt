package com.michaeltchuang.walletsdk.ui.base.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.final_class.webview_multiplatform_mobile.webview.WebViewPlatform
import com.final_class.webview_multiplatform_mobile.webview.controller.WebViewController
import com.final_class.webview_multiplatform_mobile.webview.controller.rememberWebViewController

/**
 * Opens a URL in the external browser (Safari on iOS, Chrome/default browser on Android).
 * This is the preferred method to avoid iOS WebView timeout issues.
 */
fun WebViewController.openUrl(url: String) {
    openInExternalBrowser(url)
}

@Composable
fun AlgoKitWebViewPlatformScreen(url: String) {
    val webViewController by rememberWebViewController()
    WebViewPlatform(webViewController = webViewController)
    LaunchedEffect(Unit) {
        webViewController.openUrl(url)
    }
}
