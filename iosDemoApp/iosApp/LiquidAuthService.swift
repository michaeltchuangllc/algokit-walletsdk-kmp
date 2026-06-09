/*
 * Copyright 2025 Algorand Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import AuthenticationServices
import composeDemoApp
import CryptoKit
import deterministicP256_swift
import Foundation
import LocalAuthentication
import MnemonicSwift
import SwiftCBOR
import UIKit
import WebRTC
import x_hd_wallet_api

/*
 * ENCODING STRATEGY SUMMARY
 * ========================
 *
 * This implementation uses different encodings for different purposes:
 *
 * 1. WebAuthn Attestation Objects → CBOR (REQUIRED by W3C spec)
 *    - Used in: createAttestationCredential()
 *    - Format: CBOR-encoded attestationObject
 *    - Why: Part of WebAuthn standard specification
 *
 * 2. Data Channel Messages (matching Android)
 *    - Incoming: Base64-encoded CBOR (from provider-sdk)
 *    - Credential Handshake: JSON (initial connection)
 *    - Transaction Responses: Base64-encoded CBOR (ARC-0027 protocol)
 *    - Why: CBOR is more efficient and matches ARC-0027 standard
 *
 * See: docs/iOS-LiquidAuth-Setup.md for full details
 */

/// LiquidAuthService handles the Liquid Auth flow for iOS
/// Similar to Android's AnswerActivity
public class LiquidAuthService {
    
    // MARK: - Properties
    
    private var origin: String
    private var requestId: String
    private var algoAddress: String
    private var mnemonic: String?
    private var signalService: SignalService?
    private var dataChannel: RTCDataChannel?
    private var credentialID: String?
    
    // WebAuthn/FIDO2 related
    private var walletInfo: WalletInfo?
    
    // Callbacks
    private var onSuccess: (() -> Void)?
    private var onError: ((Error) -> Void)?
    private var onConnected: (() -> Void)?  // Called when connection established

    /// Optional handler that receives every raw data-channel message string.
    /// Set this from the streaming viewer layer to forward video frames and
    /// payment messages to `IOSLiquidStreamViewerConnectionManager.notifyMessageReceived`.
    public var messageForwardingHandler: ((String) -> Void)?
    
    // MARK: - Initialization
    
    public init(origin: String, requestId: String, algoAddress: String) {
        // Normalize origin - remove trailing slash if present
        self.origin = origin.hasSuffix("/") ? String(origin.dropLast()) : origin
        self.requestId = requestId
        self.algoAddress = algoAddress
        
        NSLog("🔗 LiquidAuthService initialized")
        NSLog("   Origin (original): \(origin)")
        NSLog("   Origin (normalized): \(self.origin)")
        NSLog("   RequestID: \(requestId)")
        NSLog("   AlgoAddress: \(algoAddress)")
    }
    
    // MARK: - Public Methods
    
    /// Start the Liquid Auth connection flow
    public func connect(
        onSuccess: @escaping () -> Void,
        onError: @escaping (Error) -> Void,
        onConnected: (() -> Void)? = nil
    ) {
        self.onSuccess = onSuccess
        self.onError = onError
        self.onConnected = onConnected
        
        NSLog("========================================")
        NSLog("🔗 Starting Liquid Auth Connection")
        NSLog("========================================")
        
        // Initialize Koin and App Group
        AppGroupHelper.configureAppGroup()
        App_iosKt.initializeKoin()
        
        // Get mnemonic for the account
        do {
            let accountMnemonic = try App_iosKt.getAccountMnemonic(address: algoAddress)
            self.mnemonic = accountMnemonic.words.joined(separator: " ")
            
            // Get wallet info (using the origin for the liquid auth server)
            // Pass the specific algoAddress to create account-specific P256 credential
            self.walletInfo = try getWalletInfo(origin: origin, forAddress: algoAddress)
            
            // Get or create credential ID
            Task {
                await checkAndInitiateAuth()
            }
        } catch {
            NSLog("❌ Failed to get account mnemonic: \(error)")
            onError(error)
        }
    }
    
    // MARK: - Private Methods
    
    private func checkAndInitiateAuth() async {
        do {
            // Check if we have a saved credential for this address
            let passkeyManager = PasskeyManager()
            let passkeys = try await passkeyManager.getPasskeysByAlgoAddress(address: algoAddress)
            
            if let existingPasskey = passkeys.first {
                // Use existing credential ID (property is 'credId' not 'credentialId')
                self.credentialID = existingPasskey.credId
                NSLog("✅ Found existing credential: \(existingPasskey.credId)")
                await authenticate()
            } else {
                // Register new credential
                NSLog("📝 No credential found, registering new one")
                await register()
            }
        } catch {
            NSLog("❌ Error checking credentials: \(error)")
            onError?(error)
        }
    }
    
