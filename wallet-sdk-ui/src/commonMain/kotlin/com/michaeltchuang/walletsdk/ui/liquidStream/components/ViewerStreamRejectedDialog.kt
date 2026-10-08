package com.michaeltchuang.walletsdk.ui.liquidStream.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ok
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.stream_rejected_title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.DialogProperties
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthViewerStateHolder
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource

/**
 * Blocking modal shown when the host refuses this viewer (e.g. the same wallet address is
 * already watching the stream). Tapping OK tears the viewer connection down; [onAcknowledged]
 * lets each platform close its viewer UI afterwards.
 */
@Composable
fun ViewerStreamRejectedDialog(
    stateHolder: LiquidAuthViewerStateHolder,
    onAcknowledged: () -> Unit,
) {
    val message by stateHolder.streamRejectedMessage.collectAsState()
    val text = message ?: return

    val acknowledge = {
        stateHolder.acknowledgeStreamRejected()
        onAcknowledged()
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(localizedStringResource(Res.string.stream_rejected_title)) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = acknowledge) {
                Text(localizedStringResource(Res.string.ok))
            }
        },
    )
}
