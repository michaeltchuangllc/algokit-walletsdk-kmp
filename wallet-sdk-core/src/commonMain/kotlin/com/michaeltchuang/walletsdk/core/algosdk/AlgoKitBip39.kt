package com.michaeltchuang.walletsdk.core.algosdk

import com.michaeltchuang.walletsdk.core.algosdk.utils.PassphraseKeywordUtils
import kotlin.random.Random

object AlgoKitBip39 {
    fun getSeedFromEntropy(entropy: ByteArray): ByteArray = deriveBip39Seed(getMnemonicFromEntropy(entropy))

    @OptIn(ExperimentalStdlibApi::class)
    fun getEntropyFromMnemonic(mnemonic: String): ByteArray {
        // First validate the mnemonic
//    if (!MnemonicCode.validate(mnemonic)) {
//        throw IllegalArgumentException("Invalid mnemonic")
//    }

        val words = mnemonic.trim().split("\\s+".toRegex())
        if (words.size != 24) {
            throw IllegalArgumentException("Expected 24 words, got ${words.size}")
        }

        val bip39WordList = PassphraseKeywordUtils.predefinedWords

        // Convert words to indices
        val indices =
            words.map { word ->
                val index = bip39WordList.indexOf(word.lowercase())
                if (index == -1) {
                    throw IllegalArgumentException("Word '$word' not found in BIP39 wordlist")
                }
                index
            }

        // Convert indices to 11-bit binary and concatenate
        val binaryString =
            indices.joinToString("") { index ->
                index.toString(2).padStart(11, '0')
            }

        // Split into entropy and checksum (for 24 words: 256 bits entropy + 8 bits checksum)
        val entropyBits = binaryString.substring(0, 256)

        // Convert entropy bits to bytes
        val entropyBytes =
            entropyBits
                .chunked(8)
                .map { byte ->
                    byte.toInt(2).toByte()
                }.toByteArray()

        // Verify checksum (optional but recommended)
        // val computedChecksum = sha256(entropyBytes).first().toString(2).padStart(8, '0')
        // if (checksumBits != computedChecksum) {
        //     throw IllegalArgumentException("Invalid checksum")
        // }
//
//        println("Extracted entropy: ${entropyBytes.toHexString()}")
        return entropyBytes
    }

    fun getMnemonicFromEntropy(entropy: ByteArray): String {
        require(entropy.size == 32) { "Expected 32 bytes of entropy, got ${entropy.size}" }

        val entropyBits =
            entropy.joinToString("") { byte ->
                byte.toUByte().toString(2).padStart(8, '0')
            }
        val checksumBits =
            entropy
                .sha256()
                .first()
                .toUByte()
                .toString(2)
                .padStart(8, '0')
        val mnemonicBits = entropyBits + checksumBits

        return mnemonicBits
            .chunked(BIP39_WORD_BIT_LENGTH)
            .map { bits -> PassphraseKeywordUtils.predefinedWords[bits.toInt(2)] }
            .joinToString(" ")
    }

    fun generate24WordMnemonic(): String {
        val entropy = ByteArray(32)
        Random.nextBytes(entropy)
        val mnemonic = getMnemonicFromEntropy(entropy)
        println("mnemonic: $mnemonic")
        return mnemonic
    }
}

internal expect fun deriveBip39Seed(mnemonic: String): ByteArray

internal expect fun ByteArray.sha256(): ByteArray

private const val BIP39_WORD_BIT_LENGTH = 11
