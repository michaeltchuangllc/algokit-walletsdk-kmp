package com.michaeltchuang.walletsdk.core.railmpp.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toPath
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal object RailMppDataStore {
    private const val FILE_NAME = "rail_mpp.preferences_pb"
    private val CHANNEL_SALT_KEY = stringPreferencesKey("channel_salt_uuid")

    private val mutex = Mutex()
    private var dataStore: DataStore<Preferences>? = null
    private var cachedChannelSalt: String? = null

    suspend fun getOrCreateChannelSalt(): ByteArray =
        mutex.withLock {
            cachedChannelSalt?.let { salt ->
                persistChannelSaltIfNeeded(salt)
                return@withLock salt.encodeToByteArray()
            }

            val salt = getStoredChannelSalt() ?: generateChannelSalt()
            cachedChannelSalt = salt
            persistChannelSaltIfNeeded(salt)
            salt.encodeToByteArray()
        }

    private fun getDataStore(): DataStore<Preferences>? {
        dataStore?.let { return it }
        val path = railMppDataStorePath(FILE_NAME) ?: return null
        return PreferenceDataStoreFactory
            .createWithPath(
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                produceFile = { path.toPath() },
            ).also { dataStore = it }
    }

    private suspend fun getStoredChannelSalt(): String? = getDataStore()?.data?.first()?.get(CHANNEL_SALT_KEY)

    private suspend fun persistChannelSaltIfNeeded(salt: String) {
        getDataStore()?.edit { preferences ->
            if (preferences[CHANNEL_SALT_KEY] == null) {
                preferences[CHANNEL_SALT_KEY] = salt
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun generateChannelSalt(): String = Uuid.random().toString()
}

internal expect fun railMppDataStorePath(fileName: String): String?
