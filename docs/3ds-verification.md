# 3DS integration and handheld UI verification

Updated 2026-11-15. Source builds, emulator UI, and physical-device game compatibility are separate acceptance claims.

| Check | Current evidence |
| --- | --- |
| Pinned Azahar 2126.2 source | Exact source/archive/patch checks, traversal rejection and ABI packaging checks pass. |
| Android ARM64 and x86_64 native builds | PASS at source `b481835` in [run 43238203790](https://github.com/choijunhuk/pocket-console/actions/runs/43238203790), including both source-built cores, APK and lint. |
| Real Linux 3DS execution | PASS with both the checksum-verified official core and the pinned source diagnostic build in run 43238203790. The unchanged original-homebrew assertions verify blue/red screens, A, touch, Circle Pad, stereo audio and stale-close/reopen behavior. This is host runtime evidence, not Android game compatibility. |
| DS Android controls and exit/reopen | PASS in run 43238203790, including held input, actual frames, shutdown status before finish, and a fresh native session. See [DS record](ds-verification.md) for the earlier baseline. |
| Library light/dark/font 1.3 images | PASS for the captured 360×640dp viewport in run 43059115481: actual library content is readable, with no horizontal clipping. See the screenshots below. This does not establish all viewport or physical-device acceptance. |
| System-bar contrast | PASS for the final captures: the light status clock is dark on paper, dark mode uses light system foreground, and the DS player retains readable bars. Shared native appearance is applied after content attachment. |
| 3DS Android test registration | PASS in run 43044950424: separate DS/3DS test APK manifests contain the selected runners. 3DS execution remains skipped by the graphics prerequisite. |
| 3DS Android runtime | NOT_RUN on the GLES 3.0 CI emulator in run 43238203790. Azahar's OpenGL path requires GLES 3.2. A declared sufficient version must still pass the unchanged native actual-context, colors, input, audio, and shutdown assertions. |
| Physical phone, game compatibility, save/relaunch | NOT_RUN. Galaxy S23 Ultra is the user's reference phone; no model whitelist or game compatibility promise is inferred. |
| Switch / Switch 2 | Separate experimental branches: the Pocket Console Hokkaido ARM64 SDK and runtime dependency closure passed [run 43235268180](https://github.com/choijunhuk/pocket-console/actions/runs/43235268180). Eden adapter verification continues. These engines are not connected to the released app; no device or game compatibility claim. |

The Android harness records insufficient GLES as `THREEDS_ANDROID_SMOKE_NOT_RUN_GLES`, never a 3DS pass. Missing capability data is an error. UI/DS checks can pass independently; Android 3DS release acceptance still requires a suitable physical device.

The emulator graphics limit and Azahar requirement are documented by [Android](https://developer.android.com/studio/run/emulator-extended-controls) and the [pinned Azahar README](https://github.com/azahar-emu/azahar/blob/219d743a1e698101fe1ab8bef43e6563126c3c27/README.md). The Linux host test uses checksum-verified Khronos GL/platform headers and Mesa GLX under Xvfb. The black frame was fixed by omitting an unrequested depth/stencil attachment from the frontend FBO. The core's selected GPU textures were already colored; its leaked depth-test state could reject later presenter fragments against the added depth buffer. Both host binaries now pass the original output assertions. The Android EGL/GLES path shares this FBO change but still requires capable-device runtime acceptance.

## Rendered evidence

- [Light library](screenshots/library-light.png)
- [Dark library at font scale 1.3](screenshots/library-dark-font-130.png)
- [Refined DS player with A held](screenshots/ds-player-refined.png)
- [APK, both test APKs and lint reports](https://github.com/choijunhuk/pocket-console/actions/runs/43059115481/artifacts/13873686569)

These archived captures are from source `19565e3` in run 43059115481. The newer full workflow at `b481835` is green, including both real 3DS host jobs and Android UI/DS checks; Android 3DS remains explicitly NOT_RUN.

## Handheld UX implementation

The shared styling in `ConsoleUi.java` is reused by `MainActivity.java`, `DsPlayerActivity.java`, and `ThreeDsPlayerActivity.java`. It supplies deliberate light/dark colors, pressed and focused controls, system-respecting haptics, and system-bar appearance. The library prioritizes import and titles, marks experimental execution, and labels revoked-file recovery honestly. The DS player keeps shutdown feedback until native close returns; cache cancellation preserves existing files.

Fresh local checks passed: 44 classification, 14 assumed capability profiles, 20 DS-cache and 33 3DS-cache checks, plus 31 Python preparation/capture/release checks. Android Java/native compilation and independent delta reviews also passed. Rendered keyboard/controller focus, physical haptics, further viewports and filesystem durability remain separate acceptance checks.
