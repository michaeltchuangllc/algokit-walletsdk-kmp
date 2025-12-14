package com.michaeltchuang.walletsdk.core.foundation.utils.date

import java.time.ZonedDateTime

interface TimeProvider {
    fun getCurrentTimeMillis(): Long
    fun getZonedDateTimeNow(): ZonedDateTime
    fun getZonedDateTimeFromSeconds(seconds: Long): ZonedDateTime
}
