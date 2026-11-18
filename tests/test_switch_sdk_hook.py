import json
import pathlib
import subprocess
import tempfile
import unittest


HOOK = pathlib.Path(__file__).resolve().parents[1] / "switch/native/project-hook.cmake"


class ProjectHookTests(unittest.TestCase):
    def test_adds_sdk_once_after_original_targets(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / "addon").mkdir()
            (root / "child").mkdir()
            (root / "CMakeLists.txt").write_text(
                "cmake_minimum_required(VERSION 3.19)\n"
                "project(HokkaidoProbe LANGUAGES CXX)\n"
                "add_library(original INTERFACE)\n"
                "add_subdirectory(child)\n"
            )
            (root / "child/CMakeLists.txt").write_text(
                "project(ChildProbe LANGUAGES CXX)\n"
            )
            (root / "addon/CMakeLists.txt").write_text(
                "if(NOT TARGET original)\n"
                "  message(FATAL_ERROR \"original target unavailable\")\n"
                "endif()\n"
                "add_library(pocket_switch2 INTERFACE)\n"
                "file(GENERATE OUTPUT \"${CMAKE_BINARY_DIR}/pocket-sdk-ready.txt\" "
                "CONTENT \"$<TARGET_EXISTS:pocket_switch2>\")\n"
            )
            build = root / "build"
            result = subprocess.run(
                ["cmake", "-S", str(root), "-B", str(build),
                 f"-DCMAKE_PROJECT_INCLUDE={HOOK}",
                 f"-DPOCKET_HOKKAIDO_SOURCE_DIR={root}",
                 f"-DPOCKET_SDK_SOURCE_DIR={root / 'addon'}"],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual((build / "pocket-sdk-ready.txt").read_text(), "1")

    def test_rejects_missing_sdk_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / "CMakeLists.txt").write_text(
                "cmake_minimum_required(VERSION 3.19)\n"
                "project(HokkaidoProbe LANGUAGES CXX)\n"
            )
            result = subprocess.run(
                ["cmake", "-S", str(root), "-B", str(root / "build"),
                 f"-DCMAKE_PROJECT_INCLUDE={HOOK}",
                 f"-DPOCKET_HOKKAIDO_SOURCE_DIR={root}",
                 f"-DPOCKET_SDK_SOURCE_DIR={root / 'missing'}"],
                capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Pocket Console SDK source directory is missing", result.stderr)

    def test_real_sdk_target_excludes_eager_jni_cache(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / "src/common/android").mkdir(parents=True)
            (root / "src/common/android/applets").mkdir(parents=True)
            (root / "src/android/app/src/main/jni").mkdir(parents=True)
            (root / "src/common/android/id_cache.cpp").write_text("void eager_cache() {}\n")
            (root / "src/common/android/applets/software_keyboard.cpp").write_text(
                "void eager_keyboard() {}\n"
            )
            (root / "src/common/common.cpp").write_text("void common_code() {}\n")
            (root / "src/common/hook.cpp").write_text("void hook() {}\n")
            (root / "src/android/app/src/main/jni/native.cpp").write_text(
                """struct EmulationSession { int System() { return 0; } };
static EmulationSession s_instance;
EmulationSession& getInstance() { return s_instance; }
void multiplayer() { s_instance.System(); }
void EmulationSession::InitializeSystem(bool reload) {
    if (!reload) {
        m_input_subsystem.Initialize();
    }
}
void EmulationSession::SurfaceChanged() {
    if (!IsRunning()) {
        return;
    }
    m_window->OnSurfaceChanged(m_native_window);
}
void Java_org_yuzu_yuzu_1emu_NativeLibrary_stopEmulation(JNIEnv* env, jclass clazz) {
    EmulationSession::GetInstance().HaltEmulation();
}
void EmulationSession::RunEmulation() {
    {
        std::scoped_lock lock(m_mutex);
        m_is_running = true;
    }
    void(m_system.Run());
}
static int RunEmulation() {
    SCOPE_EXIT {
        EmulationSession::GetInstance().ShutdownEmulation();
    };
}
void waitLoop() {
                    return !m_is_running || m_pending_shader_cache_title.has_value();
                })) {
                if (!m_is_running) {
}
"""
            )
            (root / "src/android/app/src/main/jni/CMakeLists.txt").write_text(
                "add_library(yuzu-android SHARED native.cpp)\n"
                "target_link_libraries(yuzu-android PRIVATE common)\n"
            )
            (root / "CMakeLists.txt").write_text(
                "cmake_minimum_required(VERSION 3.19)\n"
                "project(HokkaidoProbe LANGUAGES CXX)\n"
                "add_library(common STATIC src/common/common.cpp)\n"
                "add_library(hook_impl SHARED src/common/hook.cpp)\n"
                "add_library(main_hook SHARED src/common/hook.cpp)\n"
                "target_sources(common PUBLIC src/common/android/id_cache.cpp)\n"
                "target_sources(common PUBLIC src/common/android/applets/software_keyboard.cpp)\n"
                "add_subdirectory(src/android/app/src/main/jni)\n"
                "file(GENERATE OUTPUT \"${CMAKE_BINARY_DIR}/pocket-sdk-dependencies.txt\" "
                "CONTENT \"$<TARGET_PROPERTY:pocket_switch2,MANUALLY_ADDED_DEPENDENCIES>\")\n"
            )
            build = root / "build"
            result = subprocess.run(
                ["cmake", "-S", str(root), "-B", str(build), "-DANDROID=ON",
                 "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                 f"-DCMAKE_PROJECT_INCLUDE={HOOK}",
                 f"-DPOCKET_HOKKAIDO_SOURCE_DIR={root}",
                 f"-DPOCKET_SDK_SOURCE_DIR={HOOK.parent}"],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            targets = subprocess.run(
                ["cmake", "--build", str(build), "--target", "help"],
                capture_output=True, text=True, check=True,
            ).stdout
            self.assertIn("pocket_switch2", targets)
            self.assertEqual(set((build / "pocket-sdk-dependencies.txt").read_text().split(";")),
                             {"hook_impl", "main_hook"})
            compiled = {pathlib.Path(item["file"]).resolve() for item in
                        json.loads((build / "compile_commands.json").read_text())}
            self.assertNotIn(root / "src/common/android/id_cache.cpp", compiled)
            self.assertNotIn(root / "src/common/android/applets/software_keyboard.cpp", compiled)
            self.assertIn(HOOK.parent / "id_cache.cpp", compiled)
            self.assertIn(HOOK.parent / "software_keyboard.cpp", compiled)
            generated = (build / "pocket-switch-sdk/native.cpp").read_text()
            self.assertIn("static EmulationSession s_instance; return s_instance;", generated)
            self.assertIn("EmulationSession::GetInstance().System()", generated)
            self.assertNotIn("static EmulationSession s_instance;\n", generated)
            self.assertNotIn("s_instance.System()", generated)
            self.assertIn("start = !pocket_stop_gate.Requested();", generated)
            self.assertIn("if (pocket_stop_gate.Requested()) return Core::SystemResultStatus::Success;", generated)
            self.assertIn("if (!m_is_running || pocket_stop_gate.Requested())", generated)
            self.assertIn("if (!m_is_running || !m_window || pocket_stop_gate.Requested())", generated)
            self.assertIn("if (pocket_stop_gate.Requested()) return;\n    void(m_system.Run());", generated)
            self.assertIn('Settings::values.udp_input_servers.SetValue("");\n        m_input_subsystem.Initialize();', generated)
            native = root / "src/android/app/src/main/jni/native.cpp"
            native.write_text(native.read_text().replace(
                "m_input_subsystem.Initialize();", "m_input_subsystem.Start();"))
            changed = subprocess.run(
                ["cmake", "-S", str(root), "-B", str(root / "changed"), "-DANDROID=ON",
                 f"-DCMAKE_PROJECT_INCLUDE={HOOK}",
                 f"-DPOCKET_HOKKAIDO_SOURCE_DIR={root}",
                 f"-DPOCKET_SDK_SOURCE_DIR={HOOK.parent}"],
                capture_output=True, text=True,
            )
            self.assertNotEqual(changed.returncode, 0)
            self.assertIn("Pinned offline_udp source seam changed", changed.stderr)

    def test_unreviewed_native_target_stops_before_toolchain_setup(self):
        script = HOOK.parents[2] / "scripts/build-switch-probe.sh"
        result = subprocess.run(
            ["bash", str(script), "arm64-v8a", "unknown-target"],
            capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("unsupported native target", result.stderr)


if __name__ == "__main__":
    unittest.main()
