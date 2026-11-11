#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
abi="${1:-}"
target="${2:-yuzu-android}"
backend="${3:-hokkaido}"
if [[ "$abi" != arm64-v8a ]]; then
  echo "usage: $0 arm64-v8a (Hokkaido supports ARM64 only)" >&2
  exit 2
fi
if [[ "$target" != yuzu-android && "$target" != pocket_switch2 ]]; then
  echo "unsupported native target: $target" >&2
  exit 2
fi
if [[ "$backend" != hokkaido && "$backend" != eden ]]; then
  echo "unsupported source backend: $backend" >&2
  exit 2
fi
if [[ "$backend" == eden && "$target" != yuzu-android ]]; then
  echo "Eden SDK adaptation is not yet built; use the upstream source probe" >&2
  exit 2
fi

lock="$repo_root/third_party/switch-hokkaido.lock.json"
ndk_version=28.2.13676358
cmake_version=3.31.5
android_api=33
source_dir="$repo_root/.cache/hokkaido-source"
build_dir="$repo_root/.cache/hokkaido-build/$abi"
output_dir="$repo_root/.cache/switch-probes/hokkaido-$abi"
source_label="Hokkaido android-0.6.2"
if [[ "$backend" == eden ]]; then
  lock="$repo_root/third_party/switch-eden.lock.json"
  ndk_version=30.0.16248370
  cmake_version=3.31.6
  android_api=26
  source_dir="$repo_root/.cache/eden-source"
  build_dir="$repo_root/.cache/eden-build/$abi"
  output_dir="$repo_root/.cache/switch-probes/eden-$abi"
  source_label="Eden cf654052"
fi

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
ndk="$sdk_root/ndk/$ndk_version"
cmake="$sdk_root/cmake/$cmake_version/bin/cmake"
ninja="$sdk_root/cmake/$cmake_version/bin/ninja"
readelf="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
cxx_runtime="$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [[ ! -f "$ndk/build/cmake/android.toolchain.cmake" || ! -x "$cmake" || ! -x "$ninja" || ! -x "$readelf" || ! -s "$cxx_runtime" ]]; then
  echo "Install Android NDK $ndk_version and CMake $cmake_version (including Ninja) on Linux" >&2
  exit 2
fi

if [[ "$target" == pocket_switch2 ]]; then
  output_dir="$repo_root/.cache/switch-probes/pocket-switch2-$abi"
  artifact=libpocket_switch2.so
  sdk_args=(-DCMAKE_PROJECT_INCLUDE="$repo_root/switch/native/project-hook.cmake"
            -DPOCKET_HOKKAIDO_SOURCE_DIR="$source_dir"
            -DPOCKET_SDK_SOURCE_DIR="$repo_root/switch/native")
else
  artifact="$backend-upstream-probe.so"
  sdk_args=()
fi
validator="$repo_root/.cache/glslang-host-build/StandAlone/glslangValidator"
python3 "$repo_root/scripts/prepare-switch-source.py" --lock "$lock" --output "$source_dir"
python3 "$repo_root/scripts/prepare-switch-host-tool.py" --cmake-version "$cmake_version"
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
  -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$android_api" -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DENABLE_QT=OFF -DENABLE_WEB_SERVICE=ON -DANDROID_ARM_NEON=ON \
  -DYUZU_USE_CPM=ON -DCPMUTIL_FORCE_BUNDLED=ON \
  -DYUZU_USE_BUNDLED_FFMPEG=ON -DYUZU_USE_BUNDLED_SDL3=ON \
  -DGLSLANGVALIDATOR="$validator" \
  -DBUILD_TESTING=OFF -DYUZU_TESTS=OFF -DDYNARMIC_TESTS=OFF \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON "${sdk_args[@]}"
if [[ "$target" == pocket_switch2 ]]; then
  sdl_java="$source_dir/externals/generated/sdl/java"
  if [[ ! -f "$sdl_java/org/libsdl/app/SDL.java" ]]; then
    echo "Pinned SDL Android Java bootstrap was not generated" >&2
    exit 1
  fi
  python3 "$repo_root/scripts/build-switch-sdk-objects.py" \
    "$build_dir/compile_commands.json" "$repo_root/switch/native" "$build_dir" "$ninja"
fi
"$cmake" --build "$build_dir" --target "$target" --parallel 2

