# 3DS integration and handheld UI verification

Updated 2026-11-12. Source builds, emulator UI, and physical-device game compatibility are separate acceptance claims.

| Check | Current evidence |
| --- | --- |
| Pinned Azahar 2126.2 source | Exact source/archive/patch checks, traversal rejection and ABI packaging checks pass. |
| Android ARM64 and x86_64 native builds | Both source-built cores and APK/lint passed in [run 43042663688](https://github.com/choijunhuk/pocket-console/actions/runs/43042663688). |
| Real Linux 3DS execution | NOT_ACCEPTED: original guest did not reach expected colors in run 43040784902. Subsequent diagnostics were blocked by graphics package mirror timeouts. Do not infer playable games from compilation. |
| DS Android controls | PASS in run 43040784902, including held input and actual native frames. See [DS record](ds-verification.md). |
| Library light/dark/font 1.3 images | Prior images captured the splash screen and were rejected. The capture now waits for both the library title and import action; fresh visual inspection is still required. |
| 3DS Android test registration | AGP runner selection now produces separate DS/3DS test APKs. CI verifies each APK manifest before installation. Fresh generation/execution is pending. |
| 3DS Android runtime | NOT_RUN on the GLES 3.0 CI emulator. Azahar's OpenGL path requires GLES 3.2. A declared sufficient version must still pass the unchanged native actual-context, colors, input, audio, and shutdown assertions. |
| Physical phone, game compatibility, save/relaunch | NOT_RUN. Galaxy S23 Ultra is the user's reference phone; no model whitelist or game compatibility promise is inferred. |
| Switch / Switch 2 | Separate source-build probe branch only; no app runtime adapter or game compatibility proof. |

The Android harness records insufficient GLES as `THREEDS_ANDROID_SMOKE_NOT_RUN_GLES`, never a 3DS pass. Missing capability data is an error. UI/DS checks can pass independently; Android 3DS release acceptance still requires a suitable physical device.

The emulator graphics limit and Azahar requirement are documented by [Android](https://developer.android.com/studio/run/emulator-extended-controls) and the [pinned Azahar README](https://github.com/azahar-emu/azahar/blob/219d743a1e698101fe1ab8bef43e6563126c3c27/README.md). Host graphics dependencies use Canonical's signed [Ubuntu snapshot service](https://snapshot.ubuntu.com/); snapshot reachability is a CI prerequisite, not runtime proof.
