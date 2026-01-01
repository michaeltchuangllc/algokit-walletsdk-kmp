package com.michaeltchuang.walletsdk.core.foundation.utils.date

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import java.time.Clock

val dateModule =
    module {
        single<TimeProvider> { TimeProviderImpl(Clock.systemDefaultZone()) }
        singleOf(::PeraRelativeTimeDifference) bind RelativeTimeDifference::class
    }
