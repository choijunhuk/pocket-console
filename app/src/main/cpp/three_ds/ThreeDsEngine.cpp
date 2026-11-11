#include "ThreeDsEngine.h"

#include <libretro.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#ifdef __ANDROID__
#include <GLES3/gl32.h>
#else
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#endif

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <vector>

namespace {

constexpr unsigned kWidth = 400;
constexpr unsigned kHeight = 480;
constexpr size_t kPixels = kWidth * kHeight;
constexpr size_t kAudioShorts = 16384;

struct Core {
    unsigned (*apiVersion)() = nullptr;
    void (*setEnvironment)(retro_environment_t) = nullptr;
    void (*setVideo)(retro_video_refresh_t) = nullptr;
    void (*setAudio)(retro_audio_sample_batch_t) = nullptr;
    void (*setInputPoll)(retro_input_poll_t) = nullptr;
    void (*setInputState)(retro_input_state_t) = nullptr;
    void (*init)() = nullptr;
    void (*deinit)() = nullptr;
    bool (*load)(const retro_game_info*) = nullptr;
    void (*unload)() = nullptr;
    void (*run)() = nullptr;
    void (*avInfo)(retro_system_av_info*) = nullptr;
};

struct Session {
    uint64_t token = 0;
    std::thread::id owner;
    void* library = nullptr;
    Core core;
    std::string rom;
    std::string userRoot;
    std::string systemRoot;
    std::string message;
    std::map<std::string, std::string> options;
    retro_game_info game{};
    retro_hw_render_callback hw{};
    bool initialized = false;
    bool loaded = false;
    bool contextReset = false;
    bool sawVideo = false;
    unsigned diagnosticCallbacks = 0;
    unsigned diagnosticNoVideo = 0;
    int buttons = 0;
    int touchX = 0;
    int touchY = 0;
    bool touching = false;
    int circleX = 0;
    int circleY = 0;
    int metrics[4]{};
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    GLuint framebuffer = 0;
    GLuint color = 0;
    GLuint depthStencil = 0;
    std::vector<uint8_t> readback;
};

struct Output {
    uint32_t* pixels;
    int16_t* audio;
    size_t frames = 0;
    bool video = false;
    const char* error = nullptr;
};

std::mutex g_mutex;
std::unique_ptr<Session> g_session;
Output* g_output = nullptr;
uint64_t g_nextToken = 0;

template <typename T>
T symbol(void* library, const char* name) {
    void* found = dlsym(library, name);
    if (!found) throw std::runtime_error(std::string("Missing 3DS core symbol: ") + name);
    return reinterpret_cast<T>(found);
}

Core loadSymbols(void* library) {
    Core core;
    core.apiVersion = symbol<decltype(core.apiVersion)>(library, "retro_api_version");
    core.setEnvironment = symbol<decltype(core.setEnvironment)>(library, "retro_set_environment");
    core.setVideo = symbol<decltype(core.setVideo)>(library, "retro_set_video_refresh");
    core.setAudio = symbol<decltype(core.setAudio)>(library, "retro_set_audio_sample_batch");
    core.setInputPoll = symbol<decltype(core.setInputPoll)>(library, "retro_set_input_poll");
    core.setInputState = symbol<decltype(core.setInputState)>(library, "retro_set_input_state");
    core.init = symbol<decltype(core.init)>(library, "retro_init");
    core.deinit = symbol<decltype(core.deinit)>(library, "retro_deinit");
    core.load = symbol<decltype(core.load)>(library, "retro_load_game");
    core.unload = symbol<decltype(core.unload)>(library, "retro_unload_game");
    core.run = symbol<decltype(core.run)>(library, "retro_run");
    core.avInfo = symbol<decltype(core.avInfo)>(library, "retro_get_system_av_info");
    if (core.apiVersion() != RETRO_API_VERSION)
        throw std::runtime_error("Unsupported 3DS libretro API version");
    return core;
}

uint32_t little32(const std::array<uint8_t, 512>& header, size_t offset) {
    return uint32_t(header[offset]) | (uint32_t(header[offset + 1]) << 8) |
           (uint32_t(header[offset + 2]) << 16) | (uint32_t(header[offset + 3]) << 24);
}

void requireDirectory(const std::string& path) {
    struct stat info {};
    if (path.empty() || path.find('\0') != std::string::npos ||
        stat(path.c_str(), &info) != 0 || !S_ISDIR(info.st_mode))
        throw std::runtime_error("Invalid 3DS directory");
}

void requireRom(const std::string& path) {
    struct stat info {};
    if (path.empty() || path.find('\0') != std::string::npos ||
        stat(path.c_str(), &info) != 0 || !S_ISREG(info.st_mode) ||
        info.st_size < 32 || info.st_size > INT64_C(8) * 1024 * 1024 * 1024)
        throw std::runtime_error("Invalid 3DS ROM file");
    std::ifstream file(path, std::ios::binary);
    std::array<uint8_t, 512> header{};
    file.read(reinterpret_cast<char*>(header.data()), header.size());
    const size_t count = static_cast<size_t>(file.gcount());
    if (count >= 32 && std::memcmp(header.data(), "3DSX", 4) == 0) {
        const unsigned headerSize = unsigned(header[4]) | (unsigned(header[5]) << 8);
        if (headerSize >= 32 && headerSize <= static_cast<uint64_t>(info.st_size)) return;
    } else if (count >= 52 && std::memcmp(header.data(), "\x7f" "ELF", 4) == 0) {
        if (header[4] == 1 && header[5] == 1 && header[18] == 40 && header[19] == 0 &&
            little32(header, 0x1c) < static_cast<uint64_t>(info.st_size)) return;
    } else if (count == header.size() &&
               (std::memcmp(header.data() + 0x100, "NCSD", 4) == 0 ||
                std::memcmp(header.data() + 0x100, "NCCH", 4) == 0)) {
        const uint64_t mediaSize = uint64_t(little32(header, 0x104)) * 512;
        if (mediaSize >= 512 && mediaSize <= static_cast<uint64_t>(info.st_size)) return;
    }
    throw std::runtime_error("Unsupported or truncated 3DS ROM header");
}

void registerOptions(const retro_core_option_v2_definition* definitions) {
    if (!definitions) return;
    for (const auto* item = definitions; item->key; ++item) {
        const char* fallback = item->default_value ? item->default_value : item->values[0].value;
        if (fallback) g_session->options[item->key] = fallback;
    }
}

void registerOptions(const retro_core_option_definition* definitions) {
    if (!definitions) return;
    for (const auto* item = definitions; item->key; ++item) {
        const char* fallback = item->default_value ? item->default_value : item->values[0].value;
        if (fallback) g_session->options[item->key] = fallback;
    }
}

void setOverrides() {
    auto& options = g_session->options;
    options["citra_graphics_api"] = "OpenGL";
    options["citra_resolution_factor"] = "1";
    options["citra_layout_option"] = "default";
    options["citra_use_libretro_save_path"] = "LibRetro Default";
    options["citra_enable_touch_touchscreen"] = "enabled";
}

void logMessage(enum retro_log_level, const char* format, ...) {
    va_list arguments;
    va_start(arguments, format);
    std::vfprintf(stderr, format, arguments);
    va_end(arguments);
}

bool environment(unsigned command, void* data) {
    switch (command) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = logMessage;
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned*>(data) = 2;
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
            registerOptions(static_cast<retro_core_options_v2*>(data)->definitions);
            setOverrides();
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
            registerOptions(static_cast<retro_core_options_v2_intl*>(data)->us->definitions);
            setOverrides();
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
            registerOptions(static_cast<retro_core_option_definition*>(data));
            setOverrides();
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
            registerOptions(static_cast<retro_core_options_intl*>(data)->us);
            setOverrides();
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            for (auto* item = static_cast<retro_variable*>(data); item && item->key; ++item) {
                if (!item->value) continue;
                std::string value(item->value);
                const size_t start = value.find(';');
                if (start != std::string::npos) {
                    value = value.substr(start + 1);
                    value.erase(0, value.find_first_not_of(' '));
                    const size_t end = value.find('|');
                    if (end != std::string::npos) value.resize(end);
                    g_session->options[item->key] = value;
                }
            }
            setOverrides();
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* item = static_cast<retro_variable*>(data);
            const auto found = g_session->options.find(item->key ? item->key : "");
            item->value = found == g_session->options.end() ? nullptr : found->second.c_str();
            return item->value != nullptr;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = g_session->userRoot.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *static_cast<const char**>(data) = g_session->systemRoot.c_str();
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            return *static_cast<retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
#ifdef __ANDROID__
            *static_cast<retro_hw_context_type*>(data) = RETRO_HW_CONTEXT_OPENGLES3;
#else
            *static_cast<retro_hw_context_type*>(data) = RETRO_HW_CONTEXT_OPENGL_CORE;
#endif
            return true;
        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
            return true;
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto* callback = static_cast<retro_hw_render_callback*>(data);
#ifdef __ANDROID__
            if (callback->context_type != RETRO_HW_CONTEXT_OPENGLES3 ||
                callback->version_major != 3 || callback->version_minor != 2) return false;
#else
            if (callback->context_type != RETRO_HW_CONTEXT_OPENGL_CORE ||
                callback->version_major != 4 || callback->version_minor != 3) return false;
#endif
            callback->get_current_framebuffer = []() -> uintptr_t { return g_session->framebuffer; };
            callback->get_proc_address = [](const char* name) -> retro_proc_address_t {
                auto address = eglGetProcAddress(name);
                if (!address) address = reinterpret_cast<decltype(address)>(dlsym(RTLD_DEFAULT, name));
                return reinterpret_cast<retro_proc_address_t>(address);
            };
            g_session->hw = *callback;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            // Azahar 2126.2 passes retro_system_av_info here rather than geometry alone.
            const auto& geometry = static_cast<retro_system_av_info*>(data)->geometry;
            return geometry.base_width == kWidth && geometry.base_height == kHeight;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE:
            if (data && static_cast<retro_message*>(data)->msg)
                g_session->message = static_cast<retro_message*>(data)->msg;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = false;
            return true;
        default:
            return false;
    }
}

