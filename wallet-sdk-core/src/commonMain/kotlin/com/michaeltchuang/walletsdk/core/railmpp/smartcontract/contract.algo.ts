import {
  Contract,
  Account,
  Asset,
  uint64,
  bytes,
  BoxMap,
  Txn,
  assert,
  itxn,
  op,
  Bytes,
  clone,
  TemplateVar,
  ensureBudget,
  OpUpFeeSource,
  gtxn,
} from '@algorandfoundation/algorand-typescript'
import { falconVerify, sha512_256 } from '@algorandfoundation/algorand-typescript/op'

/**
 * Compile-time network-specific USDC ASA id.
 * Set via environment variable: TMPL_USDC_ASSET_ID
 */
const USDC_ASSET_ID = TemplateVar<uint64>('USDC_ASSET_ID')
const CLOSE_GRACE_PERIOD_SECONDS: uint64 = 888 // ~15 minutes

/**
 * ChannelInfo: source of truth for a single payment channel.
 * authorizedSigner stores signer pubkey hash (sha512_256(pubkey)).
 */
export interface ChannelInfo {
  payer: Account
  payee: Account
  authorizedSigner: bytes
  totalDeposit: uint64
  lastSettled: uint64
  latestVoucherAmount: uint64
  startRound: uint64
  startTimestamp: uint64
  closeRequestedAt: uint64
}

export class EscrowSessionVaultManager extends Contract {
  /**
   * BoxMap for channel data, keyed by channelId bytes.
   */
  channels = BoxMap<bytes, ChannelInfo>({ keyPrefix: '' })

  /**
   * Full authorized signer public key storage, keyed by channelId.
   */
  authorizedSignerPublicKey = BoxMap<bytes, bytes>({ keyPrefix: 'p' })

  /**
   * Opens a channel with initial USDC deposit and returns derived channelId.
   * Caller becomes payer.
   * authorizedSigner is signer pubkey hash (32 bytes) computed client-side.
   * authorizedSignerPublicKey is optional: if provided, stores full signer pubkey in box.
   */
  open(
    payee: Account,
    deposit: gtxn.AssetTransferTxn,
    salt: bytes,
    authorizedSigner: bytes,
    authorizedSignerPublicKey: bytes,

  ): bytes {
    assert(authorizedSigner.length === 32, 'Signer hash must be 32 bytes')

    const channelId = this.deriveChannelId(Txn.sender, payee, authorizedSigner, salt)
    const channel = this.getChannel(channelId)

    if (!channel.exists) {
      const data: ChannelInfo = {
        payer: Txn.sender,
        payee,
        authorizedSigner,
        totalDeposit: 0,
        lastSettled: 0,
        latestVoucherAmount: 0,
        startRound: op.Global.round,
        startTimestamp: op.Global.latestTimestamp,
        closeRequestedAt: 0,
      }
      this.setAuthorizedSignerPublicKeyIfProvided(channelId, authorizedSignerPublicKey, authorizedSigner)
      this.applyTopUp(data, deposit)
      channel.value = clone(data)
      return channelId
    }

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can reopen channel')
    assert(payee === data.payee, 'Payee mismatch')
    assert(authorizedSigner === data.authorizedSigner, 'Authorized signer hash mismatch')

    this.setAuthorizedSignerPublicKeyIfProvided(channelId, authorizedSignerPublicKey, data.authorizedSigner)
    this.applyTopUp(data, deposit)
    channel.value = clone(data)

    return channelId
  }

  /**
   * Adds funds to an existing channel using a grouped USDC asset transfer.
   */
  topUp(channelId: bytes, cumulativeAmount: gtxn.AssetTransferTxn): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can top up')

