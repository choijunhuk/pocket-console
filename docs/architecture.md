# Architecture and staged core integration

## Current foundation

`MainActivity` owns library navigation and Android's document picker. `ConsolePreviewView` renders original handheld artwork; native controls report preview input. `ConsoleType` classifies filename extensions; it does not inspect, authenticate or execute file contents. `LibraryStore` saves independent metadata records in private SharedPreferences, keyed by document URI. Malformed records are preserved instead of rewriting the whole collection.

There is no native emulator library, JNI core adapter, firmware loader, CPU emulator, graphics translation layer or save-state feature in v0.1. The application has no Internet or broad storage permission.

The initial UI uses Android SDK Views and Java 17, without a UI runtime dependency. Rendering a future core directly into an Android Surface keeps its frame/audio loops separate from library and decorative UI. See Android's [native GameActivity/SurfaceView guidance](https://developer.android.com/games/agdk/game-activity/get-started).

## Engine candidates, verified from upstream on 2026-11-11

| System | Candidate | Integration evidence and boundary |
| --- | --- | --- |
| DS | [melonDS](https://github.com/melonDS-emu/melonDS), [Android port](https://github.com/rafaelvcaetano/melonDS-android) | GPLv3+ upstream; Android port documents SDK/NDK/CMake builds. Its third-party frontend Intent launches a separate app; embedding requires native integration. |
| 3DS | [Azahar](https://github.com/azahar-emu/azahar) | Android source and libretro builds exist. Check the chosen stable revision; 2126.2 includes a security fix, and 2127 RC changes GPLv2+ to GPLv3+. |
| Switch 1 | [Eden first-party source](https://git.eden-emu.dev/eden-emu/eden), [mirror/build docs](https://github.com/eden-emulator/mirror/blob/master/docs/build/Android.md) | C++, Android build, GPLv3+. Native integration is substantial; this is not a drop-in SDK. |

These are candidates, not bundled dependencies. No forks or native binaries have been added. Use a dedicated source/build module per core only when the first integration is actually implemented; do not invent a common API ahead of measured needs.

## First real emulation acceptance

For DS: build a selected revision, show an original homebrew frame and audio, verify simultaneous buttons and lower-screen touch mapping, then check pause/resume, rotation, screen-off, document permissions and game-internal save persistence. Compatibility and save-state support must be reported per core/version.

For 3DS: repeat with its dual-screen dimensions and graphics backend, including explicit error states for unsupported inputs.

For Switch: first prove a native Android build and original homebrew boot on a reference ARM64 phone. Define a compatibility matrix from measured device/GPU/driver results before advertising support. Eden's [platform guide](https://github.com/eden-emulator/mirror/blob/master/docs/user/Architectures.md) identifies GPU, memory and thermal constraints. Long sessions must be measured; Android [Thermal API](https://developer.android.com/games/optimize/adpf/thermal) describes why burst speed differs from sustained performance.

## Decisions still open

- Reference Android device and supported performance tier.
- Exact engine revisions and patch/update procedure.
- Core combination and source/license distribution strategy.
- Store distribution and public project license before release.