    /// Register a new credential (similar to Android's register())
    private func register() async {
        NSLog("========================================")
        NSLog("📝 REGISTRATION FLOW STARTED")
        NSLog("========================================")
        
        do {
            guard let walletInfo = self.walletInfo else {
                throw NSError(domain: "WalletInfo not initialized", code: -1)
            }
            
            // Initialize signal service using the shared instance
            self.signalService = SignalService.shared
            
            // Start signal service with origin URL
            signalService?.start(url: origin, httpClient: URLSession.shared)
            
            // Get registration options from server
            let attestationApi = AttestationApi()
            
            // Build user-agent (matching Android format)
            let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
            let systemVersion = await UIDevice.current.systemVersion
            let deviceModel = await UIDevice.current.model
            let userAgent = "com.michaeltchuang.walletsdk.demo/\(appVersion) (iOS \(systemVersion); \(deviceModel); Apple)"
            
            NSLog("📱 User-Agent: \(userAgent)")
            
            // Extract RP ID from origin (remove https://)
            let rpId = origin
                .replacingOccurrences(of: "https://", with: "")
                .replacingOccurrences(of: "http://", with: "")
            
            // Build attestation options (matching Android)
            // Note: Server expects BOTH "username" AND "address" fields
            let options: [String: Any] = [
                "username": algoAddress,
                "address": algoAddress,  // Server needs this too!
                "displayName": "Liquid Auth User",
                "authenticatorSelection": [
                    "authenticatorAttachment": "platform",
                    "userVerification": "required",
                    "requireResidentKey": false
                ],
                "rp": [
                    "id": rpId,
                    "name": "Liquid Auth"
                ],
                "extensions": [
                    "liquid": true
                ]
            ]
            
            NSLog("📤 Attestation Request Body:")
            NSLog("   username: \(algoAddress)")
            NSLog("   address: \(algoAddress)")
            NSLog("   rpId: \(rpId)")
            NSLog("   Full options: \(options)")
            
            let (responseData, sessionCookie) = try await attestationApi.postAttestationOptions(
                origin: origin,
                userAgent: userAgent,
                options: options
            )
            
            // Store the session cookie for subsequent requests
            if let cookie = sessionCookie {
                NSLog("🍪 Received session cookie: \(cookie.name)=\(cookie.value)")
                HTTPCookieStorage.shared.setCookie(cookie)
            } else {
                NSLog("⚠️ No session cookie received from server")
            }
            
            // Parse the server response to get the actual challenge
            guard let attestationOptions = try? JSONSerialization.jsonObject(with: responseData) as? [String: Any] else {
                throw NSError(domain: "Failed to parse attestation options from server", code: -1)
            }
            
            NSLog("📥 Received attestation options from server")
            if let challenge = attestationOptions["challenge"] as? String {
                NSLog("   Challenge: \(challenge.prefix(20))...")
            }
            
            // Create attestation credential
            let credential = try await createAttestationCredential(
                options: attestationOptions,
                walletInfo: walletInfo
            )
            
            // Save to database with base64url encoded credential ID
            let passkeyManager = PasskeyManager()
            let credentialIDString = credential.credentialID.base64urlEncodedString()
            try await passkeyManager.savePasskey(
                siteUrl: origin,
                siteName: origin,
                algoAddress: algoAddress,
                uid: algoAddress,
                username: algoAddress,
                displayName: algoAddress,
                credentialId: credentialIDString
            )
            
            self.credentialID = credentialIDString
            NSLog("✅ Saved credential ID: \(credentialIDString.prefix(20))...")
            
            NSLog("✅ Credential created locally, sending to server...")
            
            // Get account type for FIDO2 (matching Android)
            let accountType = App_iosKt.getAccountTypeForFido2(address: algoAddress)
            NSLog("📋 Account type: \(accountType)")
            
            // Extract challenge from server response and sign it with Algorand wallet
            guard let challengeB64 = attestationOptions["challenge"] as? String else {
                NSLog("❌ No challenge found in attestation options")
                throw NSError(domain: "No challenge in server response", code: -1)
            }
            
            NSLog("📥 Challenge from server (REGISTRATION): \(challengeB64)")
            NSLog("   Challenge length: \(challengeB64.count) chars")
            NSLog("   Challenge (full): '\(challengeB64)'")
            
            // Convert base64url to Data (WebAuthn uses base64url encoding)
            guard let challengeData = challengeB64.base64urlToData() else {
                NSLog("❌ Failed to decode challenge as base64url")
                NSLog("   Original: \(challengeB64)")
                throw NSError(domain: "Invalid challenge encoding", code: -1)
            }
            
            NSLog("✅ Challenge decoded: \(challengeData.count) bytes")
            NSLog("   Challenge hex: \(challengeData.map { String(format: "%02x", $0) }.joined())")
            NSLog("   Challenge base64 (standard): \(challengeData.base64EncodedString())")
            NSLog("   Challenge base64url: \(challengeData.base64urlEncodedString())")
            
            let algoSignature = try signWithAlgorandWallet(
                challenge: challengeData,
                address: algoAddress
            )
            
            NSLog("✅ Algorand signature computed: \(algoSignature.base64EncodedString().prefix(20))...")
            NSLog("   Signature (full base64): \(algoSignature.base64EncodedString())")
            NSLog("   Signature size: \(algoSignature.count) bytes")
            
            // Get Algorand wallet public key (not P256 key)
            guard let algoPublicKey = App_iosKt.getPublicKeyForAlgorandWallet(address: algoAddress) else {
                NSLog("❌ Failed to get Algorand public key")
                throw NSError(domain: "Failed to get Algorand public key", code: -1)
            }
            
            NSLog("✅ Algorand public key: \(algoPublicKey.prefix(20))...")
            
            // Build liquid extension JSON (matching Android)
            NSLog("🔍 Building liquid extension (registration):")
            NSLog("   requestId: '\(self.requestId)'")
            NSLog("   accountType: '\(accountType)'")
            
            let liquidExt: [String: Any] = await [
                "type": accountType,  // "algorand" or "falcon-1024"
                "requestId": self.requestId,  // ✅ Explicitly use self.requestId
                "address": self.algoAddress,  // ✅ Explicitly use self.algoAddress
                "publicKey": algoPublicKey,  // Algorand wallet public key (not P256 key)
                "signature": algoSignature.base64EncodedString(),  // Algorand wallet signature of challenge
                "device": UIDevice.current.model
            ]
            
            // Build credential dictionary for server with base64url encoding (WebAuthn standard)
            let credentialDict: [String: Any] = [
                "id": credential.credentialID.base64urlEncodedString(),  // ✅ base64url encoding
                "rawId": credential.credentialID.base64urlEncodedString(),
                "type": "public-key",
                "response": [
                    "attestationObject": credential.attestationObject.base64urlEncodedString(),
                    "clientDataJSON": credential.clientDataJSON.base64urlEncodedString()
                ]
            ]
            
            NSLog("🔍 Registration credential ID (first 20 chars): \(credential.credentialID.base64urlEncodedString().prefix(20))...")
            
            // Send attestation response to server
            NSLog("📤 Sending attestation response to server...")
            
            // Verify session cookie is present
            if let url = URL(string: "https://\(origin)"),
               let cookies = HTTPCookieStorage.shared.cookies(for: url) {
                NSLog("🍪 Sending \(cookies.count) cookie(s) with request:")
                for cookie in cookies {
                    NSLog("   - \(cookie.name)=\(cookie.value.prefix(10))...")
                }
            } else {
                NSLog("⚠️ No cookies found for \(origin)")
            }
            
            let _ = try await attestationApi.postAttestationResult(
                origin: origin,
                userAgent: userAgent,
                credential: credentialDict,
                liquidExt: liquidExt,
                device: UIDevice.current.model
            )
            
            NSLog("✅ Attestation response sent successfully!")
            NSLog("✅ Registration complete!")
            NSLog("🔐 Now authenticating to establish login session...")
            
            // After successful registration, authenticate to establish the login session
            await authenticate()
            
        } catch {
            NSLog("❌ Registration failed: \(error)")
            onError?(error)
        }
    }
    
