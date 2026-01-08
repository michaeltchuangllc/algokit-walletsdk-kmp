package com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk

import app.perawallet.xhdwalletapi.Bip32DerivationType
import app.perawallet.xhdwalletapi.KeyContext
import app.perawallet.xhdwalletapi.XHDWalletAPIAndroid
import app.perawallet.xhdwalletapi.XHDWalletAPIBase.Companion.fromSeed
import app.perawallet.xhdwalletapi.XHDWalletAPIBase.Companion.getBIP44PathFromContext
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.sdk.Sdk
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Bip39Entropy
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Bip39Mnemonic
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Bip39Seed
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.Falcon24
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddress
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressDerivationType
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressIndex
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressLite
import com.michaeltchuang.walletsdk.core.encryption.domain.utils.clearFromMemory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

internal class AlgorandBip39Wallet internal constructor(
    private val entropy: Bip39Entropy,
) : Bip39Wallet {
    private val seed: Bip39Seed
    private val mnemonic: Bip39Mnemonic
    private var walletApi: XHDWalletAPIAndroid?

    init {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 0)

        val mnemonicCode = Mnemonics.MnemonicCode(entropy.value)
        seed = Bip39Seed(mnemonicCode.toSeed())
        mnemonic = Bip39Mnemonic(mnemonicCode.words.map { String(it) })
        walletApi = XHDWalletAPIAndroid(seed.value)
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

    @OptIn(kotlin.ExperimentalStdlibApi::class)
    override fun generateFalcon24Address(mnemonic: String): Falcon24 {
        val algorandKeyInfo = Sdk.deriveFromMnemonic(mnemonic, "")
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
        walletApi = null
    }

    private fun generatePrivateKey(index: HdKeyAddressIndex): ByteArray =
        walletApi!!.deriveKey(fromSeed(seed.value), getBip44Path(index), isPrivate = true)

    private fun getBip44Path(index: HdKeyAddressIndex): List<UInt> =
        getBIP44PathFromContext(
            context = KeyContext.Address,
            account = index.accountIndex.toUInt(),
            change = index.changeIndex.toUInt(),
            keyIndex = index.keyIndex.toUInt(),
        )

    private fun generatePublicKey(index: HdKeyAddressIndex): ByteArray =
        walletApi!!.keyGen(
            context = KeyContext.Address,
            account = index.accountIndex.toUInt(),
            change = index.changeIndex.toUInt(),
            keyIndex = index.keyIndex.toUInt(),
            derivationType = Bip32DerivationType.Peikert,
        )
}
