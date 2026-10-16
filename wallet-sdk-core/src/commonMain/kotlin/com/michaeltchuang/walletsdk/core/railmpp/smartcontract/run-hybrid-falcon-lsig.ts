import { AlgorandClient, microAlgo } from '@algorandfoundation/algokit-utils'
import {
  ALGORAND_ZERO_ADDRESS_STRING,
  LogicSigAccount,
  OnApplicationComplete,
  addressWithSignersFromRawFalcon1024Signer,
  decodeAddress,
  encodeUint64,
  assignGroupID,
  getApplicationAddress,
  makeApplicationCallTxnFromObject,
  makePaymentTxnWithSuggestedParamsFromObject,
  pq25WordMnemonicToSeed,
  signLogicSigTransactionObject,
  waitForConfirmation,
  FALCON_1024_SCHEME,
} from 'algosdk'
import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

type FalconModule = typeof import('falcon-1024')

function loadFalcon(): FalconModule {
  // The PR-matched `falcon-1024@1.0.0-beta.2` package has a Node-compatible CJS export.
  // This deferred require keeps the runner compatible with the project’s CommonJS ts-node invocation.
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  return require('falcon-1024') as FalconModule
}

function falconAccount(falcon: FalconModule, mnemonic: string) {
  // Matches falcon-signatures-mobile v0.0.18's mnemonic.ToPQSeed(..., Falcon1024).
  const { publicKey, privateKey } = falcon.falcon1024.generateKey(
    pq25WordMnemonicToSeed(mnemonic, FALCON_1024_SCHEME),
  )
  return {
    ...addressWithSignersFromRawFalcon1024Signer({
      falcon1024PublicKey: publicKey,
      falcon1024Signer: async (bytesToSign) => falcon.falcon1024.signCompressed(privateKey, bytesToSign),
    }),
    publicKey,
    privateKey,
  }
}
function signAvmFalconVoucher(falcon: FalconModule, privateKey: Uint8Array, voucher: Uint8Array): Uint8Array {
  const signature = falcon.falcon1024.signCompressed(privateKey, voucher)
  if (signature[0] !== 0xba) throw new Error('Falcon signer did not produce an AVM deterministic signature')
  return signature
}

import { EscrowSessionVaultHybridManagerClient } from './artifacts/escrow_session_vault_hybrid_manager/EscrowSessionVaultHybridManagerClient'

