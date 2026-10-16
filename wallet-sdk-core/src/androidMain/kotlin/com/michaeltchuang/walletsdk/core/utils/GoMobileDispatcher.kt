package com.michaeltchuang.walletsdk.core.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Dedicated single-OS-thread [CoroutineDispatcher] for Falcon mobile SDK calls.
 *
 * ### Why this is needed
 * Go's GC write-barrier requires that all pointer-containing structs (e.g. `BytesArray.v [][]byte`)
 * live at 8-byte-aligned addresses.  When Falcon mobile SDK functions are called from JVM thread-pool
 * threads (Dispatchers.IO, Dispatchers.Default), each call may land on a different OS thread
 * whose TLS / heap state was not initialised by the Go runtime, causing:
 *
 *   `fatal error: bulkBarrierPreWrite: unaligned arguments`
 *
 * This is a native Go `runtime.throw()` → `runtime.exit()` path that **cannot** be caught
 * by any Kotlin/Java try-catch.
 *
 * ### The fix
 * A single persistent OS thread is used for every Falcon mobile SDK call.  The Go runtime initialises
 * itself on this thread during the first call, after which all subsequent invocations run with
 * a consistent, properly-aligned memory context.
 *
 * ### Important
 * This is a **process-wide singleton**.  All modules (`wallet-sdk-core`, `wallet-sdk-ui`) must
 * share this exact instance so that every `Sdk.signFalconBundle` / `BytesArray` call lands on
 * the **same** OS thread.  Having two separate dispatchers would re-introduce the alignment
 * crash because Go could be initialised on one thread and then called from another.
 */
object GoMobileDispatcher {
    /**
     * Shared JVM lock around every Kotlin <-> Go-mobile transition.
     *
     * The dispatcher pins calls to one OS thread, while this lock prevents overlapping entry from
     * mixed synchronous (`runBlocking`) and suspending callers. Keep every `Sdk` and `BytesArray`
     * construction/mutation inside [runOnGoThread] or [withGoThread].
     */
    private val goMobileLock = ReentrantLock()

    /**
     * Use [runOnGoThread] or [withGoThread] instead of using this dispatcher directly when calling
     * any Falcon mobile SDK function that involves pointer-containing types (e.g.
     * `io.github.algorandecosystem.sdk.BytesArray`). This includes all construction and mutation of
     * `io.github.algorandecosystem.sdk.BytesArray` instances as well as every
     * `io.github.algorandecosystem.sdk.Sdk` call.
     */
    val dispatcher: CoroutineDispatcher =
        Executors
            .newSingleThreadExecutor { r ->
                Thread(r, THREAD_NAME).also { it.isDaemon = true }
            }.asCoroutineDispatcher()

    /**
     * Runs [block] on the dedicated Go-mobile OS thread while holding [goMobileLock].
     *
     * If the calling thread is already the Go-mobile thread, [block] is executed inline to avoid a
     * deadlock. Otherwise the call is dispatched via [runBlocking] so that the current thread is
     * blocked until [block] completes.
     */
    fun <T> runOnGoThread(block: () -> T): T =
        goMobileLock.withLock {
            if (Thread.currentThread().name == THREAD_NAME) {
                block()
            } else {
                runBlocking(dispatcher) { block() }
            }
        }

    /**
     * Suspends onto the dedicated Go-mobile OS thread while holding [goMobileLock].
     *
     * Prefer this from suspend functions instead of calling `withContext(dispatcher)` directly so
     * synchronous and suspending Go-mobile calls cannot overlap.
     */
    suspend fun <T> withGoThread(block: () -> T): T {
        goMobileLock.lock()
        return try {
            if (Thread.currentThread().name == THREAD_NAME) {
                block()
            } else {
                withContext(dispatcher) { block() }
            }
        } finally {
            goMobileLock.unlock()
        }
    }

    private const val THREAD_NAME = "go-mobile-falcon"
}
