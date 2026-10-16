package com.michaeltchuang.walletsdk.ui.settings.utils.debug

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.createLiquidAuthConnectionManager

@Composable
actual fun rememberLiveDebugConnectionManager(): LiquidAuthConnectionManager {
    val context = LocalContext.current
    return remember(context) { createLiquidAuthConnectionManager(context) }
}

@Composable
actual fun LiveDebugPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val permissions = remember { arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) }

    fun hasPermissions() = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    var granted by remember(context) { mutableStateOf(hasPermissions()) }
    var requested by remember { mutableStateOf(false) }
    var requestInFlight by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasPermissions()
            requestInFlight = false
        }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text("Live debug needs camera and microphone permissions before starting the preview and host.")
            if (requested && !requestInFlight) {
                Text("Both permissions are required. Retry, or enable them in app settings if Android no longer asks.")
            }
            Button(
                enabled = !requestInFlight,
                onClick = {
                    granted = hasPermissions()
                    if (!granted) {
                        requested = true
                        requestInFlight = true
                        launcher.launch(permissions)
                    }
                },
            ) {
                Text(if (requested) "Retry permissions" else "Grant permissions")
            }
        }
    }
}
