package com.michaeltchuang.walletsdk.core.railmpp.internal

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * JVM-level singleton that installs the full BouncyCastle provider at position 0,
 * replacing Android's stripped-down bundled version.
 *
 * Android ships a hobbled BC that lacks EdDSA support. Algorand's SDK uses
 * `BCEdDSAPrivateKey` for Ed25519 signing; without the full BC at position 0
 * every sign attempt throws:
 *   `InvalidKeyException: No installed provider supports this key: BCEdDSAPrivateKey`
 *
 * ## Why a singleton matters
 * Multiple classes previously each held their own `ensureBouncyCastleProvider()`
 * guarded only with instance-level `@Synchronized`.  Concurrent calls from
 * different instances (e.g. two payment coroutines on IO threads) could
 * interleave `removeProvider` and `insertProviderAt`, leaving the JVM with no
 * BC provider at the exact moment a signing call needed it.
 *
 * All callers must invoke [ensure] before any cryptographic operation.
 *  - The `@Volatile` flag makes the fast-path (already installed) check safe
 *    without acquiring the lock.
 *  - The `synchronized(this)` block serialises the remove/insert pair across
 *    ALL callers, regardless of which class instance they originate from.
 */
internal object BouncyCastleProviderSetup {
    @Volatile
    private var installed = false

    /**
     * Idempotent: installs the full BouncyCastle provider exactly once per JVM
     * lifecycle.  Safe to call from any thread or coroutine dispatcher.
     */
    fun ensure() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            // Remove Android's bundled (hobbled) BC before inserting the real one.
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 0)
            installed = true
        }
    }
}
