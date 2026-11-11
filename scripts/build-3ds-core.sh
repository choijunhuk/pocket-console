#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
abi="${1:-}"
case "$abi" in
  arm64-v8a|x86_64) ;;
  *) echo "usage: $0 {arm64-v8a|x86_64}" >&2; exit 2 ;;
esac

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" || ! -d "$sdk_root" ]]; then
  echo "ANDROID_SDK_ROOT must point to an installed Android SDK" >&2
  exit 2
fi
ndk="$sdk_root/ndk/29.0.14206865"
cmake="$sdk_root/cmake/4.1.2/bin/cmake"
if [[ ! -f "$ndk/build/cmake/android.toolchain.cmake" || ! -x "$cmake" ]]; then
  echo "Install Android NDK 29.0.14206865 and CMake 4.1.2 with sdkmanager" >&2
  exit 2
fi

source_dir="$repo_root/.cache/azahar-source"
build_dir="$repo_root/.cache/azahar-build/$abi"
output_dir="$repo_root/.cache/3ds-cores/$abi"
python3 "$repo_root/scripts/prepare-3ds-source.py" --output "$source_dir"

"$cmake" -S "$source_dir" -B "$build_dir" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=20 \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DENABLE_LIBRETRO=ON -DENABLE_TESTS=OFF \
  -DENABLE_VULKAN=OFF -DENABLE_OPENGL=ON \
  -DENABLE_BUILTIN_KEYBLOB=OFF -DENABLE_LTO=OFF \
  -DCITRA_WARNINGS_AS_ERRORS=OFF -DCITRA_USE_PRECOMPILED_HEADERS=ON
"$cmake" --build "$build_dir" --target azahar_libretro --config Release --parallel 2

binary="$build_dir/bin/Release/azahar_libretro.so"
if [[ ! -s "$binary" ]]; then
  echo "Azahar build did not produce $binary" >&2
  exit 1
fi

mkdir -p "$(dirname "$output_dir")"
stage="$(mktemp -d "$(dirname "$output_dir")/.${abi}.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
cp "$binary" "$stage/libazahar_libretro.so"
cp "$repo_root/third_party/azahar.lock.json" "$stage/source-manifest.json"
(cd "$source_dir" && find . -type f \( -iname 'license*' -o -iname 'copying*' -o -iname 'notice*' \) -print0 | tar --null -czf "$stage/licenses.tar.gz" -T -)
tar -tzf "$stage/licenses.tar.gz" > "$stage/license-files.txt"
if [[ ! -s "$stage/license-files.txt" ]]; then
  echo "Azahar source license archive is missing" >&2
  exit 1
fi
(cd "$stage" && sha256sum libazahar_libretro.so licenses.tar.gz license-files.txt source-manifest.json > SHA256SUMS)
if [[ -e "$output_dir" ]]; then
  echo "Output already exists; remove the stale ABI directory first: $output_dir" >&2
  exit 2
fi
mv "$stage" "$output_dir"
trap - EXIT
echo "Built $output_dir/libazahar_libretro.so"
