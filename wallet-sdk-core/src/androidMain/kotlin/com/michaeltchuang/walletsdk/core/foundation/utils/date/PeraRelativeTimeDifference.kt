package com.michaeltchuang.walletsdk.core.foundation.utils.date

import android.text.format.DateUtils
import com.michaeltchuang.walletsdk.core.foundation.utils.date.RelativeTimeDifference.RelativeTime
import com.michaeltchuang.walletsdk.core.foundation.utils.date.RelativeTimeDifference.RelativeTime.Days
import com.michaeltchuang.walletsdk.core.foundation.utils.date.RelativeTimeDifference.RelativeTime.Hours
import com.michaeltchuang.walletsdk.core.foundation.utils.date.RelativeTimeDifference.RelativeTime.Minutes
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

internal data class PeraRelativeTimeDifference @Inject constructor(
    private val timeProvider: TimeProvider
) : RelativeTimeDifference {

    override fun getRelativeTime(time: ZonedDateTime, timeDifference: Long): RelativeTime {
        return when {
            timeDifference < DateUtils.MINUTE_IN_MILLIS -> RelativeTime.Now
            timeDifference < DateUtils.HOUR_IN_MILLIS -> Minutes((timeDifference / DateUtils.MINUTE_IN_MILLIS).toInt())
            timeDifference < DateUtils.DAY_IN_MILLIS -> Hours((timeDifference / DateUtils.HOUR_IN_MILLIS).toInt())
            timeDifference < DateUtils.WEEK_IN_MILLIS -> Days((timeDifference / DateUtils.DAY_IN_MILLIS).toInt())
            else -> RelativeTime.Date(time.format(DateTimeFormatter.ofPattern(MONTH_DAY_YEAR_PATTERN)))
        }
    }

    override fun getCurrentRelativeTime(timestampMillis: Long): RelativeTime {
        val timeDifference = timeProvider.getCurrentTimeMillis() - timestampMillis
        val time = timeProvider.getZonedDateTimeNow().minusNanos(timeDifference * NANOS_TO_MILLIS_MULTIPLIER)
        return getRelativeTime(time, timeDifference)
    }

    private companion object {
        const val MONTH_DAY_YEAR_PATTERN = "MMMM dd, yyyy"
        const val NANOS_TO_MILLIS_MULTIPLIER = 1_000_000
    }
}
