package com.michaeltchuang.walletsdk.core.foundation.utils.date.parser

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

internal class ISO8601DateTimeParser @Inject constructor() : DateTimeParser {

    override fun parseOffsetDateTime(dateTime: String): OffsetDateTime? {
        return tryParse(dateTime) ?: tryParseFormatted(dateTime)
    }

    private fun tryParse(dateTime: String): OffsetDateTime? {
        return try {
            OffsetDateTime.parse(dateTime)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryParseFormatted(dateTime: String): OffsetDateTime? {
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            OffsetDateTime.parse(dateTime, formatter)
        } catch (e: Exception) {
            null
        }
    }
}
