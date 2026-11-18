# Third-party source

The DS build embeds [libretro/melonDS](https://github.com/libretro/melonDS), pinned at `66b5d2634cd0a79030562811e6e05f5532f800ba` in `third_party/melonDS`.

melonDS is copyright Arisotura and its contributors, licensed under GNU GPL version 3 or later. The Libretro frontend, in-tree libretro-common, FreeBIOS and other upstream components retain their individual copyright and license notices. Their complete source and notices remain in the pinned submodule. No proprietary Nintendo BIOS, firmware, artwork or games are included.

Local patches under `patches/` are applied by `scripts/prepare-core.py`: preserve pending SRAM at close and select the correct GNU `strerror_r` signature on Android. Include these patches and the preparation/build scripts with corresponding source for any distributed binary.

Pocket Console's combined DS build is provided under GPLv3-or-later; see LICENSE. Source for this private development build is in this repository, including the exact submodule revision and Android build recipe. Before distributing a build to anyone without repository access, provide its corresponding source and notices together with it.

Original native smoke-test homebrew is generated from source under `tests/native`; it contains no Nintendo logo or other console firmware assets.

## Azahar / 3DS

The 3DS build uses [Azahar 2126.2](https://github.com/azahar-emu/azahar/releases/tag/2126.2), source commit `219d743a1e698101fe1ab8bef43e6563126c3c27`, licensed under GPL version 2 or later. The combined Pocket Console build follows GPL version 3 or later. The exact upstream source archive, digest and local error-loop patch are recorded in `third_party/azahar.lock.json`; `scripts/build-3ds-core.sh` records the build recipe. Built-in key blobs and Vulkan are disabled for this first GLES frontend.

The per-ABI core artifacts include the source manifest, checksums, local patch and upstream license archive. The [unified source archive](https://github.com/azahar-emu/azahar/releases/download/2126.2/azahar-unified-source-2126.2.tar.xz) supplies upstream dependencies. Include this corresponding source, local patch, notices and build recipe with any distributed binary.

The official Linux libretro binary is used only in CI for host acceptance, with its own pinned checksum; it is not packaged in Android. Original 3DS test code under `tests/three_ds` uses the open devkitPro/libctru toolchain. Its emulator-specific audio test follows devkitPro's documented DSP HLE zero-byte stand-in; no dumped Nintendo DSP firmware is bundled.

## Switch homebrew

The ARM64 APK embeds the [Hokkaido](https://github.com/pavelgajdo/Hokkaido) native SDK at the exact commit in `third_party/switch-hokkaido.lock.json`, licensed GPL-3.0-or-later. `switch/native/` and the Switch preparation/build scripts contain Pocket Console's local changes. The pinned SDK artifact includes its runtime dependency closure, source/build manifest, license inventory and SHA-256 checksums. Its producer run and artifact digest are pinned in `third_party/switch-sdk.lock.json`.

The release source archive includes the pinned Hokkaido checkout and its recursively pinned Git submodules, along with Pocket Console's tracked source and build scripts. It does not include Hokkaido's build-time CPM cache; the build fetches those dependencies from their pinned upstream URLs, so source reproduction still depends on those URLs remaining available. No keys, firmware, games or homebrew files are included in the APK. This experimental NRO path does not establish general Switch or Switch 2 game compatibility.

The CI SDK artifact is retained for 90 days. If it expires, Android/release packaging stops; rerun `switch-sdk.yml` at the intended source commit, verify the new artifact and update `third_party/switch-sdk.lock.json` before releasing.

## Khronos host-test headers

Linux host tests download exact Khronos EGL/OpenGL registry headers listed in `third_party/host-graphics.lock.json`. Every header is checked by SHA-256 and retains its original copyright and license notice. These development headers are not bundled in the Android APK. Rendering still uses the runner's installed Mesa libraries; no replacement renderer or synthetic frames are supplied.
