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
} from '@algorandfoundation/algorand-typescript'

/**
 * Compile-time network-specific USDC ASA id.
 * Set via environment variable: TMPL_USDC_ASSET_ID
 */
const USDC_ASSET_ID = TemplateVar<uint64>('USDC_ASSET_ID')
const CLOSE_GRACE_PERIOD_SECONDS: uint64 = 900
const SIGNER_TYPE_ED25519: uint64 = 0
const SIGNER_TYPE_FALCON_TXN_AUTH: uint64 = 1

/**
 * ChannelInfo: source of truth for a single payment channel.
 */
export interface ChannelInfo {
  payer: Account
  payee: Account
  authorizedSigner: Account
  signerType: uint64
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
   * Opens a channel with initial deposit and returns derived channelId.
   * Caller becomes payer.
   * signerType: 0=Ed25519 voucher signer, 1=Falcon txn-auth mode (no voucher signature verification)
   */
  open(payee: Account, deposit: uint64, salt: bytes, authorizedSigner: Account, signerType: uint64): bytes {
    assert(deposit > 0, 'Deposit must be > 0')
    assert(signerType <= SIGNER_TYPE_FALCON_TXN_AUTH, 'Unsupported signer type')

    const channelId = this.deriveChannelId(Txn.sender, payee, authorizedSigner, salt)
    const channel = this.getChannel(channelId)

    if (!channel.exists) {
      channel.value = {
        payer: Txn.sender,
        payee,
        authorizedSigner,
        signerType,
        totalDeposit: deposit,
        lastSettled: 0,
        latestVoucherAmount: 0,
        startRound: op.Global.round,
        startTimestamp: op.Global.latestTimestamp,
        closeRequestedAt: 0,
      }
      return channelId
    }

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can reopen channel')
    assert(payee === data.payee, 'Payee mismatch')
    assert(authorizedSigner === data.authorizedSigner, 'Authorized signer mismatch')
    assert(signerType === data.signerType, 'Signer type mismatch')

    data.totalDeposit += deposit
    data.closeRequestedAt = 0
    channel.value = clone(data)

    return channelId
  }

  /**
   * Adds funds to an existing channel.
   */
  topUp(channelId: bytes, additionalDeposit: uint64): void {
    assert(additionalDeposit > 0, 'Deposit must be > 0')

    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can top up')

    data.totalDeposit += additionalDeposit
    // Per spec: top-up cancels pending close request.
    data.closeRequestedAt = 0
    channel.value = clone(data)
  }

  /**
   * Stores latest cumulative voucher amount on-chain.
   * In Ed25519 mode, requires valid voucher signature.
   * In Falcon txn-auth mode, requires payer sender auth.
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
   * Payee settles voucher funds, with support for partial settlement.
   */
  settle(channelId: bytes, cumulativeAmount: uint64, signature: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)

    assert(Txn.sender === data.payee, 'Only payee can settle')
    assert(cumulativeAmount > data.lastSettled, 'Nothing new to settle')
    assert(cumulativeAmount <= data.latestVoucherAmount, 'Settle exceeds latest voucher')

    this.verifySettleSignature(channelId, cumulativeAmount, signature)

    const payout: uint64 = cumulativeAmount - data.lastSettled

    itxn.assetTransfer({
      xferAsset: Asset(USDC_ASSET_ID),
      assetReceiver: data.payee,
      assetAmount: payout,
    }).submit()

    data.lastSettled = cumulativeAmount
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