std::string eglFailure(const char* message) {
    char error[16];
    std::snprintf(error, sizeof(error), "0x%04x", eglGetError());
    return std::string(message) + " (EGL " + error + ")";
}

std::string observedVersion(GLint major, GLint minor) {
    const auto* version = glGetString(GL_VERSION);
    return std::to_string(major) + "." + std::to_string(minor) + " (" +
           (version ? reinterpret_cast<const char*>(version) : "unknown") + ")";
}

void createGraphics(Session& session) {
    session.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (session.display == EGL_NO_DISPLAY || !eglInitialize(session.display, nullptr, nullptr))
        throw std::runtime_error(eglFailure("3DS EGL display is unavailable"));
#ifdef __ANDROID__
    if (!eglBindAPI(EGL_OPENGL_ES_API))
        throw std::runtime_error(eglFailure("3DS OpenGL ES is unavailable"));
    constexpr EGLint renderable = EGL_OPENGL_ES3_BIT_KHR;
#else
    if (!eglBindAPI(EGL_OPENGL_API))
        throw std::runtime_error(eglFailure("3DS OpenGL is unavailable"));
    constexpr EGLint renderable = EGL_OPENGL_BIT;
#endif
    const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, renderable,
                                 EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                                 EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config = nullptr;
    EGLint count = 0;
    if (!eglChooseConfig(session.display, attributes, &config, 1, &count) || count != 1)
        throw std::runtime_error(eglFailure("3DS EGL configuration is unavailable"));
    const EGLint pbuffer[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    session.surface = eglCreatePbufferSurface(session.display, config, pbuffer);
    if (session.surface == EGL_NO_SURFACE)
        throw std::runtime_error(eglFailure("3DS EGL surface failed"));
#ifdef __ANDROID__
    const EGLint context[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 2,
                              EGL_NONE};
#else
    const EGLint context[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, 4, EGL_CONTEXT_MINOR_VERSION_KHR, 3,
                              EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR,
                              EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR, EGL_NONE};
#endif
    session.context = eglCreateContext(session.display, config, EGL_NO_CONTEXT, context);
    if (session.context == EGL_NO_CONTEXT ||
        !eglMakeCurrent(session.display, session.surface, session.surface, session.context))
        throw std::runtime_error(eglFailure("3DS graphics context failed"));
    GLint major = 0, minor = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &major);
    glGetIntegerv(GL_MINOR_VERSION, &minor);
#ifdef __ANDROID__
    if (major < 3 || (major == 3 && minor < 2))
        throw std::runtime_error("OpenGL ES 3.2 is required for 3DS; found " +
                                 observedVersion(major, minor));
#else
    if (major < 4 || (major == 4 && minor < 3))
        throw std::runtime_error("OpenGL 4.3 is required for 3DS; found " +
                                 observedVersion(major, minor));
#endif
    glGenFramebuffers(1, &session.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, session.framebuffer);
    glGenTextures(1, &session.color);
    glBindTexture(GL_TEXTURE_2D, session.color);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, kWidth, kHeight, 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, session.color, 0);
    glGenRenderbuffers(1, &session.depthStencil);
    glBindRenderbuffer(GL_RENDERBUFFER, session.depthStencil);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, kWidth, kHeight);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER,
                              session.depthStencil);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        throw std::runtime_error("3DS framebuffer is incomplete");
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    session.readback.resize(kPixels * 4);
}

