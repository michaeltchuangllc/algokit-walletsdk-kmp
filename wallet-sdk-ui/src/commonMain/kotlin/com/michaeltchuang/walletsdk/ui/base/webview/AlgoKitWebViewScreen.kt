package com.michaeltchuang.walletsdk.ui.base.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState

@Composable
fun AlgoKitWebViewScreen(
    modifier: Modifier,
    url: String,
) {
    val state = rememberWebViewState(url)
    val navigator = rememberWebViewNavigator()

    // Explicitly load URL to ensure it loads properly
    LaunchedEffect(url) {
        navigator.loadUrl(url)
    }

    WebView(
        state = state,
        modifier = modifier,
        navigator = navigator,
    )
}
