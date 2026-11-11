# Original 3DS smoke fixture

`smoke.c` is original homebrew. It uses no game, firmware, DSP dump, or Nintendo asset.
It draws blue on the top screen and red on the bottom screen; A turns the top green,
touch turns the bottom yellow, and a rightward circle-pad move turns it cyan. It
starts an original NDSP PCM tone when X is held. The fixture creates a zero-byte
`sdmc:/3ds/dspfirm.cdc` stand-in only when no component exists; Azahar's DSP HLE
does not execute proprietary firmware. The host and Android tests require nonzero
PCM, so unsupported DSP output fails explicitly. CSND was removed after CI showed
Azahar 2126.2 stubs its service and no fixture color appeared after its command call.

Build with `scripts/build-3ds-homebrew.sh`. It emits `tests/three_ds/smoke.3dsx`
using a local devkitPro installation or the official
[`devkitpro/devkitarm:20260610`](https://hub.docker.com/r/devkitpro/devkitarm/tags)
image. The script prints the pulled image's full digest; pin that observed digest
in CI after its first successful pull. The Docker daemon is unavailable locally.

Run the Linux host check with
`scripts/test-3ds-native.sh tests/three_ds/smoke.3dsx /path/to/libazahar_libretro.so`.
It needs a compiler, Mesa software GL 4.3, EGL/GL development headers and
libraries (`libegl1-mesa-dev`, `libgl1-mesa-dev`, `libgl1-mesa-dri` on Ubuntu),
and `libretro.h` from the already pinned melonDS submodule.

API sources: [devkitPro 3DS application template](https://github.com/devkitPro/3ds-examples/tree/master/templates/application),
[libctru framebuffer API](https://github.com/devkitPro/libctru/blob/master/libctru/include/3ds/gfx.h),
[libctru NDSP API](https://github.com/devkitPro/libctru/blob/master/libctru/include/3ds/ndsp/ndsp.h),
[devkitPro audio example](https://github.com/devkitPro/3ds-examples/tree/master/audio/streaming).