void destroyGraphics(Session& session) {
    if (session.display == EGL_NO_DISPLAY) return;
    if (session.context != EGL_NO_CONTEXT && session.surface != EGL_NO_SURFACE)
        eglMakeCurrent(session.display, session.surface, session.surface, session.context);
    if (session.depthStencil) glDeleteRenderbuffers(1, &session.depthStencil);
    if (session.color) glDeleteTextures(1, &session.color);
    if (session.framebuffer) glDeleteFramebuffers(1, &session.framebuffer);
    eglMakeCurrent(session.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (session.context != EGL_NO_CONTEXT) eglDestroyContext(session.display, session.context);
    if (session.surface != EGL_NO_SURFACE) eglDestroySurface(session.display, session.surface);
    eglTerminate(session.display);
}

void readVideo(bool diagnose, unsigned ordinal) {
    auto& session = *g_session;
    GLint previousFramebuffer = 0, previousDrawFramebuffer = 0, previousPack = 0;
    GLint previousPbo = 0, previousReadBuffer = 0, rowLength = 0, skipRows = 0, skipPixels = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previousFramebuffer);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previousDrawFramebuffer);
    glGetIntegerv(GL_PACK_ALIGNMENT, &previousPack);
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &previousPbo);
    glGetIntegerv(GL_PACK_ROW_LENGTH, &rowLength);
    glGetIntegerv(GL_PACK_SKIP_ROWS, &skipRows);
    glGetIntegerv(GL_PACK_SKIP_PIXELS, &skipPixels);
    glGetIntegerv(GL_READ_BUFFER, &previousReadBuffer);
    const GLenum beforeError = glGetError();
    while (glGetError() != GL_NO_ERROR) {}
    glBindFramebuffer(GL_READ_FRAMEBUFFER, session.framebuffer);
    const GLenum status = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
    if (diagnose) {
        std::fprintf(stderr,
                     "THREEDS_VIDEO_DIAG #%u fbo=%u status=0x%x read_fbo=%d draw_fbo=%d "
                     "pbo=%d pack=%d row_length=%d skip_rows=%d skip_pixels=%d gl_prior=0x%x\n",
                     ordinal, session.framebuffer, status, previousFramebuffer,
                     previousDrawFramebuffer, previousPbo, previousPack, rowLength,
                     skipRows, skipPixels, beforeError);
    }
    if (rowLength > static_cast<GLint>(kWidth) || skipRows || skipPixels) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, previousFramebuffer);
        g_output->error = "3DS pixel pack state exceeds readback buffer";
        return;
    }
    glReadBuffer(GL_COLOR_ATTACHMENT0);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    const GLenum beforeReadError = glGetError();
    while (glGetError() != GL_NO_ERROR) {}
    glReadPixels(0, 0, kWidth, kHeight, GL_RGBA, GL_UNSIGNED_BYTE, session.readback.data());
    const GLenum error = glGetError();
    glPixelStorei(GL_PACK_ALIGNMENT, previousPack);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPbo);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, previousFramebuffer);
    glReadBuffer(previousReadBuffer);
    if (diagnose)
        std::fprintf(stderr, "THREEDS_VIDEO_DIAG #%u gl_before_read=0x%x gl_after_read=0x%x\n",
                     ordinal, beforeReadError, error);
    if (error != GL_NO_ERROR) {
        g_output->error = "3DS framebuffer read failed";
        return;
    }
    for (unsigned y = 0; y < kHeight; ++y) {
        const uint8_t* source = session.readback.data() + (kHeight - 1 - y) * kWidth * 4;
        uint32_t* target = g_output->pixels + y * kWidth;
        for (unsigned x = 0; x < kWidth; ++x) {
            const uint8_t* rgba = source + x * 4;
            target[x] = 0xff000000u | (uint32_t(rgba[0]) << 16) |
                        (uint32_t(rgba[1]) << 8) | rgba[2];
        }
    }
    if (diagnose) {
        const size_t top = ((kHeight - 1 - 120) * kWidth + 200) * 4;
        const size_t bottom = ((kHeight - 1 - 360) * kWidth + 200) * 4;
        std::fprintf(stderr,
                     "THREEDS_VIDEO_DIAG #%u top_rgba=%02x%02x%02x%02x top_argb=%08x "
                     "bottom_rgba=%02x%02x%02x%02x bottom_argb=%08x\n",
                     ordinal, session.readback[top], session.readback[top + 1],
                     session.readback[top + 2], session.readback[top + 3],
                     g_output->pixels[120 * kWidth + 200], session.readback[bottom],
                     session.readback[bottom + 1], session.readback[bottom + 2],
                     session.readback[bottom + 3], g_output->pixels[360 * kWidth + 200]);
    }
    g_output->video = true;
    session.sawVideo = true;
}

