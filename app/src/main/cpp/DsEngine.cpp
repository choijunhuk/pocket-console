#include "DsEngine.h"

#include <libretro.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <sys/stat.h>
#include <vector>

namespace {

constexpr size_t kPixels = 256 * 384;
constexpr size_t kMaxAudioShorts = 8192;
constexpr size_t kMaxRomBytes = 512 * 1024 * 1024;

struct Session {
    uint64_t token;
    std::string path;
    std::string system;
    std::string saves;
    std::vector<uint8_t> rom;
    retro_game_info info{};
    bool initialized = false;
    bool loaded = false;
    int mask = 0;
    int x = 0;
    int y = 0;
    bool down = false;
};

struct FrameOutput {
    uint32_t* pixels;
    int16_t* audio;
    size_t audioCapacity;
    size_t audioFrames = 0;
    bool videoWritten = false;
    const char* error = nullptr;
};

std::mutex g_mutex;
std::unique_ptr<Session> g_session;
FrameOutput* g_output = nullptr;
uint64_t g_nextToken = 0;

uint32_t little32(const std::vector<uint8_t>& bytes, size_t offset) {
    return uint32_t(bytes[offset]) | (uint32_t(bytes[offset + 1]) << 8) |
           (uint32_t(bytes[offset + 2]) << 16) | (uint32_t(bytes[offset + 3]) << 24);
}

void requireExecutable(const std::vector<uint8_t>& rom, size_t offset) {
    const uint32_t start = little32(rom, offset);
    const uint32_t size = little32(rom, offset + 12);
    if (size < 4 || (start & 3) || (size & 3) || start < 512 ||
        start > rom.size() || size > rom.size() - start)
        throw std::runtime_error("Invalid DS executable range");
}

void requireDirectory(const std::string& path) {
    struct stat st {};
    if (path.empty() || path.find('\0') != std::string::npos || path.size() >= 4096 ||
        stat(path.c_str(), &st) != 0 || !S_ISDIR(st.st_mode))
        throw std::runtime_error("Invalid DS directory");
}

std::vector<uint8_t> readRom(const std::string& path) {
    if (path.empty() || path.find('\0') != std::string::npos)
        throw std::runtime_error("Invalid DS ROM path");
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) throw std::runtime_error("Cannot open DS ROM");
    const std::streamoff size = file.tellg();
    if (size < 512 || size > static_cast<std::streamoff>(kMaxRomBytes))
        throw std::runtime_error("Invalid DS ROM size");
    std::vector<uint8_t> rom(static_cast<size_t>(size));
    file.seekg(0);
    if (!file.read(reinterpret_cast<char*>(rom.data()), size))
        throw std::runtime_error("Cannot read DS ROM");
    requireExecutable(rom, 0x20);  // ARM9 offset and length
    requireExecutable(rom, 0x30);  // ARM7 offset and length
    size_t allocated = 512;
    while (allocated < rom.size()) allocated *= 2;
    const uint32_t banner = little32(rom, 0x68);
    const uint32_t arm9 = little32(rom, 0x20);
    // This core copies a 4 KiB header and a 9,152-byte banner from its rounded ROM buffer.
    if (allocated < 16384 || banner > allocated || 9152 > allocated - banner ||
        (arm9 >= 0x4000 && arm9 < 0x8000 && (arm9 > allocated || 0x800 > allocated - arm9)) ||
        (banner && (banner > rom.size() || 0x840 > rom.size() - banner)))
        throw std::runtime_error("Invalid DS banner range");
    return rom;
}

bool environment(unsigned command, void* data) {
    switch (command) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *static_cast<const char**>(data) = g_session->system.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = g_session->saves.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* variable = static_cast<retro_variable*>(data);
            static const struct { const char* key; const char* value; } options[] = {
                {"melonds_console_mode", "DS"},
                {"melonds_boot_directly", "enabled"},
                {"melonds_opengl_renderer", "disabled"},
                {"melonds_screen_layout", "Top/Bottom"},
                {"melonds_screen_gap", "0"},
                {"melonds_touch_mode", "Touch"},
                {"melonds_threaded_renderer", "disabled"},
                {"melonds_jit_enable", "enabled"},
                {"melonds_jit_fast_memory", "disabled"},
            };
            variable->value = nullptr;
            for (const auto& option : options) {
                if (std::strcmp(variable->key, option.key) == 0) {
                    variable->value = option.value;
                    return true;
                }
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            return *static_cast<retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = false;
            return true;
        default:
            return false;
    }
}

void logMessage(enum retro_log_level, const char* format, ...) {
    va_list args;
    va_start(args, format);
    std::vfprintf(stderr, format, args);
    va_end(args);
}

