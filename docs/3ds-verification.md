# 3DS integration and handheld UI verification

Updated 2026-11-12. Source builds, emulator UI, and physical-device game compatibility are separate acceptance claims.

| Check | Current evidence |
| --- | --- |
| Pinned Azahar 2126.2 source | Exact source/archive/patch checks, traversal rejection and ABI packaging checks pass. |
| Android ARM64 and x86_64 native builds | Both source-built cores and APK/lint passed at source `19565e3` in [run 43059115481](https://github.com/choijunhuk/pocket-console/actions/runs/43059115481). |
| Real Linux 3DS execution | NOT_ACCEPTED: GLX runs the original guest through graphics initialization, input, fill, flush, swap and VBlank, but returned screen centers remain black. Failure-time image statistics and a default-surface probe locate the remaining render/readback boundary. Do not infer playable games from compilation. |
| DS Android controls and exit/reopen | PASS in run 43059115481, including held input, actual frames, shutdown status before finish, and a fresh native session. Emulator cleanup completed normally. See [DS record](ds-verification.md). |
| Library light/dark/font 1.3 images | PASS for the captured 360×640dp viewport in run 43059115481: actual library content is readable, with no horizontal clipping. See the screenshots below. This does not establish all viewport or physical-device acceptance. |
| System-bar contrast | PASS for the final captures: the light status clock is dark on paper, dark mode uses light system foreground, and the DS player retains readable bars. Shared native appearance is applied after content attachment. |
| 3DS Android test registration | PASS in run 43044950424: separate DS/3DS test APK manifests contain the selected runners. 3DS execution remains skipped by the graphics prerequisite. |
| 3DS Android runtime | NOT_RUN on the GLES 3.0 CI emulator. Azahar's OpenGL path requires GLES 3.2. A declared sufficient version must still pass the unchanged native actual-context, colors, input, audio, and shutdown assertions. |
| Physical phone, game compatibility, save/relaunch | NOT_RUN. Galaxy S23 Ultra is the user's reference phone; no model whitelist or game compatibility promise is inferred. |
| Switch / Switch 2 | Hokkaido ARM64 upstream Android native build PASS in [run 43044388952](https://github.com/choijunhuk/pocket-console/actions/runs/43044388952), on a separate probe branch. No app runtime adapter or game compatibility proof. |

The Android harness records insufficient GLES as `THREEDS_ANDROID_SMOKE_NOT_RUN_GLES`, never a 3DS pass. Missing capability data is an error. UI/DS checks can pass independently; Android 3DS release acceptance still requires a suitable physical device.

The emulator graphics limit and Azahar requirement are documented by [Android](https://developer.android.com/studio/run/emulator-extended-controls) and the [pinned Azahar README](https://github.com/azahar-emu/azahar/blob/219d743a1e698101fe1ab8bef43e6563126c3c27/README.md). The Linux host test now uses exact, checksum-verified Khronos GL/platform headers and the runner's Mesa GLX libraries under Xvfb. The runner lacks EGL, and package mirrors and Canonical's snapshot service failed DNS resolution. The Android EGL/GLES path remains separate; the shared framebuffer and actual-output assertions are unchanged. Linux linked runtime acceptance is still pending.

## Rendered evidence

- [Light library](screenshots/library-light.png)
- [Dark library at font scale 1.3](screenshots/library-dark-font-130.png)
- [Refined DS player with A held](screenshots/ds-player-refined.png)
- [APK, both test APKs and lint reports](https://github.com/choijunhuk/pocket-console/actions/runs/43059115481/artifacts/13873686569)

These final captures and the DS exit/reopen regression are from source `19565e3` in run 43059115481. The overall workflow remains red because the separate real 3DS output smoke fails; APK/lint and Android UI/DS jobs pass.

## Handheld UX implementation

The shared styling in `ConsoleUi.java` is reused by `MainActivity.java`, `DsPlayerActivity.java`, and `ThreeDsPlayerActivity.java`. It supplies deliberate light/dark colors, pressed and focused controls, system-respecting haptics, and system-bar appearance. The library prioritizes import and titles, marks experimental execution, and labels revoked-file recovery honestly. The DS player keeps shutdown feedback until native close returns; cache cancellation preserves existing files.

Fresh local checks passed: 72 classification/capability/DS-cache checks, 33 3DS-cache checks, 22 Python preparation/capture/cleanup checks, Android Java compilation, and independent delta reviews. Rendered keyboard/controller focus, physical haptics, further viewports and filesystem durability remain separate acceptance checks.
