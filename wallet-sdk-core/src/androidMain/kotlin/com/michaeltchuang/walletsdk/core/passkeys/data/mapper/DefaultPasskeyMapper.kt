/*
 * Copyright 2022-2025 Pera Wallet, LDA
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.michaeltchuang.walletsdk.core.passkeys.data.mapper

import com.michaeltchuang.walletsdk.core.account.data.database.model.PasskeyEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.SiteEntity
import com.michaeltchuang.walletsdk.core.passkeys.model.Passkey
import com.michaeltchuang.walletsdk.core.passkeys.model.PasskeySite


internal class DefaultPasskeyMapper constructor() : PasskeyMapper {

    override fun mapToPasskey(entity: PasskeyEntity, siteEntity: SiteEntity): Passkey {
        return Passkey(
            bip44Address = entity.algoAddress,
            userId = entity.userId,
            username = entity.userName,
            displayName = entity.userDisplayName ?: entity.userName,
            credId = entity.credentialId,
            lastUsed = entity.lastUsedTimeMs,
            site = PasskeySite(id = siteEntity.id, url = siteEntity.url, name = siteEntity.name)
        )
    }
}
