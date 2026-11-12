#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
original="$repo_root/.cache/switch-probes/eden-arm64-v8a"
output="$repo_root/.cache/switch-probes/pocket-switch-arm64-v8a"
source_dir="$repo_root/.cache/eden-source"
build_dir="$repo_root/.cache/eden-build/arm64-v8a"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
cmake="$sdk_root/cmake/3.31.6/bin/cmake"
ninja="$sdk_root/cmake/3.31.6/bin/ninja"
readelf="$sdk_root/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"

if [[ -e "$output" ]]; then
  echo "Pocket Switch output already exists: $output" >&2
  exit 2
fi
if [[ ! -d "$original" ]]; then
  "$repo_root/scripts/build-switch1-probe.sh"
fi
(cd "$original" && sha256sum --check SHA256SUMS)
cmp "$original/source-lock.json" "$repo_root/third_party/switch-eden.lock.json"
if [[ ! -f "$build_dir/CMakeCache.txt" || ! -x "$cmake" || ! -x "$ninja" || ! -x "$readelf" ]]; then
  echo "Exact Eden toolchain and its original CMake build tree are required" >&2
  exit 2
fi
sha256sum --check "$original/cxx-runtime-sha256.txt"

"$cmake" -S "$source_dir" -B "$build_dir" \
  -DCMAKE_PROJECT_INCLUDE="$repo_root/switch/native/project-hook.cmake" \
  -DPOCKET_EDEN_SOURCE_DIR="$source_dir" \
  -DPOCKET_SDK_SOURCE_DIR="$repo_root/switch/native" \
  -DPOCKET_SDK_TARGET=pocket_switch
python3 "$repo_root/scripts/build-switch-sdk-objects.py" \
  "$build_dir/compile_commands.json" "$repo_root/switch/native" "$build_dir" "$ninja" pocket_switch
"$cmake" --build "$build_dir" --target pocket_switch --parallel 2

binary="$build_dir/pocket-switch-sdk/libpocket_switch.so"
if [[ ! -s "$binary" ]]; then
  echo "Pocket Switch target produced no shared library" >&2
  exit 1
fi
"$readelf" -h "$binary" | grep AArch64 >/dev/null
source_commit="$(git -C "$repo_root" rev-parse HEAD)"
mkdir -p "$(dirname "$output")"
stage="$(mktemp -d "$(dirname "$output")/.eden-sdk-arm64-v8a.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
cp "$binary" "$stage/libpocket_switch.so"
cp "$build_dir/pocket-switch-sdk/native.cpp" "$stage/pocket-native-source.cpp"
cp "$build_dir/CMakeCache.txt" "$stage/CMakeCache.txt"
files=(source-lock.json host-tool-lock.json glslang-LICENSE.txt
  glslang-version.txt host-tool-sha256.txt cxx-runtime-sha256.txt
  submodule-status.txt cmake-version.txt clang-version.txt
  licenses.tar.gz license-files.txt sdl-java-bootstrap.tar.gz sdl-java-sha256.txt)
for name in "${files[@]}"; do
  cp "$original/$name" "$stage/$name"
done
"$readelf" -d "$binary" > "$stage/probe-dynamic-section.txt"
"$readelf" -h "$binary" > "$stage/probe-elf-header.txt"
find "$build_dir" "$source_dir" -type f -name '*.so' -print0 | sort -z | while IFS= read -r -d '' lib; do
  if "$readelf" -h "$lib" >/dev/null 2>&1; then
    printf '%s\n' "$lib" >> "$stage/shared-libraries.txt"
    sha256sum "$lib" >> "$stage/shared-library-sha256.txt"
    "$readelf" -d "$lib" | grep -E '\((SONAME|NEEDED)\)' >> "$stage/shared-libraries.txt" || true
  fi
done
cat > "$stage/build-manifest.txt" <<EOF
Source: exact Eden commit and URL in source-lock.json
Pocket Console source commit: $source_commit
Target: Pocket Console libpocket_switch.so, linked to the original six core libraries
Build: native SDK target reconfigured from the verified upstream Android build
Status: source ELF only; APK dependency closure and ARM64 play remain unverified
EOF
(cd "$stage" && sha256sum libpocket_switch.so pocket-native-source.cpp CMakeCache.txt \
  "${files[@]}" probe-dynamic-section.txt probe-elf-header.txt \
  shared-libraries.txt shared-library-sha256.txt build-manifest.txt > SHA256SUMS)
mv "$stage" "$output"
trap - EXIT
echo "Built Pocket Switch source target: $output/libpocket_switch.so"
