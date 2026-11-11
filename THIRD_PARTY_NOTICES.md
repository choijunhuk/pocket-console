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

## Switch and Switch 2 candidates

Eden and Hokkaido are research candidates, not bundled backends in this revision. Hokkaido's narrow maintainer-reported Switch 2 results do not establish Galaxy S23 Ultra or broad Android compatibility. Keep engine readiness separate from device prerequisites and catalog classification.

## Khronos host-test headers

Linux host tests download exact Khronos EGL/OpenGL registry headers listed in `third_party/host-graphics.lock.json`. Every header is checked by SHA-256 and retains its original copyright and license notice. These development headers are not bundled in the Android APK. Rendering still uses the runner's installed Mesa libraries; no replacement renderer or synthetic frames are supplied.
