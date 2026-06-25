import AuthenticationServices
import Base32
import composeDemoApp
import CryptoKit
import deterministicP256_swift
import LocalAuthentication
import MnemonicSwift
import SwiftCBOR
import UIKit

/**
 * IMPORTANT: AutoFill Credential Extension
 *
 * This extension handles standard WebAuthn/Passkey flows when the user:
 * - Uses Safari or other apps that request passkeys
 * - Sees this wallet appear in the system's passkey picker
 *
 * It uses LiquidAuthSDK for shared WebAuthn utilities like AuthenticatorData,
 * but doesn't use the custom LiquidAuth signaling protocol (no QR codes, no P2P).
 *
 * Passkeys are stored in the shared database via PasskeyManager (using Koin DI).
 */

class CredentialProviderViewController: ASCredentialProviderViewController {
    // PasskeyManager for database operations
    private let passkeyManager = PasskeyManager()
    
    // Registration flow
    override func prepareInterface(forPasskeyRegistration request: ASCredentialRequest) {
        if #available(iOSApplicationExtension 17.0, *) {
            guard let passkeyRequest = request as? ASPasskeyCredentialRequest else { return }
            Task {
                let consent = await presentUserConsentAlert(
                    title: "Register Passkey",
                    message: "Do you want to register a new passkey for this site?"
                )
                guard consent else {
                    self.extensionContext.cancelRequest(withError: NSError(domain: "User cancelled", code: -1))
                    return
                }
                do {
                    let credential = try await createRegistrationCredential(for: passkeyRequest)

                    // Save passkey to database
                    if let passkeyIdentity = passkeyRequest.credentialIdentity as? ASPasskeyCredentialIdentity {
                        try await savePasskeyToDatabase(
                            passkeyIdentity: passkeyIdentity,
                            credentialID: credential.credentialID
                        )
                    }

                    await extensionContext.completeRegistrationRequest(using: credential)
                } catch let error as NSError {
                    NSLog("❌ [CredentialProvider] Registration error: \(error.localizedDescription)")

                    // Check if it's a "no account" error
                    if error.code == 1001 || error.code == 1002 {
                        await self.presentErrorAlert(
                            title: "Account Required",
                            message: error.localizedDescription
                        )
                    } else if error.domain == "Credential already exists for this site" {
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                    }

                    self.extensionContext.cancelRequest(withError: error)
                }
            }
        } else {
            extensionContext.cancelRequest(withError: NSError(domain: "Passkeys require iOS 17+", code: -1))
        }
    }

    override func prepareCredentialList(
        for _: [ASCredentialServiceIdentifier],
        requestParameters: ASPasskeyCredentialRequestParameters
    ) {
        Task {
            await handleCredentialListRequest(requestParameters: requestParameters)
        }
    }
    
    private func handleCredentialListRequest(requestParameters: ASPasskeyCredentialRequestParameters) async {
        let origin = requestParameters.relyingPartyIdentifier
        
        do {
            // Check if we have a passkey registered for this site in the database
            // For now, we'll generate the credential as before, but this could be enhanced
            // to query the database for all passkeys for this origin
            
            let walletInfo = try getWalletInfo(origin: origin)
            let credentialID = Data(Utility.hashSHA256(walletInfo.p256KeyPair.publicKey.rawRepresentation))

            // Check if this credential exists in database (optional verification)
            let credentialIDString = credentialID.base64EncodedString()
            let existsInDB = try await checkPasskeyExists(credentialID: credentialIDString)
            
            // Only provide credential if it exists in database
            guard existsInDB else {
                extensionContext.cancelRequest(withError: NSError(
                    domain: ASExtensionErrorDomain,
                    code: ASExtensionError.Code.credentialIdentityNotFound.rawValue
                ))
                return
            }

            let userHandleData = Data(walletInfo.address.utf8)
            let clientDataHash = requestParameters.clientDataHash

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
            let dataToSign = authenticatorData + clientDataHash
            let signature = try walletInfo.p256KeyPair.signature(for: dataToSign).derRepresentation

            let credential = ASPasskeyAssertionCredential(
                userHandle: userHandleData,
                relyingParty: origin,
                signature: signature,
                clientDataHash: clientDataHash,
                authenticatorData: authenticatorData,
                credentialID: credentialID
            )
            
            // Update last used timestamp
            do {
                try await passkeyManager.updateLastUsedTime(credentialId: credentialIDString)
            } catch {
                // Non-fatal: log but continue with authentication
                NSLog("Failed to update lastUsed time: \(error)")
            }

            await extensionContext.completeAssertionRequest(using: credential)
        } catch let error as NSError {
            NSLog("❌ [CredentialProvider] Assertion error: \(error.localizedDescription)")

            // Check if it's a "no account" error
            if error.code == 1001 || error.code == 1002 {
                await presentErrorAlert(
                    title: "Account Required",
                    message: error.localizedDescription
                )
            }

            extensionContext.cancelRequest(withError: NSError(
                domain: ASExtensionErrorDomain,
                code: ASExtensionError.Code.failed.rawValue
            ))
        }
    }

    func presentUserConsentAlert(title: String, message: String) async -> Bool {
        await withCheckedContinuation { continuation in
            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "Continue", style: .default) { _ in
                continuation.resume(returning: true)
            })
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in
                continuation.resume(returning: false)
            })
            // Present on the main thread
            DispatchQueue.main.async {
                self.present(alert, animated: true, completion: nil)
            }
        }
    }
    
    func presentErrorAlert(title: String, message: String) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in
                continuation.resume()
            })
            // Present on the main thread
            DispatchQueue.main.async {
                self.present(alert, animated: true, completion: nil)
            }
        }
    }

    // Registration
    private func createRegistrationCredential(for request: ASPasskeyCredentialRequest) async throws -> ASPasskeyRegistrationCredential {
        guard let credentialIdentity = request.credentialIdentity as? ASPasskeyCredentialIdentity else {
            throw NSError(domain: "Missing credential identity", code: -1)
        }

        let origin = credentialIdentity.relyingPartyIdentifier
        let clientDataHash = request.clientDataHash

        let walletInfo = try getWalletInfo(origin: origin) // , userHandle: userHandle)
        let pubkey = walletInfo.p256KeyPair.publicKey.rawRepresentation
        let credentialID = Data([UInt8](Utility.hashSHA256(pubkey)))

        // --- ExcludeCredentials check ---
        // Check if credential already exists in database
        let credentialIDString = credentialID.base64EncodedString()
        if try await checkPasskeyExists(credentialID: credentialIDString) {
            let shouldCancel = await presentCredentialExistsAlert()
            if shouldCancel {
                throw NSError(domain: "Credential already exists for this site", code: -2)
            }
        }

        // Also check excludedCredentials from the request
        if let excludedCredentials = request.excludedCredentials {
            for excluded in excludedCredentials {
                if excluded.credentialID == credentialID {
                    let shouldCancel = await presentCredentialExistsAlert()
                    if shouldCancel {
                        throw NSError(domain: "Credential already exists for this site", code: -2)
                    }
                }
            }
        }

        // --- Build attestationObject ---
        let aaguid = UUID(uuidString: "1F59713A-C021-4E63-9158-2CC5FDC14E52")!
        let attestedCredData = Utility.getAttestedCredentialData(
            aaguid: aaguid,
            credentialId: credentialID,
            publicKey: pubkey
        )

        let rpIdHash = Utility.hashSHA256(origin.data(using: .utf8)!)

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

        let attObj: [CBOR: CBOR] = [
                    .utf8String("attStmt"): .map([:]),
                    .utf8String("authData"): .byteString([UInt8](authData)),
                    .utf8String("fmt"): .utf8String("none"),
                ]

        let cborObject = CBOR.map(attObj)
        let cborEncoded = cborObject.encode()
        let attestationObject = Data(cborEncoded)

        return ASPasskeyRegistrationCredential(
            relyingParty: credentialIdentity.relyingPartyIdentifier,
            clientDataHash: clientDataHash,
            credentialID: credentialID,
            attestationObject: attestationObject
        )
    }

    override func prepareInterfaceForExtensionConfiguration() {
        // This method is called when the user enables the extension in Settings.
        // You can present a configuration UI here if needed.
        // For now, we will just dismiss the view controller.
        extensionContext.completeExtensionConfigurationRequest()
    }

    func requireUserVerification(reason: String = "Authenticate to continue") async -> Bool {
        let context = LAContext()
        var error: NSError?
        let policy: LAPolicy = .deviceOwnerAuthentication // biometrics OR passcode

        if context.canEvaluatePolicy(policy, error: &error) {
            return await withCheckedContinuation { continuation in
                context.evaluatePolicy(policy, localizedReason: reason) { success, _ in
                    continuation.resume(returning: success)
                }
            }
        } else {
            // Device does not support biometrics/passcode
            return false
        }
    }

    private func presentCredentialExistsAlert() async -> Bool {
        await withCheckedContinuation { continuation in
            let alert = UIAlertController(
                title: "Credential Already Exists",
                message: "A passkey for this site already exists. Do you want to cancel registration?",
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "Cancel Registration", style: .destructive) { _ in
                continuation.resume(returning: true) // Cancel
            })
            alert.addAction(UIAlertAction(title: "Continue Anyway", style: .default) { _ in
                continuation.resume(returning: false) // Continue
            })
            DispatchQueue.main.async {
                self.present(alert, animated: true, completion: nil)
            }
        }
    }

    private func savePasskeyToDatabase(
        passkeyIdentity: ASPasskeyCredentialIdentity,
        credentialID: Data
    ) async throws {
        let origin = passkeyIdentity.relyingPartyIdentifier
        let walletInfo = try getWalletInfo(origin: origin)
        
        // Extract user info
        let username = passkeyIdentity.user
        let displayName = passkeyIdentity.user // Use username as displayName if not available separately
        let userHandle = String(data: passkeyIdentity.userHandle, encoding: .utf8) ?? walletInfo.address
        
        // Save to database via PasskeyManager
        try await passkeyManager.savePasskey(
            siteUrl: origin,
            siteName: origin, // Use origin as siteName (could be enhanced to parse domain)
            algoAddress: walletInfo.address,
            uid: userHandle,
            username: username,
            displayName: displayName,
            credentialId: credentialID.base64EncodedString()
        )
    }
    
    private func checkPasskeyExists(credentialID: String) async throws -> Bool {
        let passkey = try await passkeyManager.getPasskey(credentialId: credentialID)
        return passkey != nil
    }
}

