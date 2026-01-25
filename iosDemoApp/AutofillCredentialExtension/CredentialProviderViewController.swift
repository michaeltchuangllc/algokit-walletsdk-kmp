//
//  CredentialProviderViewController.swift
//  AutofillCredentialExtension
//
//  Created by Mithilesh on 24/01/26.
//

import AuthenticationServices
import Base32
import CryptoKit
import deterministicP256_swift
import LiquidAuthSDK
import LocalAuthentication
import MnemonicSwift
import SwiftCBOR
import UIKit
import x_hd_wallet_api

/**
 * IMPORTANT: AutoFill Credential Extension
 *
 * This extension handles standard WebAuthn/Passkey flows when the user:
 * - Uses Safari or other apps that request passkeys
 * - Sees this wallet appear in the system's passkey picker
 *
 * It uses LiquidAuthSDK for shared WebAuthn utilities like AuthenticatorData,
 * but doesn't use the custom LiquidAuth signaling protocol (no QR codes, no P2P).
 */

class CredentialProviderViewController: ASCredentialProviderViewController {
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
                    // Save userHandle for this RP
                    if let passkeyIdentity = passkeyRequest.credentialIdentity as? ASPasskeyCredentialIdentity,
                       let userHandle = String(data: passkeyIdentity.userHandle, encoding: .utf8)
                    {
                        saveRegisteredUserHandle(userHandle, forRP: passkeyIdentity.relyingPartyIdentifier)
                    }
                    await extensionContext.completeRegistrationRequest(using: credential)
                } catch let error as NSError {
                    if error.domain == "Credential already exists for this site" {
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
//        var credentials: [ASPasskeyAssertionCredential] = []
//
//        // Use relyingPartyIdentifier from requestParameters
//        let origin = requestParameters.relyingPartyIdentifier
//        if let walletInfo = try? getWalletInfo(origin: origin) {
//            let credentialID = Data(Utility.hashSHA256(walletInfo.p256KeyPair.publicKey.rawRepresentation))
//            let userHandleData = Data(walletInfo.address.utf8)
//            let clientDataHash = requestParameters.clientDataHash
//
//            // Authenticator data
//            let rpIdHash = Utility.hashSHA256(origin.data(using: .utf8)!)
//            let authenticatorData = LiquidAuthSDK.AuthenticatorData.assertion(
//                rpIdHash: rpIdHash,
//                userPresent: true,
//                userVerified: true,
//                backupEligible: true,
//                backupState: true,
//                signCount: 0
//            ).toData()
//
//            // Signature: sign authenticatorData || clientDataHash
//            let dataToSign = authenticatorData + clientDataHash
//            let signature: Data
//            do {
//                signature = try walletInfo.p256KeyPair.signature(for: dataToSign).derRepresentation
//            } catch {
//                NSLog("Failed to sign assertion: \(error)")
//                signature = Data()
//            }
//
//            let credential = ASPasskeyAssertionCredential(
//                userHandle: userHandleData,
//                relyingParty: origin,
//                signature: signature,
//                clientDataHash: clientDataHash,
//                authenticatorData: authenticatorData,
//                credentialID: credentialID
//            )
//            credentials.append(credential)
//        }
//
//        // Only 0 or 1 credential is ever present; auto-select if available
//        Task { [weak self] in
//            if let credential = credentials.first {
//                await self?.extensionContext.completeAssertionRequest(using: credential)
//            } else {
//                self?.extensionContext.cancelRequest(withError: NSError(domain: ASExtensionErrorDomain, code: ASExtensionError.Code.userCanceled.rawValue))
//            }
//        }
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
        if let excludedCredentials = request.excludedCredentials {
            for excluded in excludedCredentials {
                if excluded.credentialID == credentialID {
                    // Optionally show a UI to the user here
                    let shouldCancel = await presentCredentialExistsAlert()
                    if shouldCancel {
                        // Throw error as before; delay is handled in prepareInterface
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

        let attObj: [String: CBOR] = [
            "attStmt": CBOR.map([:]),
            "authData": CBOR.byteString([UInt8](authData)),
            "fmt": CBOR.utf8String("none"),
        ]
        let cborEncoded = try CBOR.encode(attObj)
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

    private func saveRegisteredUserHandle(_ userHandle: String, forRP rp: String) {
        var dict = UserDefaults.standard.dictionary(forKey: "registeredUserHandles") as? [String: [String]] ?? [:]
        var handles = dict[rp] ?? []
        if !handles.contains(userHandle) {
            handles.append(userHandle)
            dict[rp] = handles
            UserDefaults.standard.set(dict, forKey: "registeredUserHandles")
        }
    }
}

