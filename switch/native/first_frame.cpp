// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/android/id_cache.h"
#include "hid_core/frontend/emulated_controller.h"
#include "hid_core/hid_core.h"
#include "input_common/drivers/virtual_gamepad.h"
#include "jni/native.h"

PocketStopGate pocket_stop_gate;

extern "C" JNIEXPORT jlong JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_resetPocketStopRequest(JNIEnv*, jclass) {
    return static_cast<jlong>(pocket_stop_gate.Reset());
}

extern "C" JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_requestPocketStop(JNIEnv*, jclass, jlong generation) {
    pocket_stop_gate.Request(static_cast<std::uint64_t>(generation));
}

extern "C" JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_haltPocketSession(JNIEnv*, jclass, jlong generation) {
    pocket_stop_gate.ApplyIfCurrent(static_cast<std::uint64_t>(generation), [] {
        EmulationSession::GetInstance().HaltEmulation();
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_pausePocketSession(JNIEnv*, jclass, jlong generation) {
    pocket_stop_gate.ApplyIfCurrent(static_cast<std::uint64_t>(generation), [] {
        auto& session = EmulationSession::GetInstance();
        if (!pocket_stop_gate.Requested() && session.IsRunning()) session.PauseEmulation();
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_unpausePocketSession(JNIEnv*, jclass, jlong generation) {
    pocket_stop_gate.ApplyIfCurrent(static_cast<std::uint64_t>(generation), [] {
        auto& session = EmulationSession::GetInstance();
        if (!pocket_stop_gate.Requested() && session.IsRunning()) session.UnPauseEmulation();
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_refreshPocketSurface(JNIEnv*, jclass, jlong generation) {
    pocket_stop_gate.ApplyIfCurrent(static_cast<std::uint64_t>(generation), [] {
        if (!pocket_stop_gate.Requested()) EmulationSession::GetInstance().SurfaceChanged();
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_probeNativeBridge(JNIEnv*, jclass) {
    return 0x50435332; // PCS2: this Pocket Console adapter, without constructing a session.
}

void EmulationSession::OnFirstFramePresented() {
    if (pocket_stop_gate.Requested()) return;
    JNIEnv* env = Common::Android::GetEnvForThread();
    jclass callbacks = Common::Android::GetNativeLibraryClass();
    jmethodID presented = env->GetStaticMethodID(callbacks, "onFirstFramePresented", "()V");
    if (presented != nullptr) {
        env->CallStaticVoidMethod(callbacks, presented);
    }
}

extern "C" void Java_org_yuzu_yuzu_1emu_features_input_NativeInput_connectPocketHandheld(
    JNIEnv*, jclass) {
    auto& hid = EmulationSession::GetInstance().System().HIDCore();
    auto* handheld = hid.GetEmulatedController(Core::HID::NpadIdType::Handheld);
    auto* player_one = hid.GetEmulatedController(Core::HID::NpadIdType::Player1);
    handheld->EnableConfiguration();
    player_one->EnableConfiguration();
    handheld->Connect();
    player_one->Disconnect();
    handheld->DisableConfiguration();
    player_one->DisableConfiguration();
    handheld->SaveCurrentConfig();
    player_one->SaveCurrentConfig();
}

extern "C" JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_features_input_NativeInput_resetPocketState(JNIEnv*, jclass) {
    EmulationSession::GetInstance().GetInputSubsystem().GetVirtualGamepad()->ResetControllers();
}