    /// Authenticate with existing credential (similar to Android's authenticate())
    private func authenticate() async {
        NSLog("========================================")
        NSLog("🔐 AUTHENTICATION FLOW STARTED")
        NSLog("========================================")
        
        if let url = URL(string: "https://\(origin)"),
               let cookies = HTTPCookieStorage.shared.cookies(for: url) {
                NSLog("🧹 Clearing \(cookies.count) old cookies")
                for cookie in cookies {
                    HTTPCookieStorage.shared.deleteCookie(cookie)
                }
            }
        
        // Wait 2 seconds to allow authentication session to establish
        try? await Task.sleep(nanoseconds: 2_000_000_000)

        do {
            guard let walletInfo = self.walletInfo,
                  let credentialID = self.credentialID else {
                throw NSError(domain: "Missing required data", code: -1)
            }
            
            // Initialize signal service using the shared instance
            self.signalService = SignalService.shared
            
            // Start signal service with origin URL
            signalService?.start(url: origin, httpClient: URLSession.shared)
            
            // Get assertion options from server
            let assertionApi = AssertionApi()
            let passkeyManager = PasskeyManager()
            
            // Build user-agent (matching Android format)
            let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
            let systemVersion = await UIDevice.current.systemVersion
            let deviceModel = await UIDevice.current.model
            let userAgent = "com.michaeltchuang.walletsdk.demo/\(appVersion) (iOS \(systemVersion); \(deviceModel); Apple)"
            
            let (responseData, httpResponse) = try await assertionApi.postAssertionOptions(
                origin: origin,
                userAgent: userAgent,
                credentialId: credentialID,
                liquidExt: true
            )
            
            NSLog("📡 Assertion request response:")
            NSLog("   Status code: \(httpResponse.statusCode)")
            NSLog("   Response data size: \(responseData.count) bytes")
            if let responseString = String(data: responseData, encoding: .utf8) {
                NSLog("   Response body (first 500 chars): \(responseString.prefix(500))")
            }
            
            // Check for HTTP errors
            if httpResponse.statusCode == 401 {
                if let responseString = String(data: responseData, encoding: .utf8),
                   responseString.contains("not_found") {
                    NSLog("⚠️ Credential not found on server, need to register")
                    // Delete local credential and re-register
                    try? await passkeyManager.deletePasskey(credId: credentialID)
                    await register()
                    return
                }
                throw NSError(domain: "HTTP 401 Unauthorized", code: 401)
            } else if !(200...299).contains(httpResponse.statusCode) {
                NSLog("❌ HTTP error: \(httpResponse.statusCode)")
                if let responseString = String(data: responseData, encoding: .utf8) {
                    NSLog("   Error response: \(responseString)")
                }
                throw NSError(domain: "HTTP error \(httpResponse.statusCode)", code: httpResponse.statusCode)
            }
            
            // Parse the server response to get the actual challenge
            guard let assertionOptions = try? JSONSerialization.jsonObject(with: responseData) as? [String: Any] else {
                NSLog("❌ Failed to parse assertion options as JSON")
                NSLog("   Response data: \(String(data: responseData, encoding: .utf8) ?? "nil")")
                throw NSError(domain: "Failed to parse assertion options from server", code: -1)
            }
            
            NSLog("📥 Received assertion options from server")
            NSLog("   Keys: \(assertionOptions.keys.joined(separator: ", "))")
            if let challenge = assertionOptions["challenge"] {
                NSLog("   Challenge type: \(type(of: challenge))")
                NSLog("   Challenge value: \(challenge)")
            } else {
                NSLog("   ❌ No 'challenge' key in response!")
            }
            
            // Parse and log challenge
            guard let challengeB64url = assertionOptions["challenge"] as? String else {
                NSLog("❌ Challenge is not a String or is missing")
                NSLog("   Full response: \(assertionOptions)")
                throw NSError(domain: "Invalid challenge from server (not a string)", code: -1)
            }
            
            let originalChallengeString = challengeB64url
            
            guard let challengeData = challengeB64url.base64urlToData() else {
                NSLog("❌ Failed to decode challenge as base64url")
                NSLog("   Challenge string: \(challengeB64url)")
                NSLog("   Challenge length: \(challengeB64url.count)")
                throw NSError(domain: "Invalid challenge encoding (base64url decode failed)", code: -1)
            }
            
            NSLog("📥 Challenge from server (AUTHENTICATION): \(challengeB64url.prefix(20))...")
            NSLog("   Challenge length: \(challengeB64url.count) chars")
            NSLog("✅ Challenge decoded: \(challengeData.count) bytes")
            
            // Get account type for FIDO2 (matching Android)
            let accountType = App_iosKt.getAccountTypeForFido2(address: algoAddress)
            NSLog("📋 Account type: \(accountType)")
            
            // Get Algorand wallet public key (not P256 key)
            guard let algoPublicKey = App_iosKt.getPublicKeyForAlgorandWallet(address: algoAddress) else {
                throw NSError(domain: "Failed to get Algorand public key", code: -1)
            }
            
            // Sign challenge with Algorand wallet
            let algoSignature = try signWithAlgorandWallet(challenge: challengeData, address: algoAddress)
            NSLog("✅ Algorand signature computed: \(algoSignature.base64EncodedString().prefix(20))...")
            NSLog("   Signature size: \(algoSignature.count) bytes")
            
            // Build liquid extension JSON (matching server requirements)
            NSLog("🔍 Building liquid extension (authentication):")
            NSLog("   requestId: '\(self.requestId)'")
            NSLog("   accountType: '\(accountType)'")
            NSLog("   address: '\(algoAddress)'")
            
            let liquidExt: [String: Any] = [
                "type": accountType,
                "requestId": self.requestId,
                "address": algoAddress,
                "publicKey": algoPublicKey,
                "signature": algoSignature.base64EncodedString(),
                "device": "iPhone"
            ]
            
            NSLog("✅ Liquid extension built (authentication - WITH signature)")
            NSLog("   Type: \(accountType)")
            NSLog("   RequestId: \(self.requestId)")
            NSLog("   Signature: \(algoSignature.base64EncodedString().prefix(20))...")
            
            // Create assertion credential
            let credential = try await createAssertionCredential(
                options: assertionOptions,
                walletInfo: walletInfo,
                credentialID: credentialID,
                challengeData: challengeData,
                originalChallengeString: originalChallengeString
            )
            
            NSLog("✅ Assertion credential created, sending to server...")
            
            // Build credential dictionary with base64url encoding (WebAuthn standard)
            let credentialDict: [String: Any] = [
                "id": credential.credentialID.base64urlEncodedString(),  // ✅ base64url encoding
                "rawId": credential.credentialID.base64urlEncodedString(),
                "type": "public-key",
                "response": [
                    "authenticatorData": credential.authenticatorData.base64urlEncodedString(),
                    "clientDataJSON": credential.clientDataJSON.base64urlEncodedString(),
                    "signature": credential.signature.base64urlEncodedString(),
                    "userHandle": credential.userHandle.base64urlEncodedString()
                ],
                "clientExtensionResults": [
                    "liquid": liquidExt
                ],
                "device": "iPhone"
            ]
            
            NSLog("🔍 Credential ID (first 20 chars): \(credential.credentialID.base64urlEncodedString().prefix(20))...")
            
            // Convert to JSON string
            guard let credentialJSON = try? JSONSerialization.data(withJSONObject: credentialDict),
                  let credentialString = String(data: credentialJSON, encoding: .utf8) else {
                throw NSError(domain: "Failed to serialize credential to JSON", code: -1)
            }
            
            // Send assertion response to server
            NSLog("📤 Sending assertion response to server...")
            
            // Verify session cookie is present before sending
            if let url = URL(string: "https://\(origin)"),
               let cookies = HTTPCookieStorage.shared.cookies(for: url) {
                NSLog("🍪 Sending \(cookies.count) cookie(s) with request:")
                for cookie in cookies {
                    NSLog("   - \(cookie.name)=\(cookie.value.prefix(10))...")
                }
            }
            
            try await assertionApi.postAssertionResult(
                origin: origin,
                userAgent: userAgent,
                credential: credentialString,
                liquidExt: liquidExt
            )
            
            NSLog("✅ Assertion response sent successfully!")
            
            // Update passkey lastUsed timestamp
            do {
                try await passkeyManager.updateLastUsedTime(credentialId: credentialID)
                NSLog("✅ Updated passkey lastUsed timestamp")
            } catch {
                NSLog("⚠️ Failed to update passkey lastUsed timestamp: \(error)")
                // Non-fatal error, continue with authentication
            }
            
            NSLog("✅ Authentication successful, setting up WebRTC...")

            // Setup WebRTC connection
            await setupWebRTC(credential: credential)
            
        } catch {
            NSLog("❌ Authentication failed: \(error)")
            
            // If credential not found, re-register
            if (error as NSError).code == 404 {
                NSLog("🔄 Credential not found on server, re-registering...")
                await register()
            } else {
                onError?(error)
            }
        }
    }
    
