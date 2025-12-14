package com.michaeltchuang.walletsdk.core.passkeys.foundation

import java.security.interfaces.ECPublicKey

 interface CoseMapper {
    fun mapPublicKeyToCose(key: ECPublicKey): MutableMap<Int, Any>
    fun mapCoseKeyToSpki(coseKey: MutableMap<Int, Any>): ByteArray?
}
