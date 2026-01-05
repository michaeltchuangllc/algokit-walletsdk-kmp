package com.michaeltchuang.walletsdk.core.foundation.utils.date

import java.time.ZonedDateTime

interface RelativeTimeDifference {
    fun getRelativeTime(
        time: ZonedDateTime,
        timeDifference: Long,
    ): RelativeTime

    fun getCurrentRelativeTime(timestampMillis: Long): RelativeTime

    sealed interface RelativeTime {
        data object Now : RelativeTime

        data class Minutes(
            val value: Int,
        ) : RelativeTime

        data class Hours(
            val value: Int,
        ) : RelativeTime

        data class Days(
            val value: Int,
        ) : RelativeTime

        data class Date(
            val value: String,
        ) : RelativeTime
    }
}
