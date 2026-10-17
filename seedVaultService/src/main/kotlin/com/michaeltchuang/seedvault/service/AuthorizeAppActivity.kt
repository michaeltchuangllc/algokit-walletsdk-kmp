package com.michaeltchuang.seedvault.service

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.michaeltchuang.seedvault.service.ui.components.SecureScreen
import com.michaeltchuang.seedvault.service.ui.screens.AuthorizeAppScreen
import com.michaeltchuang.seedvault.service.ui.theme.SeedVaultTheme

/**
 * Lets the user authorize the app that launched this activity to use their seeds. Client apps must
 * start it with startActivityForResult: the calling package comes from the system, so an app
 * cannot ask for authorization on behalf of another one. Without a caller the activity just closes.
 */
class AuthorizeAppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callerPackage = callingActivity?.packageName
        if (callerPackage == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val callerLabel = appLabel(callerPackage)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            SeedVaultTheme {
                SecureScreen()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AuthorizeAppScreen(
                        appPackageName = callerPackage,
                        appLabel = callerLabel,
                        onAllowed = {
                            setResult(Activity.RESULT_OK)
                            finish()
                        },
                        onDeny = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }

    private fun appLabel(packageName: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
}
