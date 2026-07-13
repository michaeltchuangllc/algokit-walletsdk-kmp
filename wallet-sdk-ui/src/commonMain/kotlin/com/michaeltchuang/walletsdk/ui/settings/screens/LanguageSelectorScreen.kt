package com.michaeltchuang.walletsdk.ui.settings.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.english
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.hindi
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.italian
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.localization_screen_title
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocalizationPreference
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.LanguageSelectorViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

private const val TAG = "LanguageSelectorScreen"

@Composable
fun LanguageScreen(navController: NavController) {
    val viewModel: LanguageSelectorViewModel = koinViewModel()
    val viewState by viewModel.state.collectAsState()

    // Handle ViewEvents
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is LanguageSelectorViewModel.ViewEvent.LanguageChanged -> {
                    Log.d(TAG, "Language changed to: ${event.language}")
                }

                is LanguageSelectorViewModel.ViewEvent.Error -> {
                    Log.e(TAG, "Error: ${event.message}")
                }
            }
        }
    }

    ScreenContent(
        navController = navController,
        viewState = viewState,
        onLanguageSelected = { viewModel.onLanguageSelected(it) },
    )
}

@Composable
 fun ScreenContent(
    navController: NavController,
    viewState: LanguageSelectorViewModel.ViewState,
    onLanguageSelected: (LocalizationPreference) -> Unit = {},
) {
    @Composable
    fun LocalizationPreference.displayString() =
        when (this) {
            LocalizationPreference.ITALIAN -> localizedStringResource(Res.string.italian)
            LocalizationPreference.ENGLISH -> localizedStringResource(Res.string.english)
            LocalizationPreference.HINDI -> localizedStringResource(Res.string.hindi)
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color = AlgoKitTheme.colors.background)
                .padding(horizontal = 16.dp),
    ) {
        AlgoKitTopBar(
            title = localizedStringResource(Res.string.localization_screen_title),
        ) {
            navController.popBackStack()
        }
        Spacer(modifier = Modifier.height(8.dp))

        when (val state = viewState) {
            is LanguageSelectorViewModel.ViewState.Loading -> {
                // Could add a loading indicator here if needed
            }
            is LanguageSelectorViewModel.ViewState.Idle -> {
                // Initial state
            }

            is LanguageSelectorViewModel.ViewState.Content -> {
                state.languageOptions.forEach { languageOption ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLanguageSelected(languageOption) }
                                .padding(vertical = 16.dp),
                    ) {
                        Text(
                            text = languageOption.displayString(),
                            color = AlgoKitTheme.colors.textMain,
                            modifier = Modifier.weight(1f),
                            style = AlgoKitTheme.typography.body.regular.sansMedium,
                        )
                        RadioButton(
                            selected = languageOption == state.currentLanguage,
                            onClick = { onLanguageSelected(languageOption) },
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
fun LanguageScreenPreview() {
    AlgoKitTheme {
        LanguageScreen(rememberNavController())
    }
}
