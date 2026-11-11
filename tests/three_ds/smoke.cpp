#include "ThreeDsEngine.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;

static constexpr int width = 400;
static constexpr int height = 480;
static std::vector<uint32_t> pixels(width * height);
static std::vector<int16_t> audio(16384);

static void require(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}

static bool color(uint32_t argb, char wanted) {
    const int r = (argb >> 16) & 255;
    const int g = (argb >> 8) & 255;
    const int b = argb & 255;
    if (wanted == 'b') return b > r + 80 && b > g + 80;
    if (wanted == 'r') return r > b + 80 && r > g + 80;
    if (wanted == 'g') return g > r + 80 && g > b + 80;
    if (wanted == 'y') return r > b + 80 && g > b + 80;
    if (wanted == 'c') return b > r + 80 && g > r + 80;
    return false;
}

static bool top(char wanted) { return color(pixels[120 * width + 200], wanted); }
static bool bottom(char wanted) { return color(pixels[360 * width + 200], wanted); }

static int run(uint64_t token, int buttons = 0, bool touch = false, int circleX = 0) {
    std::fill(audio.begin(), audio.end(), 0);
    const int count = pocket3ds::frame(token, buttons, 160, 120, touch, circleX, 0,
                                       pixels.data(), pixels.size(), audio.data(), audio.size());
    require(count >= -1 && count <= static_cast<int>(audio.size() / 2),
            "invalid 3DS stereo frame count");
    return count;
}

static void waitFor(uint64_t token, bool (*matches)(), int buttons = 0,
                    bool touch = false, int circleX = 0) {
    const auto deadline = Clock::now() + std::chrono::seconds(90);
    while (Clock::now() < deadline) {
        run(token, buttons, touch, circleX);
        if (matches()) return;
    }
    throw std::runtime_error("original 3DS homebrew color did not appear within 90 seconds");
}

static bool topBlue() { return top('b'); }
static bool topGreen() { return top('g'); }
static bool bottomYellow() { return bottom('y'); }
static bool bottomCyan() { return bottom('c'); }

static void test_configuration(uint64_t token) {
    int metrics[4]{};
    pocket3ds::configuration(token, metrics);
    require(metrics[0] == width && metrics[1] == height &&
            metrics[2] > 0 && metrics[3] > 0, "unexpected 3DS geometry or timing");
}

static void test_video(uint64_t token) {
    waitFor(token, topBlue);
    require(bottom('r'), "original 3DS homebrew lower red screen missing");
}

static void test_button(uint64_t token) {
    waitFor(token, topGreen, 1 << 8);  // libretro A
}

static void test_touch(uint64_t token) {
    waitFor(token, bottomYellow, 0, true);
}

static void test_circle(uint64_t token) {
    waitFor(token, bottomCyan, 0, false, 32767);
}

static void test_audio(uint64_t token) {
    const auto deadline = Clock::now() + std::chrono::seconds(30);
    bool callback = false;
    bool pcm = false;
    while (Clock::now() < deadline && !pcm) {
        const int frames = run(token);
        if (frames > 0) {
            callback = true;
            pcm |= std::any_of(audio.begin(), audio.begin() + frames * 2,
                               [](int16_t sample) { return sample != 0; });
        }
    }
    require(callback, "3DS core produced no stereo audio frames");
    require(pcm, "original CSND PSG produced no nonzero PCM");
}

static void test_truncated_rom(const fs::path& rom, const fs::path& root) {
    std::ifstream input(rom, std::ios::binary);
    std::array<char, 16> bytes{};
    input.read(bytes.data(), bytes.size());
    require(input.gcount() == static_cast<std::streamsize>(bytes.size()), "3DS fixture missing");
    const fs::path bad = root / "truncated.3dsx";
    std::ofstream output(bad, std::ios::binary);
    output.write(bytes.data(), bytes.size());
    output.close();
    bool rejected = false;
    try {
        const uint64_t token = pocket3ds::open(bad.string(), root.string(), root.string());
        pocket3ds::close(token);
    } catch (const std::runtime_error&) { rejected = true; }
    require(rejected, "truncated 3DSX was accepted");
}

static void test_stale_close(const fs::path& rom, const fs::path& root, uint64_t old) {
    const uint64_t current = pocket3ds::open(rom.string(), root.string(), root.string());
    pocket3ds::close(old);
    int metrics[4]{};
    pocket3ds::configuration(current, metrics);
    pocket3ds::close(current);
    bool rejected = false;
    try { run(current); }
    catch (const std::runtime_error&) { rejected = true; }
    require(rejected, "closed 3DS session token was accepted");
}

int main(int argc, char** argv) {
    try {
        require(argc == 3, "usage: smoke ROM WORKDIR");
        const fs::path rom = fs::absolute(argv[1]);
        const fs::path root = fs::absolute(argv[2]);
        fs::create_directories(root);
        const uint64_t token = pocket3ds::open(rom.string(), root.string(), root.string());
        test_configuration(token);
        test_video(token);
        test_button(token);
        test_touch(token);
        test_circle(token);
        test_audio(token);
        pocket3ds::close(token);
        test_truncated_rom(rom, root);
        test_stale_close(rom, root, token);
        std::cout << "THREEDS_NATIVE_SMOKE_PASS\n";
    } catch (const std::exception& failure) {
        std::cerr << "THREEDS_NATIVE_SMOKE_FAIL: " << failure.what() << '\n';
        return 1;
    }
}
