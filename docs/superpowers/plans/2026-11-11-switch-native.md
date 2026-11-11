# Switch backend source and native-adapter plan

**User goal:** One Android Pocket Console app for DS, 3DS, Switch and Switch 2, with authentic handheld UI/UX. Reference Galaxy S23 Ultra; support decisions must use actual capabilities, never a model whitelist.

## Selected boundary
- Keep Eden as the maintained Switch 1 baseline and Hokkaido as an experimental Switch 2 candidate.
- Pin Hokkaido android-0.6.2 to `eb85a37114f492ce186a23a82ea0965e7139dcb6`; Eden candidate inspected at `cf654052c416f9e7c80cef0a7d321476b1c52f99`.
- Build separate native targets/artifacts; do not import both upstream application shells or ship duplicate `libyuzu-android.so`/org.yuzu classes.
- Own a thin JNI/session/window frontend that preserves upstream emulation lifecycle, filesystem, content providers, shader cache, applets, program switching and shutdown.
- Use a real Android Surface/ANativeWindow and Vulkan presentation, unlike the DS/3DS CPU-frame handoff.
- Keep required native SDL audio plus generated Java bootstrap. Replace eager application-model JNI cache with explicit platform/JVM callbacks, not fake stubs.
- Require absolute private paths for ROM/settings/saves/cache. Keep validated/cancellable Java imports and no game/firmware/key downloads.
- Implement keyboard completion/cancellation honestly or fail the affected session explicitly. Preserve FFmpeg JVM registration and exactly one VMA implementation.
- Separate non-exported :switch and :switch2 processes isolate singleton state; distinct SONAMEs and a verified DT_NEEDED inventory prevent APK path collisions. Do not use pickFirst for mismatched binaries.

## Source/build lane first
Files: new `third_party/switch*.lock.json`, source/build preparation scripts, `.github/workflows/switch-backends.yml`, source-preparation tests.
1. Reproduce source preparation and upstream Android native target build without changing the Pocket Console app or claiming runtime success.
2. Pin every source commit/submodule graph and record toolchain/flags/licenses; handle unreachable upstream dependencies explicitly.
3. Produce a probe artifact under a non-product filename and inspect ELF/DT_NEEDED/SONAME. A renamed file alone is not a renamed SONAME.
4. Hokkaido is ARM64 Android 13+; tag Gradle uses NDK 28.2.13676358 and CMake 3.31.5, while its workflow installs another CMake version. Resolve actual compiler compatibility from build evidence.

## Following adapter lanes
- Per-backend CMake target linking audio_core/common/core/input_common/frontend_common/video_core; preserve or deliberately replace Android sources added PUBLIC through common.
- Adapt session initialization/load/run/pause/shader/surface/shutdown logic, native Android window and virtual_gamepad/touch seam.
- Minimal Java Surface player, callbacks and SDL bootstrap; no transplanted Material/Fragments/Room/Hilt application shell.
- Original NRO guest for real presentation, input, audio, pause/surface recreation and clean shutdown. Loaded callback alone is not first-frame proof.

## Acceptance boundaries
- Hokkaido maintainer reports narrow Exynos/Xclipse results; S23 Ultra/Adreno and broad compatibility are not reproduced.
- Switch 1 regressions and Switch 2 program/game compatibility are separate. An original Switch NRO proves host integration, not Switch 2 commercial games.
- Unsupported graphics/device loss or failed smoke leaves the backend unavailable with a real diagnostic. Preview/catalog/device prerequisite checks never mean engine readiness.
- Existing DS/3DS implementations and saves must remain intact; source/build spikes do not change application readiness.
