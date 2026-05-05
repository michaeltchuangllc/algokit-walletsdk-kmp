// Reference-only TypeScript client placeholder.
// Kotlin implementation is the source of truth for Android usage:
// `EscrowSessionVaultManagerClient.kt`
//
// Keep this file in-project for contract/API reference while Kotlin code is actively used.

export type EscrowSessionVaultSignerType = 0 | 1

export interface EscrowSessionVaultManagerClientReference {
  openAndDeposit: (...args: unknown[]) => Promise<unknown>
  topUp: (...args: unknown[]) => Promise<unknown>
  updateVoucher: (...args: unknown[]) => Promise<unknown>
  settle: (...args: unknown[]) => Promise<unknown>
  verifySettleSignatureOnChain: (...args: unknown[]) => Promise<unknown>
  deriveChannelId: (...args: unknown[]) => Uint8Array
}
