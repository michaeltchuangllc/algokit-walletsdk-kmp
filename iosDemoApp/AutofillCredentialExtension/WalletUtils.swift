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
import CryptoKit
import deterministicP256_swift
import Foundation
import MnemonicSwift
import x_hd_wallet_api

public struct WalletInfo {
  public let ed25519Wallet: XHDWalletAPI
  public let dp256: DeterministicP256
  public let derivedMainKey: Data
  public let p256KeyPair: P256.Signing.PrivateKey
  public let address: String
  public init(ed25519Wallet: XHDWalletAPI, dp256: DeterministicP256, derivedMainKey: Data, p256KeyPair: P256.Signing.PrivateKey, address: String) {
    self.ed25519Wallet = ed25519Wallet
    self.dp256 = dp256
    self.derivedMainKey = derivedMainKey
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

public func getWalletInfo(origin: String) throws -> WalletInfo {
  let phrase = "youth clog use limit else hub select cause digital oven stand bike alarm ring phone remain trigger essay royal tortoise bless goose forum reflect"
  let seed = try Mnemonic.deterministicSeedString(from: phrase)
  guard let ed25519Wallet = XHDWalletAPI(seed: seed) else {
    throw NSError(domain: "Wallet creation failed", code: -1, userInfo: nil)
  }

  let pk = try ed25519Wallet.keyGen(context: KeyContext.Address, account: 0, change: 0, keyIndex: 0)
  let address = try encodeAddress(bytes: pk)

  let dp256 = DeterministicP256()
  let derivedMainKey = try dp256.genDerivedMainKeyWithBIP39(phrase: phrase)
  let p256KeyPair = dp256.genDomainSpecificKeyPair(derivedMainKey: derivedMainKey, origin: origin, userHandle: address)

  return WalletInfo(
    ed25519Wallet: ed25519Wallet,
    dp256: dp256,
    derivedMainKey: derivedMainKey,
    p256KeyPair: p256KeyPair,
    address: address
  )
}
