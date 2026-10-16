package com.michaeltchuang.walletsdk.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

abstract class LifecycleScopedCoroutineOwner {
    lateinit var currentScope: CoroutineScope
    private var lifecycle: Lifecycle? = null

    fun assignToLifecycle(lifecycle: Lifecycle) {
        if (this.lifecycle == null) {
            this.lifecycle = lifecycle
            currentScope = CoroutineScope(lifecycle.coroutineScope.coroutineContext + SupervisorJob())
            addDestroyObserver()
        }
    }

    abstract fun stopAllResources()

    protected fun clearLifecycle() {
        lifecycle = null
    }

    protected fun isCurrentScopeInitialized(): Boolean = ::currentScope.isInitialized

    private fun addDestroyObserver() {
        lifecycle?.addObserver(
            object : LifecycleEventObserver {
                override fun onStateChanged(
                    source: LifecycleOwner,
                    event: Lifecycle.Event,
                ) {
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        stopAllResources()
                        lifecycle?.removeObserver(this)
                        lifecycle = null
                    }
                }
            },
        )
    }
}