    private func createAttestationCredential(
        options: [String: Any],
        walletInfo: WalletInfo
    ) async throws -> AttestationCredential {
        
        let pubkey = walletInfo.p256KeyPair.publicKey.rawRepresentation
        let credentialID = Data([UInt8](Utility.hashSHA256(pubkey)))
        
        // Build attestationObject
        let aaguid = UUID(uuidString: "1F59713A-C021-4E63-9158-2CC5FDC14E52")!
        let attestedCredData = Utility.getAttestedCredentialData(
            aaguid: aaguid,
            credentialId: credentialID,
            publicKey: pubkey
        )
        
        // Extract RP ID from origin (remove https:// or http://)
        let rpId = origin
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
        
        // Hash the RP ID (NOT the full origin URL)
        let rpIdHash = Utility.hashSHA256(rpId.data(using: .utf8)!)
        
        // Get challenge from options (as base64url string from server)
        guard let challengeB64url = options["challenge"] as? String,
              let challengeData = challengeB64url.base64urlToData() else {
            throw NSError(domain: "Invalid challenge", code: -1)
        }
        
        let authData = AuthenticatorData.attestation(
            rpIdHash: rpIdHash,
            userPresent: true,
            userVerified: true,
            backupEligible: true,
            backupState: true,
            signCount: 0,
            attestedCredentialData: attestedCredData,
            extensions: nil
        ).toData()
        
        // Note: CBOR encoding is REQUIRED here for WebAuthn attestationObject
        // This is different from data channel messages which use JSON
        // See: https://www.w3.org/TR/webauthn-2/#attestation-object
        let attObj: [String: Any] = [
                    "attStmt": [:],
                    "authData": authData,
                    "fmt": "none",
                ]
    
        let cborEncoded = try CBOR.encodeMap(attObj)
        let attestationObject = Data(cborEncoded)
        
        let clientData: [String: Any] = [
            "type": "webauthn.create",
            "challenge": challengeB64url,  // Keep as base64url string (WebAuthn spec)
            "origin": origin,
            "crossOrigin": false
        ]

        let clientDataJSON = try JSONSerialization.data(withJSONObject: clientData)
        
        return AttestationCredential(
            credentialID: credentialID,
            attestationObject: attestationObject,
            clientDataJSON: clientDataJSON
        )
    }
    
    private func createAssertionCredential(
        options: [String: Any],
        walletInfo: WalletInfo,
        credentialID: String,
        challengeData: Data,
        originalChallengeString: String
    ) async throws -> AssertionCredential {

        // Credential ID is stored as base64url, decode it
        guard let credIDData = credentialID.base64urlToData() else {
            throw NSError(domain: "Invalid credential ID encoding", code: -1)
        }
        let userHandleData = Data(walletInfo.address.utf8)
        
        // Extract RP ID from origin (remove https:// or http://)
        let rpId = origin
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
        
        // Build clientDataJSON (matching WebAuthn spec for assertion)
        let challengeB64 = challengeData.base64EncodedString()
        let clientData: [String: Any] = [
            "type": "webauthn.get",
            "challenge": originalChallengeString,
            "origin": "https://\(rpId)"
        ]
        
        guard let clientDataJSON = try? JSONSerialization.data(withJSONObject: clientData) else {
            throw NSError(domain: "Failed to serialize clientDataJSON", code: -1)
        }
        
        let clientDataHash = Utility.hashSHA256(clientDataJSON)
        
        // Authenticator data - hash the RP ID (NOT the full origin URL)
        let rpIdHash = Utility.hashSHA256(rpId.data(using: .utf8)!)
        let authenticatorData = AuthenticatorData.assertion(
            rpIdHash: rpIdHash,
            userPresent: true,
            userVerified: true,
            backupEligible: true,
            backupState: true,
            signCount: 0
        ).toData()
        
        // Signature: sign authenticatorData || clientDataHash (using P256 FIDO2 key)
        let dataToSign = authenticatorData + clientDataHash
        let signature = try walletInfo.p256KeyPair.signature(for: dataToSign).derRepresentation
        
        return AssertionCredential(
            credentialID: credIDData,
            authenticatorData: authenticatorData,
            signature: signature,
            userHandle: userHandleData,
            clientDataJSON: clientDataJSON
        )
    }
    
