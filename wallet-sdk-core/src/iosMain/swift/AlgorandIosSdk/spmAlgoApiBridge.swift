import Foundation
import x_hd_wallet_api
import MnemonicSwift
import AlgoSDK

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


            let extendedPrivateKey = try wallet.deriveKey(
                rootKey: wallet.fromSeed(seedData),
                bip44Path: bip44Path,
                isPrivate: true,
                derivationType: BIP32DerivationType.Peikert
            )

            return extendedPrivateKey.base64EncodedString()

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

    public func getAlgo25SecretKey(mnemonic: String?) -> String {
        if mnemonic != nil {
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
            print("Error: Failed to decode secret key from Base64")
            return ""
        }

        guard let encodedTxData = Data(base64Encoded: encodedTxBase64) else {
            print("Error: Failed to decode transaction from Base64")
            return ""
        }

        guard skData.count == 64 else {
            print("Error signing transaction: Secret key (sk) must be 64 bytes long, but received \(skData.count) bytes.")
            return ""
        }

        guard !encodedTxData.isEmpty else {
            print("Error signing transaction: Unsigned transaction data (encodedTx) is empty.")
            return ""
        }

        let result = skData.withUnsafeBytes { (skPtr: UnsafeRawBufferPointer) -> String in
            let sk = Data(bytes: skPtr.baseAddress!, count: skData.count)

            return encodedTxData.withUnsafeBytes { (txPtr: UnsafeRawBufferPointer) -> String in
                let encodedTx = Data(bytes: txPtr.baseAddress!, count: encodedTxData.count)

                var error: NSError?
                guard let signedTxn = AlgoSDK.AlgoSdkSignTransaction(sk, encodedTx, &error) else {
                    if let error = error {
                        print("Error signing transaction (SDK failed): \(error.localizedDescription)")
                    } else {
                        print("Failed to sign transaction (SDK failed): unknown error.")
                    }
                    return ""
                }

                return signedTxn.base64EncodedString()
            }
        }

        return result
    }

    public func getFalconAddressFromMnemonic(passphrase: String) -> String {
        var error: NSError?
        guard let algorandKeyInfo = AlgoSdkDeriveFromBIP39(passphrase, &error) else {
            if let error = error {
                print("Error deriving from BIP39: \(error)")
            } else {
                print("Failed to derive from BIP39 - no key info returned")
            }
            return ""
        }

        return algorandKeyInfo.algorandAddress
    }

    public func getFalconPublicKeyFromMnemonic(passphrase: String) -> String {
        var error: NSError?
        guard let algorandKeyInfo = AlgoSdkDeriveFromBIP39(passphrase, &error) else {
            if let error = error {
                print("Error deriving from BIP39: \(error)")
            }
            return ""
        }

        guard let publicKeyData = algorandKeyInfo.publicKey else {
            print("Public key data is nil")
            return ""
        }
        return publicKeyData.base64EncodedString()
    }

    public func getFalconPrivateKeyFromMnemonic(passphrase: String) -> String {
        var error: NSError?
        guard let algorandKeyInfo = AlgoSdkDeriveFromBIP39(passphrase, &error) else {
            if let error = error {
                print("Error deriving from BIP39: \(error)")
            }
            return ""
        }

        guard let privateKeyData = algorandKeyInfo.privateKey else {
            print("Private key data is nil")
            return ""
        }
        return privateKeyData.base64EncodedString()
    }

    public func signFalconTransaction(
        transactionBytes: Data,
        publicKeyBase64: String,
        privateKeyBase64: String
    ) -> Data? {
        guard let publicKeyData = Data(base64Encoded: publicKeyBase64),
              let privateKeyData = Data(base64Encoded: privateKeyBase64) else {
            print("Failed to decode base64 keys")
            return nil
        }

        var error: NSError?
        guard let signedBytes = AlgoSDK.AlgoSdkSignFalconTransaction(
            transactionBytes,
            publicKeyData,
            privateKeyData,
            &error
        ) else {
            if let error = error {
                print("Error signing Falcon transaction: \(error)")
            }
            return nil
        }

        return signedBytes
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
}