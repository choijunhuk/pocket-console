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

#ifdef __linux__
#define GL_GLEXT_PROTOTYPES
#include <GL/glcorearb.h>
#endif

namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;

static constexpr int width = 400;
static constexpr int height = 480;
static std::vector<uint32_t> pixels(width * height);
static std::vector<int16_t> audio(16384);
static std::vector<uint32_t> lastActual;
static fs::path evidenceDir;
static uint64_t frameCalls = 0, waitingFrames = 0, readyFrames = 0;
static uint64_t audioBatches = 0, stereoFrames = 0;

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
    if (wanted == 'm') return r > g + 80 && b > g + 80;
    return false;
}

static bool top(char wanted) { return color(pixels[120 * width + 200], wanted); }
static bool bottom(char wanted) { return color(pixels[360 * width + 200], wanted); }

static int run(uint64_t token, int buttons = 0, bool touch = false, int circleX = 0) {
    std::fill(audio.begin(), audio.end(), 0);
    ++frameCalls;
    const int count = pocket3ds::frame(token, buttons, 160, 120, touch, circleX, 0,
                                       pixels.data(), pixels.size(), audio.data(), audio.size());
    require(count >= -1 && count <= static_cast<int>(audio.size() / 2),
            "invalid 3DS stereo frame count");
    if (count < 0) ++waitingFrames;
    else {
        ++readyFrames;
        lastActual.assign(pixels.begin(), pixels.end());
        if (count > 0) { ++audioBatches; stereoFrames += count; }
    }
    return count;
}

static void waitFor(uint64_t token, bool (*matches)(), const char* phase, int buttons = 0,
                    bool touch = false, int circleX = 0) {
    const auto deadline = Clock::now() + std::chrono::seconds(90);
    while (Clock::now() < deadline) {
        run(token, buttons, touch, circleX);
        if (matches()) return;
    }
    throw std::runtime_error(std::string("original 3DS homebrew color missing after 90s: ") + phase);
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
    waitFor(token, topBlue, "top blue");
    require(bottom('r'), "original 3DS homebrew lower red screen missing");
}

static void test_button(uint64_t token) {
    waitFor(token, topGreen, "A top green", 1 << 8);  // libretro A
}

static void test_touch(uint64_t token) {
    waitFor(token, bottomYellow, "touch bottom yellow", 0, true);
}

static void test_circle(uint64_t token) {
    waitFor(token, bottomCyan, "circle bottom cyan", 0, false, 32767);
}

static void diagnostics() {
    std::cerr << "THREEDS_FRAME_DIAG calls=" << frameCalls << " waiting=" << waitingFrames
              << " ready=" << readyFrames << " audio_batches=" << audioBatches
              << " stereo_frames=" << stereoFrames << " top_raw=0x" << std::hex
              << pixels[120 * width + 200] << " bottom_raw=0x"
              << pixels[360 * width + 200] << std::dec << '\n';
    if (lastActual.empty()) {
        std::cerr << "THREEDS_FRAME_DIAG no actual video frame; no PPM emitted\n";
        return;
    }
    struct Box {
        size_t count = 0;
        int left = width, top = height, right = -1, bottom = -1;
        void add(int x, int y) {
            ++count;
            left = std::min(left, x); top = std::min(top, y);
            right = std::max(right, x); bottom = std::max(bottom, y);
        }
    } bluePixels, redPixels;
    size_t nonblack = 0, upperNonblack = 0, lowerNonblack = 0;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const uint32_t pixel = lastActual[y * width + x];
            if (pixel & 0x00ffffff) {
                ++nonblack;
                if (y < height / 2) ++upperNonblack;
                else ++lowerNonblack;
            }
            if (color(pixel, 'b')) bluePixels.add(x, y);
            if (color(pixel, 'r')) redPixels.add(x, y);
        }
    }
    std::cerr << "THREEDS_PIXEL_DIAG nonblack=" << nonblack
              << " upper_nonblack=" << upperNonblack << " lower_nonblack=" << lowerNonblack
              << " blue=" << bluePixels.count << " red=" << redPixels.count
              << " other_nonblack=" << nonblack - bluePixels.count - redPixels.count << '\n';
    if (bluePixels.count)
        std::cerr << "THREEDS_PIXEL_DIAG blue_bbox=" << bluePixels.left << ',' << bluePixels.top
                  << ',' << bluePixels.right << ',' << bluePixels.bottom << '\n';
    if (redPixels.count)
        std::cerr << "THREEDS_PIXEL_DIAG red_bbox=" << redPixels.left << ',' << redPixels.top
                  << ',' << redPixels.right << ',' << redPixels.bottom << '\n';
    const fs::path output = evidenceDir / "last-actual-3ds-frame.ppm";
    std::ofstream ppm(output, std::ios::binary);
    ppm << "P6\n" << width << ' ' << height << "\n255\n";
    std::vector<char> rgb(lastActual.size() * 3);
    for (size_t i = 0; i < lastActual.size(); ++i) {
        rgb[3 * i] = static_cast<char>(lastActual[i] >> 16);
        rgb[3 * i + 1] = static_cast<char>(lastActual[i] >> 8);
        rgb[3 * i + 2] = static_cast<char>(lastActual[i]);
    }
    ppm.write(rgb.data(), rgb.size());
    require(static_cast<bool>(ppm), "cannot write last actual 3DS frame PPM");
    std::cerr << "THREEDS_FRAME_DIAG ppm=" << output << '\n';
}

