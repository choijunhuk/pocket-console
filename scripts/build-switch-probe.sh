#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
abi="${1:-}"
if [[ "$abi" != arm64-v8a ]]; then
  echo "usage: $0 arm64-v8a (Hokkaido supports ARM64 only)" >&2
  exit 2
fi

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
ndk="$sdk_root/ndk/28.2.13676358"
cmake="$sdk_root/cmake/3.31.5/bin/cmake"
ninja="$sdk_root/cmake/3.31.5/bin/ninja"
readelf="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
if [[ ! -f "$ndk/build/cmake/android.toolchain.cmake" || ! -x "$cmake" || ! -x "$ninja" || ! -x "$readelf" ]]; then
  echo "Install Android NDK 28.2.13676358 and CMake 3.31.5 (including Ninja) on Linux" >&2
  exit 2
fi

source_dir="$repo_root/.cache/hokkaido-source"
build_dir="$repo_root/.cache/hokkaido-build/$abi"
output_dir="$repo_root/.cache/switch-probes/hokkaido-$abi"
validator="$repo_root/.cache/glslang-host-build/StandAlone/glslangValidator"
python3 "$repo_root/scripts/prepare-switch-source.py" --output "$source_dir"
python3 "$repo_root/scripts/prepare-switch-host-tool.py"
if [[ ! -x "$validator" ]]; then
  echo "Pinned host glslangValidator is missing: $validator" >&2
  exit 1
fi
if [[ -e "$output_dir" ]]; then
  echo "Probe output already exists: $output_dir" >&2
  exit 2
fi

"$cmake" -S "$source_dir" -B "$build_dir" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-33 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DENABLE_QT=OFF -DENABLE_WEB_SERVICE=ON -DANDROID_ARM_NEON=ON \
  -DYUZU_USE_CPM=ON -DCPMUTIL_FORCE_BUNDLED=ON \
  -DYUZU_USE_BUNDLED_FFMPEG=ON -DYUZU_USE_BUNDLED_SDL3=ON \
  -DGLSLANGVALIDATOR="$validator" \
  -DBUILD_TESTING=OFF -DYUZU_TESTS=OFF -DDYNARMIC_TESTS=OFF
"$cmake" --build "$build_dir" --target yuzu-android --parallel 2

mapfile -t binaries < <(find "$build_dir" -type f -name 'libyuzu-android.so' -print)
if [[ ${#binaries[@]} -ne 1 ]]; then
  echo "Expected one upstream libyuzu-android.so, found ${#binaries[@]}" >&2
  exit 1
fi
"$readelf" -h "${binaries[0]}" | grep -q 'AArch64'

mkdir -p "$(dirname "$output_dir")"
stage="$(mktemp -d "$(dirname "$output_dir")/.hokkaido-$abi.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
cp "${binaries[0]}" "$stage/hokkaido-upstream-probe.so"
cp "$repo_root/third_party/switch-hokkaido.lock.json" "$stage/source-lock.json"
cp "$repo_root/third_party/switch-glslang.lock.json" "$stage/host-tool-lock.json"
cp "$repo_root/.cache/glslang-host-source/LICENSE.txt" "$stage/glslang-LICENSE.txt"
cp "$source_dir/.pocket-console-submodules.txt" "$stage/submodule-status.txt"
"$validator" --version > "$stage/glslang-version.txt"
sha256sum "$validator" > "$stage/host-tool-sha256.txt"
"$cmake" --version > "$stage/cmake-version.txt"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version > "$stage/clang-version.txt"
cp "$build_dir/CMakeCache.txt" "$stage/CMakeCache.txt"
cat > "$stage/build-manifest.txt" <<EOF
Source: Hokkaido android-0.6.2 (exact commit and URL in source-lock.json)
Submodules: exact recursive gitlinks in submodule-status.txt
Target: unmodified upstream yuzu-android
ABI: $abi
Android API: 33
Toolchain: NDK 28.2.13676358, CMake 3.31.5, Ninja
Host shader compiler: Khronos glslang 15.4.0, exact source in host-tool-lock.json
Configuration: CMakeCache.txt
Artifact: hokkaido-upstream-probe.so (renamed file only; original ELF SONAME is recorded)
Status: source/build probe only; no Pocket Console runtime adapter or compatibility claim
EOF

# Inventory every built or downloaded ELF shared library; the probe is not a shipping bundle.
find "$build_dir" "$source_dir" -type f -name '*.so' -print0 | sort -z | while IFS= read -r -d '' lib; do
  if "$readelf" -h "$lib" >/dev/null 2>&1; then
    printf '%s\n' "$lib" >> "$stage/shared-libraries.txt"
    sha256sum "$lib" >> "$stage/shared-library-sha256.txt"
    "$readelf" -d "$lib" | grep -E '\((SONAME|NEEDED)\)' >> "$stage/shared-libraries.txt" || true
  fi
done
"$readelf" -d "$stage/hokkaido-upstream-probe.so" > "$stage/probe-dynamic-section.txt"
"$readelf" -h "$stage/hokkaido-upstream-probe.so" > "$stage/probe-elf-header.txt"

find "$source_dir" -path '*/.git' -prune -o -type f \( -iname 'license*' -o -iname 'copying*' -o -iname 'notice*' \) -print0 \
  | tar --null -czf "$stage/licenses.tar.gz" --files-from -
tar -tzf "$stage/licenses.tar.gz" > "$stage/license-files.txt"
if [[ ! -s "$stage/license-files.txt" ]]; then
  echo "Hokkaido source license inventory is empty" >&2
  exit 1
fi

(cd "$stage" && sha256sum hokkaido-upstream-probe.so source-lock.json host-tool-lock.json glslang-LICENSE.txt glslang-version.txt host-tool-sha256.txt submodule-status.txt cmake-version.txt clang-version.txt CMakeCache.txt build-manifest.txt shared-libraries.txt shared-library-sha256.txt probe-dynamic-section.txt probe-elf-header.txt licenses.tar.gz license-files.txt > SHA256SUMS)
mv "$stage" "$output_dir"
trap - EXIT
echo "Built upstream source probe: $output_dir/hokkaido-upstream-probe.so"
