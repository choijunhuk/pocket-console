if(NOT ANDROID OR NOT TARGET yuzu-android)
    message(FATAL_ERROR "Pocket Switch 2 SDK requires the pinned Android native target")
endif()

get_target_property(upstream_sources yuzu-android SOURCES)
get_target_property(upstream_source_dir yuzu-android SOURCE_DIR)
get_target_property(upstream_links yuzu-android LINK_LIBRARIES)
if(NOT upstream_sources OR NOT upstream_links)
    message(FATAL_ERROR "Pinned Android target has no sources or links")
endif()
set(sdk_sources)
foreach(source IN LISTS upstream_sources)
    if(source STREQUAL "emu_window/emu_window.cpp")
        continue()
    endif()
    if(source STREQUAL "native.cpp")
        file(READ "${upstream_source_dir}/native.cpp" native_source)
        string(FIND "${native_source}" "static EmulationSession s_instance;" instance_decl)
        string(FIND "${native_source}" "return s_instance;" instance_return)
        if(instance_decl LESS 0 OR instance_return LESS 0)
            message(FATAL_ERROR "Pinned session initialization contract changed")
        endif()
        string(REPLACE "static EmulationSession s_instance;" "" native_source "${native_source}")
        string(REPLACE "return s_instance;"
            "static EmulationSession s_instance; return s_instance;" native_source "${native_source}")
        string(FIND "${native_source}" "s_instance.System()" multiplayer_reference)
        if(multiplayer_reference LESS 0)
            message(FATAL_ERROR "Pinned multiplayer session reference changed")
        endif()
        string(REPLACE "s_instance.System()" "EmulationSession::GetInstance().System()"
            native_source "${native_source}")
        string(REGEX MATCHALL "s_instance" instance_references "${native_source}")
        list(LENGTH instance_references instance_count)
        if(NOT instance_count EQUAL 2)
            message(FATAL_ERROR "Pinned session has another eager reference")
        endif()
        set(run_before [=[void EmulationSession::RunEmulation() {
    {
        std::scoped_lock lock(m_mutex);
        m_is_running = true;
    }]=])
        set(run_after [=[void EmulationSession::RunEmulation() {
    bool start;
    {
        std::scoped_lock lock(m_mutex);
        start = !pocket_stop_gate.Requested();
        m_is_running = start;
    }
    if (!start) return;]=])
        set(scope_before [=[    SCOPE_EXIT {
        EmulationSession::GetInstance().ShutdownEmulation();
    };]=])
        set(scope_after [=[    if (pocket_stop_gate.Requested()) return Core::SystemResultStatus::Success;

    SCOPE_EXIT {
        EmulationSession::GetInstance().ShutdownEmulation();
    };]=])
        set(wait_before [=[                    return !m_is_running || m_pending_shader_cache_title.has_value();
                })) {
                if (!m_is_running) {]=])
        set(wait_after [=[                    return !m_is_running || m_pending_shader_cache_title.has_value()
                           || pocket_stop_gate.Requested();
                })) {
                if (!m_is_running || pocket_stop_gate.Requested()) {]=])
        set(surface_before [=[void EmulationSession::SurfaceChanged() {
    if (!IsRunning()) {
        return;
    }
    m_window->OnSurfaceChanged(m_native_window);
}]=])
        set(surface_after [=[void EmulationSession::SurfaceChanged() {
    std::scoped_lock lock(m_mutex);
    if (!m_is_running || !m_window || pocket_stop_gate.Requested()) {
        return;
    }
    m_window->OnSurfaceChanged(m_native_window);
}]=])
        set(start_core_before [=[    void(m_system.Run());]=])
        set(start_core_after [=[    if (pocket_stop_gate.Requested()) return;
    void(m_system.Run());]=])
        # The offline Android virtual gamepad does not use Cemuhook UDP controllers.
        set(offline_udp_before [=[        m_input_subsystem.Initialize();]=])
        set(offline_udp_after [=[        Settings::values.udp_input_servers.SetValue("");
        m_input_subsystem.Initialize();]=])
        set(stop_core_before [=[    // Unload user input.
    m_system.HIDCore().UnloadInputDevices();]=])
        set(stop_core_after [=[    // Suspend the guest before GPU shutdown can invalidate its in-flight NVDRV requests.
    if (m_load_result == Core::SystemResultStatus::Success) m_system.Pause();
    // Unload user input.
    m_system.HIDCore().UnloadInputDevices();]=])
        foreach(patch IN ITEMS run scope wait surface start_core offline_udp stop_core)
            string(FIND "${native_source}" "${${patch}_before}" patch_location)
            if(patch_location LESS 0)
                message(FATAL_ERROR "Pinned ${patch} source seam changed")
            endif()
            string(REPLACE "${${patch}_before}" "${${patch}_after}"
                native_source "${native_source}")
        endforeach()
        file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/pocket-switch-sdk")
        file(WRITE "${CMAKE_BINARY_DIR}/pocket-switch-sdk/native.cpp" "${native_source}")
        list(APPEND sdk_sources "${CMAKE_BINARY_DIR}/pocket-switch-sdk/native.cpp")
        continue()
    endif()
    if(source MATCHES "^\\$<")
        message(FATAL_ERROR "Unreviewed generated Android source: ${source}")
    elseif(IS_ABSOLUTE "${source}")
        list(APPEND sdk_sources "${source}")
    else()
        list(APPEND sdk_sources "${upstream_source_dir}/${source}")
    endif()
