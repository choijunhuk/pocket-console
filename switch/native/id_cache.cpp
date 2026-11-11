// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <jni.h>

#include "applets/software_keyboard.h"
#include "applets/web_browser.h"
#include "common/android/id_cache.h"
#include "common/assert.h"
#include "common/fs/fs_android.h"
#include "video_core/rasterizer_interface.h"
#include "common/android/multiplayer/multiplayer.h"
#include <network/network.h>


static JavaVM *s_java_vm;
static jclass s_native_library_class;
static jclass s_disk_cache_progress_class;
static jclass s_load_callback_stage_class;
static jclass s_game_dir_class;
static jmethodID s_game_dir_constructor;
static jmethodID s_exit_emulation_activity;
static jmethodID s_disk_cache_load_progress;
static jmethodID s_on_emulation_started;
static jmethodID s_on_emulation_stopped;
static jmethodID s_on_program_changed;
static jmethodID s_copy_to_storage;
static jmethodID s_file_exists;
static jmethodID s_file_extension;

static jclass s_game_class;
static jmethodID s_game_constructor;
static jfieldID s_game_title_field;
static jfieldID s_game_path_field;
static jfieldID s_game_program_id_field;
static jfieldID s_game_developer_field;
static jfieldID s_game_version_field;
static jfieldID s_game_is_homebrew_field;

static jclass s_string_class;
static jclass s_pair_class;
static jmethodID s_pair_constructor;
static jfieldID s_pair_first_field;
static jfieldID s_pair_second_field;

static jclass s_overlay_control_data_class;
static jmethodID s_overlay_control_data_constructor;
static jfieldID s_overlay_control_data_id_field;
static jfieldID s_overlay_control_data_enabled_field;
static jfieldID s_overlay_control_data_individual_scale_field;
static jfieldID s_overlay_control_data_landscape_position_field;
static jfieldID s_overlay_control_data_portrait_position_field;
static jfieldID s_overlay_control_data_foldable_position_field;

static jclass s_patch_class;
static jmethodID s_patch_constructor;
static jfieldID s_patch_enabled_field;
static jfieldID s_patch_name_field;
static jfieldID s_patch_version_field;
static jfieldID s_patch_type_field;
static jfieldID s_patch_program_id_field;
static jfieldID s_patch_title_id_field;

static jclass s_double_class;
static jmethodID s_double_constructor;
static jmethodID s_double_value_method;

static jclass s_integer_class;
static jmethodID s_integer_constructor;
static jmethodID s_integer_value_method;

static jclass s_boolean_class;
static jmethodID s_boolean_constructor;
static jmethodID s_boolean_value_method;

static jclass s_player_input_class;
static jmethodID s_player_input_constructor;
static jfieldID s_player_input_connected_field;
static jfieldID s_player_input_buttons_field;
static jfieldID s_player_input_analogs_field;
static jfieldID s_player_input_motions_field;
static jfieldID s_player_input_vibration_enabled_field;
static jfieldID s_player_input_vibration_strength_field;
static jfieldID s_player_input_body_color_left_field;
static jfieldID s_player_input_body_color_right_field;
static jfieldID s_player_input_button_color_left_field;
static jfieldID s_player_input_button_color_right_field;
static jfieldID s_player_input_profile_name_field;
static jfieldID s_player_input_use_system_vibrator_field;

static jclass s_yuzu_input_device_interface;
static jmethodID s_yuzu_input_device_get_name;
static jmethodID s_yuzu_input_device_get_guid;
static jmethodID s_yuzu_input_device_get_port;
static jmethodID s_yuzu_input_device_get_supports_vibration;
static jmethodID s_yuzu_input_device_vibrate;
static jmethodID s_yuzu_input_device_get_axes;
static jmethodID s_yuzu_input_device_has_keys;

static jmethodID s_add_netplay_message;
static jmethodID s_clear_chat;

static constexpr jint JNI_VERSION = JNI_VERSION_1_6;

