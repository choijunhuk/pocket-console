#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
android_jar="$sdk_root/platforms/android-36/android.jar"
if [[ ! -f "$android_jar" ]]; then
  echo "Android SDK 36 is required" >&2
  exit 2
fi
output="$repo_root/.cache/switch-java-check"
mkdir -p "$output"
javac --release 17 -cp "$android_jar" -d "$output" \
  "$repo_root/app/src/main/java/org/yuzu/yuzu_emu/NativeLibrary.java" \
  "$repo_root/app/src/main/java/org/yuzu/yuzu_emu/features/input/NativeInput.java" \
  "$repo_root/app/src/main/java/org/yuzu/yuzu_emu/applets/keyboard/SoftwareKeyboard.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/ConsoleUi.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/ConsoleType.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/DeviceCapabilities.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/DeviceProbe.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/Switch2PlayerActivity.java" \
  "$repo_root/tests/SwitchNativeLibraryTest.java"
java -cp "$output:$android_jar" SwitchNativeLibraryTest
