import { strict as assert } from 'node:assert'
import { randomBytes } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { devnetRpc, environmentWallet, keypair } from './wallet'
import { AnchorError, AnchorProvider, BN, Idl, Program, Wallet } from '@coral-xyz/anchor'
import { Connection, Keypair, LAMPORTS_PER_SOL, PublicKey, SystemProgram } from '@solana/web3.js'
import {
  ASSOCIATED_TOKEN_PROGRAM_ID,
  TOKEN_PROGRAM_ID,
  createMint,
  getAccount,
  getAssociatedTokenAddressSync,
  getMint,
  getOrCreateAssociatedTokenAccount,
  mintTo,
  transfer,
} from '@solana/spl-token'

// Setup (Anchor 0.31.1 and Solana CLI required):
//   npm ci
//   anchor keys sync             # Sync a newly generated deployment key with Rust/Anchor.toml.
//   npm test                     # Build local-testing, start validator, deploy, run lifecycle.
// Public devnet: npm run deploy:devnet (build + deploy/upgrade, see deploy.ts),
// then npm run test:devnet (both load ../../.env.testnet).
// SOLANA_PAYER_MNEMONIC / SOLANA_PAYEE_MNEMONIC restore account indices 1 / 0
// using m/44'/501'/index'. Override with SOLANA_{PAYER,PAYEE}_DERIVATION_PATH.
// SOLANA_{PAYER,PAYEE}_PRIVATE_KEY accepts base58 or JSON bytes; *_KEYPAIR accepts a file.
// Set SOLANA_{PAYER,PAYEE}_ADDRESS to enforce expected addresses before any transaction.
// Wallets need SOL; payer needs DEPOSIT_AMOUNT + TOP_UP_AMOUNT raw USDC units.
// SOLANA_SESSION_KEYPAIR optionally loads an agent key; otherwise generate an ephemeral signer.
// Public clusters never mint tokens or request airdrops. Never deploy local-testing there.
// WAIT_FOR_WITHDRAW=true tests successful forced withdrawal too (takes at least 888 seconds).

const DEVNET_USDC = '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU'
const MAINNET_USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v'
const U64_MAX = (1n << 64n) - 1n
const WITHDRAW_DELAY_SECONDS = 888

interface ChannelData {
  payer: PublicKey
  payee: PublicKey
  mint: PublicKey
  authorizedSigner: PublicKey
  totalDeposit: BN
  lastSettled: BN
  latestVoucherAmount: BN
  startSlot: BN
  startTimestamp: BN
  closeRequestedAt: BN
}

function amount(name: string, fallback: string): bigint {
  const raw = process.env[name] ?? fallback
  if (!/^\d+$/.test(raw)) throw new Error(`${name} must be an unsigned integer in raw token units`)
  const value = BigInt(raw)
  if (value > U64_MAX) throw new Error(`${name} exceeds u64`)
  return value
}

async function expectError(action: () => Promise<unknown>, code: string): Promise<void> {
  try {
    await action()
  } catch (error) {
    // Do not mistake RPC outages, missing signatures, or insufficient funds for an expected rejection.
    const parsed = error instanceof AnchorError ? error : AnchorError.parse(
      (error as { logs?: string[] }).logs ?? [],
    )
    assert.equal(parsed?.error.errorCode.code, code, `Expected ${code}, received ${String(error)}`)
    console.log(`Confirmed rejection: ${code}`)
    return
  }
  assert.fail(`Expected ${code}, but the transaction succeeded`)
}

