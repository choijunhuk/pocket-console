#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

patch_file="$PWD/patches/melonds-save-close.patch"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/pocket-save-flush.XXXXXX")"
trap 'rm -rf "$build_dir"' EXIT
mkdir -p "$build_dir/src"
core_source="$PWD/third_party/melonDS/src/NDSCart_SRAMManager.cpp"
if [ -f "$core_source" ]; then
    python3 scripts/prepare-core.py
    cp "$core_source" "$build_dir/src/NDSCart_SRAMManager.cpp"
else
    core_source="$PWD/.cache/review-upstream/NDSCart_SRAMManager.cpp"
    if [ ! -f "$core_source" ]; then
        echo 'melonDS checkout or exact cached source is required for save flush test' >&2
        exit 2
    fi
    python3 - "$core_source" <<'PY'
from hashlib import sha256
from pathlib import Path
import sys
assert sha256(Path(sys.argv[1]).read_bytes()).hexdigest() == "8689b109939bb2fdc182f2fe338fe5ef053e342e7c8609d9f955b68a2a383218", "cached melonDS source drifted"
PY
    cp "$core_source" "$build_dir/src/NDSCart_SRAMManager.cpp"
    (cd "$build_dir" && git apply --include=src/NDSCart_SRAMManager.cpp "$patch_file")
fi

# Compile the actual patched manager with only the platform file/mutex seams stubbed.
cat > "$build_dir/src/NDSCart_SRAMManager.h" <<'EOF'
#pragma once
#include <cstdint>
using u8 = uint8_t;
using u32 = uint32_t;
using s32 = int32_t;
namespace NDSCart_SRAMManager {
bool Init();
void DeInit();
void Setup(const char*, u8*, u32);
void RequestFlush();
bool NeedsFlush();
void FlushSecondaryBuffer(u8* = nullptr, s32 = 0);
void Flush();
}
EOF
cat > "$build_dir/src/Platform.h" <<'EOF'
#pragma once
#include <cstdio>
namespace Platform {
struct Thread;
struct Mutex;
FILE* OpenFile(const char*, const char*, bool = false);
void Mutex_Free(Mutex*);
void Mutex_Lock(Mutex*);
void Mutex_Unlock(Mutex*);
}
EOF

"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -Wno-sign-compare -D__LIBRETRO__ \
    -I"$build_dir/src" tests/native/save_flush.cpp "$build_dir/src/NDSCart_SRAMManager.cpp" \
    -o "$build_dir/save_flush"
"$build_dir/save_flush" "$build_dir/reopen.sav"
