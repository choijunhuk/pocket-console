# 3DS integration and handheld UI verification

Updated 2026-11-12. Source builds, emulator UI, and physical-device game compatibility are separate acceptance claims.

| Check | Current evidence |
| --- | --- |
| Pinned Azahar 2126.2 source | Exact source/archive/patch checks, traversal rejection and ABI packaging checks pass. |
| Android ARM64 and x86_64 native builds | Both source-built cores and APK/lint passed in [run 43042663688](https://github.com/choijunhuk/pocket-console/actions/runs/43042663688). |
| Real Linux 3DS execution | NOT_ACCEPTED: GLX runs the original guest through graphics initialization, input, fill, flush, swap and VBlank, but returned screen centers remain black. Failure-time image statistics and a default-surface probe locate the remaining render/readback boundary. Do not infer playable games from compilation. |
| DS Android controls and exit/reopen | PASS in [run 43048249131](https://github.com/choijunhuk/pocket-console/actions/runs/43048249131), including held input, actual frames, shutdown status before finish, and a fresh native session. The emulator action later timed out during teardown after PASS; CI cleanup containment is being verified. See [DS record](ds-verification.md). |
| Library light/dark/font 1.3 images | PASS for the captured 360×640dp viewport in [run 43044950424](https://github.com/choijunhuk/pocket-console/actions/runs/43044950424): actual library content is readable, with no horizontal clipping. See the screenshots below. This does not establish all viewport or physical-device acceptance. |
| System-bar contrast | A later light screenshot exposed stale white status icons on paper. Shared native window appearance is now applied after content attachment; fresh rendered confirmation is pending. |
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
- [APK, both test APKs and lint reports](https://github.com/choijunhuk/pocket-console/actions/runs/43044950424/artifacts/13866094923)

These captures are from the UI build in run 43048249131. The DS exit/reopen regression passed in that build. Visible keyboard/controller focus, physical haptics and further viewport acceptance remain separate checks.
