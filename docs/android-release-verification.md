# Android release verification

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
