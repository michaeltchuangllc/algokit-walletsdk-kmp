package com.michaeltchuang.walletsdk.core.foundation.utils.date.parser

import java.time.OffsetDateTime

interface DateTimeParser {
    fun parseOffsetDateTime(dateTime: String): OffsetDateTime?
}