void video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!g_session) return;
    const unsigned ordinal = g_session->diagnosticCallbacks;
    const bool diagnose = ordinal < 3;
    if (diagnose) {
        ++g_session->diagnosticCallbacks;
        std::fprintf(stderr, "THREEDS_VIDEO_DIAG #%u marker=%s width=%u height=%u pitch=%zu output=%d\n",
                     ordinal + 1, data == RETRO_HW_FRAME_BUFFER_VALID ? "HW" : (data ? "SW" : "NULL"),
                     width, height, pitch, g_output != nullptr);
    }
    if (!g_output) return;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        if (width != kWidth || height != kHeight) {
            g_output->error = "Unexpected 3DS video geometry";
            return;
        }
        readVideo(diagnose, ordinal + 1);
        return;
    }
    if (!data) return;
    if (width != kWidth || height != kHeight || pitch < kWidth * 4) {
        g_output->error = "Unexpected 3DS software frame";
        return;
    }
    const auto* source = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < kHeight; ++y) {
        for (unsigned x = 0; x < kWidth; ++x) {
            uint32_t pixel;
            std::memcpy(&pixel, source + y * pitch + x * 4, 4);
            g_output->pixels[y * kWidth + x] = pixel | 0xff000000u;
        }
    }
    g_output->video = true;
    g_session->sawVideo = true;
}

