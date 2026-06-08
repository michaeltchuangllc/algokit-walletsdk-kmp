package com.michaeltchuang.walletsdk.core.railmpp.internal

/**
 * Installs the platform crypto provider required for Ed25519 signing, if any.
 *
 * On Android this swaps in the full BouncyCastle provider (the bundled one lacks
 * EdDSA support); other platforms are no-ops.
 */
internal expect fun ensureCryptoProvider()
