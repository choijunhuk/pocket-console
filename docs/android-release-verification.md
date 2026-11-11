# Android 0.3.0-alpha.1 verification

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