    private func setupWebRTC(credential: Any) async {
        NSLog("🌐 Setting up WebRTC connection...")
        NSLog("   Request ID: '\(self.requestId)'")
        NSLog("   Origin: '\(self.origin)'")
        
        guard let signalService = self.signalService else {
            NSLog("❌ SignalService not initialized")
            onError?(NSError(domain: "SignalService not initialized", code: -1))
            return
        }
        
        // Configure ICE servers (Google's public STUN servers)
        let iceServers = [
            RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"]),
            RTCIceServer(urlStrings: ["stun:stun1.l.google.com:19302"])
        ]
        
        NSLog("🔗 Initiating peer connection...")

        // ── Viewer-side payment DC hook ────────────────────────────────────────
        // When Android is the HOST it creates a separate "x402-payment-channel" DataChannel
        // (symmetrical to what iOS hosts do for Android viewers).  SignalService will call
        // this closure once that DC becomes open, wiring iosViewerPaymentDCSendMessageHandler
        // so that segment:payment messages are delivered on the correct channel.
        signalService.onPaymentDataChannelReady = { [weak self] _ in
            guard let self else { return }
            App_iosKt.setViewerPaymentSendMessageHandler { [weak self] message in
                self?.signalService?.sendPaymentMessage(message)
            }
            NSLog("✅ iosViewerPaymentDCSendMessageHandler wired → x402-payment-channel")
        }

        // Connect to peer using SignalService
        signalService.connectToPeer(
            requestId: self.requestId,  // ✅ Explicitly use self.requestId
            type: "answer",  // iOS wallet acts as the "answer" side
            origin: self.origin,  // ✅ Explicitly use self.origin
            iceServers: iceServers,
            onMessage: { [weak self] message in
                guard let self = self else { return }

                // ── Forward every message to the streaming viewer manager (if active) ──
                // This lets IOSLiquidStreamViewerConnectionManager decode video frames and
                // payment requests without needing its own WebRTC connection.
                self.messageForwardingHandler?(message)

                // Only show a confirmation dialog for CBOR-encoded transaction signing requests.
                // JSON messages (pings, video frames, credential handshakes, etc.)
                // are handled silently so the dialog does not spam for every message.
                if Data(base64Encoded: message) != nil {
                    // Looks like a Base64/CBOR payload → could be a signing request
                    self.showConfirmationDialog(message: message)
                } else {
                    // Plain JSON message (ping, liquid:video:frame, etc.) → handle directly
                    self.handleMessage(message)
                }
            },
            onStateChange: { [weak self] state in
                guard let self = self else { return }
                NSLog("📡 Data channel state change: \(state ?? "unknown")")
                
                if state == "open" {
                    NSLog("✅ Data channel is OPEN, sending credential")
                    
                    // ── Wire the Kotlin viewer manager's send handler ──────────────────
                    // The Kotlin-side IOSLiquidStreamViewerConnectionManager.sendMessage()
                    // routes through iosViewerSendMessageHandler.  We set it here (data
                    // channel open) so that notifyConnected() → sendViewerHello() reliably
                    // delivers the liquid:viewer:hello via this connection.
                    // We call the App_iosKt wrapper (composeDemoApp scope) rather than
                    // accessing the wallet-sdk-ui Kt class directly to avoid module-prefix
                    // ambiguity in the ObjC bridge.
                    App_iosKt.setViewerSendMessageHandler { [weak self] message in
                        self?.signalService?.sendMessage(message)
                    }
                    NSLog("✅ iosViewerSendMessageHandler wired to signalService.sendMessage")
                    
                    self.sendCredentialMessage(credential: credential)
                } else if state == "connecting" {
                    NSLog("⏳ Data channel is CONNECTING...")
                } else if state == "closed" || state == "failed" {
                    NSLog("❌ Data channel FAILED/CLOSED: \(state ?? "unknown")")
                    self.onError?(NSError(domain: "Data channel failed", code: -1))
                }
            }
        )
        
        NSLog("⏳ Waiting for WebRTC connection to establish...")
        NSLog("   (This may take 10-30 seconds)")
    }
    
    /// Sends a `liquid:viewer:hello` message to the host so the Android
    /// `LiquidAuthConnectionManager.tryCaptureViewerAddressFromMessage` can record the
    /// viewer's wallet address (and authorised-signer public key) before it calls
    /// `sendPaymentRequest()`.  Without this message `viewer=null` is logged and the
    /// session-vault funded-skip optimisation is skipped for the first segment.
    private func sendViewerHelloMessage() {
        let publicKeyBase64 = App_iosKt.getPublicKeyForAlgorandWallet(address: self.algoAddress)
        let keyField = (publicKeyBase64 != nil && !publicKeyBase64!.isEmpty)
            ? ",\"viewerPublicKey\":\"\(publicKeyBase64!)\""
            : ""
        let helloJson = "{\"reference\":\"liquid:viewer:hello\",\"viewer\":\"\(self.algoAddress)\"\(keyField)}"
        signalService?.sendMessage(helloJson)
        NSLog("👋 [VIEWER_HELLO_SENT] viewer=\(self.algoAddress) keyPresent=\(publicKeyBase64 != nil)")
    }

