package com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import com.algorand.algosdk.crypto.Address
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Bip39Entropy
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Bip39Mnemonic
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Bip39Seed
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Falcon24
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddress
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressDerivationType
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressIndex
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressLite
import com.michaeltchuang.walletsdk.core.encryption.domain.utils.clearFromMemory
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import io.github.algorandecosystem.sdk.Sdk
import org.bouncycastle.jce.provider.BouncyCastleProvider
import uniffi.algokit_crypto_ffi.XhdDerivedAccount
import uniffi.algokit_crypto_ffi.XhdKeyContext
import uniffi.algokit_crypto_ffi.xhdDerive
import uniffi.algokit_crypto_ffi.xhdRootKeyFromSeed
import java.security.Security

internal class AlgorandBip39Wallet internal constructor(
    private val entropy: Bip39Entropy,
) : Bip39Wallet {
    private val seed: Bip39Seed
    private val mnemonic: Bip39Mnemonic
    private val rootKey: ByteArray

    init {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 0)

        val mnemonicCode = Mnemonics.MnemonicCode(entropy.value)
        seed = Bip39Seed(mnemonicCode.toSeed())
        mnemonic = Bip39Mnemonic(mnemonicCode.words.map { String(it) })
        rootKey = xhdRootKeyFromSeed(seed.value)
    }

    override fun generateAddress(index: HdKeyAddressIndex): HdKeyAddress {
        val publicKey = generatePublicKey(index)
        return HdKeyAddress(
            address = Address(publicKey).toString(),
            index = index,
            publicKey = publicKey,
            privateKey = generatePrivateKey(index),
            derivationType = HdKeyAddressDerivationType.Peikert,
        )
    }

    override fun generateAddressLite(index: HdKeyAddressIndex): HdKeyAddressLite {
        val publicKey = generatePublicKey(index)
        return HdKeyAddressLite(
            address = Address(publicKey).toString(),
            index = index,
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun generateFalcon24Address(mnemonic: String): Falcon24 {
        val algorandKeyInfo = GoMobileDispatcher.runOnGoThread { Sdk.deriveFalconLsigFromMnemonic(mnemonic, "") }
        return Falcon24(
            address = algorandKeyInfo.algorandAddress,
            publicKey = algorandKeyInfo.publicKey,
            privateKey = algorandKeyInfo.privateKey,
        )
    }

    override fun getEntropy(): Bip39Entropy = Bip39Entropy(entropy.value.copyOf())

    override fun getSeed(): Bip39Seed = Bip39Seed(seed.value.copyOf())

    override fun getMnemonic(): Bip39Mnemonic = Bip39Mnemonic(mnemonic.words)

    override fun invalidate() {
        entropy.value.clearFromMemory()
        seed.value.clearFromMemory()
        rootKey.clearFromMemory()
    }

    private fun generatePrivateKey(index: HdKeyAddressIndex): ByteArray = deriveAccount(index).extendedPrivateKey

    private fun generatePublicKey(index: HdKeyAddressIndex): ByteArray = deriveAccount(index).publicKey

    private fun deriveAccount(index: HdKeyAddressIndex): XhdDerivedAccount {
        require(index.changeIndex == 0) {
            "AlgoKit Crypto xHD derivation only supports change index 0. Requested: ${index.changeIndex}"
        }
        return xhdDerive(
            rootKey = rootKey,
            keyContext = XhdKeyContext.ADDRESS,
            account = index.accountIndex.toUInt(),
            keyIndex = index.keyIndex.toUInt(),
        )
    }
}
