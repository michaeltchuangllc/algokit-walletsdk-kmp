import {
  Account,
  Application,
  Bytes,
  LogicSig,
  TemplateVar,
  Txn,
  TransactionType,
  assert,
  bytes,
  gtxn,
  logicsig,
  op,
  uint64,
} from '@algorandfoundation/algorand-typescript'

const HYBRID_APP_ID = TemplateVar<uint64>('HYBRID_APP_ID')
const CHANNEL_ID = TemplateVar<bytes>('CHANNEL_ID')
// Teardown sweep recipient: whoever pre-funded this LogicSig account's ALGO fee
// buffer (the payer, by default) so any unused amount can be reclaimed once the
// session is done settling.
const SWEEP_DESTINATION = TemplateVar<Account>('SWEEP_DESTINATION')

/**
 * A constrained companion LogicSig used solely to add 1,000 bytes of pooled
 * LogicSig-argument capacity for the Falcon settlement voucher.
 */
@logicsig({ avmVersion: 13, name: 'EscrowSessionSettlementPaddingLogicSig' })
export class EscrowSessionSettlementPaddingLogicSig extends LogicSig {
  program(): boolean {
    if (op.Global.groupSize === 1) {
      // Teardown sweep: return this account's unused ALGO balance to whoever
      // funded it, once settlement is done.
      assert(Txn.typeEnum === TransactionType.Payment, 'Sweep must be a payment')
      assert(Txn.receiver === Txn.sender, 'Sweep receiver must be self')
      assert(Txn.amount === 0, 'Sweep amount must be zero')
      assert(Txn.closeRemainderTo === SWEEP_DESTINATION, 'Sweep must return funds to the funder')
      assert(Txn.rekeyTo === Account(), 'Rekey not allowed')
      return true
    }

    assert(op.Global.groupSize === 2, 'Padding requires a two-LogicSig transaction group')
    assert(Txn.groupIndex === 1, 'Padding must be second in group')
    assert(Txn.receiver === Txn.sender, 'Padding payment must be self-payment')
    assert(Txn.amount === 0, 'Padding payment amount must be zero')
    assert(Txn.fee === 0, 'Padding payment fee must be zero')
    assert(Txn.rekeyTo === Account(), 'Padding rekey not allowed')

    // Bind padding to the paired settlement app call; it cannot authorize any other group.
    const settlementTxn = gtxn.ApplicationCallTxn(0)
    assert(settlementTxn.appId === Application(HYBRID_APP_ID), 'Wrong hybrid application')
    assert(settlementTxn.numAppArgs === 3, 'Unexpected settlement arguments')
    assert(
      settlementTxn.appArgs(0) === op.sha512_256(Bytes('settleFromLogicSig(byte[],uint64)void')).slice(0, 4),
      'Wrong method',
    )
    assert(settlementTxn.appArgs(1) === CHANNEL_ID, 'Wrong channel')
    return true
  }
}
