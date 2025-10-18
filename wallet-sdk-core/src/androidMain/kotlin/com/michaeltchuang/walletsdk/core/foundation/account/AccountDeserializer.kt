package com.michaeltchuang.walletsdk.core.foundation.account

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.lang.reflect.Type

const val NOT_INITIALIZED_ACCOUNT_INDEX = -1

class AccountDeserializer : JsonDeserializer<Account> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Account {
        val jsonObject = json.asJsonObject

        val type: Account.Type? = try {
            Account.Type.valueOf(jsonObject.get("type").asString)
        } catch (exception: Exception) {
            null
        }

        // This object comes null because used without in pre-3.0.2. Don't change null check here.
        val detail = if (type == null) {
            val secretKeyObject = jsonObject.get("secretKey")
            val secretKey = context.deserialize<ByteArray>(secretKeyObject, ByteArray::class.java)
            if (secretKey != null) {
                Account.Detail.Standard(secretKey)
            } else {
                val exception = Exception("secretKey is unknown. $jsonObject")
                throw exception
            }
        } else {
            val detailJsonObject = jsonObject.get("detail")?.asJsonObject
            context.deserializeDetail(type, detailJsonObject)
        }

        val name = jsonObject.get("accountName").asString
        val publicKey = jsonObject.get("publicKey").asString
        val accountIndex =
            jsonObject.get("index")?.asString?.toIntOrNull() ?: NOT_INITIALIZED_ACCOUNT_INDEX
        val isBackedUp = jsonObject.get("isBackedUp")?.asBoolean ?: true

        return Account.create(
            publicKey = publicKey,
            detail = detail,
            accountName = name,
            index = accountIndex,
            isBackedUp = isBackedUp,
        )
    }

    private fun JsonDeserializationContext.deserializeDetail(
        type: Account.Type,
        detailJsonObject: JsonObject?
    ): Account.Detail {
        return when (type) {
            Account.Type.STANDARD -> {
                deserialize<Account.Detail.Standard>(
                    detailJsonObject,
                    Account.Detail.Standard::class.java
                )
            }

            Account.Type.LEDGER -> {
                deserialize<Account.Detail.Ledger>(
                    detailJsonObject,
                    Account.Detail.Ledger::class.java
                )
            }

            Account.Type.REKEYED -> {
                deserialize<Account.Detail.Rekeyed>(
                    detailJsonObject,
                    Account.Detail.Rekeyed::class.java
                )
            }

            Account.Type.WATCH -> {
                deserialize<Account.Detail.Watch>(
                    detailJsonObject,
                    Account.Detail.Watch::class.java
                )
            }

            Account.Type.REKEYED_AUTH -> deserializeRekeyedAuth(detailJsonObject)
        }
    }

    private fun JsonDeserializationContext.deserializeRekeyedAuth(jsonObject: JsonObject?): Account.Detail.RekeyedAuth {
        val authDetailJsonObject = jsonObject?.get("authDetail")?.asJsonObject

        var rekeyedAuthDetail: Account.Detail? = null
        var rekeyedAuthType: Account.Type? = null
        if (authDetailJsonObject != null) {
            rekeyedAuthType = try {
                Account.Type.valueOf(jsonObject.get("authDetailType").asString)
            } catch (exception: Exception) {
                null
            }
            if (rekeyedAuthType != null) {
                rekeyedAuthDetail = deserializeDetail(rekeyedAuthType, authDetailJsonObject)
            }
        }
        val rekeyedAuthDetailJsonObject = jsonObject?.get("rekeyedAuthDetail")?.asJsonObject
        val rekeyedAuthDetailMapType =
            object : com.google.gson.reflect.TypeToken<Map<String, Account.Detail.Ledger>>() {}.type
        val rekeyedAuthDetailMap = deserialize<Map<String, Account.Detail.Ledger>>(
            rekeyedAuthDetailJsonObject,
            rekeyedAuthDetailMapType
        )
        val secretKeyObject = jsonObject?.get("secretKey")
        val secretKey = deserialize<ByteArray>(secretKeyObject, ByteArray::class.java)
        return Account.Detail.RekeyedAuth(
            rekeyedAuthDetail,
            rekeyedAuthType,
            secretKey,
            rekeyedAuthDetailMap
        )
    }
}
