package com.michaeltchuang.walletsdk.ui.settings.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.are_you_sure_remove_passkey
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.cancel
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_passkey
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_passkeys
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.last_used
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.loading
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.no_passkeys_yet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.passkeys_title
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.remove
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.remove_passkey
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.username
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.PasskeysViewModel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PasskeysScreen(onBackClick: () -> Unit) {
    val viewModel: PasskeysViewModel = koinViewModel()
    val viewState = viewModel.state.collectAsStateWithLifecycle().value
    val showDeleteConfirm = remember { mutableStateOf(false) }
    val selectedPasskey = remember { mutableStateOf<PasskeysViewModel.Passkey?>(null) }

    LaunchedEffect(Unit) {
        viewModel.fetchPasskeys()
    }
    ScreenContentPasskeys(
        viewState = viewState,
        onBackClick = onBackClick,
        onDeletePasskey = { passkey ->
            selectedPasskey.value = passkey
            showDeleteConfirm.value = true
        },
        showDeleteConfirm = showDeleteConfirm.value,
        selectedPasskey = selectedPasskey.value,
        onDeleteConfirm = {
            selectedPasskey.value?.let {
                viewModel.deletePasskey(it.credId)
                showDeleteConfirm.value = false
                selectedPasskey.value = null
            }
        },
        onDeleteDismiss = {
            showDeleteConfirm.value = false
        },
    )
}

@Composable
fun ScreenContentPasskeys(
    viewState: PasskeysViewModel.ViewState,
    onBackClick: () -> Unit = {},
    onDeletePasskey: (PasskeysViewModel.Passkey) -> Unit = {},
    showDeleteConfirm: Boolean = false,
    selectedPasskey: PasskeysViewModel.Passkey? = null,
    onDeleteConfirm: () -> Unit = {},
    onDeleteDismiss: () -> Unit = {},
) {
    Box(
        modifier = Modifier.background(color = AlgoKitTheme.colors.background).fillMaxSize().padding(horizontal = 16.dp),
    ) {
        ScreenContent(
            viewState = viewState,
            onBackClick = onBackClick,
            onDeletePasskey = onDeletePasskey,
        )
        if (showDeleteConfirm && selectedPasskey != null) {
            AlertDialog(
                onDismissRequest = onDeleteDismiss,
                title = { Text(localizedStringResource(Res.string.remove_passkey)) },
                text = { Text(localizedStringResource(Res.string.are_you_sure_remove_passkey)) },
                confirmButton = {
                    Button(onClick = onDeleteConfirm) {
                        Text(localizedStringResource(Res.string.remove))
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDeleteDismiss) {
                        Text(localizedStringResource(Res.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
internal fun ScreenContent(
    viewState: PasskeysViewModel.ViewState,
    onBackClick: () -> Unit = {},
    onDeletePasskey: (PasskeysViewModel.Passkey) -> Unit = {},
) {
    Box(
        modifier =
            Modifier
                .background(color = AlgoKitTheme.colors.background)
                .fillMaxSize(),
    ) {
        Column {
            AlgoKitTopBar(
                title = localizedStringResource(Res.string.passkeys_title),
                onClick = onBackClick,
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (viewState) {
                is PasskeysViewModel.ViewState.Idle -> {}
                is PasskeysViewModel.ViewState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            localizedStringResource(Res.string.loading),
                            color = AlgoKitTheme.colors.textGray,
                        )
                    }
                }

                is PasskeysViewModel.ViewState.Content -> {
                    if (viewState.passkeys.isEmpty()) {
                        EmptyState()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(viewState.passkeys) { passkey ->
                                PasskeyListItem(
                                    passkey = passkey,
                                    onDelete = {
                                        onDeletePasskey(passkey)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasskeyListItem(
    passkey: PasskeysViewModel.Passkey,
    onDelete: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .border(
                    width = 1.dp,
                    color = AlgoKitTheme.colors.layerGray,
                    shape = RoundedCornerShape(16.dp),
                ).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = vectorResource(Res.drawable.ic_passkey),
                contentDescription = null,
                tint = AlgoKitTheme.colors.textMain,
            )
            Column(
                modifier =
                    Modifier
                        .padding(horizontal = 12.dp)
                        .weight(1f),
            ) {
                Text(
                    text = passkey.title,
                    color = AlgoKitTheme.colors.textMain,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = passkey.domain,
                    color = AlgoKitTheme.colors.textGray,
                )
            }
            Icon(
                modifier =
                    Modifier
                        .size(24.dp)
                        .padding(start = 4.dp)
                        .background(Color.Transparent)
                        .clickable { onDelete() },
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = AlgoKitTheme.colors.textGray,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        DetailsRow(localizedStringResource(Res.string.last_used), passkey.lastUsed)
        Spacer(modifier = Modifier.height(12.dp))
        DetailsRow(localizedStringResource(Res.string.username), passkey.username)
    }
}

@Composable
fun DetailsRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = AlgoKitTheme.colors.textGray,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = AlgoKitTheme.colors.textMain,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            imageVector = vectorResource(Res.drawable.ic_passkeys),
            contentDescription = null,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(Res.string.no_passkeys_yet),
            style = AlgoKitTheme.typography.body.regular.sans,
            color = AlgoKitTheme.colors.textMain,
        )
    }
}

@Preview
@Composable
fun PasskeysScreenContentPreview() {
    val fakePasskeys =
        listOf(
            PasskeysViewModel.Passkey(
                credId = "credId123",
                title = "Liquid Auth Passkey",
                domain = "liquid-auth.onrender.com",
                lastUsed = "Today",
                username = "kyle007",
            ),
            PasskeysViewModel.Passkey(
                credId = "credId123",
                title = "Liquid Auth Passkey",
                domain = "liquid-auth.onrender.com",
                lastUsed = "5d ago",
                username = "kyle007",
            ),
        )
    val fakeViewState = PasskeysViewModel.ViewState.Content(passkeys = fakePasskeys)
    ScreenContent(viewState = fakeViewState)
}
