package com.michaeltchuang.walletsdk.core.railmpp.usecases

import android.util.Log
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.util.Encoder
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

private const val TAG = "GetSessionVaultRemainingBalanceUseCase"
private const val TESTNET_ALGOD_URL = "https://testnet-api.algonode.cloud"

/**
 * Fetches and decodes the viewer's remaining session vault balance from on-chain box storage.
 */
class GetSessionVaultRemainingBalanceUseCase {
    operator fun invoke(
        viewerAddress: String,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Long? =
        runCatching {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 0)

            val client = algodClient(algodUrl)
            val boxName = sessionBoxName(viewerAddress)
            val boxNameB64 = Encoder.encodeToBase64(boxName)
            val response =
                client
                    .GetApplicationBoxByName(appId)
                    .name("b64:$boxNameB64")
                    .execute()

            if (!response.isSuccessful) {
                Log.e(
                    TAG,
                    "[SESSION_VAULT_REMAINING_ERR] reason=box_fetch_failed appId=$appId viewer=$viewerAddress box=b64:$boxNameB64 code=${response.code()} message=${response.message()}",
                )
                return null
            }

            val sessionBytes = response.body()?.value
            if (sessionBytes == null) {
                Log.e(
                    TAG,
                    "[SESSION_VAULT_REMAINING_ERR] reason=empty_box_value appId=$appId viewer=$viewerAddress box=b64:$boxNameB64",
                )
                return null
            }

            decodeRemainingBalanceFromSessionInfo(sessionBytes)
        }.onFailure {
            Log.e(
                TAG,
                "[SESSION_VAULT_REMAINING_ERR] reason=exception appId=$appId viewer=$viewerAddress algodUrl=$algodUrl",
                it,
            )
        }.getOrNull()

    private fun decodeRemainingBalanceFromSessionInfo(bytes: ByteArray): Long {
        if (bytes.size < 48) error("Invalid session box payload size=${bytes.size}")
        val totalDeposit = decodeUint64BigEndian(bytes, 32)
        val lastSettled = decodeUint64BigEndian(bytes, 40)
        return (totalDeposit - lastSettled).coerceAtLeast(0L)
    }

    private fun decodeUint64BigEndian(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var out = 0L
        for (i in 0 until 8) {
            out = (out shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return out
    }

    private fun algodClient(url: String): AlgodClient {
        val clean = url.removeSuffix("/")
        val uri = java.net.URI(clean)
        val host = uri.host ?: error("Invalid algod host: $url")
        val scheme = uri.scheme ?: "https"
        val port = if (uri.port == -1) if (scheme == "https") 443 else 80 else uri.port
        return AlgodClient("$scheme://$host", port, "")
    }

    private fun sessionBoxName(viewerAddress: String): ByteArray {
        val publicKey = Address(viewerAddress).bytes
        return byteArrayOf('s'.code.toByte()) + publicKey
    }
}