namespace Common::Android {
    JNIEnv *GetEnvForThread() {
        thread_local static struct OwnedEnv {
            OwnedEnv() {
                status = s_java_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
                if (status == JNI_EDETACHED)
                    s_java_vm->AttachCurrentThread(&env, nullptr);
            }

            ~OwnedEnv() {
                if (status == JNI_EDETACHED)
                    s_java_vm->DetachCurrentThread();
            }

            int status;
            JNIEnv *env = nullptr;
        } owned;
        return owned.env;
    }

    jclass GetNativeLibraryClass() {
        return s_native_library_class;
    }

    jclass GetDiskCacheProgressClass() {
        return s_disk_cache_progress_class;
    }

    jclass GetDiskCacheLoadCallbackStageClass() {
        return s_load_callback_stage_class;
    }

    jclass GetGameDirClass() {
        return s_game_dir_class;
    }

    jmethodID GetGameDirConstructor() {
        return s_game_dir_constructor;
    }

    jmethodID GetExitEmulationActivity() {
        return s_exit_emulation_activity;
    }

    jmethodID GetDiskCacheLoadProgress() {
        return s_disk_cache_load_progress;
    }

    jmethodID GetCopyToStorage() {
        return s_copy_to_storage;
    }

    jmethodID GetFileExists() {
        return s_file_exists;
    }

    jmethodID GetFileExtension() {
        return s_file_extension;
    }

    jmethodID GetOnEmulationStarted() {
        return s_on_emulation_started;
    }

    jmethodID GetOnEmulationStopped() {
        return s_on_emulation_stopped;
    }

    jmethodID GetOnProgramChanged() {
        return s_on_program_changed;
    }

    jclass GetGameClass() {
        return s_game_class;
    }

    jmethodID GetGameConstructor() {
        return s_game_constructor;
    }

    jfieldID GetGameTitleField() {
        return s_game_title_field;
    }

    jfieldID GetGamePathField() {
        return s_game_path_field;
    }

    jfieldID GetGameProgramIdField() {
        return s_game_program_id_field;
    }

    jfieldID GetGameDeveloperField() {
        return s_game_developer_field;
    }

    jfieldID GetGameVersionField() {
        return s_game_version_field;
    }

    jfieldID GetGameIsHomebrewField() {
        return s_game_is_homebrew_field;
    }

    jclass GetStringClass() {
        return s_string_class;
    }

    jclass GetPairClass() {
        return s_pair_class;
    }

    jmethodID GetPairConstructor() {
        return s_pair_constructor;
    }

    jfieldID GetPairFirstField() {
        return s_pair_first_field;
    }

    jfieldID GetPairSecondField() {
        return s_pair_second_field;
    }

    jclass GetOverlayControlDataClass() {
        return s_overlay_control_data_class;
    }

    jmethodID GetOverlayControlDataConstructor() {
        return s_overlay_control_data_constructor;
    }

    jfieldID GetOverlayControlDataIdField() {
        return s_overlay_control_data_id_field;
    }

    jfieldID GetOverlayControlDataEnabledField() {
        return s_overlay_control_data_enabled_field;
    }

    jfieldID GetOverlayControlDataIndividualScaleField() {
        return s_overlay_control_data_individual_scale_field;
    }

    jfieldID GetOverlayControlDataLandscapePositionField() {
        return s_overlay_control_data_landscape_position_field;
    }

    jfieldID GetOverlayControlDataPortraitPositionField() {
        return s_overlay_control_data_portrait_position_field;
    }

    jfieldID GetOverlayControlDataFoldablePositionField() {
        return s_overlay_control_data_foldable_position_field;
    }

    jclass GetPatchClass() {
        return s_patch_class;
    }

    jmethodID GetPatchConstructor() {
        return s_patch_constructor;
    }

    jfieldID GetPatchEnabledField() {
        return s_patch_enabled_field;
    }

    jfieldID GetPatchNameField() {
        return s_patch_name_field;
    }

    jfieldID GetPatchVersionField() {
        return s_patch_version_field;
    }

    jfieldID GetPatchTypeField() {
        return s_patch_type_field;
    }

    jfieldID GetPatchProgramIdField() {
        return s_patch_program_id_field;
    }

    jfieldID GetPatchTitleIdField() {
        return s_patch_title_id_field;
    }

