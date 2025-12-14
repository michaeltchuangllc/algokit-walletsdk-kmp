package com.michaeltchuang.walletsdk.core.foundation.utils.date

import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime

internal class TimeProviderImpl(private val clock: Clock) : TimeProvider {

    override fun getCurrentTimeMillis(): Long {
        return clock.millis()
    }

    override fun getZonedDateTimeNow(): ZonedDateTime {
        return ZonedDateTime.now(clock)
    }

    override fun getZonedDateTimeFromSeconds(seconds: Long): ZonedDateTime {
        return Instant.ofEpochSecond(seconds).atZone(clock.zone)
    }
}
