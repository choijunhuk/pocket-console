# Isolated Switch SDK Android smoke

This project builds only the real Switch 2 Java frontend and the verified ARM64
native SDK closure. It does not trigger the DS, 3DS, or Switch SDK source builds.
Production Java files are linked from `app/src/main/java`; SDL Java and all
`lib/arm64-v8a/*.so` files come from the SDK artifact.

After a successful SDK run and original NRO run, stage their downloaded artifacts:

```sh
python3 tests/android-switch-sdk/prepare.py SDK_ARTIFACT_DIR ORIGINAL_SMOKE_NRO
./gradlew -p tests/android-switch-sdk :app:assembleDebug :app:assembleDebugAndroidTest
```

`prepare.py` checks the exact DSO set and hashes from `runtime-deps.json`,
unresolved DT_NEEDED names, SDL Java paths, and the NRO header. The closure must
also include `libhook_impl.so` and `libmain_hook.so`, which adrenotools opens with
`dlopen`. The APK extracts native libraries so its loader can open those paths.
The separate
`switch-sdk-smoke.yml` workflow runs when its own smoke files change, using
successful SDK and NRO artifact run IDs (overrideable by manual dispatch). It
checks out the SDK run's exact source commit for the linked Java files, then
installs the ARM64-only app on an API 35 Google APIs x86_64 emulator using its
advertised ARM native bridge. Its runner emits
`JNI_LINK_PASS` only after the actual backend loads, SDL/JVM initialization
returns, and a real JNI symbol call succeeds.

`NRO_FRAME_INPUT_PASS` requires the original NRO's blue/red frame and A-triggered
green frame on the real Surface. `NRO_NOT_RUN_UNSUPPORTED_GPU` means JNI loaded,
but the device's Vulkan prerequisite is below 1.3; it is not game or graphics
acceptance. Missing capability data and native failures remain failures or
explicit unknown states. A physical ARM64 device is still needed for device
performance and broader game compatibility.
The workflow's historical default SDK run `43235268180` and the later run
`43692879856` lack the Vulkan hook libraries; staging now rejects them. Build a
hook-complete SDK and select its run ID with `sdk_run_id` on manual dispatch.
The CI shell runner requires x86 ARM translation; on a native ARM64 emulator,
install both APKs with `adb -s SERIAL` and invoke the same instrumentation directly.
Android frame/input/shutdown/reopen acceptance remains pending. This smoke does
not authorize release.
