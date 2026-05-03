package com.michaeltchuang.walletsdk.core.railmpp.spec

import org.erdtman.jcs.JsonCanonicalizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON Canonicalization Scheme (RFC 8785) helpers.
 *
 * Used for:
 * - Computing the HMAC input for challenge IDs (server side)
 * - Serializing the `request` field of a challenge (both sides)
 * - Building the canonical credential body before base64url encoding
 *
 * Backed by `org.erdtman:java-json-canonicalization`.
 */
internal object JcsJson {
    /** Canonicalize an arbitrary JSON string. Output is UTF-8 bytes. */
    fun canonicalize(json: String): ByteArray = JsonCanonicalizer(json).encodedUTF8

    /** Canonicalize a JSONObject and return the canonical string. */
    fun canonicalize(obj: JSONObject): String = String(canonicalize(obj.toString()), Charsets.UTF_8)

    /** Canonicalize a JSONArray and return the canonical string. */
    fun canonicalize(arr: JSONArray): String = String(canonicalize(arr.toString()), Charsets.UTF_8)
}
