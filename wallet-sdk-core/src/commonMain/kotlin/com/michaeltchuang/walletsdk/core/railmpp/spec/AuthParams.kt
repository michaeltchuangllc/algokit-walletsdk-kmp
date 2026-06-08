package com.michaeltchuang.walletsdk.core.railmpp.spec

/**
 * Parser/serializer for HTTP `WWW-Authenticate: Payment <params>` and
 * `Authorization: Payment <token>` headers per RFC 9110 §11.6.1.
 *
 * Mirrors mppx's behaviour:
 * - Quoted values may contain commas — the parser tracks quote state.
 * - Unknown keys are preserved (so `opaque` round-trips for free).
 * - Backslash escapes inside quoted values are honored.
 */
internal object AuthParams {
    /** Parse the auth-params (everything after `Payment ` in a header). */
    fun parse(header: String): LinkedHashMap<String, String> {
        val body = stripPaymentPrefix(header)
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = body.length
        while (i < n) {
            // Skip whitespace + commas between params
            while (i < n && (body[i] == ' ' || body[i] == '\t' || body[i] == ',')) i++
            if (i >= n) break

            // Read key
            val keyStart = i
            while (i < n && body[i] !in ",= \t\"") i++
            val key = body.substring(keyStart, i)
            if (key.isEmpty()) {
                // Junk char — skip
                i++
                continue
            }
            // Skip whitespace before '='
            while (i < n && (body[i] == ' ' || body[i] == '\t')) i++
            if (i >= n || body[i] != '=') break
            i++ // consume '='
            while (i < n && (body[i] == ' ' || body[i] == '\t')) i++

            // Read value (quoted or token)
            val (value, next) = readValue(body, i)
            i = next
            out[key] = value
        }
        return out
    }

    /** Serialize auth-params back to a `Payment <k1="v1", k2="v2", ...>` header. */
    fun serialize(params: Map<String, String>): String {
        val parts =
            params.entries.joinToString(", ") { (k, v) ->
                "$k=\"${escape(v)}\""
            }
        return "Payment $parts"
    }

    /**
     * Strip the leading `Payment ` (or `Payment\t…`) scheme from a header.
     * Returns the rest if present; otherwise returns the input unchanged.
     */
    fun stripPaymentPrefix(header: String): String {
        val m = PREFIX_RE.matchAt(header, 0) ?: return header
        return header.substring(m.range.last + 1)
    }

    fun hasPaymentPrefix(header: String): Boolean = PREFIX_RE.containsMatchIn(header)

    // ─── internal ─────────────────────────────────────────

    private val PREFIX_RE = Regex("^\\s*Payment\\s+", RegexOption.IGNORE_CASE)

    private fun readValue(
        s: String,
        start: Int,
    ): Pair<String, Int> {
        if (start >= s.length) return "" to start
        if (s[start] != '"') {
            // Unquoted token — read until comma.
            var i = start
            while (i < s.length && s[i] != ',') i++
            return s.substring(start, i).trim() to i
        }
        // Quoted-string with backslash escapes.
        val sb = StringBuilder()
        var i = start + 1
        var escaped = false
        while (i < s.length) {
            val c = s[i]
            i++
            if (escaped) {
                sb.append(c)
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') return sb.toString() to i
            sb.append(c)
        }
        // Unterminated — accept what we have.
        return sb.toString() to i
    }

    private fun escape(value: String): String {
        if (value.indexOf('"') < 0 && value.indexOf('\\') < 0) return value
        val sb = StringBuilder(value.length + 4)
        for (c in value) {
            if (c == '"' || c == '\\') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }
}