    private func sendCredentialMessage(credential: Any) {
        NSLog("📤 Sending credential message as JSON")
        NSLog("   requestId: '\(self.requestId)'")
        NSLog("   address: '\(self.algoAddress)'")

        // ── 1. Send liquid:viewer:hello FIRST ─────────────────────────────────
        // Android's host (LiquidAuthConnectionManager.tryCaptureViewerAddressFromMessage)
        // uses this message to capture the viewer's wallet address and authorised-signer
        // public key BEFORE the payment flow starts.  Without it `viewer=null` is logged
        // and the session-vault funded-skip optimisation is disabled for the first segment.
        sendViewerHelloMessage()

        // ── 2. Send the credential handshake ─────────────────────────────────
        // Note: Transaction responses use CBOR, but credential handshake uses JSON
        let credentialMessage: [String: Any] = [
            "type": "credential",
            "address": self.algoAddress,  // ✅ Explicitly use self.algoAddress
            "requestId": self.requestId,  // ✅ Explicitly use self.requestId
            "provider": "WalletSDK-iOS"
        ]
        
        if let jsonData = try? JSONSerialization.data(withJSONObject: credentialMessage),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            signalService?.sendMessage(jsonString)
            NSLog("✅ Credential handshake sent as JSON")
            NSLog("   Full message: \(jsonString)")
            NSLog("✅ RequestId in message: '\(self.requestId)'")
            NSLog("⏳ Connection remains open, waiting for messages from server...")
            
            // Notify UI that we're connected and waiting
            DispatchQueue.main.async { [weak self] in
                self?.onConnected?()
            }
            
            // Don't call onSuccess() yet - keep connection open to receive transaction requests
            // The connection will be closed when:
            // 1. User dismisses the view manually
            // 2. An error occurs
            // 3. Server closes the connection
        }
    }
    
    private func handleMessage(_ message: String) {
        // Messages come in as Base64-encoded CBOR (matching Android)
        // And we respond with Base64-encoded CBOR (matching Android)
        
        do {
            // Try to decode as Base64 CBOR first (for transaction requests from provider-sdk)
            if let messageData = Data(base64Encoded: message) {
                NSLog("📦 Decoding Base64-encoded CBOR message")
                try handleCBORMessage(messageData)
            } else {
                // Fallback to JSON for simple messages (ping/pong)
                try handleJSONMessage(message)
            }
        } catch {
            NSLog("❌ Error handling message: \(error)")
        }
    }
    
    private func handleCBORMessage(_ data: Data) throws {
        // Log first bytes to verify incoming CBOR encoding type
        if !data.isEmpty {
            let firstBytes = data.prefix(10).map { String(format: "0x%02X", $0) }.joined(separator: " ")
            NSLog("📥 Incoming CBOR first bytes: \(firstBytes)")
            let firstByte = data[0]
            let isIndefinite = (firstByte & 0x1F) == 0x1F
            NSLog("   Incoming CBOR encoding: \(isIndefinite ? "INDEFINITE-LENGTH" : "DEFINITE-LENGTH")")
        }
        
        // Decode CBOR message
        let cborValue = try CBOR.decode([UInt8](data))
        
        // The decoded CBOR should be a map
        guard case let .map(messageMap) = cborValue else {
            throw NSError(domain: "Invalid CBOR format - expected map", code: -1)
        }
        
        // Extract reference to determine message type
        var reference: String?
        var requestId: String?
        
        for (key, value) in messageMap {
            if case let .utf8String(keyStr) = key {
                switch keyStr {
                case "reference":
                    if case let .utf8String(refStr) = value {
                        reference = refStr
                    }
                case "id":
                    if case let .utf8String(idStr) = value {
                        requestId = idStr
                    }
                default:
                    break
                }
            }
        }
        
        NSLog("📨 CBOR Message Reference: \(reference ?? "unknown")")
        NSLog("   Request ID: \(requestId ?? "unknown")")
        
        // Handle different message types
        switch reference {
        case "arc0027:sign_transactions:request":
            NSLog("📝 Transaction signing request received")
            
            // Extract params from CBOR message
            guard let paramsValue = messageMap[.utf8String("params")],
                  case let .map(paramsMap) = paramsValue else {
                NSLog("❌ Failed to extract params from CBOR message")
                sendTransactionErrorResponse(requestId: requestId ?? "unknown")
                return
            }
            
            // Sign transactions asynchronously
            Task {
                await handleTransactionSigningRequest(
                    requestId: requestId ?? "unknown",
                    paramsMap: paramsMap
                )
            }
            
        default:
            NSLog("⚠️ Unknown CBOR message reference: \(reference ?? "nil")")
        }
    }
    
    private func handleJSONMessage(_ message: String) throws {
        guard let messageData = message.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: messageData) as? [String: Any],
              let type = json["type"] as? String else {
            NSLog("⚠️ Unable to parse JSON message")
            return
        }
        
        switch type {
        case "ping":
            NSLog("📡 Received ping")
            // Respond with JSON (not CBOR)
            signalService?.sendMessage("{\"type\":\"pong\"}")
            
        default:
            NSLog("⚠️ Unknown JSON message type: \(type)")
        }
    }
    
    /// Handle transaction signing request from server
    private func handleTransactionSigningRequest(
        requestId: String,
        paramsMap: [CBOR: CBOR]
    ) async {
        NSLog("========================================")
        NSLog("📝 PROCESSING TRANSACTION SIGNING REQUEST")
        NSLog("   RequestId: \(requestId)")
        
        // Debug: Log all keys in params map
        NSLog("🔍 Params map keys:")
        for (key, value) in paramsMap {
            if case let .utf8String(keyStr) = key {
                NSLog("   Key: '\(keyStr)', Value type: \(value)")
            } else {
                NSLog("   Key (non-string): \(key)")
            }
        }
        
        // Extract transactions array from params
        guard let txnsValue = paramsMap[.utf8String("txns")],
              case let .array(txnsArray) = txnsValue else {
            NSLog("❌ Failed to extract txns array from params")
            NSLog("   txnsValue: \(paramsMap[.utf8String("txns")] ?? "nil")")
            sendTransactionErrorResponse(requestId: requestId)
            return
        }
        
        NSLog("   Number of transactions: \(txnsArray.count)")
        
        // Parse and sign each transaction
        var signedTxns: [String] = []
        
        for (index, txnCBOR) in txnsArray.enumerated() {
            guard case let .map(txnMap) = txnCBOR else {
                NSLog("❌ Transaction \(index) is not a map")
                sendTransactionErrorResponse(requestId: requestId)
                return
            }
            
            // Debug: Log all keys in the transaction map
            NSLog("🔍 Transaction \(index) map keys:")
            for (key, value) in txnMap {
                if case let .utf8String(keyStr) = key {
                    NSLog("   Key: '\(keyStr)', Value type: \(value)")
                } else {
                    NSLog("   Key (non-string): \(key), Value type: \(value)")
                }
            }
            
            // Extract transaction bytes - might be byteString instead of utf8String
            var txnData: Data?
            
            // Try different possible formats
            if let txnValue = txnMap[.utf8String("txn")] {
                switch txnValue {
                case let .utf8String(txnBase64url):
                    // Base64url-encoded string (WebAuthn standard)
                    // Try base64url first, then fall back to standard base64
                    if let data = txnBase64url.base64urlToData() {
                        txnData = data
                        NSLog("📄 Transaction \(index): base64url string format")
                    } else if let data = Data(base64Encoded: txnBase64url) {
                        txnData = data
                        NSLog("📄 Transaction \(index): standard base64 string format")
                    } else {
                        NSLog("❌ Failed to decode base64/base64url string")
                        NSLog("   String (first 50 chars): \(txnBase64url.prefix(50))...")
                    }
                case let .byteString(txnBytes):
                    // Raw bytes
                    txnData = Data(txnBytes)
                    NSLog("📄 Transaction \(index): raw bytes format")
                default:
                    NSLog("❌ Transaction \(index) has unexpected value type: \(txnValue)")
                }
            }
            
            guard let txnData = txnData else {
                NSLog("❌ Failed to extract transaction \(index) bytes")
                sendTransactionErrorResponse(requestId: requestId)
                return
            }
            
            NSLog("📄 Transaction \(index): \(txnData.count) bytes")
            
            // Sign the transaction using KMP transaction signing function
            let txnKotlin = txnData.toKotlinByteArray()
            
            guard let signedTxnKotlin = App_iosKt.signTxnWithAlgorandWallet(
                address: self.algoAddress,
                txnBytes: txnKotlin
            ) else {
                NSLog("❌ Failed to sign transaction \(index)")
                sendTransactionErrorResponse(requestId: requestId)
                return
            }
            
            // Convert signed transaction to base64
            let signedTxnData = signedTxnKotlin.toSwiftData()
            let signedTxnBase64 = signedTxnData.base64EncodedString()
            signedTxns.append(signedTxnBase64)
            
            NSLog("✅ Transaction \(index) signed: \(signedTxnData.count) bytes")
        }
        
        NSLog("✅ All \(signedTxns.count) transactions signed successfully")
        NSLog("========================================")
        
        // Send response (providerId extracted from params if needed)
        let providerId = "liquid-auth-ios"
        sendTransactionResponse(
            requestId: requestId,
            signedTxns: signedTxns,
            providerId: providerId
        )
    }
    
    private func sendTransactionErrorResponse(requestId: String) {
        NSLog("📤 Sending transaction error response as CBOR")

        do {
            // Build error response structure
            let errorResponse: [String: Any] = [
                "id": requestId,
                "reference": "arc0027:sign_transactions:response",
                "error": [
                    "code": 4100,
                    "message": "Transaction signing failed"
                ]
            ]
            
            // Convert to CBOR
            let cborData = try encodeToCBOR(errorResponse)
            
            // Base64 encode
            let base64String = cborData.base64EncodedString()
            
            NSLog("   CBOR error response length: \(cborData.count) bytes")
            NSLog("   Base64 encoded length: \(base64String.count) chars")
            
            signalService?.sendMessage(base64String)
            NSLog("✅ Error response sent as CBOR!")
        } catch {
            NSLog("❌ Failed to encode error response as CBOR: \(error)")
        }
    }
    
    /// Send signed transaction response as CBOR
    /// Use this when transaction signing is implemented
    /// Important: Send as CBOR (to match Android)
    private func sendTransactionResponse(requestId: String, signedTxns: [String], providerId: String?) {
        NSLog("📤 Sending signed transactions as CBOR")
        
        do {
            // Build response structure
            let response: [String: Any] = [
                "id": requestId,
                "reference": "arc0027:sign_transactions:response",
                "result": [
                    "stxns": signedTxns,
                    "providerId": providerId ?? "WalletSDK-iOS"
                ]
            ]
            
            // Convert to CBOR
            let cborData = try encodeToCBOR(response)
            
            // Base64 encode
            let base64String = cborData.base64EncodedString()
            
            NSLog("   CBOR response length: \(cborData.count) bytes")
            NSLog("   Base64 encoded length: \(base64String.count) chars")
            
            // Log first bytes to verify CBOR encoding type
            if !cborData.isEmpty {
                let firstBytes = cborData.prefix(10).map { String(format: "0x%02X", $0) }.joined(separator: " ")
                NSLog("   CBOR first bytes: \(firstBytes)")
                let firstByte = cborData[0]
                let isIndefinite = (firstByte & 0x1F) == 0x1F
                NSLog("   CBOR encoding: \(isIndefinite ? "INDEFINITE-LENGTH (needs 0xFF break)" : "DEFINITE-LENGTH")")
            }
            
            signalService?.sendMessage(base64String)
            NSLog("✅ Signed transactions sent successfully as CBOR!")
        } catch {
            NSLog("❌ Failed to encode transaction response as CBOR: \(error)")
        }
    }
    
    /// Sign challenge data with Algorand wallet's private key using KMP
    /// - Parameters:
    ///   - challenge: The challenge data to sign
    ///   - address: The Algorand address to sign with
    /// - Returns: The signature bytes
    /// - Throws: Error if signing fails
    private func signWithAlgorandWallet(
        challenge: Data,
        address: String
    ) throws -> Data {
        // Convert Swift Data to Kotlin ByteArray
        let challengeKotlin = challenge.toKotlinByteArray()
        
        NSLog("🔍 Swift signing debug:")
        NSLog("   Challenge Data size: \(challenge.count) bytes")
        NSLog("   Challenge Kotlin size: \(challengeKotlin.size) bytes")
        
        // Call KMP function that handles all account types
        guard let signatureKotlin = App_iosKt.signWithAlgorandWallet(
            address: address,
            challenge: challengeKotlin
        ) else {
            NSLog("❌ KMP signWithAlgorandWallet returned nil")
            throw NSError(domain: "Signing failed (returned nil)", code: -1)
        }
        
        NSLog("🔍 Signature returned from KMP:")
        NSLog("   Kotlin ByteArray size: \(signatureKotlin.size)")
        
        // Convert Kotlin ByteArray back to Swift Data
        let signatureData = signatureKotlin.toSwiftData()
        NSLog("   Swift Data size: \(signatureData.count) bytes")
        
        if signatureData.isEmpty {
            NSLog("❌ WARNING: Signature is empty after conversion!")
        }
        
        return signatureData
    }
    
    /// Encode a dictionary to CBOR using SwiftCBOR library
    /// - Parameter dict: Dictionary to encode
    /// - Returns: CBOR-encoded data
    /// - Throws: Error if encoding fails
    private func encodeToCBOR(_ dict: [String: Any]) throws -> Data {
        // Convert dictionary to CBOR-compatible format
        func convertValue(_ value: Any) -> CBOR {
            switch value {
            case let str as String:
                return .utf8String(str)
            case let num as Int:
                return .unsignedInt(UInt64(num))
            case let num as UInt64:
                return .unsignedInt(num)
            case let dict as [String: Any]:
                var cborMap: [CBOR: CBOR] = [:]
                for (key, val) in dict {
                    cborMap[.utf8String(key)] = convertValue(val)
                }
                return .map(cborMap)
            case let array as [Any]:
                return .array(array.map { convertValue($0) })
            case let array as [String]:
                return .array(array.map { .utf8String($0) })
            default:
                NSLog("⚠️ Unknown value type in CBOR encoding: \(type(of: value))")
                return .null
            }
        }
        
        let cborValue = convertValue(dict)
        // Use explicit type annotation to resolve ambiguity
        let cborBytes: [UInt8] = cborValue.encode()
        return Data(cborBytes)
    }
    
    // MARK: - Cleanup
    
    public func disconnect() {
        NSLog("🔌 Disconnecting Liquid Auth")
        // Clear payment DC handler so IOSLiquidStreamViewer falls back to main DC (or nil).
        App_iosKt.setViewerPaymentSendMessageHandler(handler: nil)
        signalService?.disconnect()
        dataChannel = nil
    }
    
    // Guard flag: prevents stacking multiple "Confirmation Required" dialogs.
    // Only one transaction-signing confirmation can be pending at a time.
    private var isShowingConfirmation = false

    private func showConfirmationDialog(message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            // ── Deduplication guard ──────────────────────────────────────────
            // Without this, rapid messages cause a new UIAlertController to be
            // presented on top of the previous one, producing an infinite stack.
            guard !self.isShowingConfirmation else {
                NSLog("⚠️ Confirmation dialog already showing — dropping duplicate request")
                return
            }

            guard let topVC = UIApplication.shared
                .connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap({ $0.windows })
                .first(where: { $0.isKeyWindow })?
                .rootViewController?
                .topMostViewController()
            else {
                NSLog("❌ Unable to find top ViewController")
                return
            }

            // Guard against presenting on an already-presenting VC
            // (topMostViewController can return an alert that is mid-presentation)
            guard topVC.presentedViewController == nil else {
                NSLog("⚠️ Top VC is already presenting — skipping confirmation dialog")
                return
            }

            let alert = UIAlertController(
                title: "Confirmation Required",
                message: "A request has been received. Do you want to sign the dApp-generated transactions?",
                preferredStyle: .alert
            )

            let confirmAction = UIAlertAction(title: "Confirm", style: .default) { [weak self] _ in
                self?.isShowingConfirmation = false
                NSLog("✅ User confirmed message handling")
                self?.handleMessage(message)
            }

            let cancelAction = UIAlertAction(title: "Cancel", style: .cancel) { [weak self] _ in
                self?.isShowingConfirmation = false
                NSLog("❌ User cancelled message handling")
            }

            alert.addAction(cancelAction)
            alert.addAction(confirmAction)

            self.isShowingConfirmation = true
            topVC.present(alert, animated: true)
        }
    }

    
}

