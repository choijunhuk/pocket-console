# Handheld surface handoff

This brief refines the existing warm tactile Android UI. The DS alpha screenshot shows real dual-screen output and 48 dp touch controls; it also shows that the screen stack and flat button grid still need visual hierarchy. The following are implementation proposals, not claims about the current build.

## First five changes

1. `MainActivity.java` — move **파일 가져오기** and the selected system's title list above the large `ConsolePreviewView` hero. Keep the preview as a compact secondary row after the playable content. An empty library puts import directly under the state message.
2. `MainActivity.java` and `ConsoleType.java` — show four systems with a readiness label on each selected surface. Keep Switch 2 selection explicit for ambiguous Switch containers; avoid a false **실행** affordance while its engine is experimental or absent.
3. `DsPlayerActivity.java` and `DsScreenView.java` — retain the working 256 × 192 × 2 frame and input path, but give the live screens a dark inset bezel, visible hinge seam, and compact top status. Pressed controls should visibly depress and release while retaining multi-touch.
4. New 3DS player surface — use the same shell language, with a 400 × 240 top frame centered over a 320 × 240 lower touch frame. At equal scale the lower frame is 80% as wide. Put circle pad/D-pad and ABXY around the lower frame; Start/Select and shoulders remain reachable. Render only core output.
5. Shared styling and state copy — add deliberate dark colors, readable 1.3 font scaling, inset-safe compact/landscape/tablet arrangements, and specific load/error/save feedback. Use native control feedback and optional system-respecting haptics, with no startup animation requirement.

## Library composition

- The first viewport at 360 × 640 dp should identify the selected system and readiness, present import, and reveal at least the start of the file list. Avoid spending it on a full-height hardware illustration.
- Four system choices can wrap or scroll horizontally without compressing their 48 dp targets. The selected choice needs a text/shape cue as well as color.
- Existing entries remain visible when an import fails or a URI is revoked. A revoked row offers reselecting the file; removal is an explicit, separately confirmed action. Preserve the catalog item until the player chooses otherwise.
- Recent play appears only from a real launch history record. If that record is not implemented, omit the section.

## Player states and copy

| State | Suggested visible copy | Recovery |
| --- | --- | --- |
| Checking document | `파일을 확인하고 있어요…` | Keep library intact. |
| Starting core | `게임을 여는 중…` | Keep Back available. |
| Play | `DS 실행 중` / `3DS 실행 중` | Back begins normal shutdown. |
| Save finalization | `종료 중… 저장을 반영하고 있어요.` | Complete only after shutdown succeeds. |
| Revoked URI | `파일에 접근할 수 없어요.` | `파일 다시 선택` and keep the catalog row. |
| Unsupported format | `이 파일 형식은 열 수 없어요.` | Return to library. |
| Encrypted game | `암호화된 게임은 열 수 없어요.` | Explain that no keys are supplied. |
| Missing device prerequisite | `이 기기에서 필요한 그래픽 기능을 사용할 수 없어요.` | Show the actual missing API/ABI/GLES/Vulkan/RAM condition. |
| Core or graphics failure | `게임 화면을 시작하지 못했어요.` | Return to library; preserve files and saves. |

Use these messages only when the corresponding error is actually diagnosed. Avoid claiming that saving succeeded just because Back was pressed.

## Acceptance for UI implementation

- Capture actual Android screenshots at portrait 360 × 640 dp and landscape 640 × 360 dp, then inspect dark mode, font scale 1.3, tablet or split window, and a cutout/inset case. Restore device settings after review.
- Walk import → list → launch → simultaneous input/touch → normal exit and each supported recovery state. DS regression and 3DS acceptance are separate.
- Check screen-reader names, focus order, 48 dp targets, contrast, predictive Back, and reduced motion. Do not infer 60 fps or all-game compatibility from a S23 Ultra screenshot.
