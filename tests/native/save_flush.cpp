#include "NDSCart_SRAMManager.h"
#include "Platform.h"

#include <array>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>

namespace Platform {
FILE* OpenFile(const char* path, const char* mode, bool) { return std::fopen(path, mode); }
void Mutex_Free(Mutex*) {}
void Mutex_Lock(Mutex*) {}
void Mutex_Unlock(Mutex*) {}
}

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

int main(int argc, char** argv) {
    try {
        require(argc == 2, "usage: save_flush SAVE_PATH");
        const std::string path = argv[1];
        std::remove(path.c_str());
        std::array<u8, 16> saved{};
        saved[0] = 0x53;
        saved[15] = 0xa7;
        require(NDSCart_SRAMManager::Init(), "manager initialization failed");
        NDSCart_SRAMManager::Setup(path.c_str(), saved.data(), saved.size());
        NDSCart_SRAMManager::RequestFlush();
        NDSCart_SRAMManager::DeInit();  // Same manager close called by retro_unload_game -> NDS::DeInit.

        std::array<u8, 16> reopened{};
        FILE* file = std::fopen(path.c_str(), "rb");
        require(file != nullptr, "closing immediately lost pending SRAM");
        const size_t read = std::fread(reopened.data(), 1, reopened.size(), file);
        const int closed = std::fclose(file);
        require(read == reopened.size() && closed == 0, "saved SRAM file is incomplete");
        require(reopened == saved, "reopened SRAM differs from saved contents");

        require(NDSCart_SRAMManager::Init(), "manager reopen failed");
        NDSCart_SRAMManager::Setup(path.c_str(), reopened.data(), reopened.size());
        require(reopened == saved, "reopened manager lost SRAM");
        NDSCart_SRAMManager::DeInit();
        std::puts("DS_SAVE_FLUSH_PASS");
    } catch (const std::exception& error) {
        std::fprintf(stderr, "DS_SAVE_FLUSH_FAIL: %s\n", error.what());
        return 1;
    }
}
