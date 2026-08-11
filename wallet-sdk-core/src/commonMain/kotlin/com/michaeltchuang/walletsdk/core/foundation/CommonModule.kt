package com.michaeltchuang.walletsdk.core.foundation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val commonModule =
    module {

        // Provide a Dispatcher.IO instance
        factory<CoroutineDispatcher> { Dispatchers.IO }

        single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

/*    // Provide LifecycleAwareManager via its implementation
    factory<LifecycleAwareManager> { LifecycleAwareManagerImpl() }*/
    }

val delegateModule =
    module {
        factory { StateDelegate<Any>() } // Generic; use with type casting in ViewModel
        factory { EventDelegate<Any>() }
    }
