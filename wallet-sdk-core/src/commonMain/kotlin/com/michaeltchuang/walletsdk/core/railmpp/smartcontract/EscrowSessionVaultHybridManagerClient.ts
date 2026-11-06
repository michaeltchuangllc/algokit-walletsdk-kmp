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
 * Box MBR for one payee-signer entry (account- or channel-level, same size):
 * 2500 + 400 * (keyLen + valueLen), key = 1-byte prefix + 32-byte payee address
 * or channelId, value = 32-byte signer address.
 */
const PAYEE_SIGNER_BOX_MBR: uint64 = 2_500 + 400 * (1 + 32 + 32)

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
   * Address of the channel-specific Falcon-verifying LogicSig, keyed by channelId.
   */
  settlementLogicSig = BoxMap<bytes, Account>({ keyPrefix: 'l' })

  /**
   * Optional payee-controlled ACCOUNT-LEVEL delegate allowed to call close on the
   * payee's behalf for every channel where that account is the payee (e.g. a hot key
   * when the payee is a Seed Vault / biometric-gated account). Keyed by payee account
   * so a creator signs once, not once per viewer channel.
   */
  authorizedPayeeSigner = BoxMap<Account, Account>({ keyPrefix: 'd' })

  /**
   * Optional payee-controlled CHANNEL-LEVEL delegate, keyed by channelId. When set,
   * it overrides the account-level delegate for that channel only.
   */
  channelPayeeSigner = BoxMap<bytes, Account>({ keyPrefix: 'e' })

  /**
   * Opens a channel with initial USDC deposit and returns derived channelId.
   * Caller becomes payer.
   * authorizedSigner is signer pubkey hash (32 bytes) computed client-side. The full
   * public key is not stored on-chain: it is compiled into the settlement LogicSig,
   * which performs voucher verification.
   */
  open(
    payee: Account,
    deposit: gtxn.AssetTransferTxn,
    salt: bytes,
    authorizedSigner: bytes,
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
      this.applyTopUp(data, deposit)
      channel.value = clone(data)
      return channelId
    }

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can reopen channel')
    assert(payee === data.payee, 'Payee mismatch')
    assert(authorizedSigner === data.authorizedSigner, 'Authorized signer hash mismatch')

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
   * Payer-controlled: records a new payer signer by updating channel.authorizedSigner
   * to sha512_256(authorizedSignerPublicKey). Record only: voucher authority is the
   * registered settlement LogicSig, so also call setSettlementLogicSig with a LogicSig
   * compiled for the new key (or revokeSettlementLogicSig) to actually switch signers.
   */
  setAuthorizedPayerSigner(channelId: bytes, authorizedSignerPublicKey: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)
    assert(Txn.sender === data.payer, 'Only payer can set authorized signer')
    assert(authorizedSignerPublicKey.length > 0, 'Authorized signer pubkey required')

    data.authorizedSigner = sha512_256(authorizedSignerPublicKey)
    channel.value = clone(data)
  }

  /**
   * Payee-controlled: registers a delegate account that may call close on the
   * payee's behalf (e.g. so a Seed Vault payee can close without a fingerprint).
   *
   * - channelId empty  -> ACCOUNT-LEVEL default: applies to every channel (current
   *   and future) where the sender is the payee, so the payee signs once.
   * - channelId set    -> CHANNEL-LEVEL: applies to that channel only and overrides
   *   the account-level default for it. Sender must be that channel's payee.
   *
   * The sender pays the box MBR via the grouped payment (required only when first
   * creating the entry; replacing an existing delegate accepts any amount).
   * Only the payee account itself (never a delegate) can set, replace, or revoke.
   */
  setAuthorizedPayeeSigner(mbrPayment: gtxn.PaymentTxn, payeeSigner: Account, channelId: bytes): void {
    assert(payeeSigner !== Account(), 'Payee signer account required')
    assert(payeeSigner !== Txn.sender, 'Payee signer must differ from payee')
    assert(mbrPayment.sender === Txn.sender, 'MBR payment sender mismatch')
    assert(mbrPayment.receiver === op.Global.currentApplicationAddress, 'MBR payment must be to contract')
    assert(mbrPayment.closeRemainderTo === Account(), 'Close remainder not allowed')
    assert(mbrPayment.rekeyTo === Account(), 'Rekey not allowed')

    if (channelId.length === 0) {
      const entry = this.authorizedPayeeSigner(Txn.sender)
      if (!entry.exists) {
        assert(mbrPayment.amount >= PAYEE_SIGNER_BOX_MBR, 'Insufficient MBR payment')
      }
      entry.value = payeeSigner
      return
    }

    this.assertSenderIsChannelPayee(channelId)
    const entry = this.channelPayeeSigner(channelId)
    if (!entry.exists) {
      assert(mbrPayment.amount >= PAYEE_SIGNER_BOX_MBR, 'Insufficient MBR payment')
    }
    entry.value = payeeSigner
  }

  /**
   * Payee-controlled: removes a delegate and refunds its box MBR to the payee.
   * channelId empty -> removes the account-level default; channelId set -> removes
   * that channel's override (the channel then falls back to the account-level default).
   */
  revokeAuthorizedPayeeSigner(channelId: bytes): void {
    if (channelId.length === 0) {
      const entry = this.authorizedPayeeSigner(Txn.sender)
      assert(entry.exists, 'Payee signer not set')
      entry.delete()
    } else {
      this.assertSenderIsChannelPayee(channelId)
      const entry = this.channelPayeeSigner(channelId)
      assert(entry.exists, 'Channel payee signer not set')
      entry.delete()
    }

    itxn.payment({
      receiver: Txn.sender,
      amount: PAYEE_SIGNER_BOX_MBR,
    }).submit()
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
   * Payee (or the payee's registered authorizedPayeeSigner) closes channel.
   * Honors the latest on-chain voucher before refunding the payer. Payouts
   * always go to data.payee, never to the delegate.
   */
  close(channelId: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')

    const data = clone(channel.value)

    const payeeSigner = this.effectivePayeeSigner(channelId, data.payee)
    const isPayeeSigner = payeeSigner !== Account() && Txn.sender === payeeSigner
    assert(Txn.sender === data.payee || isPayeeSigner, 'Only payee or payee signer can close')

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
   * Returns a delegated close signer, or the zero address if none applies.
   * channelId empty -> the payee account's account-level default.
   * channelId set   -> the signer effective for that channel (channel-level override
   *                    if set, otherwise the channel payee's account-level default);
   *                    the payee argument is ignored in this case.
   */
  @abimethod({ readonly: true })
  getAuthorizedPayeeSigner(payee: Account, channelId: bytes): Account {
    if (channelId.length === 0) {
      const payeeSigner = this.authorizedPayeeSigner(payee)
      return payeeSigner.exists ? payeeSigner.value : Account()
    }

    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')
    const data = clone(channel.value)
    return this.effectivePayeeSigner(channelId, data.payee)
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

  private assertSenderIsChannelPayee(channelId: bytes): void {
    const channel = this.getChannel(channelId)
    assert(channel.exists, 'Channel does not exist')
    const data = clone(channel.value)
    assert(Txn.sender === data.payee, 'Only payee can manage payee signer')
  }

  /**
   * Channel-level delegate overrides the payee's account-level default.
   * Returns the zero address if neither is set.
   */
  private effectivePayeeSigner(channelId: bytes, payee: Account): Account {
    const channelSigner = this.channelPayeeSigner(channelId)
    if (channelSigner.exists) {
      return channelSigner.value
    }
    const accountSigner = this.authorizedPayeeSigner(payee)
    return accountSigner.exists ? accountSigner.value : Account()
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
    const logicSig = this.settlementLogicSig(channelId)
    if (logicSig.exists) {
      logicSig.delete()
    }
    // Channel-level payee signer dies with the channel; the payee paid its MBR, so
    // refund it. (The account-level default is kept for the payee's other channels.)
    const channelSigner = this.channelPayeeSigner(channelId)
    if (channelSigner.exists) {
      channelSigner.delete()
      itxn.payment({
        receiver: data.payee,
        amount: PAYEE_SIGNER_BOX_MBR,
      }).submit()
    }
  }

}
