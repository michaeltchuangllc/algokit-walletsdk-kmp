# AlgoKit Wallet SDK

[![Maven Central - wallet-sdk-ui](https://img.shields.io/maven-central/v/com.michaeltchuang.algokit.walletsdk/wallet-sdk-ui.svg?label=wallet-sdk-ui)](https://central.sonatype.com/artifact/com.michaeltchuang.algokit.walletsdk/wallet-sdk-ui)
[![Maven Central - wallet-sdk-core](https://img.shields.io/maven-central/v/com.michaeltchuang.algokit.walletsdk/wallet-sdk-core.svg?label=wallet-sdk-core)](https://central.sonatype.com/artifact/com.michaeltchuang.algokit.walletsdk/wallet-sdk-core)

This mobile utils library project provides common wallet UI components and screens out of the box, allowing native developers to skip building standard wallet functionality and focus more on unique, value-added features for their mobile applications.

## How to Use

Add the following to your `build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.michaeltchuang.algokit.walletsdk:wallet-sdk-ui:3.202602.4")
    implementation("com.michaeltchuang.algokit.walletsdk:wallet-sdk-core:3.202602.4")
}
```

> **Note:** Check Maven Central for the latest
> versions: [wallet-sdk-ui](https://central.sonatype.com/artifact/com.michaeltchuang.algokit.walletsdk/wallet-sdk-ui) | [wallet-sdk-core](https://central.sonatype.com/artifact/com.michaeltchuang.algokit.walletsdk/wallet-sdk-core)

## Overview

```mermaid
---
title: AlgoKit Wallet SDK High Level Overview
---
graph TD
    subgraph "Apps"
        App1["App 1"]
        App2["App 2"]
    end

    subgraph wallet["AlgoKit Wallet SDK"]
        SDK1["Wallet SDK Service<br/>(Android Only)"]
        SDK2["Wallet SDK UI<br/>(Embedded Wallet UI)"]
        SDK3["Wallet SDK Core<br/>(Headless Wallet Engine)"]
    end

    subgraph "Algorand SDKs"
        Core["AlgoKit-Core Rust SDK"]
        xHD["Algo xHD Kotlin/Swift SDK"]
        JavaSDK["Algo Java SDK"]
        GoSDK["Algo Go SDK"]
    end

    App1 <--> wallet
    App2 <--> wallet
    SDK1 <--> SDK2
    SDK2 <--> SDK3
    SDK3 <--> Core
    SDK3 <--> xHD
    SDK3 <--> JavaSDK
    SDK3 <--> GoSDK
```

The demo apps (Android & iOS) in this repo demonstrate `wallet-sdk` library usage through a simplified "Pera-lite" sample wallet application. Current and planned features include:

- Create and recover accounts (Algo25, Universal HD, Falcon24)
- Theme customization
- Network switching between mainnet/testnet (code hasn't been audited, so use mainnet at your own risk)
- QR code scanning for account imports and keyreg transactions
- Algo-only experience for now (to swap memecoins...please use Pera app, Haystack app, etc)
- Account detail screen
- Passphrase management
- Localization

AlgoKit Wallet SDK currently uses UI theming inspired by [Pera Android](https://github.com/perawallet/pera-android) as a placeholder until official Algorand Foundation branding guidelines are available.

```mermaid
---
config:
  theme: 'neutral'
---
timeline
    title AlgoKit Wallet SDK tentative roadmap

    section Completed ✅
    2025Q3  : ✅ Create sample KMP app ("Pera Lite")
            : ✅ Onboarding - Create Algo25/HD/Falcon24 wallet and account flow
            : ✅ Onboarding - Recover Algo25/Falcon24 account flow
            : ✅ Deeplink - Import Algo25/Falcon24 account using QR code flow
            : ✅ Settings - Theme picker and network switcher flow
            : ✅ Onboarding - Embedded and external webview flow
            : ✅ Account Details - View passphrase flow
            : ✅ Onboarding - Encrypt secret keys in DB
            : ✅ GitOps - Setup Maven Central for library releases
            : ✅ Transaction - Sign KeyReg online/offline flow with QR code

    2025Q4  : ✅ Transaction - Send Algo using account detail or QR code flows (between accounts)
            : ✅ Account Details - Add copy and show address button
            : ✅ Account Details - Add testnet dispenser and transaction history links
            : ✅ GitOps - Setup stores for demo Android/iOS app releases
            : ✅ GitOps - CLA agreement bot
            : ✅ Onboarding - Add Watch accounts support
            : ✅ Onboarding - Recover HD account flow
            : ✅ Settings - Localization (English, Italian, Hindi)
            : ✅ Testing - Setup unit test coverage and screenshot testing foundation infrastructure
            : ✅ Onboarding - ECC Passkeys & Testnet Liquid Auth (Android)

    2026Q1  : ✅ Onboarding - ECC Passkeys & Liquid Auth (iOS)
            : ✅ Transaction - Integrate new algokit-transact rust library (iOS)
            : ✅ Android - Wallet SDK as a background service integration
            : ✅ Onboarding - Upgrade Liquid Auth service to support PQ accounts (Web)
            : ✅ Transaction - Opt-In / Opt-Out USDC QR flow
            : ✅ Transaction - Send USDC using QR code flow (between accounts)
            : ✅ Onboarding - Re-enable Use-Wallet Liquid Auth functionality (Non-Rekey Accounts)
            : ✅ Onboarding - Add Liquid Auth PQ Account Integration for Lora (Txn Wizard)
            : ✅ Onboarding - Add Liquid Auth PQ Account Integration for xGov Website (Subscribe to xGov)
            : ✅ Onboarding - Add Liquid Auth Integration for Android<>Android Connections

    section In Progress 🔄
    2026Q2  : ✅ Transaction - Sign test transactions using Seed Vault in emulator app
            : ✅ Seed Vault - Modify Seed Vault emulator to allow Algorand seeds (R&D prototype)
            : ✅ Seed Vault - Integrate cross-chain (Solana & Algorand) account support for passkeys and liquid auth experience
            : ✅ Design - Create app design system in Figma project
            : ✅ Liquid Stream - Implement new Figma viewer/host screens
            : ✅ Liquid Stream - Implement new Figma app modals
            : ✅ Transaction - Upgrade to escrow smart contract manager for account micro-billing (Android)
            : ✅ GitOps - Fix crypto dependencies for new 16KB Android requirement
            : 🔄 Account Details - Send USDC in account detail (between accounts)
            
    section Future 🔮
     2026Q3  : Onboarding - Add Liquid Stream Integration for iOS<>iOS Connections
             : Seed Vault - Integrate Use-Wallet v5 with cross-chain accounts
             : Onboarding - Integrate new algokit-crypto rust library (iOS/Android)
             : Liquid Stream - Implement new landscape Figma Liquid Stream app screens
             : Transaction - Upgrade to escrow MPP session standard for micro-billing (Android)
             : TBD
    Backlog
            : Onboarding - Rekey flow
            : Onboarding - Liquid Auth (Rekeyed Accounts)
            : Research - React Native sample app talking to service
            : Onboarding - PQ Passkeys
            : Onboarding - Ledger flow
            : Account Details - Asset Inbox
            : Onboarding - Fun app animations
            : Onboarding - Multi-sig flow
            : Settings - Localization (Chinese, Spanish, French, Portuguese, Japanese, Korean, German)
            : Seed Vault - Sign Liquid Auth using Algorand seed vault seeds
```

## Project structure

This repo has the following modules:

- **composeDemoApp**: A [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) sample wallet app that demonstrates `wallet-sdk` usage.  This app on Android also contains a [service](https://developer.android.com/develop/background-work/services) that provides a headless way for other android applications on the same device to share the same wallet-sdk-core wallet-sdk-ui instance (using AIDL).
- **iosDemoApp**: The iOS app for `composeSampleApp`. Open this module in Xcode if needed.
- **wallet-sdk-core**: The AlgoKit Wallet SDK core module - a headless wallet utils library built with [Kotlin Multiplatform](https://developer.android.com/kotlin/multiplatform). It provides foundational wallet functionality and is built on top of [AlgoKit-Core SDK](https://github.com/algorandfoundation/algokit-core), [Algo xHD Swift SDK](https://github.com/algorandfoundation/xHD-Wallet-API-swift), [Algo xHD Kotlin SDK](https://github.com/algorandfoundation/xHD-Wallet-API-kt), [Algo Java SDK](https://github.com/algorand/java-algorand-sdk), and [Algo Go SDK](https://github.com/perawallet/algorand-go-mobile-sdk).
- **wallet-sdk-ui**: The AlgoKit Wallet SDK UI module - an embedded wallet utils library built with [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform). This module extends wallet-sdk-core and provides ready-to-use UI components for developers who want an integrated wallet interface in their applications.
- **serviceDemoApp**: A secondary Android only (non-KMP) demo app that is able to test service functionality in composeDemoApp.

This project is developed using [Android Studio](https://developer.android.com/studio) (stable version) and the [Kotlin Multiplatform Plugin](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform). As a mobile development project, it is primarily developed on macOS, support for Windows and Linux is quite limited.  We also follow the [KMP compatibility guide](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-compatibility-guide.html).

## Screenshots

#### Running Screenshot Tests

**Record/Generate Reference Images:**

```bash
./gradlew :wallet-sdk-ui:executeScreenshotTests -Precord
```

This command will run all screenshot tests and save the captured screenshots as reference images.

```bash
./gradlew :wallet-sdk-ui:executeScreenshotTests -Pandroid.testInstrumentationRunnerArguments.class=com.michaeltchuang.walletsdk.ui.settings.screens.PasskeysScreenshotTest -Precord
```

This command will run screenshot tests for a single class and save the captured screenshots as reference images.

**Verify Screenshots:**

```bash
./gradlew :wallet-sdk-ui:executeScreenshotTests
```

This command will run the tests and compare current screenshots against the previously recorded
reference images. If there are differences, the test will fail and generate a comparison report.

**Screenshot Default Storage Location:**

- `wallet-sdk-ui/screenshots/debug/*.png`

> **Note:** Screenshot tests require an Android device or emulator to be connected.

### Sample App - Accounts List

#### Fetching All Accounts Flow

<img width="200" alt="Image" src="https://github.com/user-attachments/assets/d86ffc6a-7fa2-4f9c-8c45-9603911efb3e" />

### Wallet-SDK Screens

**Interactive Screenshot Viewer:**

View all Wallet SDK screens with an interactive gallery that allows you to filter by locale 
(English, Hindi, Italian), theme (Dark/Light), and view various user flows. [Open Screenshot Gallery →](https://michaeltchuangllc.github.io/algokit-walletsdk-kmp/screenshots/index.html)

## Architecture

# Database Schema

```mermaid
---
title: AlgoKitDatabase
---
erDiagram
    custom_account_info {
        String algo_address PK
        String custom_name
        Int order_index
        Boolean is_backed_up
    }
    custom_hd_seed_info {
        Int seed_id PK,FK
        String entropy_custom_name
        Int order_index
        Boolean is_backed_up
    }
    algo_25 {
        String algo_address PK
        ByteArray encrypted_secret_key
    }
    ledger_ble {
        String algo_address PK
        String device_mac_address
        Int account_index_in_ledger
        String bluetooth_name
    }
    no_auth {
        String algo_address PK
    }
    hd_seeds {
        Int seed_id PK
        ByteArray encrypted_entropy UK
        ByteArray encrypted_seed UK
    }
    falcon_24 {
        String algo_address PK
        Int seed_id FK
        ByteArray public_key UK
        ByteArray encrypted_secret_key
    }
    hd_keys {
        String algo_address PK
        ByteArray public_key UK
        ByteArray encrypted_private_key
        Int seed_id FK
        Int account
        Int change
        Int key_index
        Int derivation_type
    }
    sites {
        Long id PK
        String url UK
        String name
    }
    passkey_table {
        String credential_id PK
        Long site_id FK
        String address
        String user_id
        String user_name
        String user_display_name
        Long last_used_time_ms
    }
    seed_vault {
        String public_key PK
        String address
        String chainId
        String account_name
    }
    custom_hd_seed_info }|--|| hd_seeds : link
    hd_keys }|--|| hd_seeds : link
    falcon_24 }|--|| hd_seeds : link
    passkey_table }|--|| sites : link
```

## Contributing
Development happens in this open source repo for the AlgoKit Wallet SDK. Algorand community is always welcome to contribute by reviewing or opening new pull requests.

## Testing

This project is tested with BrowserStack (open source license).

For QR code importing, you can use a tool like [Cyber Chef](https://gchq.github.io/CyberChef/#recipe=Generate_QR_Code('PNG',5,4,'Medium')) to get QR codes online

### 24 word account

```json
{
  "mnemonic": "define claw hungry wave umbrella boost blind never muscle also grab gaze fluid echo predict describe turkey unaware dash phone urge crunch eyebrow abstract team"
}
```

### KeyReg offline (account address should exist on device)
```
algorand://ANUR5SYMURBFD3ELITINYNTHVAKKBCWJ7LGHJRPMQM3KQG25ENMIHYEBNY?type=keyreg
```

### Asset Opt-In (select account screen will pop up after scanning) - USDC (testnet - 10458941, mainnet - 31566704)
```
algorand://?amount=0&asset=10458941
```

### Algo Transfer (receiver address & amount in microAlgos)
```
algorand://7N54HZSGBRQF7FW6YNC6F5H42AT5OXN3F5OQDAXF6H6PDFHNXIEBCJFHOY?amount=1000000&note=1_ALGO_Transfer
```

### ASA Transfer (receiver address & amount in microAlgos) - USDC (testnet - 10458941, mainnet - 31566704)
```
algorand://7N54HZSGBRQF7FW6YNC6F5H42AT5OXN3F5OQDAXF6H6PDFHNXIEBCJFHOY?amount=1000000&asset=10458941&note=1_ALGO_Transfer
```

### Liquid Auth (sample uri)
```
liquid://michaeltchuang.ngrok.dev/?requestId=019c3ff0-70fd-7663-823b-2ce5bbc5fca6
```
