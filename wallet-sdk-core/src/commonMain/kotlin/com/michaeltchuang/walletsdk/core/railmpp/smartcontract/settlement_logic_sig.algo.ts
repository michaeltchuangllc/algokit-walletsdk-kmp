import {
  Account,
  Application,
  Bytes,
  gtxn,
  LogicSig,
  TemplateVar,
  Txn,
  TransactionType,
  assert,
  bytes,
  logicsig,
  op,
  uint64,
} from '@algorandfoundation/algorand-typescript'
import { falconVerify } from '@algorandfoundation/algorand-typescript/op'

/**
 * Template variables are supplied by the Kotlin client when it compiles one
 * LogicSig per channel. The resulting account can authorize only one hybrid
 * app-call shape, for one channel and one payee.
 */
const HYBRID_APP_ID = TemplateVar<uint64>('HYBRID_APP_ID')
// ARC-4 encodes the `byte[]` application argument with its two-byte length prefix:
// [0x00, 0x20] + the raw 32-byte channel ID. The voucher message needs the raw
// 32-byte form (matching the off-chain box key / signed voucher), which is just
// this value with the fixed 2-byte length prefix sliced off - no separate
// template var needed since one is fully derivable from the other.
const CHANNEL_ID = TemplateVar<bytes>('CHANNEL_ID')
// The payee doubles as the teardown sweep recipient: whoever funds this LogicSig
// account's ALGO fee buffer is expected to be (or be reimbursed by) the payee, so
// reusing this template var avoids baking in a second redundant 32-byte account
// constant. The sweep can never move USDC or channel escrow funds - only this
// account's own pre-funded ALGO.
const PAYEE = TemplateVar<Account>('PAYEE')
const AUTHORIZED_PUBLIC_KEY = TemplateVar<bytes>('AUTHORIZED_PUBLIC_KEY')

const ED25519_SIGNATURE_LENGTH: uint64 = 64
const ED25519_PUBLIC_KEY_LENGTH: uint64 = 32

/**
 * Arguments supplied when signing the application call with this LogicSig:
 *   arg 0: Falcon or Ed25519 signature over the domain-separated settlement voucher
 *   arg 1: cumulativeAmount encoded as an 8-byte unsigned integer
 *
 * `program()` validates the app call itself, so arguments cannot be replayed
 * against a different app, channel, recipient, or cumulative amount.
 */
@logicsig({ avmVersion: 13, name: 'EscrowSessionSettlementLogicSig' })
export class EscrowSessionSettlementLogicSig extends LogicSig {
  program(): boolean {
    if (op.Global.groupSize === 1) {
      // Teardown sweep: once settlement is done, return this account's unused ALGO
      // fee balance to whoever funded it. Mirrors the self-payment shape used
      // elsewhere so this stays a plain, non-USDC-touching account closeout.
      assert(Txn.typeEnum === TransactionType.Payment, 'Sweep must be a payment')
      assert(Txn.receiver === Txn.sender, 'Sweep receiver must be self')
      assert(Txn.amount === 0, 'Sweep amount must be zero')
      assert(Txn.closeRemainderTo === PAYEE, 'Sweep must return funds to the payee')
      assert(Txn.rekeyTo === Account(), 'Rekey not allowed')
      return true
    }

    // A second minimal LogicSig transaction contributes another 1,000-byte LogicSig-argument pool,
    // allowing a compressed Falcon-1024 voucher to accompany this app call.
    assert(op.Global.groupSize === 2, 'Settlement requires a two-LogicSig transaction group')
    assert(Txn.groupIndex === 0, 'Settlement must be first in group')
    assert(Txn.rekeyTo === Account(), 'Rekey not allowed')
    assert(Txn.applicationId === Application(HYBRID_APP_ID), 'Wrong hybrid application')

    // The only companion transaction is the argument-capacity padding LogicSig's no-op self-payment.
    const paddingTxn = gtxn.PaymentTxn(1)
    assert(paddingTxn.receiver === paddingTxn.sender, 'Padding payment must be self-payment')
    assert(paddingTxn.amount === 0, 'Padding payment amount must be zero')
    assert(paddingTxn.fee === 0, 'Padding payment fee must be zero')
    assert(paddingTxn.rekeyTo === Account(), 'Padding rekey not allowed')
    assert(Txn.numAppArgs === 3, 'Unexpected settlement arguments')
    assert(Txn.applicationArgs(0) === op.sha512_256(Bytes('settleFromLogicSig(byte[],uint64)void')).slice(0, 4), 'Wrong method')
    assert(Txn.applicationArgs(1) === CHANNEL_ID, 'Wrong channel')

    const cumulativeAmount = op.extractUint64(op.arg(1), 0)
    assert(op.arg(1).length === 8, 'Amount argument must be uint64')
    assert(Txn.applicationArgs(2) === op.itob(cumulativeAmount), 'Amount argument mismatch')

    const message = op
      .itob(HYBRID_APP_ID)
      .concat(CHANNEL_ID.slice(2))
      .concat(op.itob(cumulativeAmount))
      .concat(PAYEE.bytes)
      .concat(Bytes('settle-lsig-v1'))

    if (op.arg(0).length === ED25519_SIGNATURE_LENGTH) {
      assert(AUTHORIZED_PUBLIC_KEY.length === ED25519_PUBLIC_KEY_LENGTH, 'Ed25519 public key must be 32 bytes')
      return op.ed25519verifyBare(message, op.arg(0), AUTHORIZED_PUBLIC_KEY)
    }

    return falconVerify(message, op.arg(0), AUTHORIZED_PUBLIC_KEY)
  }
}
