package com.michaeltchuang.walletsdk.seedvault.service.domain.usecase

import com.michaeltchuang.walletsdk.core.algosdk.getFalcon25EntropyFromMnemonic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ValidateSeedPhraseUseCase(
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ValidateSeedPhrase {
    override suspend fun invoke(words: List<String>): Boolean =
        withContext(coroutineDispatcher) {
            if (words.size != SEED_PHRASE_WORD_COUNT) return@withContext false
            // Decoding fails on unknown words and on a wrong checksum word
            runCatching { getFalcon25EntropyFromMnemonic(words.joinToString(" ")) }
                .onSuccess { entropy -> entropy.fill(0) }
                .isSuccess
        }
}

internal const val SEED_PHRASE_WORD_COUNT = 25
