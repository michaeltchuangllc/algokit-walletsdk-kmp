import composeDemoApp

class PasskeyManager {
    // Get the repository from Koin
    private let passkeyRepository: composeDemoApp.Wallet_sdk_corePasskeyRepository

    init() {
        // Configure app group for database sharing BEFORE initializing Koin
        AppGroupHelper.configureAppGroup()
        
        // Initialize Koin if not already initialized
        App_iosKt.initializeKoin()
        
        // Get PasskeyRepository from Koin
        self.passkeyRepository = App_iosKt.getPasskeyRepository()
    }

    func savePasskey(
        siteUrl: String,
        siteName: String,
        algoAddress: String,
        uid: String,
        username: String,
        displayName: String,
        credentialId: String
    ) async throws {
        let args = composeDemoApp.Wallet_sdk_coreAddPasskeyArgs(
            siteUrl: siteUrl,
            siteName: siteName,
            algoAddress: algoAddress,
            uid: uid,
            username: username,
            displayName: displayName,
            credId: credentialId
        )

        try await passkeyRepository.addNewPasskey(args: args)
    }

    func getPasskey(credentialId: String) async throws -> composeDemoApp.Wallet_sdk_corePasskey? {
        return try await passkeyRepository.getPasskey(credId: credentialId)
    }
    
    func updateLastUsedTime(credentialId: String) async throws {
        let currentTimeMs = Int64(Date().timeIntervalSince1970 * 1000)
        try await passkeyRepository.setPasskeyLastUsedTime(credId: credentialId, lastUsed: currentTimeMs)
    }

    func getAllPasskeys() async throws -> [composeDemoApp.Wallet_sdk_corePasskey] {
        // Get all passkeys from the repository
        // Note: You may need to convert Flow to async/await or use Combine
        // For now, this is a placeholder - you'll need to handle the Flow conversion
        return []
    }
    
    func deletePasskey(credId: String) async throws {
        try await passkeyRepository.removePasskeyByCredentialId(credId: credId)
    }
    
    /// Get passkeys for a specific Algorand address
    func getPasskeysByAlgoAddress(algoAddress: String) async throws -> [composeDemoApp.Wallet_sdk_corePasskey] {
        // Get credential ID for this address
        guard let credId = try await passkeyRepository.getCredentialIdByAlgoAddress(algoAddress: algoAddress) else {
            return []
        }
        
        // Get the passkey using the credential ID
        if let passkey = try await passkeyRepository.getPasskey(credId: credId) {
            return [passkey]
        }
        
        return []
    }
}