bool environmentWithLog(unsigned command, void* data) {
    if (command == RETRO_ENVIRONMENT_GET_LOG_INTERFACE) {
        static_cast<retro_log_callback*>(data)->log = logMessage;
        return true;
    }
    return environment(command, data);
}

void video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!g_output) return;
    if (!data || width != 256 || height != 384 || pitch < width * sizeof(uint32_t)) {
        g_output->error = "Unexpected DS video frame";
        return;
    }
    auto* source = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < height; ++y) {
        for (unsigned x = 0; x < width; ++x) {
            uint32_t pixel;
            std::memcpy(&pixel, source + y * pitch + x * sizeof(pixel), sizeof(pixel));
            g_output->pixels[y * width + x] = pixel | 0xff000000u;
        }
    }
    g_output->videoWritten = true;
}

size_t audioBatch(const int16_t* data, size_t frames) {
    if (!g_output || !data) return 0;
    if (frames > (g_output->audioCapacity / 2) - g_output->audioFrames) {
        g_output->error = "DS audio frame exceeds buffer";
        return 0;
    }
    std::memcpy(g_output->audio + g_output->audioFrames * 2, data, frames * 2 * sizeof(int16_t));
    g_output->audioFrames += frames;
    return frames;
}

void pollInput() {}

int16_t input(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port != 0 || !g_session) return 0;
    if (device == RETRO_DEVICE_JOYPAD)
        return id < 12 && (g_session->mask & (1 << id)) ? 1 : 0;
    if (device != RETRO_DEVICE_POINTER || !g_session->down) return 0;
    if (id == RETRO_DEVICE_ID_POINTER_PRESSED) return 1;
    const int x = std::clamp(g_session->x, 0, 255);
    const int y = std::clamp(g_session->y, 0, 191) + 192;
    if (id == RETRO_DEVICE_ID_POINTER_X) return static_cast<int16_t>((2 * x + 1) * 32768 / 256 - 32768);
    if (id == RETRO_DEVICE_ID_POINTER_Y) return static_cast<int16_t>((2 * y + 1) * 32768 / 384 - 32768);
    return 0;
}

void release() {
    g_output = nullptr;
    if (!g_session) return;
    if (g_session->loaded) retro_unload_game();
    if (g_session->initialized) retro_deinit();
    g_session.reset();
}

}  // namespace

namespace pocket {

uint64_t open(const std::string& path, const std::string& system, const std::string& saves) {
    requireDirectory(system);
    requireDirectory(saves);
    auto rom = readRom(path);
    std::lock_guard<std::mutex> lock(g_mutex);
    release();
    auto session = std::make_unique<Session>();
    session->token = ++g_nextToken;
    session->path = path;
    session->system = system;
    session->saves = saves;
    session->rom = std::move(rom);
    session->info.path = session->path.c_str();
    session->info.data = session->rom.data();
    session->info.size = session->rom.size();
    g_session = std::move(session);
    try {
        retro_set_environment(environmentWithLog);
        retro_set_video_refresh(video);
        retro_set_audio_sample_batch(audioBatch);
        retro_set_input_poll(pollInput);
        retro_set_input_state(input);
        retro_init();
        g_session->initialized = true;
        retro_system_info info{};
        retro_get_system_info(&info);
        if (info.need_fullpath) throw std::runtime_error("DS core requires a file path");
        if (!retro_load_game(&g_session->info))
            throw std::runtime_error("DS core rejected the ROM");
        g_session->loaded = true;
        return g_session->token;
    } catch (...) {
        release();
        throw;
    }
}

size_t frame(uint64_t token, int mask, int x, int y, bool down,
             uint32_t* pixels, size_t pixelCapacity,
             int16_t* audio, size_t audioCapacity) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!token || !g_session || token != g_session->token)
        throw std::runtime_error("DS session is closed");
    if (!pixels || !audio || pixelCapacity < kPixels || audioCapacity < kMaxAudioShorts)
        throw std::runtime_error("DS output buffer is too small");
    g_session->mask = mask & 0xfff;
    g_session->x = x;
    g_session->y = y;
    g_session->down = down;
    FrameOutput output{pixels, audio, kMaxAudioShorts};
    g_output = &output;
    try {
        retro_run();
    } catch (...) {
        g_output = nullptr;
        throw;
    }
    g_output = nullptr;
    if (output.error) throw std::runtime_error(output.error);
    if (!output.videoWritten) throw std::runtime_error("DS core produced no video frame");
    return output.audioFrames;
}

void close(uint64_t token) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (token && g_session && token == g_session->token) release();
}

}  // namespace pocket
