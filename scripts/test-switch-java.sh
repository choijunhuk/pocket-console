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
  "$repo_root/app/src/main/java/dev/pocketconsole/PreparationProgress.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/SwitchNroPreparation.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/SwitchNroValidator.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/SwitchKeyFile.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/SwitchFirmwareZip.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/SwitchContentStatus.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/SwitchContentService.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/SwitchProbeService.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/Switch2PlayerActivity.java" \
  "$repo_root/app/src/main/java/dev/pocketconsole/SwitchSessionStop.java" \
  "$repo_root/tests/SwitchNativeLibraryTest.java" \
  "$repo_root/tests/SwitchNroPreparationTest.java" \
  "$repo_root/tests/SwitchNroValidatorTest.java" \
  "$repo_root/tests/SwitchSessionStopTest.java" \
  "$repo_root/tests/SwitchKeyFileTest.java" \
  "$repo_root/tests/SwitchFirmwareZipTest.java" \
  "$repo_root/tests/SwitchContentStatusTest.java" \
  "$repo_root/tests/SwitchContentRecoveryTest.java" \
  "$repo_root/tests/SwitchKeyboardRequestTest.java"
java -cp "$output:$android_jar" SwitchNativeLibraryTest
java -cp "$output:$android_jar" dev.pocketconsole.SwitchNroPreparationTest
java -cp "$output:$android_jar" dev.pocketconsole.SwitchNroValidatorTest "${SWITCH_ORIGINAL_NRO:-}"
java -cp "$output:$android_jar" dev.pocketconsole.SwitchSessionStopTest
java -cp "$output:$android_jar" dev.pocketconsole.SwitchKeyFileTest
java -cp "$output:$android_jar" dev.pocketconsole.SwitchFirmwareZipTest
java -cp "$output:$android_jar" dev.pocketconsole.SwitchContentStatusTest
java -cp "$output:$android_jar" dev.pocketconsole.SwitchContentRecoveryTest
java -cp "$output:$android_jar" org.yuzu.yuzu_emu.applets.keyboard.SwitchKeyboardRequestTest
