package com.michaeltchuang.seedvault.service

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.FragmentActivity
import com.michaeltchuang.seedvault.service.remote.SeedVaultContract
import com.michaeltchuang.seedvault.service.remote.SignApprovalCoordinator
import com.michaeltchuang.seedvault.service.remote.SignApprovalRequest
import com.michaeltchuang.seedvault.service.remote.SignOperation
import com.michaeltchuang.seedvault.service.ui.navigation.AuthorizeApp
import com.michaeltchuang.seedvault.service.ui.navigation.ConfirmSign
import com.michaeltchuang.seedvault.service.ui.navigation.SeedVaultNavHost
import com.michaeltchuang.seedvault.service.ui.navigation.SeedVaultSettings
import com.michaeltchuang.seedvault.service.ui.theme.SeedVaultTheme
import org.koin.android.ext.android.inject
import java.math.BigDecimal
import android.graphics.Color as AndroidColor

/**
 * The only activity of Seed Vault. It opens on the settings screen, or straight on:
 * - the authorize screen, for a client app that started it with the AUTHORIZE_APP action and
 *   startActivityForResult. The calling package comes from the system, so an app cannot ask for
 *   authorization on behalf of another one. Without a caller the activity just closes.
 * - the confirm sign screen, for a client app that is about to ask for a signature. It starts it
 *   with the CONFIRM_SIGN action and startActivityForResult, since a service cannot open a screen
 *   from the background, and calls the AIDL sign method after RESULT_OK.
 */
class MainActivity : FragmentActivity() {
    private val signApprovals: SignApprovalCoordinator by inject()
    private var confirmRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startDestination = startDestination()
        if (startDestination == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        // The Seed Vault look is dark only, so keep light system bar icons in both modes
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        setContent {
            SeedVaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    // The confirm sign sheet floats over the calling app, so nothing is painted behind it
                    color = if (startDestination is ConfirmSign) Color.Transparent else MaterialTheme.colorScheme.background,
                ) {
                    SeedVaultNavHost(
                        onExit = ::finish,
                        startDestination = startDestination,
                        onFlowFinished = ::finishFlow,
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Leaving the confirm sign screen any other way is a no
        if (isFinishing) confirmRequestId?.let { signApprovals.resolve(it, false) }
        super.onDestroy()
    }

    /** Null when the intent asks for something that cannot be shown. */
    private fun startDestination(): Any? =
        when (intent.action) {
            SeedVaultContract.ACTION_AUTHORIZE_APP -> {
                // Only the system knows who started this activity for a result
                callingActivity?.packageName?.let { AuthorizeApp(it, appLabel(it)) }
            }
            SeedVaultContract.ACTION_CONFIRM_SIGN -> confirmSignDestination()
            else -> SeedVaultSettings
        }

    /**
     * The client starts this screen itself with the values it is about to have signed, then calls
     * the AIDL sign method. Null when the request is malformed or has no caller.
     */
    private fun confirmSignDestination(): Any? {
        val callerPackage = callingActivity?.packageName ?: return null
        val seedId = intent.getLongExtra(SeedVaultContract.EXTRA_SEED_ID, NO_SEED_ID)
        if (seedId == NO_SEED_ID) return null
        val receiver = intent.getStringExtra(SeedVaultContract.EXTRA_RECEIVER)
        val operation: SignOperation
        val description: String
        if (receiver != null) {
            val amount = intent.getLongExtra(SeedVaultContract.EXTRA_AMOUNT_MICRO_ALGOS, NO_AMOUNT)
            val paramsJson = intent.getStringExtra(SeedVaultContract.EXTRA_PARAMS_JSON)
            if (amount == NO_AMOUNT || paramsJson == null) return null
            operation = SignOperation.payment(seedId, receiver, amount, paramsJson)
            val algos = BigDecimal(amount).movePointLeft(MICRO_ALGO_DECIMALS).toPlainString()
            description = getString(R.string.confirm_sign_payment, algos, receiver)
        } else {
            val data = intent.getByteArrayExtra(SeedVaultContract.EXTRA_DATA) ?: return null
            operation = SignOperation.bytes(seedId, data)
            description = getString(R.string.confirm_sign_bytes, data.size)
        }
        val requestId =
            signApprovals.begin(
                SignApprovalRequest(callerPackage, appLabel(callerPackage), operation, description),
            )
        confirmRequestId = requestId
        return ConfirmSign(requestId)
    }

    private fun finishFlow(approved: Boolean) {
        setResult(if (approved) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    private fun appLabel(packageName: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

    private companion object {
        const val NO_SEED_ID = -1L
        const val NO_AMOUNT = -1L
        const val MICRO_ALGO_DECIMALS = 6
    }
}
