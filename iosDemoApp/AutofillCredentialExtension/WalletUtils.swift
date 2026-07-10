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

import Base32
import sharedDemoApp
import CryptoKit
import deterministicP256_swift
import Foundation
import MnemonicSwift

public struct WalletInfo {
  public let p256KeyPair: P256.Signing.PrivateKey
  public let address: String
  public init(p256KeyPair: P256.Signing.PrivateKey, address: String) {
    self.p256KeyPair = p256KeyPair
    self.address = address
  }
}

/// Encode an Ed25519 public key into an Algorand Base32 address with checksum
/// - Parameter bytes: The Ed25519 public key bytes
/// - Returns: Base32 encoded Algorand address string
/// - Throws: NSError if the address length is unexpected
public func encodeAddress(bytes: Data) throws -> String {
  let lenBytes = 32
  let checksumLenBytes = 4
  let expectedStrEncodedLen = 58

  // compute sha512/256 checksum
  let hash = Data(SHA512_256().hash([UInt8](bytes)))
  let hashedAddr = hash[..<lenBytes] // Take the first 32 bytes

  // take the last 4 bytes of the hashed address, and append to original bytes
  let checksum = hashedAddr[(hashedAddr.count - checksumLenBytes)...]
  let checksumAddr = bytes + checksum

  // encodeToMsgPack addr+checksum as base32 and return. Strip padding.
  let res = base32Encode(checksumAddr).trimmingCharacters(in: ["="])
  if res.count != expectedStrEncodedLen {
    throw NSError(
      domain: "",
      code: 0,
      userInfo: [NSLocalizedDescriptionKey: "unexpected address length \(res.count)"]
    )
  }
  return res
}

public func getWalletInfo(origin: String, forAddress: String? = nil) throws -> WalletInfo {
    // Initialize Koin and App Group before anything else
    AppGroupHelper.configureAppGroup()
    App_iosKt.initializeKoin()

    let phrase: String
    let targetAddress: String

    do {
        // If specific address provided, use it; otherwise use first HD seed
        if let forAddress = forAddress {
            targetAddress = forAddress
            let accountMnemonic = try App_iosKt.getAccountMnemonic(address: forAddress)
            phrase = accountMnemonic.words.joined(separator: " ")
        } else {
            let hdSeedFirstAddresses = try App_iosKt.getAllHdSeedFirstAddresses()

            guard let firstHdSeed = hdSeedFirstAddresses.first else {
                throw NSError(
                    domain: "com.michaeltchuang.walletsdk.demo",
                    code: 1001,
                    userInfo: [
                        NSLocalizedDescriptionKey: "No valid account found. Please create one in the WalletSDK Demo app first.",
                        NSLocalizedFailureReasonErrorKey: "Account not found in database"
                    ]
                )
            }
            
            targetAddress = firstHdSeed.firstAddress
            let accountMnemonic = try App_iosKt.getAccountMnemonic(address: targetAddress)
            phrase = accountMnemonic.words.joined(separator: " ")
        }
    } catch {
        // Convert Kotlin exception to proper NSError for XPC compatibility
        throw NSError(
            domain: "com.michaeltchuang.walletsdk.demo",
            code: 1002,
            userInfo: [
                NSLocalizedDescriptionKey: "Unable to retrieve account. Please ensure you have created an account in the WalletSDK Demo app.",
                NSLocalizedFailureReasonErrorKey: error.localizedDescription
            ]
        )
    }

    // Check if this is an Algo25 account (25 words) or HD/Falcon (24 words)
    let wordCount = phrase.components(separatedBy: " ").count
    let isAlgo25 = (wordCount == 25)
    
    let dp256 = DeterministicP256()
    let derivedMainKey: Data
    
    if isAlgo25 {
        // Algo25: Use Ed25519 secret key for P256 derivation (25-word mnemonic is NOT BIP39)
        // Get the secret key from the Algo25 account
        guard let algo25SecretKeyKotlin = App_iosKt.getAlgo25SecretKeyFromMnemonic(phrase: phrase) else {
            throw NSError(domain: "Failed to get Algo25 secret key", code: -1, userInfo: nil)
        }
        
        // Convert Kotlin ByteArray to Swift Data
        var secretKeyBytes = [UInt8]()
        for i in 0..<algo25SecretKeyKotlin.size {
            let byte = algo25SecretKeyKotlin.get(index: i)
            secretKeyBytes.append(UInt8(bitPattern: byte))
        }
        
        // Use first 32 bytes (seed) of Ed25519 secret key for P256 derivation
        derivedMainKey = Data(secretKeyBytes.prefix(32))
    } else {
        // HD/Falcon: Use standard BIP39 derivation (24-word mnemonic)
        derivedMainKey = try dp256.genDerivedMainKeyWithBIP39(phrase: phrase)
    }
    
    // IMPORTANT: Use targetAddress for P256 derivation - this creates unique credentials per account!
    let p256KeyPair = dp256.genDomainSpecificKeyPair(derivedMainKey: derivedMainKey, origin: origin, userHandle: targetAddress)
    
    // We already have the address from targetAddress parameter - no need to derive it!
    return WalletInfo(
        p256KeyPair: p256KeyPair,
        address: targetAddress  // Use the provided address directly
    )
}
