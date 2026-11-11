#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

core_dir="$PWD/third_party/melonDS"
if [ ! -f "$core_dir/Makefile" ]; then
    echo 'melonDS submodule is absent; run git submodule update --init --recursive' >&2
    exit 2
fi

build_dir="$PWD/.cache/native-smoke"
mkdir -p "$build_dir"
python3 tests/native/make_smoke_nds.py "$build_dir/smoke.nds"

make -C "$core_dir" -j2 platform=unix HAVE_OPENGL=0 LIBS='-lm -lpthread -lrt'
core_lib="$core_dir/melonds_libretro.so"
if [ ! -f "$core_lib" ]; then
    echo "core build did not produce $core_lib" >&2
    exit 1
fi

"${CXX:-c++}" -std=c++17 -O2 -Wall -Wextra \
    -Iapp/src/main/cpp -I"$core_dir/src/libretro" \
    -I"$core_dir/src/libretro/libretro-common/include" \
    tests/native/smoke.cpp app/src/main/cpp/DsEngine.cpp \
    "$core_lib" -Wl,-rpath,"$core_dir" -ldl -pthread \
    -o "$build_dir/smoke"
"$build_dir/smoke" "$build_dir/smoke.nds" "$build_dir/runtime"