const METHOD_SELECTOR = Uint8Array.from([0x43, 0x9c, 0x5f, 0xb1])
const CHANNEL_BOX_PREFIX = new Uint8Array()
const PUBLIC_KEY_BOX_PREFIX = new TextEncoder().encode('p')
const LOGIC_SIG_BOX_PREFIX = new TextEncoder().encode('l')
const NATIVE_FALCON_FEE = 3_000n
// Two outer LogicSig transactions and one inner ASA transfer need 3,000 microAlgos.
// Hardened LogicSig programs add encoded-byte fee on Futurenet; the teardown-sweep
// branch grew the settlement LogicSig program, bumping this from 18 to 30 microAlgos.
const LOGIC_SIG_SETTLEMENT_GROUP_FEE = 3_030n
const SETTLEMENT_COUNT = 3n
// The settlement LogicSig pays the pooled outer and inner-transfer fee; the padding LogicSig pays none.
const MIN_SETTLEMENT_LOGIC_SIG_FUNDING = microAlgo(
  100_000 + Number(LOGIC_SIG_SETTLEMENT_GROUP_FEE * SETTLEMENT_COUNT),
)
const MIN_PADDING_LOGIC_SIG_FUNDING = microAlgo(100_000)
// MBR added by each new channel's session, 1,793-byte Falcon public-key, and LogicSig boxes.
const CHANNEL_BOX_MBR_FUNDING = 835_900n

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing required environment variable: ${name}`)
  return value
}

// Derives the lora.algokit.io network path segment (testnet/mainnet/fnet/...) from
// ALGOD_SERVER so explorer links stay correct regardless of which .env.* file is loaded.
// Set ALGOD_NETWORK to override the detected segment if a server URL doesn't self-identify.
function loraExplorerNetworkSegment(): string {
  const override = process.env.ALGOD_NETWORK?.trim().toLowerCase()
  if (override) return override
  const server = (process.env.ALGOD_SERVER ?? '').toLowerCase()
  if (server.includes('mainnet')) return 'mainnet'
  if (server.includes('testnet')) return 'testnet'
  if (server.includes('fnet') || server.includes('futurenet')) return 'fnet'
  if (server.includes('betanet')) return 'betanet'
  if (server.includes('localhost') || server.includes('127.0.0.1')) return 'localnet'
  throw new Error(
    `Unable to determine network from ALGOD_SERVER "${process.env.ALGOD_SERVER ?? ''}"; set ALGOD_NETWORK explicitly.`,
  )
}

function positiveBigInt(name: string): bigint {
  // Algorand tooling commonly displays IDs as e.g. `17794220L`; accept that display suffix in env files.
  const rawValue = required(name).trim()
  if (!/^\d+L?$/.test(rawValue)) throw new Error(`${name} must be a positive integer, optionally suffixed with L`)
  const value = BigInt(rawValue.replace(/L$/, ''))
  if (value <= 0n) throw new Error(`${name} must be greater than zero`)
  return value
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const length = parts.reduce((total, part) => total + part.length, 0)
  const result = new Uint8Array(length)
  let offset = 0
  for (const part of parts) {
    result.set(part, offset)
    offset += part.length
  }
  return result
}

function sha512_256(value: Uint8Array): Uint8Array {
  return createHash('sha512-256').update(value).digest()
}

// Persists one UUID per device key (e.g. payer address) so repeated runs on the
// same device derive the same channelId and reopen/top-up the existing channel
// instead of creating a new one. Delete the entry (or the whole file) to reset.
const DEVICE_SALT_STORE_PATH = resolve(__dirname, '.device-salts.json')

async function getOrCreateDeviceSalt(deviceKey: string): Promise<Uint8Array> {
  let store: Record<string, string> = {}
  try {
    store = JSON.parse(await readFile(DEVICE_SALT_STORE_PATH, 'utf8')) as Record<string, string>
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
  }

  let uuid = store[deviceKey]
  if (!uuid) {
    uuid = randomUUID()
    store[deviceKey] = uuid
    await mkdir(resolve(__dirname), { recursive: true })
    await writeFile(DEVICE_SALT_STORE_PATH, JSON.stringify(store, null, 2))
    console.log(`Generated new device salt for ${deviceKey}: ${uuid}`)
  } else {
    console.log(`Reusing existing device salt for ${deviceKey}: ${uuid}`)
  }

  // Salt just needs to be bytes for channelId derivation; use the UUID's raw 16 bytes.
  return Uint8Array.from(Buffer.from(uuid.replace(/-/g, ''), 'hex'))
}

function formatDynamicData(data: readonly [bigint, bigint, bigint, string] | undefined): string {
  if (!data) return 'no return value'
  const [totalDeposit, lastSettled, latestVoucherAmount, settlementLogicSig] = data
  const logicSigLabel =
    settlementLogicSig === ALGORAND_ZERO_ADDRESS_STRING ? 'none (setSettlementLogicSig required)' : settlementLogicSig
  return `totalDeposit=${totalDeposit}, lastSettled=${lastSettled}, latestVoucherAmount=${latestVoucherAmount}, settlementLogicSig=${logicSigLabel}`
}

async function main(): Promise<void> {
  const appId = positiveBigInt('HYBRID_APP_ID')
  const usdcAssetId = positiveBigInt('USDC_ASSET_ID')
  const depositAmount = BigInt(process.env.DEPOSIT_AMOUNT ?? '1000000')
  const settlementAmount = BigInt(process.env.SETTLEMENT_AMOUNT ?? '400000')
  const finalSettlementAmount = settlementAmount * SETTLEMENT_COUNT
  if (depositAmount <= 0n || settlementAmount <= 0n || finalSettlementAmount > depositAmount) {
    throw new Error(`Require 0 < SETTLEMENT_AMOUNT * ${SETTLEMENT_COUNT} <= DEPOSIT_AMOUNT`)
  }

  const algorand = AlgorandClient.fromEnvironment()
  const falcon = loadFalcon()
  const payer = falconAccount(falcon, required('PAYER_MNEMONIC'))
  const payee = falconAccount(falcon, required('PAYEE_MNEMONIC'))
  const appClient = algorand.client.getTypedAppClientById(EscrowSessionVaultHybridManagerClient, {
    appId,
    defaultSender: payer.address,
  })
  const payerAddress = payer.address.toString()
  const payeeAddress = payee.address.toString()
  const expectedPayerAddress = process.env.PAYER_ADDRESS
  const expectedPayeeAddress = process.env.PAYEE_ADDRESS
  if (expectedPayerAddress && expectedPayerAddress !== payerAddress) {
    throw new Error(`PAYER_MNEMONIC derives ${payerAddress}, not configured PAYER_ADDRESS ${expectedPayerAddress}`)
  }
  if (expectedPayeeAddress && expectedPayeeAddress !== payeeAddress) {
    throw new Error(`PAYEE_MNEMONIC derives ${payeeAddress}, not configured PAYEE_ADDRESS ${expectedPayeeAddress}`)
  }
  const appAddress = getApplicationAddress(appId).toString()

  const authorizedSigner = sha512_256(payer.publicKey)
  const salt = await getOrCreateDeviceSalt(payerAddress)
  // Must match contract.deriveChannelId(): sha256(payer || payee || assetId || salt || signerHash).
  const channelId = createHash('sha256').update(
    concat(
      decodeAddress(payerAddress).publicKey,
      decodeAddress(payeeAddress).publicKey,
      encodeUint64(usdcAssetId),
      salt,
      authorizedSigner,
    ),
  ).digest()

  console.log(`App: ${appId}`)
  console.log(`Payer: ${payerAddress}`)
  console.log(`Payee: ${payeeAddress}`)
  console.log(`Channel: ${Buffer.from(channelId).toString('hex')}`)

  // Each fresh channel creates session and Falcon public-key boxes, locking this MBR in the app account.
  // APP_BOX_MBR_FUNDING can add extra headroom when needed.
  const appBoxMbrFunding = CHANNEL_BOX_MBR_FUNDING + BigInt(process.env.APP_BOX_MBR_FUNDING ?? '0')
  if (appBoxMbrFunding > 0n) {
    await algorand.send.payment({
      sender: payer.address,
      signer: payer.txnSigner,
      receiver: appAddress,
      amount: microAlgo(appBoxMbrFunding),
      staticFee: microAlgo(NATIVE_FALCON_FEE),
    })
  }

  const deposit = await algorand.createTransaction.assetTransfer({
    sender: payer.address,
    receiver: appAddress,
    assetId: usdcAssetId,
    amount: depositAmount,
    staticFee: microAlgo(3_000),
  })
  await appClient.send.open({
    args: {
      payee: payeeAddress,
      deposit: { txn: deposit, signer: payer.txnSigner },
      salt,
      authorizedSigner,
      authorizedSignerPublicKey: payer.publicKey,
    },
    sender: payer.address,
    signer: payer.txnSigner,
    staticFee: microAlgo(3_000),
    boxReferences: [channelId, concat(PUBLIC_KEY_BOX_PREFIX, channelId)],
  })
  console.log('Opened channel and deposited USDC.')

  // Use the build artifact. The legacy `out/` copy was still AVM 12 and does not represent
  // the AVM 13 LogicSig source compiled by `npm run build`.
  const tealPath = resolve(__dirname, 'artifacts/escrow_session_vault_hybrid_manager/EscrowSessionSettlementLogicSig.teal')
  const tealTemplate = await readFile(tealPath, 'utf8')
  // ABI encodes a dynamic `byte[]` as a 2-byte length followed by its contents.
  // The raw channel ID remains the box key, while this form is passed to the app method.
  const encodedChannelId = concat(Uint8Array.from([0, channelId.length]), channelId)
  if (!tealTemplate.startsWith('#pragma version 13\n')) {
    throw new Error(`Expected an AVM 13 LogicSig artifact at ${tealPath}`)
  }
  const compiled = await algorand.app.compileTealTemplate(tealTemplate, {
    TMPL_HYBRID_APP_ID: appId,
    TMPL_CHANNEL_ID: encodedChannelId,
    // TMPL_PAYEE doubles as the teardown sweep destination for this LogicSig - see
    // settlement_logic_sig.algo.ts. Whoever funds this account's fee buffer should
    // do so expecting the payee to reclaim any unspent remainder, not themselves.
    TMPL_PAYEE: decodeAddress(payeeAddress).publicKey,
    TMPL_AUTHORIZED_PUBLIC_KEY: payer.publicKey,
  })
  const compiledLogic = compiled.compiledBase64ToBytes
  if (!Buffer.from(compiledLogic).includes(Buffer.from(payer.publicKey))) {
    throw new Error('Compiled LogicSig does not contain the raw Falcon-1024 public key')
  }
  const logicSig = new LogicSigAccount(compiledLogic)
  const logicSigAddress = logicSig.address()

  // Payer-signed: only the payer can register a settlement LogicSig for this channel.
  await appClient.send.setSettlementLogicSig({
    args: { channelId, logicSig: logicSigAddress.toString() },
    sender: payer.address,
    signer: payer.txnSigner,
    staticFee: microAlgo(3_000),
    boxReferences: [channelId, concat(LOGIC_SIG_BOX_PREFIX, channelId)],
  })

  // Payee-side check: confirm the payer actually registered *this* LogicSig address
  // before funding it. getSessionDynamicData is readonly, so this is a free simulate
  // call - no fee, no signature required in practice (staticFee/signer here only
  // matter if a client falls back to a real send).
  const afterRegistration = await appClient.send.getSessionDynamicData({
    args: { channelId },
    sender: payee.address,
    signer: payee.txnSigner,
    staticFee: microAlgo(3_000),
    boxReferences: [channelId, concat(LOGIC_SIG_BOX_PREFIX, channelId)],
  })
  const registeredLogicSig = afterRegistration.return?.[3]
  if (!registeredLogicSig || registeredLogicSig === ALGORAND_ZERO_ADDRESS_STRING) {
    throw new Error('Payee check failed: no settlement LogicSig is registered on-chain yet')
  }
  if (registeredLogicSig !== logicSigAddress.toString()) {
    throw new Error(
      `Payee check failed: registered LogicSig ${registeredLogicSig} does not match the expected ${logicSigAddress.toString()}`,
    )
  }
  console.log(`Payee confirmed settlement LogicSig is registered: ${registeredLogicSig}`)

  // Payee funds the settlement LogicSig's fee buffer, since the payee is also the
  // hardcoded sweep destination baked into the compiled program (see
  // settlement_logic_sig.algo.ts) - whoever funds it is who reclaims it at teardown.
  await algorand.send.payment({
    sender: payee.address,
    signer: payee.txnSigner,
    receiver: logicSigAddress,
    amount: MIN_SETTLEMENT_LOGIC_SIG_FUNDING,
    staticFee: microAlgo(3_000),
  })
  // Compile a constrained second LogicSig to pool another 1,000 bytes of LogicSig-argument capacity.
  const paddingTealPath = resolve(
    __dirname,
    'artifacts/escrow_session_vault_hybrid_manager/EscrowSessionSettlementPaddingLogicSig.teal',
  )
  const paddingTealTemplate = await readFile(paddingTealPath, 'utf8')
  if (!paddingTealTemplate.startsWith('#pragma version 13\n')) {
    throw new Error(`Expected an AVM 13 padding LogicSig artifact at ${paddingTealPath}`)
  }
  const paddingProgram = await algorand.app.compileTealTemplate(paddingTealTemplate, {
    TMPL_HYBRID_APP_ID: appId,
    TMPL_CHANNEL_ID: encodedChannelId,
    // Payee funds the padding LogicSig too, so it should also reclaim the leftover.
    TMPL_SWEEP_DESTINATION: decodeAddress(payeeAddress).publicKey,
  })
  const paddingLogicSig = new LogicSigAccount(paddingProgram.compiledBase64ToBytes)
  const paddingLogicSigAddress = paddingLogicSig.address()
  await algorand.send.payment({
    sender: payee.address,
    signer: payee.txnSigner,
    receiver: paddingLogicSigAddress,
    amount: MIN_PADDING_LOGIC_SIG_FUNDING,
    staticFee: microAlgo(3_000),
  })
  console.log(`Registered and funded LogicSig: ${logicSigAddress}`)

  const beforeSettlement = afterRegistration
  console.log(`Balances after open: ${formatDynamicData(beforeSettlement.return)}`)

  // Submits one settleFromLogicSig group signed with the agent's ephemeral Falcon
  // session key. Reused both for the normal settlement loop and for the
  // post-revoke negative test below.
  async function submitSettlement(cumulativeAmount: bigint): Promise<string> {
    const voucher = concat(
      encodeUint64(appId),
      channelId,
      encodeUint64(cumulativeAmount),
      decodeAddress(payeeAddress).publicKey,
      new TextEncoder().encode('settle-lsig-v1'),
    )
    const signature = signAvmFalconVoucher(falcon, payer.privateKey, voucher)
    if (!falcon.falcon1024.verifyCompressed(payer.publicKey, signature, voucher)) {
      throw new Error('AVM Falcon voucher failed cross-library verification')
    }

    const settlementParams = await algorand.client.algod.getTransactionParams().do()
    settlementParams.fee = LOGIC_SIG_SETTLEMENT_GROUP_FEE
    settlementParams.flatFee = true
    const paddingParams = await algorand.client.algod.getTransactionParams().do()
    paddingParams.fee = 0n
    paddingParams.flatFee = true
    const settlementTxn = makeApplicationCallTxnFromObject({
      sender: logicSigAddress,
      appIndex: appId,
      onComplete: OnApplicationComplete.NoOpOC,
      appArgs: [METHOD_SELECTOR, encodedChannelId, encodeUint64(cumulativeAmount)],
      accounts: [payeeAddress],
      boxes: [
        { appIndex: 0, name: channelId },
        { appIndex: 0, name: concat(LOGIC_SIG_BOX_PREFIX, channelId) },
      ],
      foreignAssets: [usdcAssetId],
      suggestedParams: settlementParams,
    })
    const paddingTxn = makePaymentTxnWithSuggestedParamsFromObject({
      sender: paddingLogicSigAddress,
      receiver: paddingLogicSigAddress,
      amount: 0,
      suggestedParams: paddingParams,
    })
    assignGroupID([settlementTxn, paddingTxn])
    logicSig.lsig.args = [signature, encodeUint64(cumulativeAmount)]
    const signedSettlement = signLogicSigTransactionObject(settlementTxn, logicSig)
    const signedPadding = signLogicSigTransactionObject(paddingTxn, paddingLogicSig)
    await algorand.client.algod.sendRawTransaction([signedSettlement.blob, signedPadding.blob]).do()
    // Readonly getSessionDynamicData calls right after this run via simulate against the
    // latest confirmed round, so we must actually wait for confirmation here (a single
    // pendingTransactionInformation fetch does not block until confirmed).
    await waitForConfirmation(algorand.client.algod, signedSettlement.txID, 4)
    return signedSettlement.txID
  }

  // Sweeps a settlement/padding LogicSig account's leftover ALGO fee buffer back to
  // whoever funded it (SWEEP_DESTINATION baked into the compiled program). This is a
  // standalone (groupSize 1) self-payment closeout; it can never touch USDC or the
  // channel's escrowed deposit, only this LogicSig account's own small ALGO balance.
  async function sweepLogicSigAccount(
    logicSig: LogicSigAccount,
    address: string,
    label: string,
    destination: string,
  ): Promise<void> {
    const suggestedParams = await algorand.client.algod.getTransactionParams().do()
    // Pad past the suggested min fee: Futurenet's fee-market congestion pricing can
    // transiently require more than the plain minFee for a lone, ungrouped txn.
    suggestedParams.fee = BigInt(suggestedParams.minFee) * 2n
    suggestedParams.flatFee = true
    const sweepTxn = makePaymentTxnWithSuggestedParamsFromObject({
      sender: address,
      receiver: address,
      amount: 0,
      closeRemainderTo: destination,
      suggestedParams,
    })
    // The sweep branch reads no LogicSig args; clear any stale settlement-voucher
    // args left on this object so a solo (groupSize 1) txn doesn't exceed the
    // single-LogicSig 1,000-byte argument pool.
    logicSig.lsig.args = []
    const signedSweep = signLogicSigTransactionObject(sweepTxn, logicSig)
    await algorand.client.algod.sendRawTransaction(signedSweep.blob).do()
    await waitForConfirmation(algorand.client.algod, signedSweep.txID, 4)
    console.log(`Swept leftover ALGO from ${label} (${address}) to ${destination}.`)
  }

  const explorerNetworkSegment = loraExplorerNetworkSegment()

  for (let settlementIndex = 1n; settlementIndex <= SETTLEMENT_COUNT; settlementIndex++) {
    const cumulativeAmount = settlementAmount * settlementIndex
    const txId = await submitSettlement(cumulativeAmount)
    console.log(`Settlement ${settlementIndex}/${SETTLEMENT_COUNT}: cumulative=${cumulativeAmount} USDC`)
    console.log(`Settlement transaction ID: ${txId}`)
    console.log(`${explorerNetworkSegment} explorer: https://lora.algokit.io/${explorerNetworkSegment}/transaction/${txId}`)
  }

  const afterSettlement = await appClient.send.getSessionDynamicData({
    args: { channelId },
    sender: payee.address,
    signer: payee.txnSigner,
    staticFee: microAlgo(NATIVE_FALCON_FEE),
    boxReferences: [channelId],
  })
  console.log(`Balances after three settlements: ${formatDynamicData(afterSettlement.return)}`)

  // Emergency-stop test: payer revokes the agent's ephemeral Falcon session key
  // mid-session, then confirms the now-orphaned LogicSig can no longer settle.
  // Set TEST_REVOKE=false to skip.
  if (process.env.TEST_REVOKE !== 'false') {
    await appClient.send.revokeSettlementLogicSig({
      args: { channelId },
      sender: payer.address,
      signer: payer.txnSigner,
      staticFee: microAlgo(3_000),
      boxReferences: [channelId, concat(LOGIC_SIG_BOX_PREFIX, channelId)],
    })
    console.log('Revoked settlement LogicSig; the ephemeral session key is now powerless.')

    const revokedAttemptAmount = settlementAmount * (SETTLEMENT_COUNT + 1n)
    try {
      await submitSettlement(revokedAttemptAmount)
      throw new Error('Settlement succeeded after revocation; revoke did not take effect!')
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      if (message.includes('Settlement succeeded after revocation')) throw error
      console.log('Confirmed: settlement after revoke was rejected, as expected.')
    }
  }

  // Closes by default so the leftover (unsettled) deposit is refunded to the payer.
  // Set CLOSE_AFTER_SETTLEMENT=false to leave the channel open for further settlements.
  if (process.env.CLOSE_AFTER_SETTLEMENT !== 'false') {
    if (afterSettlement.return) {
      const [totalDeposit, , latestVoucherAmount] = afterSettlement.return
      const expectedPayerRefund = totalDeposit - latestVoucherAmount
      console.log(`Closing channel; expecting payer refund of ${expectedPayerRefund} USDC.`)
    }
    await appClient.send.close({
      args: { channelId },
      sender: payee.address,
      signer: payee.txnSigner,
      staticFee: microAlgo(6_000),
      // The payer refund inner-txn needs the payer account available to the app call.
      accountReferences: [payerAddress],
      assetReferences: [usdcAssetId],
      boxReferences: [
        channelId,
        concat(PUBLIC_KEY_BOX_PREFIX, channelId),
        concat(LOGIC_SIG_BOX_PREFIX, channelId),
      ],
      coverAppCallInnerTransactionFees: true,
      maxFee: microAlgo(6_000),
    })
    console.log('Closed channel as payee; remaining USDC was refunded to payer.')

    // Teardown: reclaim whatever unused ALGO fee buffer is left in both LogicSig
    // accounts now that no more settlements will happen for this channel.
    // Both LogicSigs are now funded by (and sweep back to) the payee, since the
    // payee is funding settlement fees moving forward. Settlement's destination is
    // hardcoded to PAYEE on-chain (see settlement_logic_sig.algo.ts); padding's
    // SWEEP_DESTINATION is an independent template var, compiled to the payee above.
    await sweepLogicSigAccount(logicSig, logicSigAddress.toString(), 'settlement LogicSig', payeeAddress)
    await sweepLogicSigAccount(paddingLogicSig, paddingLogicSigAddress.toString(), 'padding LogicSig', payeeAddress)
  }
}

main().catch((error: unknown) => {
  console.error(error)
  process.exitCode = 1
})
