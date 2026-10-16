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
  clone,
  TemplateVar,
  gtxn,
} from '@algorandfoundation/algorand-typescript'
import { sha512_256 } from '@algorandfoundation/algorand-typescript/op'
import { abimethod } from '@algorandfoundation/algorand-typescript/arc4'

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

export class EscrowSessionVaultHybridManager extends Contract {
  /**
   * BoxMap for channel data, keyed by channelId bytes. latestVoucherAmount is
   * the replay-protection watermark and is atomically advanced on settlement.
   */
  channels = BoxMap<bytes, ChannelInfo>({ keyPrefix: '' })

  /**
   * Full authorized signer public key storage, keyed by channelId.
   */
  authorizedSignerPublicKey = BoxMap<bytes, bytes>({ keyPrefix: 'p' })

  /**
   * Address of the channel-specific Falcon-verifying LogicSig, keyed by channelId.
   */
  settlementLogicSig = BoxMap<bytes, Account>({ keyPrefix: 'l' })

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
   * Registers the channel-specific LogicSig used for Falcon-authorized settlement.
   * The payer compiles it with this app id, channel id, payee, and the public key
   * whose sha512_256 hash is stored on the channel.
   */
  setSettlementLogicSig(channelId: bytes, logicSig: Account): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can set LogicSig')
    assert(logicSig !== Account(), 'LogicSig account required')

    this.settlementLogicSig(channelId).value = logicSig
  }

  /**
   * Emergency stop: payer immediately revokes the AI agent's settlement authority
   * (e.g. if the ephemeral Falcon session key is suspected compromised) without
   * closing the channel or losing the deposit. settleFromLogicSig will fail until
   * the payer registers a fresh LogicSig via setSettlementLogicSig.
   */
  revokeSettlementLogicSig(channelId: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can revoke LogicSig')

    const logicSig = this.settlementLogicSig(channelId)
    assert(logicSig.exists, 'Settlement LogicSig not set')
    logicSig.delete()
  }

  /**
   * Settle through the registered LogicSig. Falcon verification occurs in the
   * LogicSig program; this call binds that authorization to the channel box and
   * advances its voucher watermark, preventing voucher replay.
   */
  settleFromLogicSig(channelId: bytes, cumulativeAmount: uint64): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    const logicSig = this.settlementLogicSig(channelId)
    assert(logicSig.exists, 'Settlement LogicSig not set')
    assert(Txn.sender === logicSig.value, 'Only settlement LogicSig can settle')

    this.applySettlement(data, cumulativeAmount)
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
  @abimethod({ readonly: true })
  getSessionStaticData(channelId: bytes): [uint64, uint64] {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    return [data.startRound, data.startTimestamp]
  }

  /**
   * Returns latest session dynamic data tuple:
   * [totalDeposit, lastSettled, latestVoucherAmount, settlementLogicSig]
   * settlementLogicSig is the zero address (Account()) if none is currently
   * registered (never set, or revoked via revokeSettlementLogicSig) — callers
   * can use that to detect when setSettlementLogicSig needs to be (re)called.
   */
  @abimethod({ readonly: true })
  getSessionDynamicData(channelId: bytes): [uint64, uint64, uint64, Account] {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    const logicSig = this.settlementLogicSig(channelId)
    return [data.totalDeposit, data.lastSettled, data.latestVoucherAmount, logicSig.exists ? logicSig.value : Account()]
  }

  /**
   * Read-only helper for clients: deterministic channelId derivation.
   * authorizedSigner must be signer pubkey hash (32 bytes).
   */
  @abimethod({ readonly: true })
  deriveChannelId(payer: Account, payee: Account, authorizedSigner: bytes, salt: bytes): bytes {
    // Algorand channel-id derivation:
    // sha256(payer || payee || assetId || salt || authorizedSignerHash)
    return op.sha256(payer.bytes.concat(payee.bytes).concat(op.itob(USDC_ASSET_ID)).concat(salt).concat(authorizedSigner))
  }

  // Helper functions

  private getChannel(channelId: bytes) {
    return this.channels(channelId)
  }

  /**
   * LogicSig-authorized settlement state transition. lastSettled is the
   * on-chain replay watermark; a previously settled or lower
   * cumulative voucher cannot produce another transfer.
   */
  private applySettlement(data: ChannelInfo, cumulativeAmount: uint64): void {
    assert(cumulativeAmount > data.lastSettled, 'Nothing new to settle')
    assert(cumulativeAmount <= data.totalDeposit, 'Voucher exceeds deposit')

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
    const signerPublicKey = this.authorizedSignerPublicKey(channelId)
    if (signerPublicKey.exists) {
      signerPublicKey.delete()
    }
    const logicSig = this.settlementLogicSig(channelId)
    if (logicSig.exists) {
      logicSig.delete()
    }
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
}