  // /**
  //  * Legacy one-step settle path (deprecated): verify+settle in one call.
  //  */
  // settleWithVoucher(channelId: bytes, cumulativeAmount: uint64, signature: bytes): void {
  //   const channel = this.getChannel(channelId)
  //   assert(channel.exists, 'Channel does not exist')
  //
  //   const data = clone(channel.value)
  //
  //   assert(Txn.sender === data.payee, 'Only payee can settle')
  //   assert(cumulativeAmount > data.lastSettled, 'Nothing new to settle')
  //   assert(cumulativeAmount <= data.totalDeposit, 'Settle exceeds deposit')
  //
  //   this.verifySettleSignature(channelId, cumulativeAmount, signature)
  //
  //   const payout: uint64 = cumulativeAmount - data.lastSettled
  //
  //   itxn.assetTransfer({
  //     xferAsset: Asset(USDC_ASSET_ID),
  //     assetReceiver: data.payee,
  //     assetAmount: payout,
  //   }).submit()
  //
  //   data.lastSettled = cumulativeAmount
  //   if (data.latestVoucherAmount < cumulativeAmount) {
  //     data.latestVoucherAmount = cumulativeAmount
  //   }
  //   channel.value = clone(data)
  // }

  /**
   * Payee closes channel after all voucher obligations are settled.
   * Refunds remainder to payer.
   */
  close(channelId: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)

    assert(Txn.sender === data.payee, 'Only payee can close')
    assert(data.latestVoucherAmount === data.lastSettled, 'Unclaimed voucher funds remain')

    const payerRefund: uint64 = data.totalDeposit - data.lastSettled
    if (payerRefund > 0) {
      itxn.assetTransfer({
        xferAsset: Asset(USDC_ASSET_ID),
        assetReceiver: data.payer,
        assetAmount: payerRefund,
      }).submit()
    }

    channel.delete()
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

    const remainingBalance: uint64 = data.totalDeposit - data.lastSettled

    if (remainingBalance > 0) {
      itxn.assetTransfer({
        xferAsset: Asset(USDC_ASSET_ID),
        assetReceiver: data.payer,
        assetAmount: remainingBalance,
      }).submit()
    }

    channel.delete()
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
   * availableBalance = totalDeposit - lastSettled
   * unclaimedVoucherBalance = latestVoucherAmount - lastSettled
   */
  getSessionDynamicData(channelId: bytes): [uint64, uint64, uint64] {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    return [data.totalDeposit, data.lastSettled, data.latestVoucherAmount]
  }

  /**
   * Read-only helper for clients: deterministic channelId derivation.
   */
  computeChannelId(payer: Account, payee: Account, authorizedSigner: Account, salt: bytes): bytes {
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
   * - Ed25519 mode: validates voucher signature.
   * - Falcon txn-auth mode: validates caller is channel participant (txn-level auth).
   */
  verifySettleSignature(channelId: bytes, cumulativeAmount: uint64, signature: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)

    if (data.signerType === SIGNER_TYPE_ED25519) {
      ensureBudget(5000, OpUpFeeSource.AppAccount)
      const message = this.getSettleMessage(channelId, cumulativeAmount)
      const signatureIsValid = op.ed25519verifyBare(message, signature, data.authorizedSigner.bytes)
      assert(signatureIsValid, 'Invalid signature')
      return
    }

    assert(data.signerType === SIGNER_TYPE_FALCON_TXN_AUTH, 'Unsupported signer type')
    assert(Txn.sender === data.payer || Txn.sender === data.payee, 'Falcon txn auth required')
  }

  // Helper functions

  private getChannel(channelId: bytes) {
    return this.channels(channelId)
  }

  private deriveChannelId(payer: Account, payee: Account, authorizedSigner: Account, salt: bytes): bytes {
    // Algorand channel-id derivation:
    // sha256(payer || payee || assetId || salt || authorizedSigner)
    return op
      .sha256(
        payer.bytes
          .concat(payee.bytes)
          .concat(op.itob(USDC_ASSET_ID))
          .concat(salt)
          .concat(authorizedSigner.bytes)
      )
  }

  private getSettleMessage(channelId: bytes, cumulativeAmount: uint64): bytes {
    return op
      .itob(op.Global.currentApplicationId.id)
      .concat(channelId)
      .concat(op.itob(cumulativeAmount))
      .concat(Bytes('settle'))
  }
}
