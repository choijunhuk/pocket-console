# DS alpha verification

Verified source: `2748a32bce2385c3824571f15b30ecdba3ebc4ce`, 2026-11-11.

[Passing CI run](https://github.com/choijunhuk/pocket-console/actions/runs/42995806854) has three successful jobs: `native-smoke`, `verify`, and `android-native-smoke`. Documentation-only commits after this revision do not change the tested app.

## Evidence

| Layer | Result | Scope |
| --- | --- | --- |
| Java model checks | PASS: 34 checks | 20 filename/console checks and 14 cache/header checks, including atomic copies, digest separation, failures and partial ARM words. |
| Real host DS core | PASS | Pinned melonDS boots our generated ARM9/ARM7 homebrew without proprietary BIOS. Expected blue video, A-button green video, nonzero stereo PCM, malformed-ROM rejection and stale-session cleanup. |
| Save-manager regression | PASS, after observed failure | Actual pinned SRAM manager loses immediate-close writes before the patch; patched manager closes/reopens the save with matching bytes. This is a manager-level regression, not game-driven EEPROM acceptance. |
| Android APK/lint | PASS | `lintDebug assembleDebug assembleDebugAndroidTest`, Java 17 / SDK 36 / NDK 27.1.12297006. |
| Packaged native libraries | PASS | `libpocket_ds.so`, `libretro.so`, `libc++_shared.so` in both arm64-v8a and x86_64. |
| Debug package/signature | PASS | `dev.pocketconsole`, version 0.2.0-ds-alpha (2), min SDK 26 / target 36, MainActivity launch entry, APK v2 signature valid. |
| Android native execution | PASS | Android API 35 x86_64 emulator executes the packaged JNI/core; visible video, input, nonzero PCM, malformed input and session-token tests pass. |
| Actual player UI | PASS | Launches DsPlayerActivity with the original homebrew; visible rendered output changes with A. All 12 controls are fully visible in portrait 360x640dp and landscape 640x360dp after rotation. |
| Screenshot inspection | OBSERVED | Actual emulator screenshot retrieved and inspected. Both LCD regions and the controls are visible. No supplied reference image exists for formal pixel-fidelity scoring. |
| Physical Android phone | NOT_RUN | Connected-device inventory was empty. ARM64 is built, not runtime-accepted on a physical phone. |
| Commercial games / game-driven save | NOT_RUN | No commercial game files were supplied or downloaded. Broad compatibility and a game's EEPROM save flow are unverified. |
| Touch gameplay / background-resume / sustained performance | NOT_RUN on hardware | Paths are implemented and source-reviewed; current generated homebrew does not consume stylus input or establish long-session/speaker-quality acceptance. |
| 3DS / Switch execution | NOT_IMPLEMENTED | Their library/preview surfaces remain; their execution cores are not bundled. |

## Artifacts

- [Debug APK and reports](https://github.com/choijunhuk/pocket-console/actions/runs/42995806854/artifacts/13843379014).
- [Android player log and screenshot](https://github.com/choijunhuk/pocket-console/actions/runs/42995806854/artifacts/13844127534).
- APK SHA-256: `984e76477b46348ced7a8fc4ab2de41ac0c457974b0b21f0831f44c5f0a51f1f` (APK checksum, not archive checksum).
- [Saved player screenshot](screenshots/ds-player-alpha.png). White/green output is the original smoke program, not a commercial game or a fake player frame.

Artifacts require access to this private repository. Debug signing is for development.

## Fixes proved during integration

- Fixed Android GNU `strerror_r` compilation against the selected NDK.
- Added a direct cartridge-header dependency to the core save-close patch.
- Reproduced immediate-close save loss; snapshot and synchronous flush fix it without advancing extra emulated frames.
- Reproduced byte-valid, word-unsafe ARM sections; both Java and C++ validators reject them.
- Preserve audio backpressure and surface negative AudioTrack results.
- Refresh test references after Android rebuilds the rotated view; retain visibility assertions and verify every control.
- Reserve landscape control width for two 3x48dp pads, allowing LCDs to use the remaining safe area.

Upstream native/deprecated-API and Actions deprecation warnings remain non-blocking. Local Gradle/ADB sockets and local submodule downloads remain sandbox-restricted; executable acceptance above ran in CI. No real phone, commercial compatibility, quick-save or completed three-system support is implied.
