package com.michaeltchuang.walletsdk.ui.settings.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.dark
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.light
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.system_default
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.theme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalThemeIsDark
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import com.michaeltchuang.walletsdk.ui.settings.domain.theme.ThemePreference
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.ThemePickerViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

private const val TAG = "ThemePickerScreen"

fun resolveIsDark(
    theme: ThemePreference,
    systemDark: Boolean,
): Boolean =
    when (theme) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> systemDark
    }

@Composable
fun ThemeScreen(navController: NavController) {
    val viewModel: ThemePickerViewModel = koinViewModel()
    val viewState by viewModel.state.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = LocalThemeIsDark.current

    // Handle ViewEvents
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is ThemePickerViewModel.ViewEvent.ThemeChanged -> {
                    Log.d(TAG, "Theme changed to: ${event.theme}")
                    // Update the theme based on preference
                    isDark.value = resolveIsDark(event.theme, systemDark)
                }

                is ThemePickerViewModel.ViewEvent.Error -> {
                    Log.e(TAG, "Error: ${event.message}")
                }
            }
        }
    }

    ScreenContent(
        navController = navController,
        viewState = viewState,
        onThemeSelected = { viewModel.onThemeSelected(it) },
    )
}

@Composable
fun ScreenContent(
    navController: NavController,
    viewState: ThemePickerViewModel.ViewState,
    onThemeSelected: (ThemePreference) -> Unit = {},
) {
    @Composable
    fun ThemePreference.displayString() =
        when (this) {
            ThemePreference.LIGHT -> localizedStringResource(Res.string.light)
            ThemePreference.DARK -> localizedStringResource(Res.string.dark)
            ThemePreference.SYSTEM -> localizedStringResource(Res.string.system_default)
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color = AlgoKitTheme.colors.background)
                .padding(horizontal = 16.dp),
    ) {
        AlgoKitTopBar(
            title = localizedStringResource(Res.string.theme),
        ) {
            navController.popBackStack()
        }
        Spacer(modifier = Modifier.height(8.dp))

        when (val state = viewState) {
            is ThemePickerViewModel.ViewState.Loading -> {
                // Could add a loading indicator here if needed
            }
            is ThemePickerViewModel.ViewState.Idle -> {
                // Initial state
            }

            is ThemePickerViewModel.ViewState.Content -> {
                state.themeOptions.forEach { themeOption ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onThemeSelected(themeOption) }
                                .padding(vertical = 16.dp),
                    ) {
                        Text(
                            text = themeOption.displayString(),
                            color = AlgoKitTheme.colors.textMain,
                            modifier = Modifier.weight(1f),
                            style = AlgoKitTheme.typography.body.regular.sansMedium,
                        )
                        RadioButton(
                            selected = themeOption == state.currentTheme,
                            onClick = { onThemeSelected(themeOption) },
                            colors =
                                RadioButtonDefaults.colors(
                                    selectedColor = AlgoKitTheme.colors.positive,
                                    unselectedColor = Color.LightGray,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun ThemeScreenPreview() {
    AlgoKitTheme {
        ThemeScreen(rememberNavController())
    }
}
