# Design

## Authority and direction

- Status: active design contract for the Android app; device review pending. Refreshed 2026-11-11.
- Purpose: make a player's own local games easy to find and launch, then make touch play feel like holding a compact console.
- Tone: quiet, warm, tactile hardware. The memorable detail is a pair of correctly proportioned inset LCDs held by a hinge and restrained physical control feedback.
- Preserve the current warm paper, gray shell, LCD green, and orange world. `docs/screenshots/ds-player-alpha.png` is the rendered DS alpha reference, not proof of final visual quality.
- Use original lettering, artwork, and sounds. Hardware proportions may inspire the app; do not copy console logos or artwork.

## Product truth

- Target: one local Android library with four selected systems: DS, 3DS, Switch, Switch 2. All four catalog choices are implemented on the active branch.
- DS is the currently integrated player. Its two 256 × 192 screens, touch, buttons, audio, and existing save path must keep working.
- 3DS execution is in development. Missing packages show **엔진 준비 중**; a packaged core on a device meeting prerequisites shows **실험적 실행 · 호환성 미확인**. Reserve unqualified readiness for actual device acceptance. Its player uses a 400 × 240 upper screen and 320 × 240 lower touch screen.
- Switch execution is pending. Switch 2 is a separate, experimental catalog target; Hokkaido is only a candidate backend. A filename or controller preview never establishes executable support.
- Readiness is per system and device: **실행 가능**, **엔진 준비 중**, **기기 조건 미충족**, or **실험적 지원**. State the specific unmet API, ABI, graphics, or memory prerequisite when known. Never gate by phone model or promise a frame rate or game compatibility from prerequisites alone.
- Galaxy S23 Ultra is a reference handset for review, not a device whitelist. Other capable Android phones should work.
- No accounts, ROM downloads, firmware/key distribution, fake game frames, or quick-save claims. Imported files stay local; save data lives in app-private storage.

## Primary flow

1. Open the library. The selected system, its honest readiness, **파일 가져오기**, and imported titles are visible before the controller preview.
2. Import with Android's document picker. Show checking, success, duplicate, unsupported format, unreadable file, and revoked-access outcomes without erasing existing entries.
3. Tap an executable title to play. A pending title explains why it cannot launch and offers **조작 미리보기** as a secondary action. Never present preview as gameplay.
4. On return, show a recent title only when an actual last-opened record exists. Do not infer play history from import order. Preserve selected system and library state.
5. When leaving play, indicate normal shutdown/save finalization while it happens. Report completion only after it succeeds; give a recoverable error if it fails. This is regular in-game save handling, not a quick save.

## Visual system

- Base tokens: paper `#F3F0E8`, ink `#202826`, shell `#DADDD5`, LCD `#C8D6A3`. Action orange is `#A34D31` with white text in light mode and `#EF9B79` with ink text in dark mode. Use semantic surface/text/action/status roles; do not merely invert colors. Check body text at 4.5:1 and large text at 3:1 in both schemes.
- Android Views and Java 17, with no new UI runtime dependency. Keep legible system `sp` text and reserve original lettering for the Pocket Console mark. Let typographic scale, weight, and spacing carry hierarchy instead of decorative all-caps labels.
- Use an 8 dp spacing rhythm and 20–24 dp library edges. The library should read as a list of cartridges beside one primary import action, not a stack of equally weighted cards. A small hardware illustration can identify the selected console after the list.
- The game display is the focal plane. Frame real output inside a dark inset LCD with a subtle bevel, hinge line, and speaker detail. Do not tint or animate over game pixels. Use one short depression/release treatment on button presses; optional haptic tick follows system settings.
- DS keeps equal 4:3 screens. 3DS keeps the top screen 5:3 and the lower screen 4:3, with the lower width at 80% of the upper at the same scale. Use a circle pad for 3DS and touch on its lower screen. Switch and Switch 2 use a single landscape display with differentiated side grips in previews only.
- Native control semantics take precedence over sculpture: minimum 48 × 48 dp targets, 8 dp separation where possible, clear selected/pressed/focus/disabled states, descriptive Korean accessibility names, and visible text alongside color-coded readiness.

## Adaptive behavior

- Apply status bar, navigation bar, cutout, and IME insets. Honor system Back and predictive Back. Content can scroll on compact screens; no screen or required control may be clipped.
- Check portrait 360 × 640 dp, landscape 640 × 360 dp, a tablet layout, and a split window. The DS and 3DS players prioritize screens plus reachable controls in portrait; landscape places controls alongside screens where space allows. Constrain shell width on tablets instead of stretching buttons.
- Check font scale 1.3, dark theme, focus order, screen-reader labels, and reduced animations. Keep the player operable with simultaneous directional and face-button input.

## Screen and state contract

- Library: selected system → readiness → import/recent (when real) → title list → controller preview. Empty state points directly to import. A title row says **실행** only when that system can open it on this device.
- Player: compact Back/exit, a live status line, correctly scaled displays, then controls. Loading identifies the phase; failure names the problem and a next action. Unsupported device, unreadable URI, encrypted/unsupported game, graphics failure, and native load failure have distinct copy.
- Preview: explicitly labeled **조작 미리보기**, responsive to presses, and escapable with Android Back. It does not show a fabricated game frame.
- Use concise Korean action labels. Confirm destructive removal of a library entry and clarify that it removes the catalog reference, not the original file.

## Evidence and implementation boundary

- Existing Android Views code supplies the warm palette, console preview, file catalog, and DS player. `docs/design/handheld-surfaces.md` gives the surface handoff. The revised hierarchy and dark scheme are implemented on the active branch; fresh rendered and physical-device acceptance remain separate from implementation.
- Verify the actual imported-file-to-player path, DS regression, 3DS homebrew output when packaged, error recovery, and device layouts separately. A build or screenshot alone does not establish device compatibility.