size_t audio(const int16_t* samples, size_t frames) {
    if (!g_output || !samples) return 0;
    if (frames > kAudioShorts / 2 - g_output->frames) {
        g_output->error = "3DS audio buffer overflow";
        return 0;
    }
    std::memcpy(g_output->audio + g_output->frames * 2, samples, frames * 2 * sizeof(int16_t));
    g_output->frames += frames;
    return frames;
}

void pollInput() {}

int16_t input(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (!g_session || port != 0) return 0;
    const Session& session = *g_session;
    if (device == RETRO_DEVICE_JOYPAD)
        return id < 12 && (session.buttons & (1 << id)) ? 1 : 0;
    if (device == RETRO_DEVICE_ANALOG && index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {
        if (id == RETRO_DEVICE_ID_ANALOG_X) return std::clamp(session.circleX, -32767, 32767);
        if (id == RETRO_DEVICE_ID_ANALOG_Y) return std::clamp(session.circleY, -32767, 32767);
    }
    if (device == RETRO_DEVICE_POINTER && session.touching) {
        if (id == RETRO_DEVICE_ID_POINTER_PRESSED) return 1;
        const int x = std::clamp(session.touchX, 0, 319) + 40;
        const int y = std::clamp(session.touchY, 0, 239) + 240;
        if (id == RETRO_DEVICE_ID_POINTER_X)
            return static_cast<int16_t>((2 * x + 1) * 32768 / kWidth - 32768);
        if (id == RETRO_DEVICE_ID_POINTER_Y)
            return static_cast<int16_t>((2 * y + 1) * 32768 / kHeight - 32768);
    }
    return 0;
}

void requireOwner(const Session& session) {
    if (session.owner != std::this_thread::get_id())
        throw std::runtime_error("3DS core must run on its opening thread");
}

void release() {
    g_output = nullptr;
    if (!g_session) return;
    Session& session = *g_session;
    if (session.contextReset && session.hw.context_destroy) session.hw.context_destroy();
    if (session.loaded) session.core.unload();
    if (session.initialized) session.core.deinit();
    destroyGraphics(session);
    if (session.library) dlclose(session.library);
    g_session.reset();
}

}  // namespace

