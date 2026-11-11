#include "DsEngine.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;

static constexpr size_t width = 256;
static constexpr size_t height = 384;
static std::vector<uint32_t> pixels(width * height);
static std::vector<int16_t> audio(8192);

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

static bool blue(uint32_t color) {
    return (color & 255) > ((color >> 8) & 255) + 80 &&
           (color & 255) > ((color >> 16) & 255) + 80;
}

static bool green(uint32_t color) {
    return ((color >> 8) & 255) > (color & 255) + 80 &&
           ((color >> 8) & 255) > ((color >> 16) & 255) + 80;
}

static bool visible(bool (*matches)(uint32_t)) {
    return matches(pixels[96 * width + 128]) ||
           matches(pixels[288 * width + 128]);
}

static size_t run(uint64_t token, int mask) {
    return pocket::frame(token, mask, 0, 0, false, pixels.data(), pixels.size(),
                         audio.data(), audio.size());
}

static void test_video(const fs::path& rom, const fs::path& root) {
    const uint64_t token = pocket::open(rom.string(), root.string(), root.string());
    bool saw_blue = false;
    for (int i = 0; i < 120 && !saw_blue; ++i) {
        run(token, 0);
        saw_blue = visible(blue);
    }
    require(saw_blue, "ARM9 did not display the expected blue pixels");
    pocket::close(token);
}

static void test_input(const fs::path& rom, const fs::path& root) {
    const uint64_t token = pocket::open(rom.string(), root.string(), root.string());
    bool saw_green = false;
    for (int i = 0; i < 120 && !saw_green; ++i) {
        run(token, 1 << 8);  // libretro A
        saw_green = visible(green);
    }
    require(saw_green, "A input did not change the visible pixels to green");
    pocket::close(token);
}

static void test_audio(const fs::path& rom, const fs::path& root) {
    const uint64_t token = pocket::open(rom.string(), root.string(), root.string());
    bool saw_frames = false;
    bool saw_samples = false;
    for (int i = 0; i < 120 && !saw_samples; ++i) {
        std::fill(audio.begin(), audio.end(), 0);
        const size_t frames = run(token, 0);
        require(frames <= audio.size() / 2, "audio frame count exceeds capacity");
        saw_frames |= frames > 0;
        saw_samples |= std::any_of(audio.begin(), audio.begin() + frames * 2,
                                   [](int16_t sample) { return sample != 0; });
    }
    require(saw_frames, "core produced no stereo audio frames");
    require(saw_samples, "ARM7 PSG did not produce nonzero PCM");
    pocket::close(token);
}

static void test_malformed_rom(const fs::path& rom, const fs::path& root) {
    const fs::path malformed = root / "truncated.nds";
    std::ifstream input(rom, std::ios::binary);
    std::array<char, 0x200> header{};
    input.read(header.data(), header.size());
    require(input.gcount() == static_cast<std::streamsize>(header.size()), "fixture header missing");
    std::ofstream output(malformed, std::ios::binary);
    output.write(header.data(), header.size());
    output.close();
    bool rejected = false;
    try { pocket::open(malformed.string(), root.string(), root.string()); }
    catch (const std::runtime_error&) { rejected = true; }
    require(rejected, "truncated ROM was accepted");
}

static void test_stale_close(const fs::path& rom, const fs::path& root) {
    const uint64_t old = pocket::open(rom.string(), root.string(), root.string());
    pocket::close(old);
    const uint64_t current = pocket::open(rom.string(), root.string(), root.string());
    pocket::close(old);
    run(current, 0);
    pocket::close(current);
    bool rejected = false;
    try { run(current, 0); }
    catch (const std::runtime_error&) { rejected = true; }
    require(rejected, "closed session token was accepted");
}

int main(int argc, char** argv) {
    try {
        require(argc == 3, "usage: smoke ROM WORKDIR");
        const fs::path rom = fs::absolute(argv[1]);
        const fs::path root = fs::absolute(argv[2]);
        fs::create_directories(root);
        test_video(rom, root);
        test_input(rom, root);
        test_audio(rom, root);
        test_malformed_rom(rom, root);
        test_stale_close(rom, root);
        std::cout << "DS_NATIVE_SMOKE_PASS\n";
    } catch (const std::exception& error) {
        std::cerr << "DS_NATIVE_SMOKE_FAIL: " << error.what() << '\n';
        return 1;
    }
}
