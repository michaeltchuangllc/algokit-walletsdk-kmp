package com.michaeltchuang.walletsdk.core.foundation.account

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import kotlinx.parcelize.Parcelize
@Parcelize
data class Account(
    @SerializedName("publicKey")
    val address: String,
    @SerializedName("accountName")
    var name: String = "",
    val type: Type? = null,
    val detail: Detail? = null,
    var index: Int = NOT_INITIALIZED_ACCOUNT_INDEX,
    var isBackedUp: Boolean
) : Parcelable {

    fun getSecretKey(): ByteArray? {
        return when (detail) {
            is Detail.Standard -> detail.secretKey
            is Detail.Rekeyed -> detail.secretKey
            is Detail.RekeyedAuth -> detail.secretKey
            else -> null // TODO may throw exception later.
        }
    }

    // TODO Combine Detail class with Account.Type class
    sealed class Detail : Parcelable {
        @Parcelize
        data class Standard(val secretKey: ByteArray) : Detail()

        @Parcelize
        data class Ledger(
            val bluetoothAddress: String,
            val bluetoothName: String?,
            val positionInLedger: Int = 0
        ) : Detail()

        // TODO: we can't keep account secret key here due to migration issue. we cannot support older version
        @Parcelize
        data class Rekeyed(val secretKey: ByteArray?) : Detail()

        @Parcelize
        data class RekeyedAuth(
            val authDetail: Detail?,
            val authDetailType: Type?,
            val secretKey: ByteArray?,
            val rekeyedAuthDetail: Map<String, Ledger>
        ) : Detail() {
            companion object {
                fun create(
                    authDetail: Detail?,
                    rekeyedAuthDetail: Map<String, Ledger>,
                    secretKey: ByteArray?
                ): RekeyedAuth {
                    val authDetailType = when (authDetail) {
                        is Standard -> Type.STANDARD
                        is Ledger -> Type.LEDGER
                        else -> null
                    }
                    val safeAuthDetail = authDetail.takeIf { authDetailType != null }
                    return RekeyedAuth(safeAuthDetail, authDetailType, secretKey, rekeyedAuthDetail)
                }
            }
        }

        @Parcelize
        data object Watch : Detail()
    }

    enum class Type {
        // STANDARD is personal account which its secretKey is stored on the device.
        STANDARD,
        LEDGER,
        REKEYED,
        REKEYED_AUTH,
        WATCH
    }

    override fun toString(): String {
        return "Account(publicKey='$address', accountName='$name', type=$type, detail=$detail, index=$index)"
    }

    companion object {

        fun create(
            publicKey: String,
            detail: Detail,
            accountName: String = publicKey.toShortenedAddress(),
            index: Int = NOT_INITIALIZED_ACCOUNT_INDEX,
            isBackedUp: Boolean = true
        ): Account {
            val type = when (detail) {
                is Detail.Standard -> Type.STANDARD
                is Detail.Ledger -> Type.LEDGER
                is Detail.Rekeyed -> Type.REKEYED
                is Detail.Watch -> Type.WATCH
                is Detail.RekeyedAuth -> Type.REKEYED_AUTH
            }

            return Account(
                address = publicKey,
                name = accountName,
                type = type,
                detail = detail,
                index = index,
                isBackedUp = isBackedUp
            )
        }
    }
}
