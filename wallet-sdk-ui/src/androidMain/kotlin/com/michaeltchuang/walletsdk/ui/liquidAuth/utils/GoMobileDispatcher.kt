package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Dedicated single-OS-thread [CoroutineDispatcher] for all Go-mobile (gomobile) calls.
 *
 * ### Why this is needed
 * Go's GC write-barrier requires that all pointer-containing structs (e.g. `BytesArray.v [][]byte`)
 * live at 8-byte-aligned addresses.  When Go-mobile functions are called from JVM thread-pool
 * threads (Dispatchers.IO, Dispatchers.Default), each call may land on a different OS thread
 * whose TLS / heap state was not initialised by the Go runtime, causing:
 *
 *   `fatal error: bulkBarrierPreWrite: unaligned arguments`
 *
 * This is a native Go `runtime.throw()` → `runtime.exit()` path that **cannot** be caught
 * by any Kotlin/Java try-catch.
 *
 * ### The fix
 * A single persistent OS thread is used for every Go-mobile call.  The Go runtime initialises
 * itself on this thread during the first call, after which all subsequent invocations run with
 * a consistent, properly-aligned memory context.
 */
internal object GoMobileDispatcher {
    /**
     * Use this dispatcher when calling any Go-mobile SDK function that involves
     * pointer-containing types (e.g. [com.algorand.algosdk.sdk.BytesArray]).
     */
    val dispatcher: CoroutineDispatcher =
        Executors
            .newSingleThreadExecutor { r ->
                Thread(r, "go-mobile-falcon").also { it.isDaemon = true }
            }.asCoroutineDispatcher()
}