extension UIViewController {
    func topMostViewController() -> UIViewController {
        if let presented = self.presentedViewController {
            return presented.topMostViewController()
        }
        if let nav = self as? UINavigationController {
            return nav.visibleViewController?.topMostViewController() ?? nav
        }
        if let tab = self as? UITabBarController {
            return tab.selectedViewController?.topMostViewController() ?? tab
        }
        return self
    }
}

// MARK: - Supporting Types

public struct AttestationCredential {
    let credentialID: Data
    let attestationObject: Data
    let clientDataJSON: Data
}

public struct AssertionCredential {
    let credentialID: Data
    let authenticatorData: Data
    let signature: Data
    let userHandle: Data
    let clientDataJSON: Data
}

// MARK: - Extensions

extension String {
    /// Convert base64url string to Data
    /// WebAuthn uses base64url encoding (RFC 4648) which uses - and _ instead of + and /
    func base64urlToData() -> Data? {
        // Convert base64url to base64
        let base64 = self
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        
        // Add padding if needed
        let remainder = base64.count % 4
        let paddedBase64 = remainder > 0 
            ? base64 + String(repeating: "=", count: 4 - remainder)
            : base64
        
        return Data(base64Encoded: paddedBase64)
    }
}

extension Data {
    /// Convert Data to base64url encoded string
    /// WebAuthn uses base64url encoding (RFC 4648) which uses - and _ instead of + and /
    func base64urlEncodedString() -> String {
        // Encode to standard base64
        let base64 = self.base64EncodedString()
        
        // Convert to base64url by replacing characters and removing padding
        return base64
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")  // Remove padding
    }
    
    /// Generate random data of specified length
    static func random(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }
    
    /// Convert Swift Data to Kotlin ByteArray
    func toKotlinByteArray() -> KotlinByteArray {
        let byteArray = KotlinByteArray(size: Int32(self.count))
        for i in 0..<self.count {
            byteArray.set(index: Int32(i), value: Int8(bitPattern: self[i]))
        }
        return byteArray
    }
}

extension KotlinByteArray {
    /// Convert Kotlin ByteArray to Swift Data
    func toSwiftData() -> Data {
        var data = Data()
        for i in 0..<self.size {
            let byte = self.get(index: i)
            data.append(UInt8(bitPattern: byte))
        }
        return data
    }
}

extension Array where Element == UInt8 {
    /// Convert [UInt8] to Kotlin ByteArray
    func toKotlinByteArray() -> KotlinByteArray {
        let byteArray = KotlinByteArray(size: Int32(self.count))
        for i in 0..<self.count {
            byteArray.set(index: Int32(i), value: Int8(bitPattern: self[i]))
        }
        return byteArray
    }
}
