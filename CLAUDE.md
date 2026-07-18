# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is **Chest Tracker (Unofficial port)**, a client-sided Fabric mod for Minecraft that remembers item locations across storage containers (chests, shulker boxes, barrels, etc.), works on realms/multiplayer, and integrates with mods like REI/JEI/EMI (via bundled "Where Is It"), Shulker Box Tooltip, WTHIT, Jade, and Litematica. The Gradle module/group is `red.jackf`, root project name `chesttracker`. This is an unofficial continuation of the original `chest-tracker` mod (original author JackFred), now maintained by `ponuing`.

## Build System

Built with **Fabric Loom** (Kotlin DSL Gradle). Source sets are split via `loom.splitEnvironmentSourceSets()` into `src/client` (this is a client-only mod — there is no `src/main`).

Common commands (use `./gradlew` on Linux/macOS, `gradlew.bat` on Windows):
- `./gradlew build` — full build (compiles, runs checks, produces jar)
- `./gradlew check` — run checks (used by CI; there is currently no dedicated unit test suite)
- `./gradlew runClient` — launch a dev Minecraft client with the mod loaded (Loom run config; username defaults to `JackFred`)
- `./gradlew genSources` — decompile/generate Minecraft sources for IDE navigation, if needed
- `./gradlew updateModDependencies` — custom task (`buildSrc`) that bumps the `# JF_AUTO_UPDATE_BLOCK` versions in `gradle.properties` for the target Minecraft version

There are no unit tests in this repo currently (no `src/test`); "build & run tests" in CI just runs `./gradlew check build`.

### Versioning & dependencies
- All mod/dependency versions live in `gradle.properties` (Minecraft version, loader version, mod version, and per-integration versions like `yacl_version`, `wthit_version`, `jade_version`, etc.). Update versions there, not inline in `build.gradle.kts`.
- `bundle_searchables=true` in `gradle.properties` controls whether the Searchables library is embedded in the jar (`include(...)`) vs. treated as a normal soft dependency.
- The branch name (e.g. `26.2`) tracks the targeted Minecraft version; the main/default branch follows the current Minecraft version.
- CI (`.github/workflows/build.yml`) builds on Java 25 across Ubuntu and Windows for every push/PR.
- Releases (`.github/workflows/publish.yml` + `mod-publish-plugin` / `github-release` plugin in `build.gradle.kts`) publish to Modrinth/CurseForge/GitHub Releases and generate changelogs from `changelogs/<mod_version>.md` plus commit messages tagged with `[feat]`, `[fix]`, `[change]`, `[update]`, `[docs]`, `[translations]` (see `changelog_filter` in `gradle.properties`). Commit message prefixes should match these tags when relevant, since they drive changelog generation.

## Architecture

### API vs. impl split
Code lives under `src/client/java/red/jackf/chesttracker/`, split into two packages with very different stability guarantees:
- **`api/`** — the public, experimental extension API (`@ApiStatus.Experimental`) that other mods use to integrate with Chest Tracker (e.g. custom `ServerProvider`s, memory access, GUI hooks). Changes here are breaking changes for downstream integrations.
- **`impl/`** — all internal implementation. Not part of the public contract; free to refactor.

