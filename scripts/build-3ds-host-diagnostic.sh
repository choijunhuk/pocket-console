#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
source_dir="$PWD/.cache/azahar-host-source"
build_dir="$PWD/.cache/azahar-host-build"
output_dir="$PWD/.cache/three_ds-core-diagnostic"
python3 scripts/prepare-3ds-source.py --output "$source_dir"

python3 - "$source_dir" <<'PY'
from hashlib import sha256
from pathlib import Path
import subprocess
import sys

source = Path(sys.argv[1])
target = source / "src/video_core/renderer_opengl/renderer_opengl.cpp"
patch = Path("patches/azahar-host-render-diag.patch").resolve()
base = "22006232527c236f72cfd2bc1310e3f198c5e2a88a6fbb0a700062b10880f09e"
patched = "11ea5e71564c08c77ca2486d27884a226124cc5fedc7f8962a0d04e845e817f0"
patch_hash = "84b04abd60e6abf27bf35b8be8daeee9098dbb706b9cf52e920bbb000afeda57"
if sha256(patch.read_bytes()).hexdigest() != patch_hash:
    raise SystemExit("Host diagnostic patch SHA256 mismatch")
current = sha256(target.read_bytes()).hexdigest()
if current == base:
    subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "--dry-run",
                    "-p1", "-i", str(patch)], cwd=source, check=True)
    subprocess.run(["patch", "--batch", "--forward", "--fuzz=0",
                    "-p1", "-i", str(patch)], cwd=source, check=True)
elif current != patched:
    raise SystemExit("Host renderer differs from exact Azahar 2126.2 pin")
if sha256(target.read_bytes()).hexdigest() != patched:
    raise SystemExit("Host diagnostic patch result SHA256 mismatch")
print("AZAHAR_HOST_DIAGNOSTIC_PATCH_PASS")
PY

cmake -S "$source_dir" -B "$build_dir" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=20 \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_CXX_FLAGS=-DPOCKET_HOST_RENDER_DIAG \
  -DENABLE_LIBRETRO=ON -DENABLE_TESTS=OFF \
  -DENABLE_QT=OFF -DENABLE_SDL2=OFF \
  -DENABLE_VULKAN=OFF -DENABLE_OPENGL=ON \
  -DENABLE_BUILTIN_KEYBLOB=OFF -DENABLE_LTO=OFF \
  -DCITRA_WARNINGS_AS_ERRORS=OFF -DCITRA_USE_PRECOMPILED_HEADERS=ON
cmake --build "$build_dir" --target azahar_libretro --parallel 2

binary="$build_dir/bin/Release/azahar_libretro.so"
test -s "$binary"
mkdir -p "$output_dir"
install -m 755 "$binary" "$output_dir/libazahar_libretro.so"
echo "AZAHAR_HOST_DIAGNOSTIC_BUILD_PASS $output_dir/libazahar_libretro.so"
