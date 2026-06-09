import Foundation
import x_hd_wallet_api
import MnemonicSwift
import AlgoSDK
import CryptoKit
import AlgoKitTransact
import CommonCrypto

@objcMembers public class spmAlgoApiBridge: NSObject {

    @_optimize(none)
    public func getHdPublicKeyFromSeed(seedBase64: String, account: Int, change: Int, keyIndex: Int) -> String {
        do {
            guard let seedData = Data(base64Encoded: seedBase64) else {
                print("Failed to decode seed")
                return ""
            }

            let seedHex = seedData.map { String(format: "%02x", $0) }.joined()

            guard let wallet = XHDWalletAPI(seed: seedHex) else {
                print("Failed to create wallet")
                return ""
            }

            let publicKey = try wallet.keyGen(
                context: .Address,
                account: UInt32(account),
                change: UInt32(change),
                keyIndex: UInt32(keyIndex),
                derivationType: .Peikert
            )
            return publicKey.base64EncodedString()

        } catch {
            print("Failed to generate key: \(error)")
            return ""
        }
    }

    @_optimize(none)
    public func getHdPrivateKeyFromSeed(seedBase64: String, account: Int, change: Int, keyIndex: Int) -> String {
        do {
            guard let seedData = Data(base64Encoded: seedBase64) else {
                print("Failed to decode seed")
                return ""
            }

            let seedHex = seedData.map { String(format: "%02x", $0) }.joined()

            guard let wallet = XHDWalletAPI(seed: seedHex) else {
                print("Failed to create wallet")
                return ""
            }

            // Construct BIP44 path as [UInt32] array
            // For Algorand: m/44'/283'/account'/change/keyIndex
            // Hardened derivation: add 0x80000000
            let hardenedOffset: UInt32 = 0x80000000
            let bip44Path: [UInt32] = [
                44 + hardenedOffset, // 44' - purpose
                283 + hardenedOffset, // 283' - Algorand coin type
                UInt32(account) + hardenedOffset, // account' - hardened
                UInt32(change), // change - not hardened
                UInt32(keyIndex)          // keyIndex - not hardened
            ]


            return try wallet.deriveKey(
                rootKey: wallet.fromSeed(seedData),
                bip44Path: bip44Path,
                isPrivate: true,
                derivationType: BIP32DerivationType.Peikert
            ).base64EncodedString()

        } catch let error {
            print("Failed to generate private key: \(error)")
            return ""
        }
    }

    @_optimize(none)
    public func signHdKeyTransaction(
        transactionBytes: Data,
        seedBase64: String,
        account: Int,
        change: Int,
        keyIndex: Int
    ) -> Data? {
        do {
            guard let seedData = Data(base64Encoded: seedBase64) else {
                print("Failed to decode seed from Base64")
                return nil
            }

            let seedHex = seedData.map { String(format: "%02x", $0) }.joined()

            guard let wallet = XHDWalletAPI(seed: seedHex) else {
                print("Failed to create wallet")
                return nil
            }

            let txPrefix = "TX".data(using: .utf8)!
            let prefixedTransaction = txPrefix + transactionBytes

            let signature = try wallet.sign(
                context: .Address,
                account: UInt32(account),
                change: UInt32(change),
                keyIndex: UInt32(keyIndex),
                message: prefixedTransaction,
                derivationType: .Peikert
            )

            let publicKey = try wallet.keyGen(
                context: .Address,
                account: UInt32(account),
                change: UInt32(change),
                keyIndex: UInt32(keyIndex),
                derivationType: .Peikert
            )

            var error: NSError?
            guard let signedTx = AlgoSDK.AlgoSdkAttachSignature(
                signature,
                transactionBytes,
                &error
            ) else {
                if let error = error {
                    print("AlgoSdkAttachSignature failed: \(error.localizedDescription)")
                } else {
                    print("AlgoSdkAttachSignature returned nil")
                }
                return nil
            }

            return signedTx

        } catch {
            print("Transaction signing failed: \(error.localizedDescription)")
            return nil
        }
    }

    @_optimize(none)
    public func signHdArbitraryDataWithSeedBase64(
        seedBase64: String,
        account: Int,
        change: Int,
        keyIndex: Int,
        dataBase64: String
    ) -> String {
        do {
            guard let seedData = Data(base64Encoded: seedBase64) else {
                NSLog("❌ Failed to decode seed from Base64")
                return ""
            }

            guard let data = Data(base64Encoded: dataBase64) else {
                NSLog("❌ Failed to decode data from Base64")
                return ""
            }

            let seedHex = seedData.map { String(format: "%02x", $0) }.joined()

            guard let wallet = XHDWalletAPI(seed: seedHex) else {
                NSLog("❌ Failed to create XHDWalletAPI wallet")
                return ""
            }

            // Sign the data with Ed25519
            let signature = try wallet.sign(
                context: .Address,
                account: UInt32(account),
                change: UInt32(change),
                keyIndex: UInt32(keyIndex),
                message: data,
                derivationType: .Peikert
            )

            return signature.base64EncodedString()

        } catch {
            NSLog("❌ HD arbitrary data signing failed: \(error.localizedDescription)")
            return ""
        }
    }

    public func getAlgo25SecretKey(mnemonic: String?) -> String {
        if let mnemonic = mnemonic {
            var error: NSError?
            guard let secretKeyData = AlgoSDK.AlgoSdkMnemonicToPrivateKey(mnemonic, &error) else {
                print("Failed to convert mnemonic to secret key.")
                return ""
            }
            return secretKeyData.base64EncodedString()
        } else {
            guard let newSecretKey = AlgoSDK.AlgoSdkGenerateSK() else {
                print("Failed to generate new secret key.")
                return ""
            }
            return newSecretKey.base64EncodedString()
        }
    }

    public func isValidAlgorandAddress(address: String?) -> Bool {
        if address != nil {
            return AlgoSDK.AlgoSdkIsValidAddress(address)
        } else {
            return false
        }
    }

    public func getAlgo25MnemonicFromSecretKey(secretKey: Data) -> String {
        var error: NSError?
        let mnemonic = AlgoSDK.AlgoSdkMnemonicFromPrivateKey(secretKey, &error)

        if let error = error {
            print("Error generating mnemonic: \(error)")
            return ""
        }

        return mnemonic
    }

    public func generateAddressFromPublicKey(publicKey: String) -> String {
        guard
            !publicKey.isEmpty,
            let data = Data(base64Encoded: publicKey)
        else {
            return ""
        }

        return AlgoSDK.AlgoSdkGenerateAddressFromPublicKey(data, nil)
    }