    jclass GetDoubleClass() {
        return s_double_class;
    }

    jmethodID GetDoubleConstructor() {
        return s_double_constructor;
    }

    jmethodID GetDoubleValueMethod() {
        return s_double_value_method;
    }

    jclass GetIntegerClass() {
        return s_integer_class;
    }

    jmethodID GetIntegerConstructor() {
        return s_integer_constructor;
    }

    jmethodID GetIntegerValueMethod() {
        return s_integer_value_method;
    }

    jclass GetBooleanClass() {
        return s_boolean_class;
    }

    jmethodID GetBooleanConstructor() {
        return s_boolean_constructor;
    }

    jmethodID GetBooleanValueMethod() {
        return s_boolean_value_method;
    }

    jclass GetPlayerInputClass() {
        return s_player_input_class;
    }

    jmethodID GetPlayerInputConstructor() {
        return s_player_input_constructor;
    }

    jfieldID GetPlayerInputConnectedField() {
        return s_player_input_connected_field;
    }

    jfieldID GetPlayerInputButtonsField() {
        return s_player_input_buttons_field;
    }

    jfieldID GetPlayerInputAnalogsField() {
        return s_player_input_analogs_field;
    }

    jfieldID GetPlayerInputMotionsField() {
        return s_player_input_motions_field;
    }

    jfieldID GetPlayerInputVibrationEnabledField() {
        return s_player_input_vibration_enabled_field;
    }

    jfieldID GetPlayerInputVibrationStrengthField() {
        return s_player_input_vibration_strength_field;
    }

    jfieldID GetPlayerInputBodyColorLeftField() {
        return s_player_input_body_color_left_field;
    }

    jfieldID GetPlayerInputBodyColorRightField() {
        return s_player_input_body_color_right_field;
    }

    jfieldID GetPlayerInputButtonColorLeftField() {
        return s_player_input_button_color_left_field;
    }

    jfieldID GetPlayerInputButtonColorRightField() {
        return s_player_input_button_color_right_field;
    }

    jfieldID GetPlayerInputProfileNameField() {
        return s_player_input_profile_name_field;
    }

    jfieldID GetPlayerInputUseSystemVibratorField() {
        return s_player_input_use_system_vibrator_field;
    }

    jclass GetYuzuInputDeviceInterface() {
        return s_yuzu_input_device_interface;
    }

    jmethodID GetYuzuDeviceGetName() {
        return s_yuzu_input_device_get_name;
    }

    jmethodID GetYuzuDeviceGetGUID() {
        return s_yuzu_input_device_get_guid;
    }

    jmethodID GetYuzuDeviceGetPort() {
        return s_yuzu_input_device_get_port;
    }

    jmethodID GetYuzuDeviceGetSupportsVibration() {
        return s_yuzu_input_device_get_supports_vibration;
    }

    jmethodID GetYuzuDeviceVibrate() {
        return s_yuzu_input_device_vibrate;
    }

    jmethodID GetYuzuDeviceGetAxes() {
        return s_yuzu_input_device_get_axes;
    }

    jmethodID GetYuzuDeviceHasKeys() {
        return s_yuzu_input_device_has_keys;
    }

    jmethodID GetAddNetPlayMessage() {
        return s_add_netplay_message;
    }

    jmethodID ClearChat() {
        return s_clear_chat;
    }

#ifdef __cplusplus
    extern "C" {
#endif

    jint InitFFmpegOnLoad(JavaVM *vm);

    void JNI_OnUnload(JavaVM *vm, void *reserved) {
        JNIEnv *env;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION) != JNI_OK) {
            return;
        }

        // UnInitialize Android Storage
        Common::FS::Android::UnRegisterCallbacks();
        env->DeleteGlobalRef(s_native_library_class);
        env->DeleteGlobalRef(s_disk_cache_progress_class);
        env->DeleteGlobalRef(s_load_callback_stage_class);
        env->DeleteGlobalRef(s_game_dir_class);
        env->DeleteGlobalRef(s_game_class);
        env->DeleteGlobalRef(s_string_class);
        env->DeleteGlobalRef(s_pair_class);
        env->DeleteGlobalRef(s_overlay_control_data_class);
        env->DeleteGlobalRef(s_patch_class);
        env->DeleteGlobalRef(s_double_class);
        env->DeleteGlobalRef(s_integer_class);
        env->DeleteGlobalRef(s_boolean_class);
        env->DeleteGlobalRef(s_player_input_class);
        env->DeleteGlobalRef(s_yuzu_input_device_interface);

