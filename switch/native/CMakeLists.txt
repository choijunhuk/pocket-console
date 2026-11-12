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
        foreach(patch IN ITEMS run scope wait surface start_core)
            string(FIND "${native_source}" "${${patch}_before}" patch_location)
            if(patch_location LESS 0)
                message(FATAL_ERROR "Pinned ${patch} cancellation seam changed")
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
target_link_options(pocket_switch2 PRIVATE "-Wl,-Bsymbolic")
set_target_properties(pocket_switch2 PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/pocket-switch-sdk")