async function main(): Promise<void> {
  const local = process.env.SOLANA_LOCAL_TEST === 'true'
  const localRpc = process.env.SOLANA_RPC_URL ?? process.env.ANCHOR_PROVIDER_URL ?? 'http://127.0.0.1:8899'
  const { url: rpc, label: rpcLabel } = local ? { url: localRpc, label: localRpc } : devnetRpc()
  const rpcHost = new URL(rpc).hostname
  if (local && !['127.0.0.1', 'localhost', '::1', '[::1]'].includes(rpcHost)) {
    throw new Error('Mock-token mode is only permitted on a loopback validator')
  }
  const payer = local ? Keypair.generate() : await environmentWallet('PAYER')
  const payee = local ? Keypair.generate() : await environmentWallet('PAYEE')
  console.log(`Restored payer: ${payer.publicKey}\nRestored payee: ${payee.publicKey}`)
  assert(!payer.publicKey.equals(payee.publicKey), 'Payer and payee must differ for balance assertions')
  const session = process.env.SOLANA_SESSION_KEYPAIR
    ? await keypair(process.env.SOLANA_SESSION_KEYPAIR) : Keypair.generate()
  const connection = new Connection(rpc, 'confirmed')
  if (local) {
    for (const wallet of [payer, payee]) {
      const signature = await connection.requestAirdrop(wallet.publicKey, 5 * LAMPORTS_PER_SOL)
      const result = await connection.confirmTransaction(signature, 'confirmed')
      assert.equal(result.value.err, null, 'Local airdrop failed')
    }
  }
  const provider = new AnchorProvider(connection, new Wallet(payer), { commitment: 'confirmed' })
  const idlPath = process.env.SOLANA_IDL_PATH ?? resolve(__dirname, 'target/idl/escrow_session_vault_solana_manager.json')
  const idl = JSON.parse(await readFile(idlPath, 'utf8')) as Idl
  if (process.env.SOLANA_PROGRAM_ID) idl.address = new PublicKey(process.env.SOLANA_PROGRAM_ID).toBase58()
  const program = new Program(idl, provider)
  const deployed = await connection.getAccountInfo(program.programId)
  assert(deployed?.executable, 'Program is not deployed; run anchor keys sync, build, and deploy first')
  const mint = local ? await createMint(connection, payer, payer.publicKey, null, 6) : new PublicKey(
    process.env.SOLANA_USDC_MINT ?? DEVNET_USDC,
  )
  if (!local && ![DEVNET_USDC, MAINNET_USDC].includes(mint.toBase58())) {
    throw new Error('Public-cluster runner only accepts the canonical devnet/mainnet USDC mints')
  }
  assert.equal((await getMint(connection, mint)).decimals, 6)
  const deposit = amount('DEPOSIT_AMOUNT', '2000000')
  const topUp = amount('TOP_UP_AMOUNT', '1000000')
  const step = amount('SETTLEMENT_AMOUNT', '400000')
  const total = deposit + topUp
  assert(deposit > 0n && topUp > 0n && step > 0n && step * 3n < total && total < U64_MAX,
    'Require positive deposit/top-up/settlement, three settlements below total deposit, and total < u64 max')
  const payerToken = (await getOrCreateAssociatedTokenAccount(connection, payer, mint, payer.publicKey)).address
  const payeeToken = (await getOrCreateAssociatedTokenAccount(connection, payer, mint, payee.publicKey)).address
  if (local) await mintTo(connection, payer, mint, payerToken, payer, total)
  const balance = async (address: PublicKey): Promise<bigint> => (await getAccount(connection, address)).amount
  const payerBefore = await balance(payerToken)
  const payeeBefore = await balance(payeeToken)
  assert(payerBefore >= total, 'Payer has insufficient USDC for the lifecycle')
  const salt = randomBytes(32) // Fresh per run; existing channels use topUp instead of open.
  const [channel] = PublicKey.findProgramAddressSync(
    [Buffer.from('channel'), payer.publicKey.toBuffer(), payee.publicKey.toBuffer(), mint.toBuffer(), salt],
    program.programId,
  )
  const vault = getAssociatedTokenAddressSync(mint, channel, true)
  // IDL is generated at build time, so keep this account shape explicit for strict TS checks.
  const channelClient = (program.account as unknown as {
    channel: { fetch(address: PublicKey): Promise<ChannelData> }
  }).channel
  const state = (): Promise<ChannelData> => channelClient.fetch(channel)
  const manage = { channel, payer: payer.publicKey }
  const settleAccounts = {
    channel, authorizedSigner: session.publicKey, mint, vault, payeeToken, tokenProgram: TOKEN_PROGRAM_ID,
  }
  const finalize = {
    channel, caller: payee.publicKey, payer: payer.publicKey, mint, vault, payerToken, tokenProgram: TOKEN_PROGRAM_ID,
  }
  const settle = (value: bigint, signer = session): Promise<string> => program.methods
    .settle(new BN(value.toString()))
    .accountsStrict({ ...settleAccounts, authorizedSigner: signer.publicKey })
    .signers([signer]).rpc()
  const topUpCall = (value: bigint): Promise<string> => program.methods.topUp(new BN(value.toString()))
    .accountsStrict({ ...manage, mint, payerToken, vault, tokenProgram: TOKEN_PROGRAM_ID }).rpc()
  const withdraw = (): Promise<string> => program.methods.withdraw()
    .accountsStrict({ ...finalize, caller: payer.publicKey }).rpc()

  console.log(`RPC: ${rpcLabel}\nProgram: ${program.programId}\nPayer: ${payer.publicKey}\nPayee: ${payee.publicKey}`)
  console.log(`USDC mint: ${mint}\nSession signer: ${session.publicKey}\nChannel: ${channel}`)
  await program.methods.open([...salt], new BN(deposit.toString()), session.publicKey).accountsStrict({
    channel, payer: payer.publicKey, payee: payee.publicKey, mint, payerToken, vault,
    tokenProgram: TOKEN_PROGRAM_ID, associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
    systemProgram: SystemProgram.programId,
  }).rpc()
  let data = await state()
  assert(data.payer.equals(payer.publicKey) && data.payee.equals(payee.publicKey) && data.mint.equals(mint))
  assert(data.authorizedSigner.equals(session.publicKey))
  assert.equal(data.totalDeposit.toString(), deposit.toString())
  assert.equal(data.lastSettled.toString(), '0')
  assert(data.startSlot.gtn(0) && data.startTimestamp.gtn(0))
  assert.equal(await balance(vault), deposit)
  assert.equal(await balance(payerToken), payerBefore - deposit)
  console.log('Opened channel and deposited USDC.')

  await expectError(withdraw, 'CloseNotRequested')
  await program.methods.requestClose().accountsStrict(manage).rpc()
  assert((await state()).closeRequestedAt.gtn(0))
  await expectError(withdraw, 'WithdrawalTooEarly')
  await expectError(() => topUpCall(0n), 'InvalidDeposit')
  await topUpCall(topUp)
  data = await state()
  assert.equal(data.closeRequestedAt.toString(), '0')
  assert.equal(data.totalDeposit.toString(), total.toString())
  assert.equal(await balance(vault), total)
  console.log('Top-up deposited USDC and cancelled the pending close request.')

  await expectError(() => settle(step, payee), 'UnauthorizedSigner')
  await expectError(() => program.methods.revokeAuthorizedSigner()
    .accountsStrict({ channel, payer: payee.publicKey }).signers([payee]).rpc(), 'UnauthorizedPayer')
  await expectError(() => program.methods.close()
    .accountsStrict({ ...finalize, caller: payer.publicKey }).rpc(), 'UnauthorizedPayee')
  await expectError(() => settle(total + 1n), 'VoucherExceedsDeposit')
  for (let index = 1n; index <= 3n; index++) {
    const cumulative = step * index
    const signature = await settle(cumulative)
    data = await state()
    assert.equal(data.lastSettled.toString(), cumulative.toString())
    assert.equal(data.latestVoucherAmount.toString(), cumulative.toString())
    assert.equal(await balance(payeeToken), payeeBefore + cumulative)
    assert.equal(await balance(vault), total - cumulative)
    console.log(`Settlement ${index}/3: cumulative=${cumulative} raw USDC; transaction=${signature}`)
  }
  await expectError(() => settle(step * 3n), 'NothingNewToSettle')
  await expectError(() => settle(step), 'NothingNewToSettle')
  await program.methods.revokeAuthorizedSigner().accountsStrict(manage).rpc()
  assert((await state()).authorizedSigner.equals(PublicKey.default))
  await expectError(() => settle(step * 3n + 1n), 'UnauthorizedSigner')
  const replacement = Keypair.generate()
  await program.methods.setAuthorizedSigner(replacement.publicKey).accountsStrict(manage).rpc()
  await expectError(() => settle(step * 3n + 1n), 'UnauthorizedSigner')
  const finalSettled = step * 3n + 1n
  await settle(finalSettled, replacement)
  assert.equal(await balance(payeeToken), payeeBefore + finalSettled)
  console.log('Revocation and session-key rotation verified.')

  // Unsolicited tokens must not prevent teardown or increase the spendable deposit watermark.
  let extra = 0n
  if (local) {
    extra = 7n
    await mintTo(connection, payer, mint, payerToken, payer, extra)
    await transfer(connection, payer, payerToken, vault, payer, extra)
    assert.equal((await state()).totalDeposit.toString(), total.toString())
  }
  const expectedRefund = total - finalSettled + extra
  const beforeRefund = await balance(payerToken)
  const rent = (await connection.getAccountInfo(channel))!.lamports + (await connection.getAccountInfo(vault))!.lamports
  const payerSolBefore = await connection.getBalance(payer.publicKey)
  const forcedWithdraw = process.env.WAIT_FOR_WITHDRAW === 'true'
  let signature: string
  if (forcedWithdraw) {
    await program.methods.requestClose().accountsStrict(manage).rpc()
    const availableAt = (await state()).closeRequestedAt.toNumber() + WITHDRAW_DELAY_SECONDS
    console.log(`Waiting for on-chain withdrawal deadline ${availableAt} (at least 888 seconds).`)
    while (true) {
      const slot = await connection.getSlot('confirmed')
      const timestamp = await connection.getBlockTime(slot)
      if (timestamp !== null && timestamp >= availableAt) break
      await new Promise((done) => setTimeout(done, 2000))
    }
    signature = await withdraw()
  } else {
    signature = await program.methods.close().accountsStrict(finalize).signers([payee]).rpc()
  }
  assert.equal(await balance(payerToken), beforeRefund + expectedRefund)
  assert.equal(await balance(payerToken), payerBefore - finalSettled + extra)
  assert.equal(await connection.getAccountInfo(channel), null, 'Channel rent was not reclaimed')
  assert.equal(await connection.getAccountInfo(vault), null, 'Vault rent was not reclaimed')
  const tx = await connection.getTransaction(signature, { commitment: 'confirmed', maxSupportedTransactionVersion: 0 })
  assert(tx?.meta && tx.meta.err === null, 'Close transaction was not confirmed')
  // Payer is the provider/fee payer even when the payee authorizes close.
  // The optional forced-withdraw branch also pays for requestClose.
  if (!forcedWithdraw) {
    assert.equal(await connection.getBalance(payer.publicKey), payerSolBefore + rent - tx.meta.fee)
  }
  console.log(`Closed via ${forcedWithdraw ? 'payer withdrawal' : 'payee close'}; refunded ${expectedRefund} raw USDC and reclaimed vault/channel rent.`)
  console.log('All lifecycle assertions passed.')
}

main().catch((error: unknown) => {
  console.error(error)
  process.exitCode = 1
})