endforeach()

# Keep the pinned core tree pristine; reject drift at the two offline Android seams.
macro(pocket_replace_exact old new label)
    string(FIND "${content}" "${old}" found)
    if(found LESS 0)
        message(FATAL_ERROR "Pinned offline ${label} source seam changed")
    endif()
    string(LENGTH "${old}" anchor_length)
    math(EXPR remainder_start "${found} + ${anchor_length}")
    string(SUBSTRING "${content}" ${remainder_start} -1 remainder)
    string(FIND "${remainder}" "${old}" duplicate)
    if(NOT duplicate LESS 0)
        message(FATAL_ERROR "Pinned offline ${label} source seam duplicated")
    endif()
    string(REPLACE "${old}" "${new}" content "${content}")
endmacro()
get_target_property(core_sources core SOURCES)
foreach(patch IN ITEMS network bsd)
    if(patch STREQUAL "network")
        set(source "internal_network/network.cpp")
        set(before [=[    case ENETDOWN:
        return Errno::NETDOWN;]=])
        set(after [=[    case ENETDOWN:
#ifdef __ANDROID__
    case EPERM: // Android without INTERNET permission reports network unavailable.
#endif
        return Errno::NETDOWN;]=])
    else()
        set(source "hle/service/sockets/bsd.cpp")
        set(before [=[    Network::Errno host_errno =
        socket.Initialize(Network::Domain::INET, Network::Type::DGRAM, Network::Protocol::UDP);]=])
        set(after [=[#ifdef __ANDROID__
    int fds[2];
    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, fds) != 0) {
        return Network::Errno::OTHER;
    }
    socket.Adopt(fds[0]);
    wake_sender.Adopt(fds[1]);
    const Network::Errno receiver_error = socket.SetNonBlock(true);
    if (receiver_error != Network::Errno::SUCCESS) return receiver_error;
    return wake_sender.SetNonBlock(true);
#else
    Network::Errno host_errno =
        socket.Initialize(Network::Domain::INET, Network::Type::DGRAM, Network::Protocol::UDP);]=])
    endif()
    list(FIND core_sources "${source}" source_index)
    if(source_index LESS 0)
        message(FATAL_ERROR "Pinned offline ${patch} source target changed")
    endif()
    set(path "${CMAKE_SOURCE_DIR}/src/core/${source}")
    file(READ "${path}" content)
    pocket_replace_exact("${before}" "${after}" "${patch}")
    if(patch STREQUAL "bsd")
        set(before "/// Opens a non-blocking host loopback UDP socket")
        set(after [=[#ifdef __ANDROID__
class OfflineWakeSocket : public Network::Socket {
public:
    void Adopt(int socket_fd) { fd = socket_fd; }
};
#else
using OfflineWakeSocket = Network::Socket;
#endif

/// Opens a non-blocking host wake socket]=])
        pocket_replace_exact("${before}" "${after}" "bsd description")
        pocket_replace_exact("    return socket.SetNonBlock(true);\n}"
            "    return socket.SetNonBlock(true);\n#endif\n}" "bsd close")
        pocket_replace_exact("OpenSignalSocket(Network::Socket& socket)"
            "OpenSignalSocket(OfflineWakeSocket& socket, OfflineWakeSocket& wake_sender)" "bsd signature")
        pocket_replace_exact("OpenSignalSocket(wake_socket)"
            "OpenSignalSocket(wake_socket, wake_sender)" "bsd caller")
        pocket_replace_exact("        const Network::Errno host_errno = wake_socket.Send(token, 0).second;"
            "#ifdef __ANDROID__\n        const Network::Errno host_errno = wake_sender.Send(token, 0).second;\n#else\n        const Network::Errno host_errno = wake_socket.Send(token, 0).second;\n#endif" "bsd send")
        pocket_replace_exact("    Network::Socket wake_socket;"
            "    OfflineWakeSocket wake_socket;\n    OfflineWakeSocket wake_sender;" "bsd members")
        pocket_replace_exact("#include <fmt/ranges.h>"
            "#ifdef __ANDROID__\n#include <sys/socket.h>\n#endif\n#include <fmt/ranges.h>" "bsd include")
    endif()
    set(generated "${CMAKE_BINARY_DIR}/pocket-switch-sdk/offline-${patch}.cpp")
    file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/pocket-switch-sdk")
    file(WRITE "${generated}" "${content}")
    list(REMOVE_AT core_sources ${source_index})
    list(APPEND core_sources "${generated}")
endforeach()
set_property(TARGET core PROPERTY SOURCES "${core_sources}")

# The original Android JNI cache eagerly resolves Kotlin application models.
foreach(property SOURCES INTERFACE_SOURCES)
    get_target_property(common_sources common ${property})
    if(common_sources)
        list(FILTER common_sources EXCLUDE REGEX
            "(^|/)android/(id_cache|applets/software_keyboard)\\.cpp($|>)")
        set_property(TARGET common PROPERTY ${property} "${common_sources}")
    endif()
endforeach()
set_target_properties(yuzu-android PROPERTIES EXCLUDE_FROM_ALL TRUE)

add_library(pocket_switch2 SHARED ${sdk_sources}
    "${CMAKE_CURRENT_LIST_DIR}/id_cache.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/emu_window.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/first_frame.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/software_keyboard.cpp")
target_include_directories(pocket_switch2 BEFORE PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${CMAKE_SOURCE_DIR}/src/common/android"
    "${upstream_source_dir}"
    "${CMAKE_SOURCE_DIR}/src/android/app/src/main")
get_target_property(upstream_includes yuzu-android INCLUDE_DIRECTORIES)
if(upstream_includes)
    target_include_directories(pocket_switch2 PRIVATE ${upstream_includes})
endif()
get_target_property(upstream_definitions yuzu-android COMPILE_DEFINITIONS)
if(upstream_definitions)
    target_compile_definitions(pocket_switch2 PRIVATE ${upstream_definitions})
endif()
get_target_property(upstream_options yuzu-android COMPILE_OPTIONS)
if(upstream_options)
    target_compile_options(pocket_switch2 PRIVATE ${upstream_options})
endif()
target_link_libraries(pocket_switch2 PRIVATE ${upstream_links})
if(NOT TARGET hook_impl OR NOT TARGET main_hook)
    message(FATAL_ERROR "Pinned Vulkan hook targets are missing")
endif()
add_dependencies(pocket_switch2 hook_impl main_hook)
target_link_options(pocket_switch2 PRIVATE "-Wl,-Bsymbolic")
set_target_properties(pocket_switch2 PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/pocket-switch-sdk")
