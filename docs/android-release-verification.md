# Android release verification

## Switch homebrew integration in the unreleased development tree — 2026-11-18

The staged merge adds the pinned Switch SDK to Android debug and alpha-release packaging. The published alpha.4 APK below is unchanged and does **not** contain this integration. The same Pocket Console APK runs experimental original `.nro` homebrew; it does not launch another emulator. Source/runtime checks, emulator results and pending release gates are recorded in [Switch verification](switch-verification.md). No physical-device, Switch audio, commercial-game or Switch 2 game acceptance is claimed.

## 0.3.0-alpha.5

Published 2026-11-18 23:39:48 UTC as a public GitHub prerelease.

- [Release and assets](https://github.com/choijunhuk/pocket-console/releases/tag/v0.3.0-alpha.5)
- [Download APK](https://github.com/choijunhuk/pocket-console/releases/download/v0.3.0-alpha.5/PocketConsole-v0.3.0-alpha.5.apk)
- [Complete release workflow](https://github.com/choijunhuk/pocket-console/actions/runs/44421082889): all nine jobs passed.
- Source: `3618aa57d5c578901c91e1f88627ef51830bee7b`; package `dev.pocketconsole`, code `8`, name `0.3.0-alpha.5`
- APK: 36,801,747 bytes; SHA-256 `6117ae3fbcc9805ab20bee9cac12125c01ce2664ad585b54b99ba3a4be6ff666`
- Source archive SHA-256: `f5608187d12b5b939121a13e48fd1791b9f93de271eea34c968d3c53db5cda44`
- Signer SHA-256 (DER): `e95c85ff0887c8bbcaebdb90238f4b6dd4ce0b8b5245f6b59c2aa15c49c5320a`, unchanged from earlier official alphas.
- Switch SDK: `switch-sdk.yml` run 44416880188 at `eb2354c`, pinned in `third_party/switch-sdk.lock.json`; the workflow emitted `SWITCH_SDK_PRODUCER_PASS` and `SWITCH_JNI_PREPARATION_PASS`.

An independent public download matched `SHA256SUMS`, the official signer, code/name above and no requested permissions. The arm64-v8a ABI contains `libpocket_switch2.so`, `libhook_impl.so` and `libmain_hook.so`; x86_64 contains no Switch engine. The APK is smaller than alpha.4 because native libraries are now stored compressed and extracted at install (`extractNativeLibs=true`), which the Switch Vulkan driver-hook loader requires.

The release Android smoke artifact contains `DS_ANDROID_SMOKE_PASS`, `DS_DISPLAY_LAYOUT_PASS`, `THREEDS_DISPLAY_UI_PASS (layout only)` and `THREEDS_ANDROID_SMOKE_NOT_RUN_EXPERIMENTAL`; the release log contains `DS_SAVE_FLUSH_PASS`, `DS_NATIVE_SMOKE_PASS` and `THREEDS_NATIVE_SMOKE_PASS` in both 3DS host paths.

Switch homebrew acceptance comes only from a native ARM64 emulator (37.2.13, SwiftShader Vulkan): original NRO frame and A input, launch/exit/relaunch from the library, dark-mode change mid-session, six malformed NROs rejected before native load, and no Switch library mapped in the main process. CI Switch frames are `NRO_NOT_RUN_TRANSLATED_ABI` because that runner translates ARM64. Physical-device Switch and 3DS execution, Switch audio, commercial content and Switch 2 game compatibility were not tested.

## 0.3.0-alpha.4

Published 2026-11-17 11:00:35 UTC as a public GitHub prerelease.

- [Release and assets](https://github.com/choijunhuk/pocket-console/releases/tag/v0.3.0-alpha.4)
- [Download APK](https://github.com/choijunhuk/pocket-console/releases/download/v0.3.0-alpha.4/PocketConsole-v0.3.0-alpha.4.apk)
- [Complete release workflow](https://github.com/choijunhuk/pocket-console/actions/runs/44067378214): all nine jobs passed.
- Source: `900595ed55bcbb3f01b44be0f201d4db4231f2f9`; package `dev.pocketconsole`, code `7`, name `0.3.0-alpha.4`
- APK: 77,028,445 bytes; SHA-256 `d14ecbc1cad9b3c02130620cad6e38dd2a6356e4c5db67c5f9648859a1c7f29b`
- Source archive SHA-256: `a57be51ec72c625ad8748d61229bce4521fea10b4cd6ac7f720daeae0a588796`
- Signer SHA-256 (DER): `e95c85ff0887c8bbcaebdb90238f4b6dd4ce0b8b5245f6b59c2aa15c49c5320a`, unchanged from earlier official alphas.

Independent public downloads passed for all six GitHub asset digests and matched the CI artifact bytes. SHA256SUMS, both manifests, official v2 signature, non-debuggable package/version/SDK/ABI metadata, ZIP alignment and every native ELF's 16KB alignment passed. The corresponding source archive contains both core licenses and matches all 19 tracked files changed since alpha3 to the tagged source commit; the APK contains `PreparationProgress`.

Actual logs contain `DS_SAVE_FLUSH_PASS`, `DS_NATIVE_SMOKE_PASS`, `THREEDS_NATIVE_SMOKE_PASS` in both 3DS host paths, `DS_ANDROID_SMOKE_PASS`, `DS_DISPLAY_LAYOUT_PASS` and `THREEDS_DISPLAY_UI_PASS (layout only)`.

Android 3DS game execution remains explicitly `THREEDS_ANDROID_SMOKE_NOT_RUN_EXPERIMENTAL`. Physical-device update/save preservation, haptics, sustained performance and commercial-game compatibility were not tested. The stable signer and increased version code establish Android update eligibility without uninstalling the existing official app. Switch/Switch 2 execution and DLC installation are not included.

## 0.3.0-alpha.3

Published 2026-11-15 10:01:52 UTC as a private GitHub prerelease.

- [Release and assets](https://github.com/choijunhuk/pocket-console/releases/tag/v0.3.0-alpha.3)
- [Download APK](https://github.com/choijunhuk/pocket-console/releases/download/v0.3.0-alpha.3/PocketConsole-v0.3.0-alpha.3.apk)
- [Complete release workflow](https://github.com/choijunhuk/pocket-console/actions/runs/43700586438): all nine jobs passed.
- Source: `597b5325d84ed9f5e2cebbd350121d4cc5e3a607`; package `dev.pocketconsole`, code `6`, name `0.3.0-alpha.3`
- APK: 77,028,445 bytes; SHA-256 `fc96434f5ef07beb71412d0c51b9e4c96f2ab726a5cf0b8b7c8430445067aac8`
- Source archive SHA-256: `0b733cf3d78801433298739b4a8a8e35b5c4923dd49e733379047a33df70a90d`
- Signer SHA-256 (DER): `e95c85ff0887c8bbcaebdb90238f4b6dd4ce0b8b5245f6b59c2aa15c49c5320a`, unchanged from earlier official alphas.

Independent download audit passed for all six GitHub digests, SHA256SUMS, manifests/source commit, APK signature/version, both core licenses, ZIP alignment and every native ELF's 16KB alignment. Release Android evidence contains `DS_ANDROID_SMOKE_PASS`, `DS_DISPLAY_LAYOUT_PASS` and `THREEDS_DISPLAY_UI_PASS (layout only)`.

Both real 3DS host cores pass their unchanged game-output/input/audio assertions. Android 3DS remains explicitly `NOT_RUN_EXPERIMENTAL`. See [player display verification](player-display-verification.md) for actual fullscreen/touch/Back and compact/large-font screenshots. Physical-device compatibility, haptics and sustained performance remain deferred.

## 0.3.0-alpha.2

Published 2026-11-15 06:23:09 UTC as a private GitHub prerelease.

- [Release and assets](https://github.com/choijunhuk/pocket-console/releases/tag/v0.3.0-alpha.2)
- [Download APK](https://github.com/choijunhuk/pocket-console/releases/download/v0.3.0-alpha.2/PocketConsole-v0.3.0-alpha.2.apk)
- [Complete release workflow](https://github.com/choijunhuk/pocket-console/actions/runs/43683446948): all nine jobs passed.
- Source commit: `f4a88c80d0101083d742e77270be60ce15742413`
- Package: `dev.pocketconsole`, versionCode `5`, versionName `0.3.0-alpha.2`, minimum SDK `26`
- APK size: 77,012,065 bytes
- APK SHA-256: `d05a3e170da0630e8470d963c59fc3cc3407471bfb8b22031e3995658311ac79`
- Source archive SHA-256: `e65dcc905e5621b205322da3a1bdb022e3519dd7497d2d7723447169be89c956`
- Public signing certificate SHA-256 (DER): `e95c85ff0887c8bbcaebdb90238f4b6dd4ce0b8b5245f6b59c2aa15c49c5320a`

| Gate | Evidence |
| --- | --- |
| Source/core/model/cache, release lint and both Android ABIs | PASS |
| Real DS native and Android release UI/frame/input/exit/reopen | PASS; release artifact contains `DS_ANDROID_SMOKE_PASS` |
| Real 3DS official and source diagnostic host cores | PASS; both logs contain `THREEDS_NATIVE_SMOKE_PASS` for the unchanged screen/input/audio/reopen assertions |
| Signed non-debuggable APK, code 5, matching official signer | PASS; independently downloaded APK verified with apksigner |
| APK ZIP and native ELF 16KB alignment | PASS; independent ELF verifier and zipalign checks |
| Published release and all six assets | PASS; non-draft prerelease, downloaded SHA256SUMS and GitHub digests match |
| Release/source provenance | PASS; both manifests identify the exact release commit and both core licenses are in the archive |
| Android 3DS runtime | NOT_RUN_EXPERIMENTAL in this release; earlier full Android run 43238203790 reports insufficient GLES 3.0 |
| Physical Galaxy S23 Ultra and commercial-game compatibility | NOT_RUN; user explicitly deferred device testing |
| Switch / Switch 2 runtime | Experimental isolated SDK branches; neither engine is included in this APK |

The official signer matches alpha1, and the version code increases from 4 to 5. This establishes update eligibility; a physical-device upgrade with existing saves was not tested. No automatic uninstall or data deletion is performed.

## 0.3.0-alpha.1

Published 2026-11-11 19:02:48 UTC as a private GitHub prerelease.

- [Release and assets](https://github.com/choijunhuk/pocket-console/releases/tag/v0.3.0-alpha.1)
- [Download APK](https://github.com/choijunhuk/pocket-console/releases/download/v0.3.0-alpha.1/PocketConsole-v0.3.0-alpha.1.apk)
- [Complete release workflow](https://github.com/choijunhuk/pocket-console/actions/runs/43079108805)
- Source commit: `77655598e06f9fab84f6caa04bd00361d55826f4`
- Package: `dev.pocketconsole`, versionCode `4`, versionName `0.3.0-alpha.1`
- APK size: 77,028,445 bytes
- APK SHA-256: `78493e4a5d5160f59cc5dba6ebabd943c6c6d22cd654aeff2aaf63b222480dab`
- Public signing certificate SHA-256 (DER): `e95c85ff0887c8bbcaebdb90238f4b6dd4ce0b8b5245f6b59c2aa15c49c5320a`
- Corresponding source archive SHA-256: `a84e45e8ecbac2a174527482f670010c24e0ec3fc378039f52f06ca963b5df34`

| Gate | Evidence |
| --- | --- |
| Source/core/model/cache checks | PASS |
| Signed non-debuggable release APK and matching test certificate | PASS, public fingerprint matches committed certificate |
| APK ZIP and each packaged native ELF 16KB alignment | PASS |
| Android release UI screenshots, DS actual frames/input/exit/reopen | PASS on API35 x86_64 emulator |
| Release publication | PASS, draft=false and prerelease=true; six assets exist |
| Uploaded asset integrity | PASS, GitHub asset digests match the independently fetched published release-manifest.json |
| Physical Galaxy S23 Ultra, haptics, sustained performance | NOT_RUN; user explicitly deferred device connection |
| 3DS real output | NOT_ACCEPTED, colored guest buffers currently reach a black frontend image |
| Switch / Switch 2 player runtime | NOT_RUN; isolated native SDK integration continues |

The private signing key and password are excluded from Git. Encrypted Actions secrets keep the official signing identity stable across later releases. The public certificate, checksums, manifest, notices and source archive accompany the APK.

Earlier temporary debug signatures cannot necessarily upgrade to this certificate. Preserve existing app-private saves before changing an old development installation; the release does not uninstall or delete user data automatically.
