package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKey
import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKeyItem

internal class DefaultRegisteredHdKeyItemMapper : RegisteredHdKeyItemMapper {
    override fun invoke(
        hdKey: RegisteredHdKey,
       /* usdToSelectedCurrencyMultiplier: BigDecimal,
        selectedCurrencySymbol: String*/
    ): RegisteredHdKeyItem =
        with(hdKey) {
            RegisteredHdKeyItem(
                address = address,
                algoValue = algoValue,
                formattedSelectedCurrencyValue = usdValue.toString(),
                accountExists = accountExists,
                isImportedToDB = isImportedToDB,
                account = account,
                change = change,
                keyIndex = keyIndex,
            )
        }

/*    private fun BigDecimal.formatAsSelectedCurrency(
        usdToSelectedCurrencyMultiplier: BigDecimal,
        selectedCurrencySymbol: String
    ): String {
        val formattedSelectedCurrencyValue =
            multiply(usdToSelectedCurrencyMultiplier).formatAsTwoDecimals()
        return StringBuilder(selectedCurrencySymbol)
            .append(formattedSelectedCurrencyValue)
            .toString()
    }*/
}
