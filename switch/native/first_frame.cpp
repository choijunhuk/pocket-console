// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/android/id_cache.h"
#include "hid_core/frontend/emulated_controller.h"
#include "hid_core/hid_core.h"
#include "input_common/drivers/virtual_gamepad.h"
#include "jni/native.h"

void EmulationSession::OnFirstFramePresented() {
    JNIEnv* env = Common::Android::GetEnvForThread();
    jclass callbacks = Common::Android::GetNativeLibraryClass();
    jmethodID presented = env->GetStaticMethodID(callbacks, "onFirstFramePresented", "()V");
    if (presented != nullptr) {
        env->CallStaticVoidMethod(callbacks, presented);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_refreshSurface(JNIEnv*, jclass) {
    EmulationSession::GetInstance().SurfaceChanged();
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
