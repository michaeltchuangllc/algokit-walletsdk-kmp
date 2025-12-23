package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.barcode.common.Barcode

import org.json.JSONObject
import javax.inject.Inject

private fun Uri.findParameterValue(parameterName: String): String? {
    return query?.split('&')?.map {
        val parts = it.split('=')
        val name = parts.firstOrNull() ?: ""
        val value = parts.drop(1).firstOrNull() ?: ""
        Pair(name, value)
    }?.firstOrNull{it.first == parameterName}?.second
}
data class AuthMessage @Inject constructor(
    var origin: String,
    val requestId: String
) {

    companion object {
        const val TAG = "connect.Message"
        fun fromUri(uri: Uri): AuthMessage {
            Log.d(TAG, "fromUri($uri)")
            Log.d(TAG, "URI scheme: ${uri.scheme}")
            Log.d(TAG, "URI host: ${uri.host}")
            Log.d(TAG, "URI path: ${uri.path}")
            Log.d(TAG, "URI query: ${uri.query}")
            
            val host = uri.host
            if (host.isNullOrEmpty()) {
                Log.e(TAG, "URI host is null or empty! Full URI: $uri")
                throw IllegalArgumentException("Invalid URI: host is null or empty")
            }
            
            val origin = "https://$host"
            val requestId = uri.findParameterValue("requestId").toString()
            
            Log.d(TAG, "Constructed origin: $origin")
            Log.d(TAG, "Extracted requestId: $requestId")
            
            return AuthMessage(origin, requestId)
        }
        /**
         * Parse the Uri string
         *
         * `liquid://<ORIGIN>/?requestId=<REQUEST_ID>`
         */
        fun fromString(stringContents: String): AuthMessage {
            Log.d(TAG, "fromString($stringContents)")
            if(stringContents.startsWith("liquid://")) {
               return fromUri(Uri.parse(stringContents))
            } else {
                // Fallback to JSON renderer
                val json = JSONObject(stringContents)
                val origin = json.get("origin").toString()
                val requestId = json.get("requestId").toString()
                return AuthMessage(origin, requestId)
            }
        }
        /**
         * Parse the `Barcode`
         *
         * uses the following url scheme:
         *
         * `liquid://<ORIGIN>/?requestId=<REQUEST_ID>`
         */
        fun fromBarcode(barcode: Barcode): AuthMessage {
            Log.d(TAG, "fromBarcode(${barcode.displayValue})")
            val stringContents = barcode.displayValue ?: throw Exception("Barcode does not contain a display value")
            return fromString(stringContents)
        }
    }
    fun toJSON() : JSONObject {
        val result = JSONObject()
        result.put("origin", origin)
        result.put("requestId", requestId)
        return result
    }
}
