---
name: seed-vault-conventions
description: Conventions for the Seed Vault app (seedVaultService) and its sample client (serviceDemoApp). Use before changing seed storage, the SeedKeyVault Room/Trusty abstraction, the AIDL service, app authorization, the Falcon-1024 signing paths, the local .aar libraries, or the demo client's Algorand/Solana tabs. Read wallet-sdk-conventions first for the general architecture, MVI and Compose rules.
---

# Seed Vault conventions

These apply on top of `wallet-sdk-conventions` (clean architecture, MVI with `StateDelegate`/`EventDelegate`, Compose, tests, lint).

## Modules

| Module | What it is |
|---|---|
| `seedVaultService` | Android-only Seed Vault app (`com.michaeltchuang.seedvault.service`). Create/import 25-word Falcon-1024 seeds, view the phrase, authorize apps, bound AIDL service. Own theme (`SeedVaultTheme`). |
| `serviceDemoApp` | Android-only sample wallet (`com.michaeltchuang.seedvault.demo`). AIDL client of `seedVaultService` on the Algorand tab, client of the Solana Mobile Seed Vault on the Solana tab. |

Neither may depend on `wallet-sdk-ui`. They may use `wallet-sdk-core`.

## Where secrets live: `SeedKeyVault`

Secret keys sit behind `SeedKeyVault` (`data/vault`), with two implementations. The repository picks one per seed and each seed row remembers its choice in the `storage` column.

- `RoomSeedKeyVault`: entropy and private key encrypted with the Android Keystore key, stored in the seed row. Used when the device has no Trusty applet. This is also the emulated path.
- `TrustySeedKeyVault`: keys stay in the Trusty applet in the TEE, reached through `libs/algokit_seed_vault.aar`. The seed row keeps only the address and public key, the encrypted columns are empty. It is available only when `client.status()` is `Available`; any failure counts as unavailable.

Rules:

- New code that touches a secret goes through `SeedKeyVault`, so both storages keep working. Add the method to the interface, implement it in both vaults, and fake it in tests.
- New seeds go to Trusty when it is available, otherwise Room. Existing seeds keep their storage. Using a seed whose storage is unavailable must fail with a clear error.
- The applet signs through `signTransaction` and `signArbitraryData` with an address index. Seeds use `humanSignIndex()` (user approval per signature), not `autosignIndex()`.
- Wrap anything that loads the native library so it cannot crash on a device without it, and keep it lazy (no native calls at class load) so JVM unit tests work. Fake `SeedVaultClientInterface` in tests.
- Room is the metadata store for every seed: name, backup state, address, public key, authorized apps. `address` is unique, so importing a phrase that is already stored returns the existing seed.

## AIDL service

- The contract is `ISeedVaultService.aidl` in `seedVaultService/src/main/aidl/...`. **`serviceDemoApp` has a copy; edit both together.** Keep `SeedVaultContract` (service) and `SeedVaultServiceConstants` (client) in sync with the manifests.
- The service is exported and protected by the dangerous permission `<package>.ACCESS_SEED_VAULT`. Every call also checks the caller's uid, resolved with `Binder.getCallingUid()`, against the seed's `authorized_app` rows. Throw `SecurityException` for an unauthorized caller. Never trust a package name the caller sends.
- Every AIDL signature needs a matching one-time approval (`SignApprovalCoordinator`, 60 s, per app and exact operation) from the confirm sign screen, where the user verifies with fingerprint / screen lock (`UserAuthenticators`: Trusty applet if it can, else the system prompt). No way to verify means no. Clients call `CONFIRM_SIGN` before every sign call.
- AIDL returns only public data (id, name, address, public key) and signatures. Never return a seed phrase, entropy, seed or private key.
- The vault builds what it signs when it can: `signAlgorandPayment` takes a receiver, amount and network values, and sets the sender and fee itself, so a caller cannot get an arbitrary transaction signed.
- `MainActivity` is the only activity: a Compose `NavHost` that also hosts the authorize screen (`AUTHORIZE_APP` action) and the confirm sign screen (`CONFIRM_SIGN`, started by the client with `startActivityForResult`; a service cannot open a screen from the background). Do not add activities.
- Authorization is started by the client with `startActivityForResult`. The caller package comes from `callingActivity`, never from an extra.
- The vault never talks to the network. The client fetches algod params and sends the signed transaction.
- Anything that can reach a seed phrase or key shows `SecureScreen()` (FLAG_SECURE).

## Demo client (`serviceDemoApp`)

- Two tabs: Algorand (Seed Vault Service over AIDL, TestNet via algod) and Solana (Solana Mobile Seed Vault through its SDK, DevNet). Solana does not depend on the AIDL connection: the Falcon-1024 keys cannot sign Solana transactions.
- The Solana Mobile Seed Vault is not a bound service. It is a content provider plus activities, reached through the `Wallet` SDK.
- Algorand network calls live in `AlgorandRepository`, Solana ones in `SolanaBalanceRepository` / `SolanaTransferRepository`.
- Explorer links: Pera TestNet explorer for Algorand, Solana explorer `?cluster=devnet` for Solana.

## Local `.aar` libraries

- Put an `.aar` in `<module>/libs/` and reference it explicitly: `implementation(files("libs/algokit_seed_vault.aar"))`. Replace it with the Maven coordinate when it is published.
- A local `.aar` brings no transitive dependencies, so declare them by hand (the seed vault client needs `net.java.dev.jna:jna@aar`).
- Do not add local copies of `algokit_composer`, `algokit_crypto` or `algokit_transact`: `wallet-sdk-core` already pulls them from Maven, and two builds of the same native library conflict at runtime.

## Checks

```
./gradlew :seedVaultService:ktlintFormat
./gradlew :seedVaultService:testDebugUnitTest :seedVaultService:ktlintCheck :seedVaultService:assembleDebug :serviceDemoApp:assembleDebug
```

After a package rename or a Room schema change, uninstall the app on the device before installing, and install `seedVaultService` before `serviceDemoApp` so the permission exists when the client is installed.
