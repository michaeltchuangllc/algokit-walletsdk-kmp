package com.michaeltchuang.walletsdk.seedvault.demo

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.michaeltchuang.walletsdk.seedvault.demo.ui.navigation.SeedVaultNavHost
import com.michaeltchuang.walletsdk.seedvault.demo.ui.theme.SeedVaultTheme
import com.michaeltchuang.walletsdk.seedvault.demo.ui.viewmodel.SeedVaultViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: SeedVaultViewModel by viewModels { SeedVaultViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Seed Vault look is dark only, so keep light system bar icons in both modes
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            SeedVaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SeedVaultNavHost(
                        viewModel = viewModel,
                        onExit = ::finish,
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }
}
