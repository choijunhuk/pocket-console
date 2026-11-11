# Design

## Source of truth
- Status: Active; initial direction, pending device review.
- Last refreshed: 2026-11-11.
- Primary surfaces: Android library, console selection, console controller preview, future gameplay.
- Evidence: user requests Android, DS + 3DS + Switch, and the feeling of using a physical Nintendo handheld. Specific colors and app name below are proposals.

## Brand
- Pocket Console: tactile, quiet, nostalgic hardware.
- Original wordmark, artwork and sounds. Use hardware proportions and interaction cues.
- Avoid distracting effects over game output; status must be truthful.

## Product goals
- One Android app with three distinct handheld experiences.
- Deliverable v0.2: integrated DS player plus DS/3DS/Switch controller previews and persistent local game-file catalog.
- Longer-term: integrated, separately maintained native cores, DS first, then 3DS, then Switch.
- Non-goals for v0.2: 3DS/Switch execution, game downloads, firmware/key distribution, cloud accounts, multiplayer, billing, quick saves.
- Success: native build/test/lint pass; library survives restart; unsupported execution is clearly described; controller demo responds to input.

## Personas and jobs
- Player opening their own local files on Android.
- Touch controls in portrait for dual-screen handhelds; landscape for Switch. Physical controller support follows core integration.

## Information architecture
- Library: select DS / 3DS / Switch, import a file, see imported titles, open a controller preview.
- Controller preview: two screens for DS/3DS, one for Switch, touch feedback, back to library.
- DS entries open the actual player; 3DS/Switch entries display a core-pending explanation. Controller previews remain labeled control tests.

## Design principles
- Hardware silhouette, inset screens, hinge, speaker grille, and raised buttons establish the handheld feeling.
- Comfortable targets take priority over exact hardware scale. Minimum 48dp for interactive controls.
- Keep files local and request access through Android's file picker.

## Visual language
- Warm paper background #F3F0E8; ink #202826; soft gray hardware #DADDD5; LCD green #C8D6A3; restrained orange #CC633F.
- System sans-serif and monospace status labels; no downloadable fonts.
- Rhythm: 8dp units, 20–24dp page edges, rounded hardware shell and inset screen borders.
- DS: warm gray clamshell, equal screens, hinge, D-pad and ABXY. In gameplay, fit both displays and 48dp controls in a normal 360x640dp viewport; use an adjacent control panel in landscape.
- 3DS: darker shell, wider upper screen, smaller touch screen, circle pad.
- Switch: landscape, dark center screen, muted blue and orange side controllers.
- Motion: brief press feedback; no compulsory startup animation. Original vector/drawn artwork only.

## Components
- Platform Android Views, custom controller preview drawing where necessary.
- Console selector, cartridge-style file row, import button, inset LCD, directional/button controls, status badge.
- States: selected, pressed, empty, unsupported file, revoked access, core pending.
- DESIGN.md owns palette and visual rules; ConsoleType owns console metadata.

## Accessibility
- Native accessible controls, descriptive Korean labels, 48dp targets, no color-only status.
- Preview button state must also appear as text. Optional subtle haptics; respect system settings.
- Preview is a visual/controller test; complete gameplay accessibility requires a later device pass.

## Responsive behavior
- Fit system bars/cutouts using window insets.
- Small screens scroll rather than clip. Handle orientation changes without losing library data.
- Switch preview prefers landscape; DS/3DS portrait, with reasonable fallback in either orientation.

## Interaction states
- Empty: explain local file import; provide controller preview without a file.
- Error: explain unsupported extension or unreadable permission; preserve existing entries.
- Success: imported title appears in its console category.
- Core pending: show explicit unavailable status; do not simulate game execution or advertise working save states.
- Offline: all v0.1 features work locally.

## Content voice
- Brief Korean interface with conventional DS / 3DS / Switch names.
- Use “조작 미리보기” and “코어 연결 예정”; never label a demo as emulation.

## Implementation constraints
- Native Android SDK/Java 17, minSdk 26, target/compile SDK 36, Gradle wrapper and pinned NDK 27.1.12297006.
- No UI runtime dependencies. DS embeds pinned libretro/melonDS with tracked correctness/build patches; model checks require only the JDK.
- Before core integration: review the exact upstream revision, license and security patches.
- Validate build, lint, file classification/deduplication, and on-device interaction separately.

## Open questions
- Target phone model and preferred hardware color: user can refine after first preview.
- Final name and distribution route: decide before first public release. The combined DS build follows GPLv3-or-later.
