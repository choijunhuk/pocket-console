# Architecture and staged core integration

## Current DS alpha

`MainActivity` owns library navigation and Android's document picker. `ConsolePreviewView` renders original handheld artwork; native controls report preview input. `ConsoleType` classifies filename extensions; it does not inspect, authenticate or execute file contents. `LibraryStore` saves independent metadata records in private SharedPreferences, keyed by document URI. Malformed records are preserved instead of rewriting the whole collection.

DS entries now launch `DsPlayerActivity`. `DsRomCache` copies the selected document into a bounded, atomic SHA-256-addressed private cache, checking executable-section bounds. `DsNative` calls the C++ `DsEngine` adapter and pinned libretro/melonDS. The core emits 256x384 software video and 32768Hz stereo audio; `DsScreenView` presents both displays and maps the actual touch-screen rectangle. A serial session owner handles pause/resume/close; native tokens reject stale work. The application has no Internet or broad storage permission.

System BIOS files are not bundled. The pinned core's FreeBIOS/direct-boot path is used. DSi, quick saves, multiplayer, 3DS execution and Switch execution remain outside this milestone.

The initial UI uses Android SDK Views and Java 17, without a UI runtime dependency. Rendering a future core directly into an Android Surface keeps its frame/audio loops separate from library and decorative UI. See Android's [native GameActivity/SurfaceView guidance](https://developer.android.com/games/agdk/game-activity/get-started).

## Engine candidates, verified from upstream on 2026-11-11

| System | Candidate | Integration evidence and boundary |
| --- | --- | --- |
| DS | [melonDS](https://github.com/melonDS-emu/melonDS), [Android port](https://github.com/rafaelvcaetano/melonDS-android) | GPLv3+ upstream; Android port documents SDK/NDK/CMake builds. Its third-party frontend Intent launches a separate app; embedding requires native integration. |
| 3DS | [Azahar](https://github.com/azahar-emu/azahar) | Android source and libretro builds exist. Check the chosen stable revision; 2126.2 includes a security fix, and 2127 RC changes GPLv2+ to GPLv3+. |
| Switch 1 | [Eden first-party source](https://git.eden-emu.dev/eden-emu/eden), [mirror/build docs](https://github.com/eden-emulator/mirror/blob/master/docs/build/Android.md) | C++, Android build, GPLv3+. Native integration is substantial; this is not a drop-in SDK. |

DS now embeds `libretro/melonDS@66b5d2634cd0a79030562811e6e05f5532f800ba`; the modern melonDS/Android-port entries remain background options. 3DS and Switch are still candidates, not bundled dependencies. No common multi-core abstraction has been invented ahead of actual integration.

## DS source and persistence boundaries

The core is a pinned git submodule. `scripts/prepare-core.py` verifies its commit and reviewed source hashes before applying tracked patches. The patch is part of the corresponding source: it fixes close-time SRAM persistence and the Android GNU `strerror_r` build path. Both CI and Gradle's native build require preparation; drift stops the build.

Game-internal saves live under app-private `files/ds-saves/<ROM-SHA-256>.sav`. They do not share the disposable ROM cache directory. Closing a player must snapshot dirty cartridge memory and synchronously flush pending SRAM before destruction; waiting out the upstream two-second debounce is not an acceptable close strategy.

Java and C++ loaders check ARM executable bounds and word alignment before reaching the native loader. The native boundary additionally protects the core's fixed-size header/banner reads. Filename extensions alone never establish validity or compatibility.

## Validation levels

Dependency-free Java checks cover file classification and atomic cache behavior. Native smoke builds the real pinned core and runs original generated ARM9/ARM7 homebrew, proving visible pixels, A input and nonzero audio. A separate save-manager close/reopen regression exercises the real upstream persistence code before and after the tracked patch. Android instrumentation loads packaged native code and exercises the actual player and orientation layouts. These do not substitute for physical ARM64 phone performance, actual speaker quality or broad commercial-game compatibility.

## First real emulation acceptance

For DS: build a selected revision, show an original homebrew frame and audio, verify simultaneous buttons and lower-screen touch mapping, then check pause/resume, rotation, screen-off, document permissions and game-internal save persistence. Compatibility and save-state support must be reported per core/version.

For 3DS: repeat with its dual-screen dimensions and graphics backend, including explicit error states for unsupported inputs.

For Switch: first prove a native Android build and original homebrew boot on a reference ARM64 phone. Define a compatibility matrix from measured device/GPU/driver results before advertising support. Eden's [platform guide](https://github.com/eden-emulator/mirror/blob/master/docs/user/Architectures.md) identifies GPU, memory and thermal constraints. Long sessions must be measured; Android [Thermal API](https://developer.android.com/games/optimize/adpf/thermal) describes why burst speed differs from sustained performance.

## Decisions still open

- Reference Android device and supported performance tier.
- Exact engine revisions and patch/update procedure.
- Core combination and source/license distribution strategy.
- Store distribution and public project license before release.
