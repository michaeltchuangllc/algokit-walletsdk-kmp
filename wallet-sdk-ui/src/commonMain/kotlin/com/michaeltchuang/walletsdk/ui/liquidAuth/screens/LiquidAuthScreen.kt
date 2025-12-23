package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.liquidAuth.connect
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LiquidAuthScreen(
    navController: NavController,
    uri: String?,
) {
    val viewModel: LiquidAuthViewModel = koinViewModel()
    val viewState = viewModel.state.collectAsStateWithLifecycle().value
    val onConnect = remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        viewModel.initialize(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is LiquidAuthViewModel.ViewEvent.AuthenticationSuccess -> {
                    navController.popBackStack()
                }

                is LiquidAuthViewModel.ViewEvent.AuthenticationError -> {
                }
            }
        }
    }

    ScreenContent(
        viewState = viewState,
        onBackClick = { navController.popBackStack() },
        onConnect = { onConnect.value = true },
    )
    if (onConnect.value) {
        connect(viewModel.authMessage)
        onConnect.value = false
    }
}

@Composable
internal fun ScreenContent(
    viewState: LiquidAuthViewModel.ViewState = LiquidAuthViewModel.ViewState.Idle,
    onBackClick: () -> Unit = {},
    onConnect: () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .background(color = AlgoKitTheme.colors.background)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
    ) {
        AlgoKitTopBar(
            title = "Liquid Auth",
            onClick = onBackClick,
        )
        Spacer(modifier = Modifier.height(24.dp))

        when (viewState) {
            is LiquidAuthViewModel.ViewState.Idle -> {
                CenteredMessage("Initializing...")
            }

            is LiquidAuthViewModel.ViewState.Loading -> {
                CenteredContent {
                    CircularProgressIndicator(color = AlgoKitTheme.colors.textMain)
                }
            }

            is LiquidAuthViewModel.ViewState.Content -> {
                ContentView(
                    origin = viewState.origin,
                    requestId = viewState.requestId,
                    rawUri = viewState.rawUri,
                    onConnect = onConnect,
                )
            }

            is LiquidAuthViewModel.ViewState.Error -> {
                CenteredContent {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Error",
                            style = AlgoKitTheme.typography.title.regular.sansMedium,
                            color = AlgoKitTheme.colors.textMain,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = viewState.message,
                            style = AlgoKitTheme.typography.body.regular.sans,
                            color = AlgoKitTheme.colors.textGray,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(message: String) {
    CenteredContent {
        Text(
            text = message,
            style = AlgoKitTheme.typography.body.regular.sans,
            color = AlgoKitTheme.colors.textGray,
        )
    }
}

@Composable
private fun CenteredContent(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ContentView(
    origin: String,
    requestId: String,
    rawUri: String,
    onConnect: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            InfoField(label = "Origin", value = origin)
            Spacer(modifier = Modifier.height(24.dp))
            InfoField(label = "Request ID", value = requestId)
            Spacer(modifier = Modifier.height(24.dp))
            InfoField(
                label = "Raw URI",
                value = rawUri,
                valueColor = AlgoKitTheme.colors.textGray,
            )
        }

        AlgoKitPrimaryButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onConnect,
            text = "Connect",
            state = AlgoKitButtonState.ENABLED,
        )
    }
}

@Composable
private fun InfoField(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = AlgoKitTheme.colors.textMain,
) {
    Text(
        text = label,
        style = AlgoKitTheme.typography.body.regular.sansMedium,
        color = AlgoKitTheme.colors.textGray,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = value,
        style = AlgoKitTheme.typography.body.regular.sans,
        color = valueColor,
    )
}

@Preview
@Composable
fun LiquidAuthScreenPreview() {
    ScreenContent()
}