    this.applyTopUp(data, cumulativeAmount)
    channel.value = clone(data)
  }

  /**
   * Set full authorized signer public key and update channel.authorizedSigner hash.
   */
  setAuthorizedSignerPublicKey(channelId: bytes, authorizedSignerPublicKey: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can set authorized signer')
    assert(authorizedSignerPublicKey.length > 0, 'Authorized signer pubkey required')

    const authorizedSignerHash = sha512_256(authorizedSignerPublicKey)
    this.setAuthorizedSignerPublicKeyIfProvided(channelId, authorizedSignerPublicKey, authorizedSignerHash)

    data.authorizedSigner = authorizedSignerHash
    channel.value = clone(data)
  }

  /**
   * Stores latest cumulative voucher amount on-chain.
   */
  updateVoucher(channelId: bytes, cumulativeAmount: uint64, signature: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)

    assert(Txn.sender === data.payer, 'Only payer can update voucher')
    assert(cumulativeAmount >= data.lastSettled, 'Voucher below settled amount')
    assert(cumulativeAmount > data.latestVoucherAmount, 'Voucher not increasing')
    assert(cumulativeAmount <= data.totalDeposit, 'Voucher exceeds deposit')

    this.verifySettleSignature(channelId, cumulativeAmount, signature)

    data.latestVoucherAmount = cumulativeAmount
    channel.value = clone(data)
  }

  /**
   * Payee settles signed voucher funds, with support for partial settlement.
   * Also advances latestVoucherAmount when the submitted signed voucher is newer.
   */
  settle(channelId: bytes, cumulativeAmount: uint64, signature: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)

    assert(Txn.sender === data.payee, 'Only payee can settle')
    assert(cumulativeAmount > data.lastSettled, 'Nothing new to settle')
    assert(cumulativeAmount <= data.totalDeposit, 'Voucher exceeds deposit')

    this.verifySettleSignature(channelId, cumulativeAmount, signature)

    const payout: uint64 = cumulativeAmount - data.lastSettled

    itxn.assetTransfer({
      xferAsset: Asset(USDC_ASSET_ID),
      assetReceiver: data.payee,
      assetAmount: payout,
    }).submit()

    data.lastSettled = cumulativeAmount
    if (cumulativeAmount > data.latestVoucherAmount) {
      data.latestVoucherAmount = cumulativeAmount
    }
    channel.value = clone(data)
  }

  /**
   * Helper for payee: settle all currently unclaimed voucher amount.
   */
  settleLatest(channelId: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)

    assert(Txn.sender === data.payee, 'Only payee can settle')
    assert(data.latestVoucherAmount > data.lastSettled, 'Nothing new to settle')

    const payout: uint64 = data.latestVoucherAmount - data.lastSettled

    itxn.assetTransfer({
      xferAsset: Asset(USDC_ASSET_ID),
      assetReceiver: data.payee,
      assetAmount: payout,
    }).submit()

    data.lastSettled = data.latestVoucherAmount
    channel.value = clone(data)
  }

  /**
   * Payee closes channel.
   * Honors the latest on-chain voucher before refunding the payer.
   */
  close(channelId: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)

    assert(Txn.sender === data.payee, 'Only payee can close')

    this.finalizeChannel(channelId, data)
  }

  /**
   * Payer requests channel closure, starting forced-close grace period.
   */
  requestClose(channelId: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can request close')

    data.closeRequestedAt = op.Global.latestTimestamp
    channel.value = clone(data)
  }

  /**
   * Payer withdraws remaining funds after grace period expires.
   * Honors the latest on-chain voucher before refunding the payer.
   */
  withdraw(channelId: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can withdraw')
    assert(data.closeRequestedAt > 0, 'Close not requested')
    assert(
      op.Global.latestTimestamp >= data.closeRequestedAt + CLOSE_GRACE_PERIOD_SECONDS,
      'Close grace period not elapsed',
    )

    this.finalizeChannel(channelId, data)
  }

  /**
   * Funds MBR/fees pool using ALGO.
   */
  fundMbrPool(payment: { receiver: Account }): void {
    assert(payment.receiver === op.Global.currentApplicationAddress, 'Payment must be to contract')
  }

  /**
   * Opt app account into configured USDC ASA so it can receive deposits.
   * Should be called once by admin/creator.
   */
  optInUsdc(): void {
    assert(Txn.sender === op.Global.creatorAddress, 'Only creator can opt in USDC')

    itxn.assetTransfer({
      xferAsset: Asset(USDC_ASSET_ID),
      assetReceiver: op.Global.currentApplicationAddress,
      assetAmount: 0,
    }).submit()
  }

  /**
   * Returns latest session static data tuple:
   * [startRound, startTimestamp]
   */
  getSessionStaticData(channelId: bytes): [uint64, uint64] {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    return [data.startRound, data.startTimestamp]
  }

  /**
   * Returns latest session dynamic data tuple:
   * [totalDeposit, lastSettled, latestVoucherAmount]
   */
  getSessionDynamicData(channelId: bytes): [uint64, uint64, uint64] {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    return [data.totalDeposit, data.lastSettled, data.latestVoucherAmount]
  }

  /**
   * Backwards-compatible alias for deterministic channelId derivation.
   * authorizedSigner must be signer pubkey hash (32 bytes).
   */
  computeChannelId(payer: Account, payee: Account, authorizedSigner: bytes, salt: bytes): bytes {
    return this.deriveChannelId(payer, payee, authorizedSigner, salt)
  }

  /**
   * Read-only helper for clients: exact bytes signed for settle/updateVoucher.
   */
  settleMessage(channelId: bytes, cumulativeAmount: uint64): bytes {
    return this.getSettleMessage(channelId, cumulativeAmount)
  }

  /**
   * Read-only helper for clients: verifies settle authorization exactly as settle/updateVoucher do.
   * Uses full authorized signer public key stored in a box for the channel.
   */
  verifySettleSignature(channelId: bytes, cumulativeAmount: uint64, signature: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    const message = this.getSettleMessage(channelId, cumulativeAmount)

    const authorizedSignerPublicKey = this.authorizedSignerPublicKey(channelId)
    assert(authorizedSignerPublicKey.exists, 'Authorized signer public key not set yet')

    const authorizedSigner = authorizedSignerPublicKey.value

    ensureBudget(5000, OpUpFeeSource.AppAccount)
    assert(sha512_256(authorizedSigner) === data.authorizedSigner, 'Invalid signer pubkey')

    if (signature.length > 64) {
      falconVerify(message, signature, authorizedSigner)
      return
    }

    assert(signature.length === 64, 'Invalid Ed25519 signature length')
    const signatureIsValid = op.ed25519verifyBare(message, signature, authorizedSigner)
    assert(signatureIsValid, 'Invalid signature')
  }

  /**
   * Read-only helper for clients: deterministic channelId derivation.
   * authorizedSigner must be signer pubkey hash (32 bytes).
   */
  deriveChannelId(payer: Account, payee: Account, authorizedSigner: bytes, salt: bytes): bytes {
    // Algorand channel-id derivation:
    // sha256(payer || payee || assetId || salt || authorizedSignerHash)
    return op.sha256(payer.bytes.concat(payee.bytes).concat(op.itob(USDC_ASSET_ID)).concat(salt).concat(authorizedSigner))
  }

  // Helper functions

  private getChannel(channelId: bytes) {
    return this.channels(channelId)
  }

  private applyTopUp(data: ChannelInfo, cumulativeAmount: gtxn.AssetTransferTxn): void {
    assert(cumulativeAmount.sender === Txn.sender, 'Payment sender mismatch')
    assert(cumulativeAmount.assetReceiver === op.Global.currentApplicationAddress, 'Payment must be to contract')
    assert(cumulativeAmount.xferAsset.id === USDC_ASSET_ID, 'Payment asset must be USDC')
    assert(cumulativeAmount.assetAmount > 0, 'Deposit must be > 0')
    assert(cumulativeAmount.assetSender === Account(), 'Clawback transfer not allowed')
    assert(cumulativeAmount.assetCloseTo === Account(), 'Asset close not allowed')

    data.totalDeposit += cumulativeAmount.assetAmount
    // Per spec: top-up cancels pending close request.
    data.closeRequestedAt = 0
  }

  private finalizeChannel(channelId: bytes, data: ChannelInfo): void {
    const payeePayout: uint64 = data.latestVoucherAmount - data.lastSettled
    if (payeePayout > 0) {
      itxn.assetTransfer({
        xferAsset: Asset(USDC_ASSET_ID),
        assetReceiver: data.payee,
        assetAmount: payeePayout,
      }).submit()
    }

    const payerRefund: uint64 = data.totalDeposit - data.latestVoucherAmount
    if (payerRefund > 0) {
      itxn.assetTransfer({
        xferAsset: Asset(USDC_ASSET_ID),
        assetReceiver: data.payer,
        assetAmount: payerRefund,
      }).submit()
    }

    this.channels(channelId).delete()
  }

  private setAuthorizedSignerPublicKeyIfProvided(
    channelId: bytes,
    authorizedSignerPublicKey: bytes,
    expectedAuthorizedSignerHash: bytes,
  ): void {
    if (authorizedSignerPublicKey.length > 0) {
      assert(sha512_256(authorizedSignerPublicKey) === expectedAuthorizedSignerHash, 'Authorized signer hash mismatch')
      const authorizedSignerKey = this.authorizedSignerPublicKey(channelId)
      authorizedSignerKey.value = authorizedSignerPublicKey
    }
  }

  private getSettleMessage(channelId: bytes, cumulativeAmount: uint64): bytes {
    return op
      .itob(op.Global.currentApplicationId.id)
      .concat(channelId)
      .concat(op.itob(cumulativeAmount))
      .concat(Bytes('settle'))
  }
}
