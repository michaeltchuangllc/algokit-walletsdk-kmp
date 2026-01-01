package com.michaeltchuang.walletsdk.core.passkeys.data.repository

import com.michaeltchuang.walletsdk.core.account.data.database.dao.PasskeyDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.PasskeySiteDao
import com.michaeltchuang.walletsdk.core.passkeys.data.mapper.PasskeyEntityMapper
import com.michaeltchuang.walletsdk.core.passkeys.data.mapper.PasskeyMapper
import com.michaeltchuang.walletsdk.core.account.data.database.model.SiteEntity
import com.michaeltchuang.walletsdk.core.passkeys.model.AddPasskeyArgs
import com.michaeltchuang.walletsdk.core.passkeys.model.Passkey
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


internal class DefaultPasskeyRepository(
    private val passkeyDao: PasskeyDao,
    private val passkeySiteDao: PasskeySiteDao,
    private val passkeyMapper: PasskeyMapper,
    private val passkeyEntityMapper: PasskeyEntityMapper
) : PasskeyRepository {

    override fun getAllPasskeysAsFlow(): Flow<List<Passkey>> {
        return passkeySiteDao.getPasskeysAsFlow().map { siteWithPasskeysQueries ->
            siteWithPasskeysQueries.map { siteWithPasskeys ->
                siteWithPasskeys.passkeys.map { passkeyEntity ->
                    passkeyMapper.mapToPasskey(passkeyEntity, siteWithPasskeys.site)
                }
            }.flatten()
        }
    }

    override suspend fun getSitePasskeysCount(url: String): Int {
        val siteId = passkeySiteDao.getSiteId(url) ?: return 0
        return passkeyDao.getPasskeyCountBySiteId(siteId)
    }

    override suspend fun getSitePasskeys(url: String): List<Passkey> {
        return passkeySiteDao.getPasskeysByUrl(url).map {
            it.passkeys.map { passkeyEntity -> passkeyMapper.mapToPasskey(passkeyEntity, it.site) }
        }.flatten()
    }

    override suspend fun addNewPasskey(args: AddPasskeyArgs) {
        val siteId = passkeySiteDao.getSiteId(args.siteUrl)
        val passkeyEntity = if (siteId == null) {
            val entity = SiteEntity(url = args.siteUrl, name = args.siteName)
            val newSiteId = passkeySiteDao.insert(entity)
            passkeyEntityMapper.mapToPasskeyEntity(args, newSiteId)
        } else {
            passkeyEntityMapper.mapToPasskeyEntity(args, siteId)
        }
        passkeyDao.insert(passkeyEntity)
    }

    override suspend fun getPasskey(credId: String): Passkey? {
        val passkeyEntity = passkeyDao.getByCredId(credId) ?: return null
        val siteEntity = passkeySiteDao.getSiteById(passkeyEntity.siteId) ?: return null
        return passkeyMapper.mapToPasskey(passkeyEntity, siteEntity)
    }

    override suspend fun getCredentialIdBySiteId(url: String): String? {
       val siteId = passkeySiteDao.getSiteId(url) ?: return null
        return passkeyDao.getCredentialIdBySiteId(siteId)
    }

    override suspend fun removePasskeyByCredentialId(credId: String) {
        val passkey = passkeyDao.getByCredId(credId)
        if (passkey != null) {
            passkeyDao.deleteByCredId(credId)
            val passkeyCountForSite = passkeyDao.getPasskeyCountBySiteId(passkey.siteId)
            if (passkeyCountForSite == 0) {
                passkeySiteDao.delete(passkey.siteId)
            }
        }
    }

    override suspend fun clearAllPasskeys() {
        passkeyDao.deleteAll()
        passkeySiteDao.deleteAll()
    }

    override suspend fun setPasskeyLastUsedTime(credId: String, lastUsed: Long) {
        passkeyDao.updateLastUsedTime(credId, lastUsed)
    }

    override suspend fun doesPasskeyExist(rpId: String, username: String, algoAddress: String): Boolean {
        return passkeyDao.doesPasskeyExist(rpId, username, algoAddress)
    }
}