    public func generateAddressFromSK(secretKey: String) -> String {
        guard
            !secretKey.isEmpty,
            let data = Data(base64Encoded: secretKey)
        else {
            return ""
        }

        return AlgoSDK.AlgoSdkGenerateAddressFromSK(data, nil)
    }

    public func signAlgo25TransactionWithBase64(skBase64: String, encodedTxBase64: String) -> String {
        guard let skData = Data(base64Encoded: skBase64) else {
            NSLog("❌ Failed to decode secret key from Base64")
            return ""
        }

        guard let encodedTxData = Data(base64Encoded: encodedTxBase64) else {
            NSLog("❌ Failed to decode transaction from Base64")
            return ""
        }

        guard skData.count == 64 else {
            NSLog("❌ Secret key must be 64 bytes, received \(skData.count) bytes")
            return ""
        }

        guard !encodedTxData.isEmpty else {
            NSLog("❌ Transaction data is empty")
            return ""
        }

        do {
            // Step 1: Decode the unsigned transaction using AlgoKitTransact
            let transaction = try decodeTransaction(encodedTx: encodedTxData)
            
            // Step 2: Sign with CryptoKit (Ed25519)
            // Extract the 32-byte seed (first half of 64-byte key)
            let seedData = skData.prefix(32)
            let privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: seedData)
            
            // Add "TX" prefix to transaction bytes before signing
            let txPrefix = "TX".data(using: .utf8)!
            let dataToSign = txPrefix + encodedTxData
            
            let signature = try privateKey.signature(for: dataToSign)
            let signatureData = Data(signature)
            
            // Step 3: Create SignedTransaction with AlgoKitTransact
            let signedTransaction = SignedTransaction(
                transaction: transaction,
                signature: signatureData,
                authAddress: nil,
                multisignature: nil
            )
            
            // Step 4: Encode to MessagePack using AlgoKitTransact
            let encodedSignedTx = try encodeSignedTransaction(signedTransaction: signedTransaction)
            
            return encodedSignedTx.base64EncodedString()
        } catch {
            NSLog("❌ Algo25 transaction signing failed: \(error.localizedDescription)")
            return ""
        }
    }
    
    public func signAlgo25ArbitraryDataWithBase64(skBase64: String, dataBase64: String) -> String {
        guard let skData = Data(base64Encoded: skBase64) else {
            NSLog("Failed to decode secret key from Base64")
            return ""
        }

        guard let data = Data(base64Encoded: dataBase64) else {
            NSLog("Failed to decode data from Base64")
            return ""
        }

        guard skData.count == 64 else {
            NSLog("Secret key must be 64 bytes, received: \(skData.count) bytes")
            return ""
        }

        guard !data.isEmpty else {
            NSLog("Message data is empty")
            return ""
        }

        // Use Apple's native CryptoKit Ed25519 implementation (Curve25519.Signing)
        // Ed25519 secret key format: first 32 bytes = seed, last 32 bytes = public key
        // CryptoKit expects just the 32-byte seed
        let seedData = skData.prefix(32)
        
        do {
            let privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: seedData)
            let signature = try privateKey.signature(for: data)
            return Data(signature).base64EncodedString()
        } catch {
            NSLog("Algo25 arbitrary data signing failed: \(error.localizedDescription)")
            return ""
        }
    }

    public func getFalconAddressFromMnemonic(mnemonic: String) -> String {
        var error: NSError?
        let passphrase = ""
        guard let algorandKeyInfo = AlgoSdkDeriveFromMnemonic(mnemonic, passphrase, &error) else {
            if let error = error {
                print("Error deriving from BIP39: \(error)")
            }
            return ""
        }

        return algorandKeyInfo.algorandAddress
    }

    public func getFalconPublicKeyFromMnemonic(mnemonic: String) -> String {
        var error: NSError?
        let passphrase = ""
        guard let algorandKeyInfo = AlgoSdkDeriveFromMnemonic(mnemonic, passphrase, &error) else {
            if let error = error {
                print("Error deriving from AlgoSdkDeriveFromMnemonic: \(error)")
            }
            return ""
        }

        guard let publicKeyData = algorandKeyInfo.publicKey else {
            print("Public key data is nil")
            return ""
        }

        let base64Key = publicKeyData.base64EncodedString()
        return base64Key
    }

    public func getFalconPrivateKeyFromMnemonic(mnemonic: String) -> String {
        var error: NSError?
        let passphrase = ""
        guard let algorandKeyInfo = AlgoSdkDeriveFromMnemonic(mnemonic, passphrase, &error) else {
            if let error = error {
                print("Error deriving from AlgoSdkDeriveFromMnemonic: \(error)")
            }
            return ""
        }

        guard let privateKeyData = algorandKeyInfo.privateKey else {
            print("Private key data is nil")
            return ""
        }

        let base64Key = privateKeyData.base64EncodedString()
        return base64Key
    }

    public func signFalconTransaction(
        transactionBytes: Data,
        publicKeyBase64: String,
        privateKeyBase64: String
    ) -> Data? {
        guard let publicKeyData = Data(base64Encoded: publicKeyBase64),
              let privateKeyData = Data(base64Encoded: privateKeyBase64)
        else {
            print("Failed to decode base64 keys")
            return nil
        }

        // Create BytesArray and add the transaction
        let txnsArray = AlgoSDK.AlgoSdkBytesArray()
        txnsArray.append(transactionBytes)

        var error: NSError?
        // AlgoSdkSignFalconBundle returns a CSV of base64 signed transactions (non-optional)
        let csv = AlgoSDK.AlgoSdkSignFalconBundle(
            txnsArray,
            publicKeyData,
            privateKeyData,
            &error
        )

        if let error = error {
            print("Error signing Falcon bundle: \(error)")
            return nil
        }

        // Parse CSV and decode all base64 transactions, then concatenate
        let signedTxns = csv.components(separatedBy: ",")
        var outputData = Data()
        for encodedTxn in signedTxns {
            guard !encodedTxn.isEmpty,
                  let decodedData = Data(base64Encoded: encodedTxn)
            else {
                print("Failed to decode transaction from CSV: \(encodedTxn.prefix(20))...")
                continue
            }
            outputData.append(decodedData)
        }

        return outputData.isEmpty ? nil : outputData
    }
    
    public func signFalconArbitraryDataWithBase64(
        dataBase64: String,
        publicKeyBase64: String,
        privateKeyBase64: String
    ) -> String {
        guard let data = Data(base64Encoded: dataBase64) else {
            print("Error: Failed to decode data from Base64")
            return ""
        }
        
        guard let publicKeyData = Data(base64Encoded: publicKeyBase64) else {
            print("Error: Failed to decode public key from Base64")
            return ""
        }
        
        guard let privateKeyData = Data(base64Encoded: privateKeyBase64) else {
            print("Error: Failed to decode private key from Base64")
            return ""
        }
        
        guard !data.isEmpty else {
            print("Error signing data: Data is empty.")
            return ""
        }
        
        // Use AlgoSDK's RawSign function (same as Android's Sdk.rawSign)
        var error: NSError?
        guard let signature = AlgoSDK.AlgoSdkRawSign(
            data,
            publicKeyData,
            privateKeyData,
            &error
        ) else {
            if let error = error {
                print("Error signing Falcon data (SDK failed): \(error.localizedDescription)")
            } else {
                print("Failed to sign Falcon data (SDK failed): unknown error.")
            }
            return ""
        }
        
        return signature.base64EncodedString()
    }

    public func createOfflineKeyRegTransaction(
        senderAddress: String,
        noteBase64: String?,
        fee: UInt64,
        flatFee: Bool,
        firstRound: UInt64,
        lastRound: UInt64,
        genesisHashBase64: String,
        genesisID: String
    ) -> Data {

        guard let genesisHashData = Data(base64Encoded: genesisHashBase64) else {
            print("Error creating Offline KeyReg Tx: Failed to decode genesisHash.")
            return Data()
        }

        let params = AlgoSdkSuggestedParams()
        params.fee = Int64(truncatingIfNeeded: fee)
        params.flatFee = flatFee
        params.firstRoundValid = Int64(truncatingIfNeeded: firstRound)
        params.lastRoundValid = Int64(truncatingIfNeeded: lastRound)
        params.genesisHash = genesisHashData
        params.genesisID = genesisID

        let noteData = noteBase64.flatMap { Data(base64Encoded: $0) }
        var error: NSError?
        guard let encodedTx = AlgoSDK.AlgoSdkMakeKeyRegTxnWithStateProofKey(
            senderAddress,
            noteData,
            params,
            "",
            "",
            "",
            nil,
            nil,
            nil,
            false,
            &error
        ) else {
            if let error = error {
                print("Error creating Offline KeyReg Tx (SDK failed): \(error.localizedDescription)")
            } else {
                print("Failed to create Offline KeyReg Tx: unknown SDK error.")
            }
            return Data()
        }

        return encodedTx
    }

    public func createOnlineKeyRegTransaction(
        senderAddress: String,
        noteBase64: String?,
        fee: UInt64,
        flatFee: Bool,
        firstRound: UInt64,
        lastRound: UInt64,
        genesisHashBase64: String,
        genesisID: String,
        voteKeyBase64: String,
        selectionKeyBase64: String,
        stateProofKeyBase64: String,
        voteFirstRound: UInt64,
        voteLastRound: UInt64,
        voteKeyDilution: UInt64
    ) -> Data {

        func convertToStandardBase64(_ urlSafeBase64: String) -> String {
            var standard = urlSafeBase64
                .replacingOccurrences(of: "-", with: "+")
                .replacingOccurrences(of: "_", with: "/")

            let padding = (4 - (standard.count % 4)) % 4
            if padding > 0 {
                standard += String(repeating: "=", count: padding)
            }

            return standard
        }

        guard let genesisHashData = Data(base64Encoded: genesisHashBase64) else {
            print("Error creating Online KeyReg Tx: Failed to decode genesisHash.")
            return Data()
        }

        let voteKeyStandard = convertToStandardBase64(voteKeyBase64)
        let selectionKeyStandard = convertToStandardBase64(selectionKeyBase64)
        let stateProofKeyStandard = convertToStandardBase64(stateProofKeyBase64)

        let params = AlgoSdkSuggestedParams()
        params.fee = Int64(truncatingIfNeeded: fee)
        params.flatFee = flatFee
        params.firstRoundValid = Int64(truncatingIfNeeded: firstRound)
        params.lastRoundValid = Int64(truncatingIfNeeded: lastRound)
        params.genesisHash = genesisHashData
        params.genesisID = genesisID

        let noteData = noteBase64.flatMap { Data(base64Encoded: $0) }

        let voteFirstRoundWrapper = AlgoSdkUint64()
        voteFirstRoundWrapper.upper = Int64(voteFirstRound >> 32)
        voteFirstRoundWrapper.lower = Int64(voteFirstRound & 0xFFFFFFFF)

        let voteLastRoundWrapper = AlgoSdkUint64()
        voteLastRoundWrapper.upper = Int64(voteLastRound >> 32)
        voteLastRoundWrapper.lower = Int64(voteLastRound & 0xFFFFFFFF)

        let voteKeyDilutionWrapper = AlgoSdkUint64()
        voteKeyDilutionWrapper.upper = Int64(voteKeyDilution >> 32)
        voteKeyDilutionWrapper.lower = Int64(voteKeyDilution & 0xFFFFFFFF)

        var error: NSError?

        guard let encodedTx = AlgoSDK.AlgoSdkMakeKeyRegTxnWithStateProofKey(
            senderAddress,
            noteData,
            params,
            voteKeyStandard,
            selectionKeyStandard,
            stateProofKeyStandard,
            voteFirstRoundWrapper,
            voteLastRoundWrapper,
            voteKeyDilutionWrapper,
            false,
            &error
        ) else {
            if let error = error {
                print("Error creating Online KeyReg Tx (SDK failed): \(error.localizedDescription)")
            } else {
                print("Failed to create Online KeyReg Tx: unknown SDK error.")
            }
            return Data()
        }

        return encodedTx
    }

    public func makePaymentTxn(
        senderAddress: String,
        receiverAddress: String,
        amount: String,
        isMax: Bool,
        noteBase64: String?,
        fee: Int64,
        flatFee: Bool,
        firstRound: Int64,
        lastRound: Int64,
        genesisHashBase64: String,
        genesisID: String
    ) -> Data {
        guard let genesisHashData = Data(base64Encoded: genesisHashBase64) else {
            print("Error creating Payment Tx: Failed to decode genesisHash.")
            return Data()
        }

        let params = AlgoSdkSuggestedParams()
        params.fee = fee
        params.flatFee = flatFee
        params.firstRoundValid = firstRound
        params.lastRoundValid = lastRound
        params.genesisHash = genesisHashData
        params.genesisID = genesisID

        let noteData = noteBase64.flatMap {
            Data(base64Encoded: $0)
        }

        guard let amountValue = UInt64(amount) else {
            print("Error: Failed to convert amount to UInt64")
            return Data()
        }

        let amountWrapper = AlgoSdkUint64()
        amountWrapper.upper = Int64(amountValue >> 32)
        amountWrapper.lower = Int64(amountValue & 0xFFFFFFFF)

        var error: NSError?
        let closeRemainderTo = isMax ? receiverAddress : ""

        guard let encodedTx = AlgoSDK.AlgoSdkMakePaymentTxn(
            senderAddress,
            receiverAddress,
            amountWrapper,
            noteData,
            closeRemainderTo,
            params,
            &error
        )
        else {
            if let error = error {
                print("Error creating Payment Tx (SDK failed): \(error.localizedDescription)")
            } else {
                print("Failed to create Payment Tx: unknown SDK error.")
            }
            return Data()
        }

        return encodedTx
    }

    public func makeAssetTransferTxn(
        senderAddress: String,
        receiverAddress: String,
        amount: String,
        assetId: Int64,
        noteBase64: String?,
        fee: Int64,
        flatFee: Bool,
        firstRound: Int64,
        lastRound: Int64,
        genesisHashBase64: String,
        genesisID: String
    ) -> Data {
        guard let genesisHashData = Data(base64Encoded: genesisHashBase64) else {
            print("Error creating Asset Transfer Tx: Failed to decode genesisHash.")
            return Data()
        }

        let params = AlgoSdkSuggestedParams()
        params.fee = fee
        params.flatFee = flatFee
        params.firstRoundValid = firstRound
        params.lastRoundValid = lastRound
        params.genesisHash = genesisHashData
        params.genesisID = genesisID

        let noteData = noteBase64.flatMap {
            Data(base64Encoded: $0)
        }

        guard let amountValue = UInt64(amount) else {
            print("Error: Failed to convert amount to UInt64")
            return Data()
        }

        let amountWrapper = AlgoSdkUint64()
        amountWrapper.upper = Int64(amountValue >> 32)
        amountWrapper.lower = Int64(amountValue & 0xFFFFFFFF)

        var error: NSError?

        guard let encodedTx = AlgoSDK.AlgoSdkMakeAssetTransferTxn(
            senderAddress,
            receiverAddress,
            "", // closeRemainderTo
            amountWrapper,
            noteData,
            params,
            assetId,
            &error
        )
        else {
            if let error = error {
                print("Error creating Asset Transfer Tx (SDK failed): \(error.localizedDescription)")
            } else {
                print("Failed to create Asset Transfer Tx: unknown SDK error.")
            }
            return Data()
        }

        return encodedTx
    }

    public func makeAssetAcceptanceTxn(
        publicKey: String,
        assetId: Int64,
        fee: Int64,
        flatFee: Bool,
        firstRound: Int64,
        lastRound: Int64,
        genesisHashBase64: String,
        genesisID: String
    ) -> Data {
        guard let genesisHashData = Data(base64Encoded: genesisHashBase64) else {
            print("Error creating Asset Acceptance Tx: Failed to decode genesisHash.")
            return Data()
        }

        let params = AlgoSdkSuggestedParams()
        params.fee = fee
        params.flatFee = flatFee
        params.firstRoundValid = firstRound
        params.lastRoundValid = lastRound
        params.genesisHash = genesisHashData
        params.genesisID = genesisID

        var error: NSError?
        guard let encodedTx = AlgoSDK.AlgoSdkMakeAssetAcceptanceTxn(
            publicKey,
            nil, // note
            params,
            assetId,
            &error
        )
        else {
            if let error = error {
                print("Error creating Asset Acceptance Tx (SDK failed): \(error.localizedDescription)")
            } else {
                print("Failed to create Asset Acceptance Tx: unknown SDK error.")
            }
            return Data()
        }

        return encodedTx
    }

    // MARK: - Hashing (CommonCrypto)

    /// Computes SHA-256 over [dataBase64] and returns the digest as a base64-encoded string.
    public func sha256WithDataBase64(_ dataBase64: String) -> String {
        guard let data = Data(base64Encoded: dataBase64) else { return "" }
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes { ptr in _ = CC_SHA256(ptr.baseAddress, CC_LONG(data.count), &digest) }
        return Data(digest).base64EncodedString()
    }

    public func sha512256WithDataBase64(_ dataBase64: String) -> String {
        guard let data = Data(base64Encoded: dataBase64) else { return "" }
        let hash = sha512_256Raw(data)
        return hash.base64EncodedString()
    }

    // MARK: - SHA-512/256 helper (private)

    /// Returns the 32-byte SHA-512/256 digest for the given data.
    /// Uses CC_SHA512_CTX initialised with the SHA-512/256 IV values from NIST FIPS 180-4 §5.3.6.2,
    /// then runs the standard SHA-512 compression function (CC_SHA512_Update / CC_SHA512_Final).
    private func sha512_256Raw(_ data: Data) -> Data {
        var ctx = CC_SHA512_CTX()
        // Zero the count and working buffer
        ctx.count = (0, 0)
        ctx.wbuf  = (0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        // SHA-512/256 initialization values (big-endian word order, NIST FIPS 180-4 §5.3.6.2)
        ctx.hash.0 = 0x22312194FC2BF72C
        ctx.hash.1 = 0x9F555FA3C84C64C2
        ctx.hash.2 = 0x2393B86B6F53B151
        ctx.hash.3 = 0x963877195940EABD
        ctx.hash.4 = 0x96283EE2A88EFFE3
        ctx.hash.5 = 0xBE5E1E2553863992
        ctx.hash.6 = 0x2B0199FC2C85B8AA
        ctx.hash.7 = 0x0EB72DDC81C52CA2
        data.withUnsafeBytes { ptr in
            _ = CC_SHA512_Update(&ctx, ptr.baseAddress, CC_LONG(data.count))
        }
        var fullDigest = [UInt8](repeating: 0, count: Int(CC_SHA512_DIGEST_LENGTH))
        _ = CC_SHA512_Final(&fullDigest, &ctx)
        // SHA-512/256 output = first 256 bits (32 bytes) of the 512-bit SHA-512 output
        return Data(fullDigest.prefix(32))
    }

    // MARK: - Ed25519 Sign / Verify (CryptoKit)

    /// Signs [messageBase64] with the Ed25519 private key derived from [seedBase64] (raw 32-byte seed).
    /// Returns base64-encoded 64-byte signature, or empty string on error.
    public func signEd25519WithSeed(seedBase64: String, messageBase64: String) -> String {
        guard let seedData = Data(base64Encoded: seedBase64), !seedData.isEmpty else {
            NSLog("❌ signEd25519WithSeed: failed to decode seed")
            return ""
        }
        guard let messageData = Data(base64Encoded: messageBase64), !messageData.isEmpty else {
            NSLog("❌ signEd25519WithSeed: failed to decode message")
            return ""
        }
        do {
            let seed = seedData.count == 64 ? seedData.prefix(32) : seedData
            let key = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
            let sig = try key.signature(for: messageData)
            return Data(sig).base64EncodedString()
        } catch {
            NSLog("❌ signEd25519WithSeed error: \(error)")
            return ""
        }
    }

    /// Verifies an Ed25519 signature over [messageBase64] using [publicKeyBase64] (raw 32 bytes).
    public func verifyEd25519Signature(publicKeyBase64: String, messageBase64: String, signatureBase64: String) -> Bool {
        guard let pkData = Data(base64Encoded: publicKeyBase64),
              let msgData = Data(base64Encoded: messageBase64),
              let sigData = Data(base64Encoded: signatureBase64) else {
            return false
        }
        do {
            let pubKey = try Curve25519.Signing.PublicKey(rawRepresentation: pkData)
            return pubKey.isValidSignature(sigData, for: msgData)
        } catch {
            return false
        }
    }

    // MARK: - Algorand Transaction Builders

    public func buildAppCallTxn(
        senderAddress: String,
        appId: Int64,
        appArgsBase64: [String],
        boxRefAppIds: [Int64],
        boxRefNamesBase64: [String],
        foreignAssets: [Int64],
        fee: Int64,
        firstRound: Int64,
        lastRound: Int64,
        genesisHashBase64: String,
        genesisID: String
    ) -> Data {
        guard let genesisHashData = Data(base64Encoded: genesisHashBase64) else {
            NSLog("❌ buildAppCallTxn: bad genesisHash")
            return Data()
        }
        let params = AlgoSdkSuggestedParams()
        params.fee = fee
        params.flatFee = true
        params.firstRoundValid = firstRound
        params.lastRoundValid = lastRound
        params.genesisHash = genesisHashData
        params.genesisID = genesisID

        let argsArray = AlgoSdkBytesArray()
        for argBase64 in appArgsBase64 {
            if let d = Data(base64Encoded: argBase64) {
                argsArray.append(d)
            }
        }

        let boxRefs = AlgoSdkAppBoxRefArray()
        for i in 0..<min(boxRefAppIds.count, boxRefNamesBase64.count) {
            if let nameData = Data(base64Encoded: boxRefNamesBase64[i]) {
                let boxRefAppId: Int64 = boxRefAppIds[i]
                do {
                    try boxRefs.append(boxRefAppId, boxName: nameData)
                    NSLog("📦 buildAppCallTxn: boxRef[%d] appID=%lld name=%@", i, boxRefAppId, nameData.base64EncodedString())
                } catch {
                    NSLog("❌ buildAppCallTxn: boxRefs.append[%d] threw: %@", i, error.localizedDescription)
                }
            } else {
                NSLog("❌ buildAppCallTxn: boxRefNamesBase64[%d] failed to decode: %@", i, boxRefNamesBase64[i])
            }
        }

        let assetsArray = AlgoSdkInt64Array()
        for assetId in foreignAssets {
            assetsArray.append(assetId)
        }

        // The Go SDK calls .Length()/.Extract() on these without nil-guarding, even though
        // the ObjC header marks them _Nullable. Always pass empty arrays, never nil.
        let foreignAppsArray = AlgoSdkInt64Array()
        let accountsArray = AlgoSdkStringArray()

        var error: NSError?
        guard let rawTxnData = AlgoSdkMakeApplicationNoOpTx(
            appId, argsArray, accountsArray, foreignAppsArray, assetsArray, boxRefs, params, senderAddress, nil, &error
        ) else {
            NSLog("❌ buildAppCallTxn SDK error: \(error?.localizedDescription ?? "unknown")")
            return Data()
        }

        let correctNames: [Data] = (0..<min(boxRefAppIds.count, boxRefNamesBase64.count)).compactMap {
            Data(base64Encoded: boxRefNamesBase64[$0])
        }
        let txnData = Self.patchBoxRefNames(in: rawTxnData, correctNames: correctNames)

        NSLog("📦 buildAppCallTxn: UNSIGNED appCall encoding (after name patch):")
        Self.logBoxRefs(in: txnData)
        return txnData
    }

    private static func patchBoxRefNames(in data: Data, correctNames: [Data]) -> Data {
        let apbxMarker = Data([0xa4, 0x61, 0x70, 0x62, 0x78])
        guard let r = data.range(of: apbxMarker) else { return data }
        var bytes = [UInt8](data)
        var p = r.upperBound

        guard p < bytes.count else { return data }

        // Array header → element count
        let arrHdr = bytes[p]; p += 1
        var count = 0
        if arrHdr & 0xf0 == 0x90 {            // fixarray
            count = Int(arrHdr & 0x0f)
        } else if arrHdr == 0xdc {            // array16
            guard p + 1 < bytes.count else { return data }
            count = Int(bytes[p]) << 8 | Int(bytes[p + 1]); p += 2
        } else {
            return data
        }

        var refIndex = 0
        for _ in 0..<count {
            guard p < bytes.count else { break }
            let mapHdr = bytes[p]; p += 1
            guard mapHdr & 0xf0 == 0x80 else { break }
            let keyCount = Int(mapHdr & 0x0f)

            for _ in 0..<keyCount {
                guard p + 1 < bytes.count else { return Data(bytes) }
                let keyChar = bytes[p + 1]
                p += 2
                guard p < bytes.count else { return Data(bytes) }
                if keyChar == 0x6e {          // 'n' → box name (bin8/bin16)
                    let v = bytes[p]
                    var len = 0
                    if v == 0xc4 { len = Int(bytes[p + 1]); p += 2 }
                    else if v == 0xc5 { len = Int(bytes[p + 1]) << 8 | Int(bytes[p + 2]); p += 3 }
                    else { p += 1 }
                    if len > 0, p + len <= bytes.count, refIndex < correctNames.count {
                        let name = [UInt8](correctNames[refIndex])
                        if name.count == len {
                            for k in 0..<len { bytes[p + k] = name[k] }
                            NSLog("   🔧 patched boxRef[%d] name (%d bytes)", refIndex, len)
                        } else {
                            NSLog("   ⚠️  boxRef[%d] length mismatch: encoded=%d expected=%d — skipped",
                                  refIndex, len, name.count)
                        }
                        p += len
                    }
                } else {                       // 'i' (index) — uint; skip its value
                    let v = bytes[p]
                    if v <= 0x7f { p += 1 }
                    else if v == 0xcc { p += 2 }
                    else if v == 0xcd { p += 3 }
                    else if v == 0xce { p += 5 }
                    else if v == 0xcf { p += 9 }
                    else { p += 1 }
                }
            }
            refIndex += 1
        }
        return Data(bytes)
    }


    private func applicationAddress(appId: Int64) -> String {
        let prefix = "appID".data(using: .utf8)!
        var bigEndian = UInt64(bitPattern: appId).bigEndian
        let idBytes = withUnsafeBytes(of: &bigEndian) { Data($0) }
        let combined = prefix + idBytes
        let hashData = sha512_256Raw(combined)
        return AlgoSdkGenerateAddressFromPublicKey(hashData, nil)
    }

    /// Builds an unsigned Asset Transfer transaction (msgpack bytes) to an application address.
    public func buildAssetTransferToAppTxn(
        senderAddress: String,
        appId: Int64,
        assetId: Int64,
        amount: Int64,
        fee: Int64,
        firstRound: Int64,
        lastRound: Int64,
        genesisHashBase64: String,
        genesisID: String
    ) -> Data {
        guard let genesisHashData = Data(base64Encoded: genesisHashBase64) else {
            NSLog("❌ buildAssetTransferToAppTxn: bad genesisHash")
            return Data()
        }
        let appAddress = applicationAddress(appId: appId)
        let params = AlgoSdkSuggestedParams()
        params.fee = fee
        params.flatFee = true
        params.firstRoundValid = firstRound
        params.lastRoundValid = lastRound
        params.genesisHash = genesisHashData
        params.genesisID = genesisID

        let amountWrapper = AlgoSdkUint64()
        amountWrapper.upper = Int64(UInt64(bitPattern: amount) >> 32)
        amountWrapper.lower = Int64(UInt64(bitPattern: amount) & 0xFFFFFFFF)

        var error: NSError?
        guard let txnData = AlgoSdkMakeAssetTransferTxn(
            senderAddress, appAddress, "", amountWrapper, nil, params, assetId, &error
        ) else {
            NSLog("❌ buildAssetTransferToAppTxn SDK error: \(error?.localizedDescription ?? "unknown")")
            return Data()
        }
        return txnData
    }

    public func getAlwaysTrueAddress() -> String {
        let program = Data([0x02, 0x20, 0x01, 0x01, 0x22])
        return AlgoSdkAddressFromProgram(program)
    }

    public func buildPaymentTxn(
        senderAddress: String,
        receiverAddress: String,
        amountMicroAlgo: Int64,
        fee: Int64,
        firstRound: Int64,
        lastRound: Int64,
        genesisHashBase64: String,
        genesisID: String,
        noteBase64: String
    ) -> Data {
        guard let genesisHashData = Data(base64Encoded: genesisHashBase64) else {
            NSLog("❌ buildPaymentTxn: bad genesisHash")
            return Data()
        }
        let params = AlgoSdkSuggestedParams()
        params.fee = fee
        params.flatFee = true
        params.firstRoundValid = firstRound
        params.lastRoundValid = lastRound
        params.genesisHash = genesisHashData
        params.genesisID = genesisID

        let amtWrapper = AlgoSdkUint64()
        amtWrapper.upper = Int64(UInt64(max(0, amountMicroAlgo)) >> 32)
        amtWrapper.lower = Int64(UInt64(max(0, amountMicroAlgo)) & 0xFFFFFFFF)

        let noteData: Data? = noteBase64.isEmpty ? nil : Data(base64Encoded: noteBase64)

        var error: NSError?
        guard let txnData = AlgoSdkMakePaymentTxn(
            senderAddress, receiverAddress, amtWrapper, noteData, nil, params, &error
        ) else {
            NSLog("❌ buildPaymentTxn SDK error: \(error?.localizedDescription ?? "unknown")")
            return Data()
        }
        return txnData
    }

    public func signFalconGroupBundle(
        txnsBase64: [String],
        publicKeyBase64: String,
        privateKeyBase64: String
    ) -> [String] {
        guard let publicKeyData = Data(base64Encoded: publicKeyBase64),
              let privateKeyData = Data(base64Encoded: privateKeyBase64)
        else {
            NSLog("❌ signFalconGroupBundle: failed to decode keys")
            return []
        }

        let decodedTxns: [Data] = txnsBase64.compactMap { Data(base64Encoded: $0) }

        // ── DIAGNOSTIC: log the sender ("snd") of each input txn. ──
        NSLog("🧭 signFalconGroupBundle: %d input txns → AlgoSdkSignFalconBundle (SDK manages dummies)",
              decodedTxns.count)
        for (idx, txn) in decodedTxns.enumerated() {
            Self.logSender(in: txn, label: "txn[\(idx)]")
        }

        // Hand the real transactions to the SDK. With no group ID present, the SDK adds its own
        // minimal-LogicSig dummies, assigns the group ID, and signs everything as-is.
        let falconArray = AlgoSdkBytesArray()
        for txnData in decodedTxns {
            falconArray.append(txnData)
        }

        var falconErr: NSError?
        let falconCsv = AlgoSdkSignFalconBundle(falconArray, publicKeyData, privateKeyData, &falconErr)
        if let err = falconErr {
            NSLog("❌ signFalconGroupBundle Falcon signing error: %@", err.localizedDescription)
            return []
        }

        let allSigned = falconCsv.components(separatedBy: ",").filter { !$0.isEmpty }
        NSLog("✅ signFalconGroupBundle: %d signed txns (real + SDK dummies)", allSigned.count)

        // Diagnostic: verify box refs (apbx) survived signing.
        let apbxMarker = Data([0xa4, 0x61, 0x70, 0x62, 0x78])
        for (idx, b64) in allSigned.enumerated() {
            if let data = Data(base64Encoded: b64) {
                let hasBoxRefs = data.range(of: apbxMarker) != nil
                if hasBoxRefs {
                    NSLog("✅ signed txn[%d]: %d bytes — apbx PRESENT", idx, data.count)
                    Self.logBoxRefs(in: data)
                }
            }
        }

        return allSigned
    }

    private static func logSender(in data: Data, label: String) {
        // marker: "snd" key = 0xa3 0x73 0x6e 0x64, value = bin8(32) = 0xc4 0x20
        let sndMarker = Data([0xa3, 0x73, 0x6e, 0x64, 0xc4, 0x20])
        guard let r = data.range(of: sndMarker) else {
            NSLog("   🧭 %@: snd NOT FOUND", label)
            return
        }
        let start = r.upperBound
        guard start + 32 <= data.count else {
            NSLog("   🧭 %@: snd truncated", label)
            return
        }
        let pkBytes = data.subdata(in: start..<(start + 32))
        let address = AlgoSdkGenerateAddressFromPublicKey(pkBytes, nil)
        let shortAddr = address.count > 12 ? String(address.prefix(12)) + "…" : address
        NSLog("   🧭 %@: sender=%@", label, shortAddr)
    }

    private static func logBoxRefs(in data: Data) {
        // Find the "apbx" key marker: fixstr(4) 0xa4 'a' 'p' 'b' 'x'
        let apbxMarker = Data([0xa4, 0x61, 0x70, 0x62, 0x78])
        guard let r = data.range(of: apbxMarker) else { return }
        let bytes = [UInt8](data)
        var p = r.upperBound  // first byte AFTER "apbx" → the array header

        // RAW HEX DUMP: 80 bytes starting at the "apbx" marker, removes all parser ambiguity.
        let dumpStart = r.lowerBound
        let dumpEnd = min(dumpStart + 80, bytes.count)
        let rawHex = bytes[dumpStart..<dumpEnd].map { String(format: "%02x", $0) }.joined(separator: " ")
        NSLog("   🔬 apbx raw bytes: %@", rawHex)

        guard p < bytes.count else { return }

        // Parse array header → element count
        let arrHdr = bytes[p]; p += 1
        var count = 0
        if arrHdr & 0xf0 == 0x90 {            // fixarray
            count = Int(arrHdr & 0x0f)
        } else if arrHdr == 0xdc {            // array16
            guard p + 1 < bytes.count else { return }
            count = Int(bytes[p]) << 8 | Int(bytes[p + 1]); p += 2
        } else {
            NSLog("   🔎 apbx: unexpected array header 0x%02x", arrHdr)
            return
        }
        NSLog("   🔎 apbx: %d box ref(s)", count)

        for n in 0..<count {
            guard p < bytes.count else { return }
            let mapHdr = bytes[p]; p += 1
            guard mapHdr & 0xf0 == 0x80 else {
                NSLog("   🔎 boxRef[%d]: unexpected map header 0x%02x", n, mapHdr)
                return
            }
            let keyCount = Int(mapHdr & 0x0f)
            var foreignAppIdx: Int64 = 0      // default when "i" omitted
            var nameHex = "(none)"

            for _ in 0..<keyCount {
                guard p + 1 < bytes.count else { return }
                // key: fixstr(1) 0xa1 <char>
                let keyChar = bytes[p + 1]
                p += 2
                guard p < bytes.count else { return }
                if keyChar == 0x69 {          // 'i' → foreign app index (uint)
                    let v = bytes[p]
                    if v <= 0x7f {            // positive fixint
                        foreignAppIdx = Int64(v); p += 1
                    } else if v == 0xcc {     // uint8
                        foreignAppIdx = Int64(bytes[p + 1]); p += 2
                    } else if v == 0xcd {     // uint16
                        foreignAppIdx = Int64(bytes[p + 1]) << 8 | Int64(bytes[p + 2]); p += 3
                    } else if v == 0xce {     // uint32
                        foreignAppIdx = Int64(bytes[p + 1]) << 24 | Int64(bytes[p + 2]) << 16
                            | Int64(bytes[p + 3]) << 8 | Int64(bytes[p + 4]); p += 5
                    } else if v == 0xcf {     // uint64
                        var acc: Int64 = 0
                        for k in 1...8 { acc = acc << 8 | Int64(bytes[p + k]) }
                        foreignAppIdx = acc; p += 9
                    } else {
                        p += 1
                    }
                } else if keyChar == 0x6e {   // 'n' → box name (bin8/bin16)
                    let v = bytes[p]
                    var len = 0
                    if v == 0xc4 { len = Int(bytes[p + 1]); p += 2 }
                    else if v == 0xc5 { len = Int(bytes[p + 1]) << 8 | Int(bytes[p + 2]); p += 3 }
                    else { p += 1 }
                    if len > 0, p + len <= bytes.count {
                        let nameBytes = Data(bytes[p..<(p + len)])
                        nameHex = nameBytes.map { String(format: "%02x", $0) }.joined()
                        if nameHex.count > 16 { nameHex = String(nameHex.prefix(16)) + "…" }
                        p += len
                    }
                } else {
                    p += 1
                }
            }
            NSLog("   🔎 boxRef[%d]: i=%lld name=0x%@ (i %@)",
                  n, foreignAppIdx, nameHex,
                  keyCount == 1 ? "OMITTED→0 ✅" : "PRESENT")
        }
    }

    private func makeAlwaysTrueSignedTxn(unsignedTxnData: Data) -> Data {
        // TEAL v2: version(0x02) intcblock([1]) intc_0 — evaluates to 1 (approve)
        let program: [UInt8] = [0x02, 0x20, 0x01, 0x01, 0x22]

        var result = Data()

        // Canonical msgpack signed transaction: fixmap(2) with keys "lsig" and "txn"
        // (alphabetical order: 'l' < 't')
        result.append(0x82)  // fixmap(2)

        // Key "lsig" — fixstr(4): 0xa4
        result.append(contentsOf: [0xa4, 0x6c, 0x73, 0x69, 0x67])  // "lsig"
        // Value: lsig map = fixmap(1) { "l": bin8(5) program }
        result.append(0x81)  // fixmap(1)
        result.append(contentsOf: [0xa1, 0x6c])  // key "l" — fixstr(1)
        result.append(0xc4)  // bin8
        result.append(UInt8(program.count))  // 5
        result.append(contentsOf: program)  // [0x02, 0x20, 0x01, 0x01, 0x22]

        // Key "txn" — fixstr(3): 0xa3
        result.append(contentsOf: [0xa3, 0x74, 0x78, 0x6e])  // "txn"
        // Value: the raw unsigned transaction msgpack (already a canonical map with grp field)
        result.append(contentsOf: unsignedTxnData)

        return result
    }

    /// Assigns group IDs to a list of transactions (each as base64 msgpack).
    /// Returns the same transactions with group fields set, as base64 strings.
    public func assignGroupIds(txnsBase64: [String]) -> [String] {
        let txnsArray = AlgoSdkBytesArray()
        for b64 in txnsBase64 {
            if let d = Data(base64Encoded: b64) {
                txnsArray.append(d)
            }
        }
        var error: NSError?
        guard let grouped = AlgoSdkAssignGroupID(txnsArray, &error) else {
            NSLog("❌ assignGroupIds error: \(error?.localizedDescription ?? "unknown")")
            return txnsBase64
        }
        var result = [String]()
        for i in 0..<grouped.length() {
            if let d = grouped.get(i) {
                result.append(d.base64EncodedString())
            }
        }
        return result
    }

    public func attachSignatureToTxn(signatureBase64: String, txnBase64: String) -> String {
        guard let sigData = Data(base64Encoded: signatureBase64),
              let txnData = Data(base64Encoded: txnBase64) else {
            NSLog("❌ attachSignatureToTxn: decode failed")
            return ""
        }
        var error: NSError?
        guard let signed = AlgoSdkAttachSignature(sigData, txnData, &error) else {
            NSLog("❌ attachSignatureToTxn SDK error: \(error?.localizedDescription ?? "unknown")")
            return ""
        }
        return signed.base64EncodedString()
    }

    // MARK: - Synchronous Algod REST Helpers

    private func syncRequest(_ request: URLRequest) -> (Data?, Int) {
        var resultData: Data?
        var statusCode: Int = 0
        let semaphore = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { data, response, _ in
            resultData = data
            if let http = response as? HTTPURLResponse {
                statusCode = http.statusCode
            }
            semaphore.signal()
        }.resume()
        semaphore.wait()
        return (resultData, statusCode)
    }

    public func syncGetAlgodBox(algodUrl: String, appId: Int64, boxNameBase64: String) -> String {
        // boxNameBase64 is STANDARD base64 (may contain '+', '/', '='). These are reserved in a
        // URL query string ('+' decodes to space), so percent-encode them before building the URL.
        let allowed = CharacterSet.alphanumerics  // encodes +, /, = and everything else
        let encodedName = boxNameBase64.addingPercentEncoding(withAllowedCharacters: allowed) ?? boxNameBase64
        let base = algodUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let urlStr = "\(base)/v2/applications/\(appId)/box?name=b64:\(encodedName)"
        guard let url = URL(string: urlStr) else { return "" }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        let (data, status) = syncRequest(request)
        guard status == 200, let data = data else {
            if let data = data, let msg = String(data: data, encoding: .utf8), !msg.isEmpty {
                NSLog("❌ syncGetAlgodBox status=\(status) appId=\(appId) body=\(msg.prefix(300))")
            } else {
                NSLog("❌ syncGetAlgodBox status=\(status) appId=\(appId) (no body)")
            }
            return ""
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    public func syncGetTxParams(algodUrl: String) -> String {
        let urlStr = "\(algodUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/v2/transactions/params"
        guard let url = URL(string: urlStr) else { return "" }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        let (data, status) = syncRequest(request)
        guard status == 200, let data = data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    public func syncBroadcastTxns(algodUrl: String, signedTxnsBase64: String) -> String {
        let urlStr = "\(algodUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/v2/transactions"
        guard let url = URL(string: urlStr),
              let txnData = Data(base64Encoded: signedTxnsBase64) else { return "" }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-binary", forHTTPHeaderField: "Content-Type")
        request.httpBody = txnData
        let (data, status) = syncRequest(request)
        guard (status == 200 || status == 201), let data = data else {
            if let data = data, let msg = String(data: data, encoding: .utf8) {
                NSLog("❌ broadcast failed status=\(status) body=\(msg.prefix(400))")
                // Return error body prefixed so callers can surface the real Algorand error.
                return "BROADCAST_ERROR:\(msg)"
            }
            return ""
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    public func syncGetPendingTxn(algodUrl: String, txId: String) -> String {
        let urlStr = "\(algodUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/v2/transactions/pending/\(txId)"
        guard let url = URL(string: urlStr) else { return "" }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        let (data, _) = syncRequest(request)
        guard let data = data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    // MARK: - MPP Charge Helpers

    public func setTxnLease(txnBase64: String, leaseBase64: String) -> String {
        guard let txnData = Data(base64Encoded: txnBase64),
              let leaseData = Data(base64Encoded: leaseBase64) else {
            NSLog("❌ setTxnLease: decode failed")
            return ""
        }
        do {
            var txn = try decodeTransaction(encodedTx: txnData)
            txn.lease = leaseData
            let encoded = try encodeTransaction(transaction: txn)
            return encoded.base64EncodedString()
        } catch {
            NSLog("❌ setTxnLease error: \(error.localizedDescription)")
            return ""
        }
    }

    public func decodeChargeTxnJson(txnBase64: String, allowUnsigned: Bool) -> String {
        guard let bytes = Data(base64Encoded: txnBase64) else { return "" }

        var isSigned = false
        let txn: Transaction
        do {
            let signed = try decodeSignedTransaction(encodedSignedTransaction: bytes)
            txn = signed.transaction
            isSigned = true
        } catch {
            if !allowUnsigned { return "" }
            do {
                txn = try decodeTransaction(encodedTx: bytes)
            } catch {
                return ""
            }
        }

        var dict: [String: Any] = [:]
        switch txn.transactionType {
        case .payment: dict["type"] = "pay"
        case .assetTransfer: dict["type"] = "axfer"
        default: dict["type"] = "other"
        }
        dict["sender"] = txn.sender
        dict["signed"] = isSigned
        if let lease = txn.lease { dict["leaseB64"] = lease.base64EncodedString() }
        if let group = txn.group { dict["groupB64"] = group.base64EncodedString() }
        dict["hasRekeyTo"] = (txn.rekeyTo?.isEmpty == false)
        if let p = txn.payment {
            dict["receiver"] = p.receiver
            dict["amount"] = String(p.amount)
            dict["hasCloseRemainderTo"] = (p.closeRemainderTo?.isEmpty == false)
        }
        if let a = txn.assetTransfer {
            dict["assetReceiver"] = a.receiver
            dict["assetAmount"] = String(a.amount)
            dict["xferAsset"] = String(a.assetId)
            dict["hasAssetCloseTo"] = (a.closeRemainderTo?.isEmpty == false)
        }
        if let txId = try? getTransactionId(transaction: txn) {
            dict["txId"] = txId
        }

        guard let jsonData = try? JSONSerialization.data(withJSONObject: dict),
              let jsonStr = String(data: jsonData, encoding: .utf8) else { return "" }
        return jsonStr
    }
}