#ifdef __linux__
static void probeDefaultFramebuffer() {
    if (!glGetString(GL_VERSION)) {
        std::cerr << "THREEDS_GL_DIAG no current OpenGL context\n";
        return;
    }
    GLint draw = 0, read = 0, readBuffer = 0, pbo = 0;
    GLint alignment = 0, rowLength = 0, skipRows = 0, skipPixels = 0;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read);
    glGetIntegerv(GL_READ_BUFFER, &readBuffer);
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pbo);
    glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
    glGetIntegerv(GL_PACK_ROW_LENGTH, &rowLength);
    glGetIntegerv(GL_PACK_SKIP_ROWS, &skipRows);
    glGetIntegerv(GL_PACK_SKIP_PIXELS, &skipPixels);
    std::cerr << "THREEDS_GL_DIAG draw_fbo=" << draw << " read_fbo=" << read
              << " read_buffer=0x" << std::hex << readBuffer << std::dec
              << " pack_pbo=" << pbo << '\n';
    if (pbo != 0) {
        std::cerr << "THREEDS_GL_DIAG default_probe_skipped_nonzero_pack_pbo\n";
        return;
    }
    const GLenum preError = glGetError();
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    GLint defaultReadBuffer = 0;
    glGetIntegerv(GL_READ_BUFFER, &defaultReadBuffer);
    const GLenum bindError = glGetError();
    std::cerr << "THREEDS_GL_DIAG default_read_buffer=0x" << std::hex
              << defaultReadBuffer << " pre_error=0x" << preError
              << " bind_error=0x" << bindError << std::dec << '\n';
    if (bindError == GL_NO_ERROR && defaultReadBuffer != GL_NONE) {
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        std::array<GLubyte, 4> upper{}, lower{};
        glReadPixels(200, 120, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, upper.data());
        const GLenum upperError = glGetError();
        glReadPixels(200, 360, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, lower.data());
        const GLenum lowerError = glGetError();
        std::cerr << "THREEDS_GL_DIAG default_upper_rgba="
                  << unsigned(upper[0]) << ',' << unsigned(upper[1]) << ','
                  << unsigned(upper[2]) << ',' << unsigned(upper[3])
                  << " default_lower_rgba=" << unsigned(lower[0]) << ','
                  << unsigned(lower[1]) << ',' << unsigned(lower[2]) << ','
                  << unsigned(lower[3]) << " upper_error=0x" << std::hex
                  << upperError << " lower_error=0x" << lowerError << std::dec << '\n';
    }
    glPixelStorei(GL_PACK_ALIGNMENT, alignment);
    glPixelStorei(GL_PACK_ROW_LENGTH, rowLength);
    glPixelStorei(GL_PACK_SKIP_ROWS, skipRows);
    glPixelStorei(GL_PACK_SKIP_PIXELS, skipPixels);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
    std::cerr << "THREEDS_GL_DIAG restore_error=0x" << std::hex
              << glGetError() << std::dec << '\n';
}
#endif

static void test_audio(uint64_t token) {
    const auto deadline = Clock::now() + std::chrono::seconds(30);
    bool callback = false;
    bool pcm = false;
    while (Clock::now() < deadline && !pcm) {
        const int frames = run(token, 1 << 9); // X starts original NDSP PCM tone.
        require(!bottom('m'), "guest NDSP startup failed");
        if (frames > 0) {
            callback = true;
            pcm |= std::any_of(audio.begin(), audio.begin() + frames * 2,
                               [](int16_t sample) { return sample != 0; });
        }
    }
    require(callback, "3DS core produced no stereo audio frames");
    require(pcm, "original NDSP PCM tone produced no nonzero samples");
}

static void test_truncated_rom(const fs::path& rom, const fs::path& root, uint64_t& active) {
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
        active = pocket3ds::open(bad.string(), root.string(), root.string());
    } catch (const std::runtime_error&) { rejected = true; }
    if (active) { pocket3ds::close(active); active = 0; }
    require(rejected, "truncated 3DSX was accepted");
}

static void test_stale_close(const fs::path& rom, const fs::path& root,
                             uint64_t old, uint64_t& active) {
    active = pocket3ds::open(rom.string(), root.string(), root.string());
    const uint64_t current = active;
    pocket3ds::close(old);
    int metrics[4]{};
    pocket3ds::configuration(current, metrics);
    pocket3ds::close(current);
    active = 0;
    bool rejected = false;
    try { run(current); }
    catch (const std::runtime_error&) { rejected = true; }
    require(rejected, "closed 3DS session token was accepted");
}

int main(int argc, char** argv) {
    uint64_t active = 0;
    try {
        require(argc == 3, "usage: smoke ROM WORKDIR");
        const fs::path rom = fs::absolute(argv[1]);
        const fs::path root = fs::absolute(argv[2]);
        fs::create_directories(root);
        evidenceDir = root;
        active = pocket3ds::open(rom.string(), root.string(), root.string());
        test_configuration(active);
        test_video(active);
        test_button(active);
        test_touch(active);
        test_circle(active);
        test_audio(active);
        const uint64_t old = active;
        pocket3ds::close(active);
        active = 0;
        test_truncated_rom(rom, root, active);
        test_stale_close(rom, root, old, active);
        std::cout << "THREEDS_NATIVE_SMOKE_PASS\n";
    } catch (const std::exception& failure) {
        std::cerr << "THREEDS_NATIVE_SMOKE_FAIL: " << failure.what() << '\n';
        if (!evidenceDir.empty()) {
            try { diagnostics(); }
            catch (const std::exception& artifact) {
                std::cerr << "THREEDS_FRAME_DIAG artifact_error=" << artifact.what() << '\n';
            }
        }
#ifdef __linux__
        if (active) probeDefaultFramebuffer();
#endif
        if (active) {
            try { pocket3ds::close(active); }
            catch (const std::exception& cleanup) {
                std::cerr << "THREEDS_NATIVE_SMOKE_CLEANUP_FAIL: " << cleanup.what() << '\n';
            }
        }
        return 1;
    }
}
