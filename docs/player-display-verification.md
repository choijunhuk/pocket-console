# Player fullscreen and layout verification

Source change: `a0d9e02`, 2026-11-15. Local QA used a separate API36 ARM64 emulator with 16KB pages; no physical phone was used.

| Check | Evidence |
| --- | --- |
| Automatic DS/3DS fit, native aspects, swap, single-screen bounds | PASS: dependency-free geometry tests, including compact and tablet bounds |
| Android Java, both native ABIs and debug APK | PASS: lintDebug, assembleDebug and assembleDebugAndroidTest |
| Real DS output, audio, malformed-file rejection, stale close | PASS |
| Fullscreen enlargement, single/swap, hidden lower-screen touch rejection | PASS on real DS player |
| Root-dispatched resized touch and two-pointer direction+A input | PASS, actual native frame becomes green while A is held |
| Back leaves fullscreen, held inputs clear, save/exit and fresh session | PASS |
| 360×640 and 640×360dp, font scale 1.3, light/dark | PASS: actual rendered captures and 48dp control-bound checks |
| 800×1280dp tablet and device rotation | PASS: same native/UI assertions after emulator display rotation; target36's large-screen policy ignores forced Activity orientation |
| 3DS control layout and error-path Back | PASS: separate-process UI without opening a ROM; this is not 3DS emulation acceptance |
| Source review and visual verdict | No remaining scoped blocker; final visual verdict 93/100 |
| Physical handset, gesture feel and legacy API26–29 runtime | NOT_RUN |

The signed alpha.3 release then passed all nine gates in [run 43700586438](https://github.com/choijunhuk/pocket-console/actions/runs/43700586438), including the new display instrumentation on the release APK. All six published assets were downloaded and independently verified. Android 3DS game execution remains separate from the UI-only check.

Tablet QA rotated the emulator's device state rather than opting out of [Android 16's large-screen orientation policy](https://developer.android.com/about/versions/16/behavior-changes-16). The earlier Activity-forced orientation probe timed out; the later device-rotation run passed without changing the app's orientation policy or pixel checks.

## Rendered evidence

These images use the project's original DS test program, not a commercial game.

- [Large lower display with upper preview, controls hidden](screenshots/player-fullscreen-large.png)
- [Single lower display](screenshots/player-fullscreen-single.png)
- [Normal portrait, dark theme, font scale 1.3](screenshots/player-layout-dark.png)

## Changed surfaces

`DualScreenLayout.java` shares display bounds across both screen views. `PlayerDisplayUi.java` shares fullscreen, safe controls, menus and per-system preferences. The two activities retain their existing emulation/save lifecycle. Fixed display-width caps and the full-width control tint were removed; no UI dependency was added.

Touch coordinates are transformed through the exact lower-screen rectangle, including letterboxing and source cropping. A hidden lower display rejects touch. Layout changes, detachment and rotation release pending screen input. Normal shell styling is retained; fullscreen uses a black, borderless viewport and a persistent menu escape.