        // UnInitialize applets
        SoftwareKeyboard::CleanupJNI(env);
        WebBrowser::CleanupJNI(env);

        AndroidMultiplayer::NetworkShutdown();
    }

#ifdef __cplusplus
    }
#endif

void Initialize(JavaVM* vm, JNIEnv* env) {
    if (vm == nullptr || env == nullptr || env->ExceptionCheck()) {
        return;
    }
    s_java_vm = vm;
    InitFFmpegOnLoad(vm);

    const jclass callbacks = env->FindClass("org/yuzu/yuzu_emu/NativeLibrary");
    if (callbacks == nullptr) {
        return;
    }
    s_native_library_class = reinterpret_cast<jclass>(env->NewGlobalRef(callbacks));
    s_disk_cache_progress_class = reinterpret_cast<jclass>(env->NewGlobalRef(callbacks));
    s_load_callback_stage_class = reinterpret_cast<jclass>(env->NewGlobalRef(callbacks));
    env->DeleteLocalRef(callbacks);

    s_exit_emulation_activity =
        env->GetStaticMethodID(s_native_library_class, "exitEmulationActivity", "(I)V");
    s_disk_cache_load_progress =
        env->GetStaticMethodID(s_native_library_class, "loadProgress", "(III)V");
    s_on_emulation_started =
        env->GetStaticMethodID(s_native_library_class, "onEmulationStarted", "()V");
    s_on_emulation_stopped =
        env->GetStaticMethodID(s_native_library_class, "onEmulationStopped", "(I)V");
    s_on_program_changed =
        env->GetStaticMethodID(s_native_library_class, "onProgramChanged", "(I)V");
    s_copy_to_storage = env->GetStaticMethodID(
        s_native_library_class, "copyFileToStorage",
        "(Ljava/lang/String;Ljava/lang/String;)Z");
    s_file_exists =
        env->GetStaticMethodID(s_native_library_class, "exists", "(Ljava/lang/String;)Z");
    s_file_extension = env->GetStaticMethodID(
        s_native_library_class, "getFileExtension",
        "(Ljava/lang/String;)Ljava/lang/String;");

    const jclass string_class = env->FindClass("java/lang/String");
    s_string_class = reinterpret_cast<jclass>(env->NewGlobalRef(string_class));
    env->DeleteLocalRef(string_class);
    const jclass double_class = env->FindClass("java/lang/Double");
    s_double_class = reinterpret_cast<jclass>(env->NewGlobalRef(double_class));
    s_double_constructor = env->GetMethodID(double_class, "<init>", "(D)V");
    s_double_value_method = env->GetMethodID(double_class, "doubleValue", "()D");
    env->DeleteLocalRef(double_class);
    const jclass int_class = env->FindClass("java/lang/Integer");
    s_integer_class = reinterpret_cast<jclass>(env->NewGlobalRef(int_class));
    s_integer_constructor = env->GetMethodID(int_class, "<init>", "(I)V");
    s_integer_value_method = env->GetMethodID(int_class, "intValue", "()I");
    env->DeleteLocalRef(int_class);
    const jclass boolean_class = env->FindClass("java/lang/Boolean");
    s_boolean_class = reinterpret_cast<jclass>(env->NewGlobalRef(boolean_class));
    s_boolean_constructor = env->GetMethodID(boolean_class, "<init>", "(Z)V");
    s_boolean_value_method = env->GetMethodID(boolean_class, "booleanValue", "()Z");
    env->DeleteLocalRef(boolean_class);

    Common::FS::Android::RegisterCallbacks(env, s_native_library_class);
    SoftwareKeyboard::InitJNI(env);
    WebBrowser::InitJNI(env);
}

} // namespace Common::Android
