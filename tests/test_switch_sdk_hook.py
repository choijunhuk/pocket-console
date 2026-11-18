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
            (root / "src/core/internal_network").mkdir(parents=True)
            (root / "src/core/hle/service/sockets").mkdir(parents=True)
            (root / "src/android/app/src/main/jni").mkdir(parents=True)
            (root / "src/core/internal_network/network.cpp").write_text(
                "    case ENETDOWN:\n        return Errno::NETDOWN;\n")
            (root / "src/core/hle/service/sockets/bsd.cpp").write_text(
                """#include <fmt/ranges.h>
/// Opens a non-blocking host loopback UDP socket
Network::Errno OpenSignalSocket(Network::Socket& socket) {
    Network::Errno host_errno =
        socket.Initialize(Network::Domain::INET, Network::Type::DGRAM, Network::Protocol::UDP);
    return socket.SetNonBlock(true);
}
OpenSignalSocket(wake_socket);
        const Network::Errno host_errno = wake_socket.Send(token, 0).second;
    Network::Socket wake_socket;
""")
            (root / "src/core/CMakeLists.txt").write_text(
                "add_library(core STATIC internal_network/network.cpp "
                "hle/service/sockets/bsd.cpp)\n")
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
void EmulationSession::ShutdownEmulation() {
    m_is_running = false;
    // Unload user input.
    m_system.HIDCore().UnloadInputDevices();
    if (m_load_result == Core::SystemResultStatus::Success) {
        m_system.ShutdownMainProcess();
    }
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
                "add_subdirectory(src/core)\n"
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
            pause = generated.index("if (m_load_result == Core::SystemResultStatus::Success) m_system.Pause();")
            unload = generated.index("m_system.HIDCore().UnloadInputDevices();")
            shutdown = generated.index("m_system.ShutdownMainProcess();")
            self.assertLess(pause, unload)
            self.assertLess(pause, shutdown)
            offline_bsd = (build / "pocket-switch-sdk/offline-bsd.cpp").read_text()
            offline_net = (build / "pocket-switch-sdk/offline-network.cpp").read_text()
            self.assertIn("socketpair(AF_UNIX, SOCK_DGRAM, 0, fds)", offline_bsd)
            self.assertIn("wake_sender.Send(token, 0)", offline_bsd)
            self.assertIn("case EPERM:", offline_net)
            start = offline_bsd.index("#ifdef __ANDROID__\nclass OfflineWakeSocket")
            function = offline_bsd.index("Network::Errno OpenSignalSocket", start)
            end = offline_bsd.index("\n}\n", function) + 3
            wake_source = offline_bsd[start:end]
            send_line = "const Network::Errno host_errno = wake_sender.Send(token, 0).second;"
            self.assertIn(send_line, offline_bsd)
            errno_start = offline_net.index("    case ENETDOWN:")
            errno_end = offline_net.index("        return Errno::NETDOWN;", errno_start) + len(
                "        return Errno::NETDOWN;")
            errno_source = offline_net[errno_start:errno_end]
            probe = root / "offline-wake-probe.cpp"
            probe.write_text("""#include <array>
#include <cerrno>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <utility>
#define __ANDROID__ 1
namespace Network {
enum class Errno { SUCCESS, NETDOWN, OTHER };
enum class Domain { INET }; enum class Type { DGRAM }; enum class Protocol { UDP };
class Socket {
protected:
    int fd = -1;
public:
    ~Socket() { if (fd >= 0) close(fd); }
    int GetFD() const { return fd; }
    Errno SetNonBlock(bool) {
        return fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK) == 0
            ? Errno::SUCCESS : Errno::OTHER;
    }
    std::pair<int, Errno> Send(const std::array<unsigned char, 1>& token, int flags) {
        int result = send(fd, token.data(), token.size(), flags);
        return {result, result == 1 ? Errno::SUCCESS : Errno::OTHER};
    }
};
}
""" + wake_source + """
Network::Errno TranslateError(int error) {
    using Network::Errno;
    switch (error) {
""" + errno_source + """
    default: return Errno::OTHER;
    }
}
int main() {
    if (TranslateError(EPERM) != Network::Errno::NETDOWN) return 9;
    for (int i = 0; i < 8; ++i) {
        int receiver_fd, sender_fd;
        {
            OfflineWakeSocket wake_socket, wake_sender;
            if (OpenSignalSocket(wake_socket, wake_sender) != Network::Errno::SUCCESS) return 1;
            receiver_fd = wake_socket.GetFD(); sender_fd = wake_sender.GetFD();
            pollfd waiting{receiver_fd, POLLIN, 0};
            if (poll(&waiting, 1, 0) != 0) return 2;
            std::array<unsigned char, 1> token{'x'};
            """ + send_line + """
            if (host_errno != Network::Errno::SUCCESS) return 3;
            if (poll(&waiting, 1, 100) != 1 || !(waiting.revents & POLLIN)) return 4;
            unsigned char received;
            if (recv(receiver_fd, &received, 1, 0) != 1 || received != 'x') return 5;
            waiting.revents = 0;
            if (poll(&waiting, 1, 0) != 0) return 6;
        }
        if (fcntl(receiver_fd, F_GETFD) != -1 || errno != EBADF) return 7;
        if (fcntl(sender_fd, F_GETFD) != -1 || errno != EBADF) return 8;
    }
}
""")
            compile_probe = subprocess.run(
                ["c++", "-std=c++20", str(probe), "-o", str(root / "offline-wake-probe")],
                capture_output=True, text=True,
            )
            self.assertEqual(compile_probe.returncode, 0, compile_probe.stderr)
            self.assertEqual(subprocess.run([str(root / "offline-wake-probe")]).returncode, 0)
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

            native.write_text(native.read_text().replace(
                "m_input_subsystem.Start();", "m_input_subsystem.Initialize();"))
            network = root / "src/core/internal_network/network.cpp"
            network.write_text(network.read_text().replace("case ENETDOWN:", "case EHOSTDOWN:"))
            changed_network = subprocess.run(
                ["cmake", "-S", str(root), "-B", str(root / "changed-network"),
                 "-DANDROID=ON", f"-DCMAKE_PROJECT_INCLUDE={HOOK}",
                 f"-DPOCKET_HOKKAIDO_SOURCE_DIR={root}",
                 f"-DPOCKET_SDK_SOURCE_DIR={HOOK.parent}"],
                capture_output=True, text=True,
            )
            self.assertNotEqual(changed_network.returncode, 0)
            self.assertIn("Pinned offline network source seam changed", changed_network.stderr)

            network.write_text(network.read_text().replace("case EHOSTDOWN:", "case ENETDOWN:"))
            bsd = root / "src/core/hle/service/sockets/bsd.cpp"
            bsd.write_text(bsd.read_text().replace("Network::Domain::INET", "Network::Domain::INET6"))
            changed_bsd = subprocess.run(
                ["cmake", "-S", str(root), "-B", str(root / "changed-bsd"),
                 "-DANDROID=ON", f"-DCMAKE_PROJECT_INCLUDE={HOOK}",
                 f"-DPOCKET_HOKKAIDO_SOURCE_DIR={root}",
                 f"-DPOCKET_SDK_SOURCE_DIR={HOOK.parent}"],
                capture_output=True, text=True,
            )
            self.assertNotEqual(changed_bsd.returncode, 0)
            self.assertIn("Pinned offline bsd source seam changed", changed_bsd.stderr)

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