Other mods integrate by implementing `red.jackf.chesttracker.api.ChestTrackerPlugin` and registering it under the `chesttracker` entrypoint key in their `fabric.mod.json` (see `DefaultChestTrackerPlugin` for this mod's own built-in registrations, and `impl/compat/mods/` for first-party integrations with other mods).

### Providers (the core extensibility mechanism)
A **`ServerProvider`** (`api/providers/ServerProvider.java`) decides *when and how* to record memories — e.g. the default provider triggers on screen open/close and block placement, while `impl/compat/servers/hypixel/` has custom providers for Hypixel Skyblock/SMP quirks. `impl/providers/ProviderHandler` tracks the "current provider" (first one that claims applicability) and dispatches lifecycle events (`ScreenOpenContext`, `ScreenCloseContext`, `BlockPlacedContext`) to it. A `MemoryBuilder` is used by providers to construct what gets remembered.

### Memory model
- A **`MemoryBank`** (`api/memory/MemoryBank.java`, impl in `impl/memory/MemoryBankImpl.java`) is the top-level save container — roughly one per world/server, holding all remembered container contents.
- A **`MemoryKey`** (`impl/memory/MemoryKeyImpl.java`) scopes memories to a dimension/world context; each key holds positions mapped to `Memory` (item contents) plus per-position `OverrideInfo` (custom names, manual overrides).
- `MemoryBankAccessImpl` is the singleton access point for the currently-loaded bank.
- `Metadata` and its `*Settings` classes (`FilteringSettings`, `SearchSettings`, `VisualSettings`, `CompatibilitySettings`, `IntegritySettings`) hold per-bank configuration persisted alongside memory data.
- `MemoryIntegrity` handles periodic/consistency checks on stored memory data; `impl/datafix/` (with `mixins/datafix/V1460Mixin.java`) handles migrating old save data forward across format changes.

### Storage backends
`impl/storage/Storage` is the entry point wiring up persistence; `impl/storage/backend/` has multiple `Backend` implementations — `JsonBackend`, `NbtBackend`, and `GameMemoryBackend` (in-memory, e.g. for singleplayer/no-save scenarios) — sharing common logic via `FileBasedBackend`. Both `JsonBackend` and `NbtBackend` do async saves; `ChestTracker.onInitializeClient()` waits for pending saves on `ServerLifecycleEvents.SERVER_STOPPING` to avoid data loss. `GlobalMemoryBankDefaults` and `ConnectionSettings` are loaded at startup and control default settings applied to new banks / per-server connection behavior.

### GUI
`impl/gui/screen/ChestTrackerScreen` is the main search/browse GUI (opened via the grave key `` ` ``, bound as `OPEN_GUI` in `ChestTracker`). `impl/gui/invbutton/` implements the in-inventory quick-access button (with its own position persistence in `ButtonPositionMap`, supporting datapack-supplied default positions). `impl/gui/widget/` and `impl/gui/util/` hold shared widgets/helpers. Config screens are built with YACL (Yet Another Config Lib) in `impl/config/ChestTrackerConfigScreenBuilder`.

### Mixins & access widening
Mixins live in `src/client/java/red/jackf/chesttracker/mixins/` (registered in `src/client/resources/chesttracker.mixins.json`), used to hook into vanilla screens/rendering (`ScreenMixin`, `AbstractContainerScreenMixin`, `LevelRendererMixin` for in-world name rendering, `BlockMixin`/`BlockItemMixin` for placement tracking) and into third-party mods (`mixins/compat/litematica/`). Additional field/method access is granted via `src/client/resources/chesttracker.accesswidener`. When adding behavior that vanilla or a compat mod doesn't expose a hook for, check here first before reaching for reflection.

### Third-party mod & server compatibility
`impl/compat/mods/` contains per-mod integration code (ModMenu, Where Is It, Shulker Box Tooltip, WTHIT, Jade, Litematica, Searchables), each gated behind `compileOnly`/optional dependencies in `build.gradle.kts` — these must handle the integrated mod being absent at runtime. `impl/compat/servers/hypixel/` contains server-specific behavior overrides (Skyblock private island handling, SMP). `impl/compat/Compatibility.java` is the central registry deciding which integrations are active.

### Darkmode / resource packs
`darkmode/` (source) and `src/client/resources/resourcepacks/darkmode_texture/` plus `src/client/resources/high_contrast/` provide built-in optional resource packs registered via `ResourceLoader.registerBuiltinPack` in `ChestTracker.onInitializeClient()`.

### Localization
Translations are managed via Crowdin (`crowdin.yml`); don't hand-edit non-English language files under `assets/chesttracker/lang/` if avoidable — expect them to be overwritten by the Crowdin sync.