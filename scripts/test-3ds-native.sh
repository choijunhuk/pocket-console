#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

rom="${1:-tests/three_ds/smoke.3dsx}"
core="${2:-.cache/three_ds-core/libazahar_libretro.so}"
test -s "$rom" || { echo "3DS homebrew absent: run scripts/build-3ds-homebrew.sh" >&2; exit 2; }
test -s "$core" || { echo "3DS core absent: pass libazahar_libretro.so as argument 2" >&2; exit 2; }
case "$core" in
    */libazahar_libretro.so) ;;
    *) echo '3DS core must be named libazahar_libretro.so' >&2; exit 2 ;;
esac

include="${LIBRETRO_INCLUDE:-$PWD/third_party/melonDS/src/libretro/libretro-common/include}"
test -f "$include/libretro.h" || { echo "libretro.h absent at $include" >&2; exit 2; }
build="$PWD/.cache/three_ds-native"
mkdir -p "$build/runtime"
"${CXX:-c++}" -std=c++17 -O2 -Wall -Wextra -Werror \
    -Iapp/src/main/cpp/three_ds -I"$include" \
    tests/three_ds/smoke.cpp app/src/main/cpp/three_ds/ThreeDsEngine.cpp \
    -lEGL -lGL -ldl -pthread -o "$build/smoke"

core_dir="$(cd "$(dirname "$core")" && pwd)"
rom_path="$(cd "$(dirname "$rom")" && pwd)/$(basename "$rom")"
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 \
    LD_LIBRARY_PATH="$core_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$build/smoke" "$rom_path" "$build/runtime"
