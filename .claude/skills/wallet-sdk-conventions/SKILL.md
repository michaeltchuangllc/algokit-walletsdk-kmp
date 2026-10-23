---
name: wallet-sdk-conventions
description: Shared coding conventions for the AlgoKit Wallet SDK repo. Use before writing or changing Kotlin, Compose UI, ViewModels, use cases, repositories, Koin modules or Room code in wallet-sdk-core, wallet-sdk-ui or the demo apps. Covers the MVI + clean architecture + Compose patterns, handling of secrets, tests, lint and commit style.
---

# Wallet SDK conventions

Follow the patterns that are already in the code. When in doubt, open a neighbouring file and copy its shape. Do not introduce a new library or pattern without a reason.

## Modules

| Module | What it is |
|---|---|
| `wallet-sdk-core` | Headless KMP library (`commonMain` + `androidMain` / `iosMain` with `expect`/`actual`). Accounts, signing, encryption, network. |
| `wallet-sdk-ui` | Compose Multiplatform screens on top of core, `AlgoKitTheme` design system. |
| `sharedDemoApp`, `androidDemoApp`, `iosDemoApp` | Demo app (shared UI, Android shell, iOS shell). |

Core must not depend on UI. Apps may depend on core, and on UI when they want the ready-made screens.

## Architecture: clean architecture, three layers

```
ui/        screens (Compose) + ViewModels         -> depends on domain
domain/    models, repository interfaces, use cases -> depends on nothing
data/      repository impls, Room, network          -> implements domain
di/        Koin module that wires the layers
```

- Dependencies point inward: `ui -> domain <- data`. `domain` never imports Android, Compose, Room or Ktor.
- **Use cases are `fun interface`s** with a single `operator fun invoke`, declared together in one `...UseCases.kt` file. Implementations are `internal class XUseCase : X` only when they have real logic; otherwise wire a repository method reference in Koin (`factory { AddSeed(get<SeedVaultRepository>()::addSeed) }`).
- **Repositories**: interface in `domain/repository`, implementation `internal class XRepositoryImpl` in `data/repository`. Take a `CoroutineDispatcher = Dispatchers.IO` constructor parameter and wrap blocking work in `withContext`.
- Room entities and DAOs are `internal` and stay in `data/database`. Map to domain models at the repository boundary; never expose an entity to the UI.
- Keep display models free of secrets (no seed phrase, private key or entropy in a model that reaches the UI).

## MVI in ViewModels

State flows down, one-time events flow separately, user actions are ViewModel functions. Use the delegates from `wallet-sdk-core` (`core.foundation`), do not hand-roll `MutableStateFlow`s:

```kotlin
class FooViewModel(
    private val getFoo: GetFoo,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<FooViewModel.ViewState> by stateDelegate,
    EventViewModel<FooViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        viewModelScope.launch { /* load, then stateDelegate.updateState { ViewState.Content(...) } */ }
    }

    fun onSave() {                       // user action = public fun named onX
        stateDelegate.onState<ViewState.Content> { content ->
            if (content.isSaving) return@onState          // guard double taps
            stateDelegate.updateState { content.copy(isSaving = true) }
            viewModelScope.launch {
                runCatching { save(content) }
                    .onSuccess { eventDelegate.sendEvent(ViewEvent.Saved) }
                    .onFailure {
                        stateDelegate.updateState { content.copy(isSaving = false) }
                        eventDelegate.sendEvent(ViewEvent.ShowError)
                    }
            }
        }
    }

    sealed interface ViewState { data object Loading : ViewState; data class Content(...) : ViewState }
    sealed interface ViewEvent { data object Saved : ViewEvent; data object ShowError : ViewEvent }
}
```

- `ViewState` is a sealed interface (`Loading`, `Content`, ...), immutable data classes, derived values as properties (`val canSave get() = ...`).
- `ViewEvent` is for one-shot effects only: navigate, toast, close. Never store something the screen needs to redraw in an event.
- Updating state always goes through `updateState { copy(...) }`. Handle failures: reset the busy flag and send an error event.
- Scope a ViewModel that holds secrets to its screen (per back stack entry) so they are dropped when the screen is popped.
- Register ViewModels in the module with `viewModel { ... StateDelegate() ... EventDelegate() }`; pass screen arguments with `koinViewModel { parametersOf(id) }`.

## Compose

- Every screen is a pair: a **stateful** `FooScreen(onBack, modifier, viewModel = koinViewModel())` that collects state with `collectAsStateWithLifecycle()` and events in a `LaunchedEffect(Unit)`, and a **stateless** `private fun FooContent(state..., onX: () -> Unit, modifier)` that only renders. Add a `@Preview` for the Content composable.
- Stateless composables take data and lambdas, hoist state, and take a `modifier: Modifier = Modifier` as the first optional parameter.
- Reuse the module's own components and theme (`AlgoKitTheme` and its widgets in `wallet-sdk-ui`). No hard-coded colors or text styles.
- User-visible text goes in string resources (`Res.string.*` with `localizedStringResource` in `wallet-sdk-ui`, `R.string.*` in Android-only modules). In Android XML strings escape apostrophes (`\'`).
- `wallet-sdk-ui` screens have screenshot tests; if you change one, record again (see Commands).

## Secrets

- Never log, persist in plain text, or pass to the UI a seed phrase, entropy, seed or private key.
- Keep secrets in `ByteArray`s and zero them (`fill(0)`) in a `finally` right after use.
- Encrypt stored secrets with the wallet-sdk-core Android Keystore encryption (`encryptByteArray` / `decryptByteArray`). Never invent your own crypto.

## Testing

- ViewModel tests extend `ViewModelTest` (StandardTestDispatcher, `collectEvents`, `stateDelegate()`/`eventDelegate()` helpers) and use in-memory fakes, not mocking frameworks. Run with `runTest { ... advanceUntilIdle() }`.
- One behavior per test, named for the behavior (`allowFailureShowsErrorAndAllowsRetry`).
- Code that needs a native library (Falcon, algokit composer) cannot run in plain unit tests; put an interface in front of it and fake the interface.

## Gradle, lint, Room

- Format and check before finishing: `./gradlew :<module>:ktlintFormat` then `:<module>:ktlintCheck`. Function/file/package naming rules are relaxed in `.editorconfig`; everything else is enforced.
- Tests: `./gradlew :<module>:testDebugUnitTest`, repo-wide `./gradlew test`. Screenshot tests: `./gradlew :wallet-sdk-ui:executeScreenshotTests` (add `-Precord` to re-record; needs a device).
- Room schemas are exported to `<module>/schemas/`. When an entity changes, commit the regenerated JSON. Android Studio installs keep an app's data, so after changing an unreleased schema, uninstall the app on the device or Room will refuse the old file with an identity-hash error.

## Style

- Kotlin official style, 4 spaces, trailing commas, `internal` by default for implementation classes, `data object` for singleton states/events.
- Comments explain why, not what; keep them short. KDoc on public interfaces and non-obvious classes.
- Match the surrounding file for naming and comment density.

## Commits and PRs

- Conventional commit prefixes, lower case: `feat:`, `fix:`, `refactor:`, `ci:`, `docs:`, `test:`. One logical change per commit.
- CI runs `./gradlew test` and `./gradlew lint`; both must pass.
- Contributors sign the CLA through the CLA bot.
