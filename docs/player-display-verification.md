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
| 3DS control layout and error-path Back | PASS: separate-process UI without opening a ROM; this is not 3DS emulation acceptance |
| Source review and visual verdict | No remaining scoped blocker; final visual verdict 93/100 |
| Physical handset, gesture feel and legacy API26–29 runtime | NOT_RUN |

## Rendered evidence

These images use the project's original DS test program, not a commercial game.

- [Large lower display with upper preview, controls hidden](screenshots/player-fullscreen-large.png)
- [Single lower display](screenshots/player-fullscreen-single.png)
- [Normal portrait, dark theme, font scale 1.3](screenshots/player-layout-dark.png)

## Changed surfaces

`DualScreenLayout.java` shares display bounds across both screen views. `PlayerDisplayUi.java` shares fullscreen, safe controls, menus and per-system preferences. The two activities retain their existing emulation/save lifecycle. Fixed display-width caps and the full-width control tint were removed; no UI dependency was added.

Touch coordinates are transformed through the exact lower-screen rectangle, including letterboxing and source cropping. A hidden lower display rejects touch. Layout changes, detachment and rotation release pending screen input. Normal shell styling is retained; fullscreen uses a black, borderless viewport and a persistent menu escape.
