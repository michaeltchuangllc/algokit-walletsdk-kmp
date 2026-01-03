package com.michaeltchuang.walletsdk.ui.liquidAuth.usecases

import android.content.Context
import android.os.Build
import android.util.Log

class LogAppSignatureUseCase {
    operator fun invoke(context: Context, tag: String = context.javaClass.simpleName) {
        try {
            val packageInfo =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.GET_SIGNATURES,
                    )
                }

            val signatures =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.signingInfo?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.signatures
                }

            signatures?.forEach { signature ->
                val md = java.security.MessageDigest.getInstance("SHA-256")
                md.update(signature.toByteArray())
                val hash = md.digest()
                val hexString = hash.joinToString(":") { byte -> "%02X".format(byte) }
                Log.d(tag, "========================================")
                Log.d(tag, "📱 APP SIGNATURE (SHA-256)")
                Log.d(tag, "Package: ${context.packageName}")
                Log.d(tag, "Fingerprint: $hexString")
                Log.d(tag, "========================================")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get app signature", e)
        }
    }
}
