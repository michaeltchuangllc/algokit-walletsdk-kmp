package com.michaeltchuang.seedvault.service.remote

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the user is asked to approve before a signature is made. */
internal class SignApprovalRequest(
    val callerPackage: String,
    val appLabel: String,
    val operation: SignOperation,
    val description: String,
) {
    val decision = CompletableDeferred<Boolean>()
}

/**
 * Remembers what the user approved on the confirm sign screen. A client app starts that screen
 * itself (a service cannot open one from the background) and then calls the AIDL sign method,
 * which has to find a matching approval here. An approval works once, for the app that got it, and
 * only for [grantMillis].
 */
internal class SignApprovalCoordinator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val grantMillis: Long = GRANT_MILLIS,
) {
    private val pending = ConcurrentHashMap<String, SignApprovalRequest>()
    private val grants = ConcurrentHashMap<Grant, Long>()

    private data class Grant(
        val callerPackage: String,
        val operation: SignOperation,
    )

    /** Registers a request the confirm sign screen is about to show and returns its id. */
    fun begin(request: SignApprovalRequest): String {
        // The screen that shows the request is exported, so the id must not be guessable by other apps
        val id = UUID.randomUUID().toString()
        pending[id] = request
        return id
    }

    /** The request behind a confirm sign screen, or null if it is settled or gone. */
    fun request(id: String): SignApprovalRequest? = pending[id]

    fun resolve(
        id: String,
        approved: Boolean,
    ) {
        val request = pending.remove(id) ?: return
        if (approved) grants[Grant(request.callerPackage, request.operation)] = clock() + grantMillis
        request.decision.complete(approved)
    }

    /** True once if one of [callerPackages] has a live approval for [operation]. */
    fun consume(
        callerPackages: Set<String>,
        operation: SignOperation,
    ): Boolean =
        callerPackages.any { packageName ->
            val expiresAt = grants.remove(Grant(packageName, operation))
            expiresAt != null && expiresAt >= clock()
        }

    companion object {
        const val GRANT_MILLIS = 60_000L
    }
}
