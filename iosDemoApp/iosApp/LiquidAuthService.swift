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
 * 2. Data Channel Messages → JSON (matching Android)
 *    - Incoming: Base64-encoded CBOR (from provider-sdk)
 *    - Outgoing: JSON (responses, credentials, transactions)
 *    - Why: Avoids indefinite-length encoding issues with CBOR
 *    - Changed: Both Android and iOS now use JSON for responses
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
    
    // MARK: - Initialization
    
    public init(origin: String, requestId: String, algoAddress: String) {
        self.origin = origin
        self.requestId = requestId
        self.algoAddress = algoAddress
        
        NSLog("🔗 LiquidAuthService initialized")
        NSLog("   Origin: \(origin)")
        NSLog("   RequestID: \(requestId)")
        NSLog("   AlgoAddress: \(algoAddress)")
    }
    
    // MARK: - Public Methods
    
    /// Start the Liquid Auth connection flow
    public func connect(
        onSuccess: @escaping () -> Void,
        onError: @escaping (Error) -> Void
    ) {
        self.onSuccess = onSuccess
        self.onError = onError
        
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
            self.walletInfo = try getWalletInfo(origin: origin)
            
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
            let passkeys = try await passkeyManager.getPasskeysByAlgoAddress(algoAddress: algoAddress)
            
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
            
            let (_, _) = try await attestationApi.postAttestationOptions(
                origin: origin,
                userAgent: userAgent,
                options: options
            )
            
            // For now, use mock attestation options
            // TODO: Parse actual response from server
            let challengeB64 = Data.random(count: 32).base64EncodedString()
            let attestationOptions: [String: Any] = [
                "challenge": challengeB64
            ]
            
            // Create attestation credential
            let credential = try await createAttestationCredential(
                options: attestationOptions,
                walletInfo: walletInfo
            )
            
            // Save to database
            let passkeyManager = PasskeyManager()
            try await passkeyManager.savePasskey(
                siteUrl: origin,
                siteName: origin,
                algoAddress: algoAddress,
                uid: algoAddress,
                username: algoAddress,
                displayName: algoAddress,
                credentialId: credential.credentialID.base64EncodedString()
            )
            
            self.credentialID = credential.credentialID.base64EncodedString()
            
            NSLog("✅ Credential created locally, sending to server...")
            
            // Get account type for FIDO2 (matching Android)
            let accountType = App_iosKt.getAccountTypeForFido2(address: algoAddress)
            NSLog("📋 Account type: \(accountType)")
            
            // Sign the challenge with Algorand wallet (NOT with P256 key!)
            let challengeData = Data(base64Encoded: challengeB64)!
            let algoSignature = try signWithAlgorandWallet(
                challenge: challengeData,
                address: algoAddress
            )
            
            NSLog("✅ Algorand signature computed: \(algoSignature.base64EncodedString().prefix(20))...")
            
            // Build liquid extension JSON (matching Android)
            let liquidExt: [String: Any] = [
                "type": accountType,  // "algorand" or "falcon-1024"
                "requestId": requestId,
                "address": algoAddress,
                "publicKey": walletInfo.p256KeyPair.publicKey.rawRepresentation.base64EncodedString(),
                "signature": algoSignature.base64EncodedString(),  // Algorand wallet signature of challenge
                "device": UIDevice.current.model
            ]
            
            // Build credential dictionary for server
            let credentialDict: [String: Any] = [
                "id": credential.credentialID.base64EncodedString(),
                "rawId": credential.credentialID.base64EncodedString(),
                "type": "public-key",
                "response": [
                    "attestationObject": credential.attestationObject.base64EncodedString(),
                    "clientDataJSON": credential.clientDataJSON.base64EncodedString()
                ]
            ]
            
            // Send attestation response to server
            NSLog("📤 Sending attestation response to server...")
            let _ = try await attestationApi.postAttestationResult(
                origin: origin,
                userAgent: userAgent,
                credential: credentialDict,
                liquidExt: liquidExt,
                device: UIDevice.current.model
            )
            
            NSLog("✅ Attestation response sent successfully!")
            NSLog("✅ Registration complete, setting up WebRTC...")
            
            // Setup WebRTC connection
            await setupWebRTC(credential: credential)
            
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
            
            // Build user-agent (matching Android format)
            let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
            let systemVersion = UIDevice.current.systemVersion
            let deviceModel = UIDevice.current.model
            let userAgent = "com.michaeltchuang.walletsdk.demo/\(appVersion) (iOS \(systemVersion); \(deviceModel); Apple)"
            
            let (responseData, httpResponse) = try await assertionApi.postAssertionOptions(
                origin: origin,
                userAgent: userAgent,
                credentialId: credentialID,
                liquidExt: true
            )
            
            // For now, use mock assertion options
            // TODO: Parse actual response from server
            let assertionOptions: [String: Any] = [
                "challenge": Data.random(count: 32).base64EncodedString()
            ]
            
            // Create assertion credential
            let credential = try await createAssertionCredential(
                options: assertionOptions,
                walletInfo: walletInfo,
                credentialID: credentialID
            )
            
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
        
        let rpIdHash = Utility.hashSHA256(origin.data(using: .utf8)!)
        
        // Get challenge from options
        guard let challengeB64 = options["challenge"] as? String,
              let challengeData = Data(base64Encoded: challengeB64) else {
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
        let attObj: [CBOR: CBOR] = [
            .utf8String("attStmt"): .map([:]),
            .utf8String("authData"): .byteString([UInt8](authData)),
            .utf8String("fmt"): .utf8String("none"),
        ]
        
        let cborObject = CBOR.map(attObj)
        let cborEncoded = cborObject.encode()
        let attestationObject = Data(cborEncoded)
        
        let clientData: [String: Any] = [
            "type": "webauthn.create",
            "challenge": challengeB64,  // Keep as base64url string
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
        credentialID: String
    ) async throws -> AssertionCredential {
        
        // Get challenge from options
        guard let challengeB64 = options["challenge"] as? String,
              let challengeData = Data(base64Encoded: challengeB64) else {
            throw NSError(domain: "Invalid challenge", code: -1)
        }
        
        let credIDData = Data(base64Encoded: credentialID)!
        let userHandleData = Data(walletInfo.address.utf8)
        
        // Authenticator data
        let rpIdHash = Utility.hashSHA256(origin.data(using: .utf8)!)
        let authenticatorData = AuthenticatorData.assertion(
            rpIdHash: rpIdHash,
            userPresent: true,
            userVerified: true,
            backupEligible: true,
            backupState: true,
            signCount: 0
        ).toData()
        
        // Signature: sign authenticatorData || clientDataHash
        let dataToSign = authenticatorData + challengeData
        let signature = try walletInfo.p256KeyPair.signature(for: dataToSign).derRepresentation
        
        return AssertionCredential(
            credentialID: credIDData,
            authenticatorData: authenticatorData,
            signature: signature,
            userHandle: userHandleData,
            clientDataJSON: challengeData
        )
    }
    
    private func setupWebRTC(credential: Any) async {
        NSLog("🌐 Setting up WebRTC connection...")
        NSLog("   Request ID: \(requestId)")
        NSLog("   Origin: \(origin)")
        
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
        
        // Connect to peer using SignalService
        signalService.connectToPeer(
            requestId: requestId,
            type: "answer",  // iOS wallet acts as the "answer" side
            origin: origin,
            iceServers: iceServers,
            onMessage: { [weak self] message in
                guard let self = self else { return }
                NSLog("💬 Received message: \(message)")
                self.handleMessage(message)
            },
            onStateChange: { [weak self] state in
                guard let self = self else { return }
                NSLog("📡 Data channel state change: \(state ?? "unknown")")
                
                if state == "open" {
                    NSLog("✅ Data channel is OPEN, sending credential")
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
    
    private func sendCredentialMessage(credential: Any) {
        NSLog("📤 Sending credential message as JSON")
        
        // Send as JSON (not CBOR) - matches Android implementation
        let credentialMessage: [String: Any] = [
            "type": "credential",
            "address": algoAddress,
            "provider": "WalletSDK-iOS"
        ]
        
        if let jsonData = try? JSONSerialization.data(withJSONObject: credentialMessage),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            signalService?.sendMessage(jsonString)
            NSLog("✅ Credential message sent as JSON (not CBOR)")
            
            // Notify success
            DispatchQueue.main.async { [weak self] in
                self?.onSuccess?()
            }
        }
    }
    
    private func handleMessage(_ message: String) {
        NSLog("🔍 Handling message: \(message)")
        NSLog("   Message length: \(message.count)")
        
        // Messages come in as Base64-encoded CBOR (matching Android)
        // But we respond with JSON (matching Android change to avoid indefinite-length encoding issues)
        
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
            // TODO: Implement transaction signing
            // For now, send an error response
            sendTransactionErrorResponse(requestId: requestId ?? "unknown")
            
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
    
    private func sendTransactionErrorResponse(requestId: String) {
        NSLog("📤 Sending transaction error response as JSON")
        
        // Send response as JSON (matching Android change)
        // Note: Android switched from CBOR to JSON to avoid indefinite-length encoding issues
        let errorResponse: [String: Any] = [
            "id": requestId,
            "reference": "arc0027:sign_transactions:response",
            "error": [
                "code": 4100,
                "message": "Transaction signing not yet implemented on iOS"
            ]
        ]
        
        if let jsonData = try? JSONSerialization.data(withJSONObject: errorResponse),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            signalService?.sendMessage(jsonString)
            NSLog("✅ Error response sent as JSON (not CBOR)")
        }
    }
    
    /// Send signed transaction response as JSON
    /// Use this when transaction signing is implemented
    /// Important: Send as JSON, NOT CBOR (to match Android and avoid encoding issues)
    private func sendTransactionResponse(requestId: String, signedTxns: [String], providerId: String?) {
        NSLog("📤 Sending signed transactions as JSON")
        
        let response: [String: Any] = [
            "id": requestId,
            "reference": "arc0027:sign_transactions:response",
            "result": [
                "stxns": signedTxns,
                "providerId": providerId ?? "WalletSDK-iOS"
            ]
        ]
        
        if let jsonData = try? JSONSerialization.data(withJSONObject: response),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            NSLog("   JSON response length: \(jsonString.count) chars")
            NSLog("   JSON response (first 500 chars): \(jsonString.prefix(500))...")
            signalService?.sendMessage(jsonString)
            NSLog("✅ Signed transactions sent successfully as JSON (not CBOR)!")
        }
    }
    
    /// Sign challenge data with Algorand wallet's private key using KMP functions
    /// - Parameters:
    ///   - challenge: The challenge data to sign
    ///   - address: The Algorand address to sign with
    /// - Returns: The signature bytes
    /// - Throws: Error if account not found or signing fails
    private func signWithAlgorandWallet(
        challenge: Data,
        address: String
    ) throws -> Data {
        NSLog("🔐 Signing challenge with Algorand wallet (using KMP)")
        NSLog("   Address: \(address)")
        NSLog("   Challenge size: \(challenge.count) bytes")
        
        // Get the account's mnemonic
        guard let mnemonic = self.mnemonic else {
            throw NSError(domain: "Mnemonic not available", code: -1)
        }
        
        do {
            let localAccount = try App_iosKt.getLocalAccount(address: address)
            
            let challengeKotlin = challenge.toKotlinByteArray()
            
            // Sign based on account type
            if localAccount is LocalAccountAlgo25 {
                // Algo25 account - use KMP's signAlgo25ArbitraryData
                guard let algo25Account = AlgoAccountKt.recoverAlgo25Account(mnemonic: mnemonic) else {
                    throw NSError(domain: "Failed to recover Algo25 account", code: -1)
                }
                
                let secretKeyKotlin = algo25Account.secretKey.toKotlinByteArray()
                
                guard let signature = AlgoAccountKt.signAlgo25ArbitraryData(
                    data: challengeKotlin,
                    secretKey: secretKeyKotlin
                ) else {
                    throw NSError(domain: "Algo25 signing failed", code: -1)
                }
                
                let signatureData = signature.toSwiftData()
                NSLog("✅ Signed with Algo25 (KMP), signature size: \(signatureData.count) bytes")
                return signatureData
                
            } else if let hdKey = localAccount as? LocalAccountHdKey {
                // HD Key account - use KMP's signHdKeyData
                guard let seed = try? App_iosKt.getHdSeed(seedId: hdKey.seedId) else {
                    throw NSError(domain: "Failed to get HD seed", code: -1)
                }
                
                let seedKotlin = seed.seed.toKotlinByteArray()
                
                guard let signature = AlgoAccountKt.signHdKeyData(
                    data: challengeKotlin,
                    seed: seedKotlin,
                    account: hdKey.account,
                    change: hdKey.change,
                    key: hdKey.keyIndex
                ) else {
                    throw NSError(domain: "HD Key signing failed", code: -1)
                }
                
                let signatureData = signature.toSwiftData()
                NSLog("✅ Signed with HD Key (KMP), signature size: \(signatureData.count) bytes")
                return signatureData
                
            } else if let falcon = localAccount as? LocalAccountFalcon24 {
                // Falcon account - use KMP's signFalcon24ArbitraryData
                guard let privateKey = try? App_iosKt.getFalcon24SecretKey(address: address) else {
                    throw NSError(domain: "Failed to get Falcon24 private key", code: -1)
                }
                
                guard let signature = AlgoAccountKt.signFalcon24ArbitraryData(
                    data: challengeKotlin,
                    publicKey: falcon.publicKey,
                    privateKey: privateKey
                ) else {
                    throw NSError(domain: "Falcon24 signing failed", code: -1)
                }
                
                let signatureData = signature.toSwiftData()
                NSLog("✅ Signed with Falcon24 (KMP), signature size: \(signatureData.count) bytes")
                return signatureData
                
            } else {
                throw NSError(
                    domain: "Unsupported account type: \(type(of: localAccount))",
                    code: -1
                )
            }
        } catch {
            NSLog("❌ Failed to get local account or sign: \(error)")
            throw error
        }
    }
    
    // MARK: - Cleanup
    
    public func disconnect() {
        NSLog("🔌 Disconnecting Liquid Auth")
        signalService?.disconnect()
        dataChannel = nil
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

extension Data {
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
