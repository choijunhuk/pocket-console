// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/android/id_cache.h"
#include "common/scope_exit.h"
#include "hid_core/frontend/emulated_controller.h"
#include "hid_core/hid_core.h"
#include "input_common/drivers/virtual_gamepad.h"
#include "jni/native.h"
#include "video_core/vulkan_common/vulkan_device.h"

#include <algorithm>
#include <dlfcn.h>
#include <string_view>

namespace {
std::string PocketVulkanDiagnostic(bool warnings = false) {
    void* library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return "Vulkan loader unavailable";
    SCOPE_EXIT { dlclose(library); };
    auto get = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
    if (!get) return "Vulkan vkGetInstanceProcAddr unavailable";
    const auto version = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        get(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    uint32_t api = VK_API_VERSION_1_0;
    if (!version || version(&api) != VK_SUCCESS || api < VK_API_VERSION_1_3)
        return "Vulkan 1.3 loader required by renderer instance";
    const auto enumerate = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
        get(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties"));
    uint32_t count{};
    if (!enumerate || enumerate(nullptr, &count, nullptr) != VK_SUCCESS)
        return "Vulkan instance extension enumeration failed";
    std::vector<VkExtensionProperties> instance_extensions(count);
    if (enumerate(nullptr, &count, instance_extensions.data()) != VK_SUCCESS)
        return "Vulkan instance extension enumeration failed";
    const char* required[] = {"VK_KHR_surface", "VK_KHR_android_surface"};
    for (const char* extension : required) {
        if (std::none_of(instance_extensions.begin(), instance_extensions.end(), [&](const auto& item) {
                return std::string_view(item.extensionName) == extension;
            })) return std::string("Missing instance extension ") + extension;
    }
    const auto create = reinterpret_cast<PFN_vkCreateInstance>(get(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!create) return "Vulkan vkCreateInstance unavailable";
    const VkApplicationInfo application{.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                                        .pApplicationName = "Pocket Console capability check",
                                        .apiVersion = VK_API_VERSION_1_3};
    const VkInstanceCreateInfo info{.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                    .pApplicationInfo = &application,
                                    .enabledExtensionCount = 2,
                                    .ppEnabledExtensionNames = required};
    VkInstance instance{};
    const auto result = create(&info, nullptr, &instance);
    if (result != VK_SUCCESS) return "Vulkan instance failed: " + std::to_string(result);
    const auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(get(instance, "vkDestroyInstance"));
    if (!destroy) return "Vulkan vkDestroyInstance unavailable";
    SCOPE_EXIT { destroy(instance, nullptr); };
#define POCKET_VK_FUNCTION(name)                                                                    \
    const auto name = reinterpret_cast<PFN_##name>(get(instance, #name));                            \
    if (!name) return "Vulkan " #name " unavailable"
    POCKET_VK_FUNCTION(vkEnumeratePhysicalDevices);
    POCKET_VK_FUNCTION(vkGetPhysicalDeviceProperties);
    POCKET_VK_FUNCTION(vkGetPhysicalDeviceFeatures2);
    POCKET_VK_FUNCTION(vkGetPhysicalDeviceQueueFamilyProperties);
    POCKET_VK_FUNCTION(vkEnumerateDeviceExtensionProperties);
#undef POCKET_VK_FUNCTION
    count = 0;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) != VK_SUCCESS || !count)
        return "Vulkan physical device unavailable";
    std::vector<VkPhysicalDevice> devices(count);
    if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) != VK_SUCCESS || !count)
        return "Vulkan physical device enumeration failed";
    // The packaged renderer uses its default device index, zero.
    const auto device = devices.front();
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(device, &properties);
    const std::string name = properties.deviceName;
    if (properties.apiVersion < VK_API_VERSION_1_1)
        return name + ": Vulkan 1.1 required; actual " +
               std::to_string(VK_VERSION_MAJOR(properties.apiVersion)) + "." +
               std::to_string(VK_VERSION_MINOR(properties.apiVersion));
    VkPhysicalDeviceTimelineSemaphoreFeatures timeline{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES};
    VkPhysicalDeviceVariablePointersFeatures variable_pointer{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VARIABLE_POINTERS_FEATURES, .pNext = &timeline};
    VkPhysicalDeviceShaderDrawParametersFeatures shader_draw_parameters{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_DRAW_PARAMETERS_FEATURES,
        .pNext = &variable_pointer};
    VkPhysicalDeviceShaderDemoteToHelperInvocationFeatures shader_demote_to_helper_invocation{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_DEMOTE_TO_HELPER_INVOCATION_FEATURES,
        .pNext = &shader_draw_parameters};
    VkPhysicalDeviceHostQueryResetFeatures host_query_reset{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES,
        .pNext = &shader_demote_to_helper_invocation};
    VkPhysicalDeviceFeatures2 queried{.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                                      .pNext = &host_query_reset};
    vkGetPhysicalDeviceFeatures2(device, &queried);
    const auto& features = queried.features;
    vkGetPhysicalDeviceQueueFamilyProperties(device, &count, nullptr);
    std::vector<VkQueueFamilyProperties> queues(count);
    vkGetPhysicalDeviceQueueFamilyProperties(device, &count, queues.data());
    if (std::none_of(queues.begin(), queues.end(), [](const auto& queue) {
            return queue.queueCount && (queue.queueFlags & VK_QUEUE_GRAPHICS_BIT);
        })) return name + ": Vulkan graphics queue unavailable";
    std::string missing;
    const auto add = [&](const char* item) {
        if (!missing.empty()) missing += ", ";
        missing += item;
    };
    // Upstream logs unsuitable drivers and continues; these features are advisory.
#define POCKET_REQUIRED_FEATURE(group, field) if (!group.field) add(#field);
    FOR_EACH_VK_MANDATORY_FEATURE(POCKET_REQUIRED_FEATURE)
#undef POCKET_REQUIRED_FEATURE
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS)
        return name + ": Vulkan extension enumeration failed";
    std::vector<VkExtensionProperties> extensions(count);
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()) != VK_SUCCESS)
        return name + ": Vulkan extension enumeration failed";
    const auto require_extension = [&](const char* required) {
        if (std::none_of(extensions.begin(), extensions.end(), [&](const auto& extension) {
                return std::string_view(extension.extensionName) == required;
            })) add(required);
    };
#define POCKET_REQUIRED_EXTENSION(extension) require_extension(extension);
    FOR_EACH_VK_MANDATORY_EXTENSION(POCKET_REQUIRED_EXTENSION)
#undef POCKET_REQUIRED_EXTENSION
    if (std::none_of(extensions.begin(), extensions.end(), [](const auto& extension) {
            return std::string_view(extension.extensionName) == VK_KHR_SWAPCHAIN_EXTENSION_NAME;
        })) return name + ": missing " + VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    // MasterSemaphore uses fences when timeline semaphores are unavailable.
    if (!timeline.timelineSemaphore) add("timelineSemaphore");
    return warnings && !missing.empty() ? missing : "";
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_pocketVulkanWarning(JNIEnv* env, jclass) {
    try {
        return env->NewStringUTF(PocketVulkanDiagnostic(true).c_str());
    } catch (const std::exception&) {
        return env->NewStringUTF("Vulkan capability query failed");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_pocketVulkanDiagnostic(JNIEnv* env, jclass) {
    try {
        return env->NewStringUTF(PocketVulkanDiagnostic().c_str());
    } catch (const std::exception&) {
        return env->NewStringUTF("Vulkan capability query failed");
    }
}

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
