package com.michaeltchuang.seedvault.service.domain.usecase

import com.michaeltchuang.walletsdk.core.algosdk.getFalcon25MnemonicFromEntropy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

internal class GenerateSeedPhraseUseCase(
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GenerateSeedPhrase {
    override suspend fun invoke(): List<String> =
        withContext(coroutineDispatcher) {
            val entropy = ByteArray(FALCON_ENTROPY_SIZE).also { SecureRandom().nextBytes(it) }
            try {
                checkNotNull(getFalcon25MnemonicFromEntropy(entropy)) { "Could not encode the Falcon-1024 seed phrase" }
                    .split(" ")
            } finally {
                entropy.fill(0)
            }
        }

    private companion object {
        // A 25-word Algorand mnemonic encodes 32 bytes of entropy
        const val FALCON_ENTROPY_SIZE = 32
    }
}