namespace pocket3ds {

uint64_t open(const std::string& rom, const std::string& userRoot, const std::string& systemRoot) {
    requireDirectory(userRoot);
    requireDirectory(systemRoot);
    requireRom(rom);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_session) {
        requireOwner(*g_session);
        release();
    }
    g_session = std::make_unique<Session>();
    Session& session = *g_session;
    session.token = ++g_nextToken;
    session.owner = std::this_thread::get_id();
    session.rom = rom;
    session.userRoot = userRoot;
    session.systemRoot = systemRoot;
    session.game.path = session.rom.c_str();
    try {
        session.library = dlopen("libazahar_libretro.so", RTLD_NOW | RTLD_LOCAL);
        if (!session.library) throw std::runtime_error("Packaged 3DS core is unavailable");
        session.core = loadSymbols(session.library);
        createGraphics(session);
        session.core.setEnvironment(environment);
        session.core.setVideo(video);
        session.core.setAudio(audio);
        session.core.setInputPoll(pollInput);
        session.core.setInputState(input);
        session.core.init();
        session.initialized = true;
        if (!session.core.load(&session.game))
            throw std::runtime_error(session.message.empty() ? "3DS core rejected ROM" : session.message);
        session.loaded = true;
        if (!session.hw.context_reset) throw std::runtime_error("3DS core did not request OpenGL");
        session.message.clear();
        session.hw.context_reset();
        session.contextReset = true;
        if (!session.message.empty()) throw std::runtime_error(session.message);
        retro_system_av_info info{};
        session.core.avInfo(&info);
        if (info.geometry.base_width != kWidth || info.geometry.base_height != kHeight ||
            info.timing.sample_rate <= 0 || info.timing.fps <= 0)
            throw std::runtime_error("Unsupported 3DS video or audio configuration");
        session.metrics[0] = kWidth;
        session.metrics[1] = kHeight;
        session.metrics[2] = static_cast<int>(std::lround(info.timing.sample_rate));
        session.metrics[3] = static_cast<int>(std::lround(info.timing.fps * 1000));
        return session.token;
    } catch (...) {
        release();
        throw;
    }
}

void configuration(uint64_t token, int metrics[4]) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_session || !token || g_session->token != token)
        throw std::runtime_error("3DS session is closed");
    std::copy_n(g_session->metrics, 4, metrics);
}

int frame(uint64_t token, int buttons, int touchX, int touchY, bool down, int circleX, int circleY,
          uint32_t* pixels, size_t pixelCapacity, int16_t* pcm, size_t audioCapacity) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_session || !token || g_session->token != token)
        throw std::runtime_error("3DS session is closed");
    Session& session = *g_session;
    requireOwner(session);
    if (!pixels || !pcm || pixelCapacity < kPixels || audioCapacity < kAudioShorts)
        throw std::runtime_error("3DS output buffer is too small");
    if (!eglMakeCurrent(session.display, session.surface, session.surface, session.context))
        throw std::runtime_error(eglFailure("3DS graphics context was lost"));
    session.buttons = buttons & 0xfff;
    session.touchX = touchX;
    session.touchY = touchY;
    session.touching = down;
    session.circleX = circleX;
    session.circleY = circleY;
    Output output{pixels, pcm};
    g_output = &output;
    try {
        session.core.run();
    } catch (...) {
        g_output = nullptr;
        throw;
    }
    g_output = nullptr;
    if (!output.video && session.diagnosticNoVideo < 3) {
        std::fprintf(stderr, "THREEDS_NO_VIDEO_DIAG #%u callbacks=%u audio_frames=%zu\n",
                     ++session.diagnosticNoVideo, session.diagnosticCallbacks, output.frames);
    }
    if (output.error) throw std::runtime_error(output.error);
    if (!session.message.empty()) throw std::runtime_error(session.message);
    if (!output.video) {
        if (session.sawVideo) throw std::runtime_error("3DS core stopped producing video");
        return -1;
    }
    return static_cast<int>(output.frames);
}

void close(uint64_t token) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!token || !g_session || g_session->token != token) return;
    requireOwner(*g_session);
    release();
}

}  // namespace pocket3ds