binary_name=lib${target}.so
mapfile -t binaries < <(find "$build_dir" -type f -name "$binary_name" -print)
if [[ ${#binaries[@]} -ne 1 ]]; then
  echo "Expected one $binary_name, found ${#binaries[@]}" >&2
  exit 1
fi
"$readelf" -h "${binaries[0]}" | grep -q 'AArch64'

mkdir -p "$(dirname "$output_dir")"
stage="$(mktemp -d "$(dirname "$output_dir")/.hokkaido-$abi.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
cp "${binaries[0]}" "$stage/$artifact"
cp "$lock" "$stage/source-lock.json"
cp "$repo_root/third_party/switch-glslang.lock.json" "$stage/host-tool-lock.json"
cp "$repo_root/.cache/glslang-host-source/LICENSE.txt" "$stage/glslang-LICENSE.txt"
cp "$source_dir/.pocket-console-submodules.txt" "$stage/submodule-status.txt"
"$validator" --version > "$stage/glslang-version.txt"
sha256sum "$validator" > "$stage/host-tool-sha256.txt"
"$cmake" --version > "$stage/cmake-version.txt"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version > "$stage/clang-version.txt"
sha256sum "$cxx_runtime" > "$stage/cxx-runtime-sha256.txt"
cp "$build_dir/CMakeCache.txt" "$stage/CMakeCache.txt"
if [[ "$target" == pocket_switch2 ]]; then
  cp "$build_dir/pocket-switch-sdk/native.cpp" "$stage/pocket-native-source.cpp"
  tar -C "$sdl_java" -czf "$stage/sdl-java-bootstrap.tar.gz" org/libsdl/app
  tar -tzf "$stage/sdl-java-bootstrap.tar.gz" | grep -q 'org/libsdl/app/SDL.java'
elif [[ "$backend" == eden ]]; then
  sdl_java="$source_dir/externals/generated/sdl/java"
  if [[ ! -f "$sdl_java/org/libsdl/app/SDL.java" ]]; then
    echo "Eden SDL3 Java bootstrap was not generated" >&2
    exit 1
  fi
  tar -C "$sdl_java" -czf "$stage/sdl-java-bootstrap.tar.gz" org/libsdl/app
  (cd "$sdl_java" && find org/libsdl/app -type f -name '*.java' -print0 | sort -z | xargs -0 sha256sum) > "$stage/sdl-java-sha256.txt"
fi
cat > "$stage/build-manifest.txt" <<EOF
Source: $source_label (exact commit and URL in source-lock.json)
Submodules: exact recursive gitlinks in submodule-status.txt
Target: $target
ABI: $abi
Android API: $android_api
Toolchain: NDK $ndk_version, CMake $cmake_version, Ninja
C++ shared runtime SHA: cxx-runtime-sha256.txt (inventory only; APK closure remains unverified)
Host shader compiler: Khronos glslang 15.4.0, exact source in host-tool-lock.json
Configuration: CMakeCache.txt
Artifact: $artifact (original ELF SONAME is recorded)
Status: source-built target only; Pocket Console runtime acceptance is separate
EOF

# Inventory every built or downloaded ELF shared library; the probe is not a shipping bundle.
find "$build_dir" "$source_dir" -type f -name '*.so' -print0 | sort -z | while IFS= read -r -d '' lib; do
  if "$readelf" -h "$lib" >/dev/null 2>&1; then
    printf '%s\n' "$lib" >> "$stage/shared-libraries.txt"
    sha256sum "$lib" >> "$stage/shared-library-sha256.txt"
    "$readelf" -d "$lib" | grep -E '\((SONAME|NEEDED)\)' >> "$stage/shared-libraries.txt" || true
  fi
done
"$readelf" -d "$stage/$artifact" > "$stage/probe-dynamic-section.txt"
"$readelf" -h "$stage/$artifact" > "$stage/probe-elf-header.txt"

license_roots=("$source_dir")
if [[ "$backend" == eden ]]; then
  license_roots+=("$build_dir")
fi
find "${license_roots[@]}" -path '*/.git' -prune -o -type f \( -iname 'license*' -o -iname 'copying*' -o -iname 'notice*' \) -print0 \
  | tar --null -czf "$stage/licenses.tar.gz" --files-from -
tar -tzf "$stage/licenses.tar.gz" > "$stage/license-files.txt"
if [[ ! -s "$stage/license-files.txt" ]]; then
  echo "Hokkaido source license inventory is empty" >&2
  exit 1
fi

checksums=("$artifact" source-lock.json host-tool-lock.json glslang-LICENSE.txt
  glslang-version.txt host-tool-sha256.txt cxx-runtime-sha256.txt submodule-status.txt cmake-version.txt
  clang-version.txt CMakeCache.txt build-manifest.txt shared-libraries.txt
  shared-library-sha256.txt probe-dynamic-section.txt probe-elf-header.txt
  licenses.tar.gz license-files.txt)
if [[ "$target" == pocket_switch2 ]]; then
  checksums+=(pocket-native-source.cpp sdl-java-bootstrap.tar.gz)
elif [[ "$backend" == eden ]]; then
  checksums+=(sdl-java-bootstrap.tar.gz sdl-java-sha256.txt)
fi
(cd "$stage" && sha256sum "${checksums[@]}" > SHA256SUMS)
mv "$stage" "$output_dir"
trap - EXIT
echo "Built native source target: $output_dir/$artifact"
