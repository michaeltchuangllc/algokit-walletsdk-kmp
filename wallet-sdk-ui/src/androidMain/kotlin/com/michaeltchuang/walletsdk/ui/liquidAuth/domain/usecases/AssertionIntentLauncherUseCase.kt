package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Use case to encapsulate registration and result handling for assertion ActivityResultLauncher.
 */
class AssertionIntentLauncherUseCase(
    private val handleAssertionResultUseCase: HandleAssertionResultUseCase,
) {
    /**
     * Registers a launcher for assertion intent, wiring the provided callback to receive Result type.
     */
    operator fun invoke(
        activity: AppCompatActivity,
        viewModel: AnswerViewModel,
        callback: (HandleAssertionResultUseCase.Result) -> Unit,
    ): ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
            ActivityResultCallback { activityResult ->
                CoroutineScope(Dispatchers.Main).launch {
                    val result =
                        handleAssertionResultUseCase(
                            activityResult = activityResult,
                            viewModel = viewModel,
                        )
                    callback(result)
                }
            },
        )
}
