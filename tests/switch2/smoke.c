#include <switch.h>
#include <stdint.h>
#include <stddef.h>

enum { Width = 1280, Height = 720, AudioSamples = 8192 };
static u32 audio_samples[AudioSamples] __attribute__((aligned(0x1000)));

static void fill_tone(void) {
    u32 phase = 0;
    for (size_t i = 0; i < AudioSamples; i++) {
        phase = (phase + 440) % 48000;
        const s16 sample = phase < 24000 ? 4000 : -4000;
        audio_samples[i] = ((u32)(u16)sample << 16) | (u16)sample;
    }
}

int main(int argc, char** argv) {
    (void)argc;
    (void)argv;
    NWindow* window = nwindowGetDefault();
    if (window == NULL) return 2;
    Framebuffer fb;
    Result result = framebufferCreate(&fb, window, Width, Height, PIXEL_FORMAT_RGBA_8888, 2);
    if (R_FAILED(result)) return 3;
    result = framebufferMakeLinear(&fb);
    if (R_FAILED(result)) {
        framebufferClose(&fb);
        return 4;
    }

    padConfigureInput(1, HidNpadStyleSet_NpadStandard);
    PadState pad;
    padInitializeDefault(&pad);
    hidInitializeTouchScreen();

    bool audio_ready = R_SUCCEEDED(audoutInitialize());
    if (audio_ready) {
        result = audoutStartAudioOut();
        if (R_FAILED(result)) {
            audoutExit();
            audio_ready = false;
        }
    }
    fill_tone();
    AudioOutBuffer sound = {0};
    sound.buffer = audio_samples;
    sound.buffer_size = sizeof(audio_samples);
    sound.data_size = sizeof(audio_samples);
    int audio_state = 0;

    while (appletMainLoop()) {
        padUpdate(&pad);
        u64 down = padGetButtonsDown(&pad);
        u64 held = padGetButtons(&pad);
        if (down & HidNpadButton_Plus) break;
        if (down & HidNpadButton_X) {
            AudioOutBuffer* released = NULL;
            audio_state = audio_ready && R_SUCCEEDED(audoutPlayBuffer(&sound, &released)) ? 1 : -1;
        }

        HidAnalogStickState stick = padGetStickPos(&pad, 0);
        HidTouchScreenState touch = {0};
        hidGetTouchScreenStates(&touch, 1);
        u32 top = (held & HidNpadButton_A) ? RGBA8_MAXALPHA(0, 220, 0)
                : stick.x > 8000 ? RGBA8_MAXALPHA(0, 220, 220)
                : RGBA8_MAXALPHA(0, 0, 230);
        u32* pixels;
        u32 stride;
        pixels = (u32*)framebufferBegin(&fb, &stride);
        if (pixels == NULL) break;
        stride /= sizeof(u32);
        for (u32 y = 0; y < Height; y++) {
            for (u32 x = 0; x < Width; x++) {
                u32 color = y < Height / 2 ? top : RGBA8_MAXALPHA(220, 0, 0);
                if (audio_state != 0 && x >= Width - 40 && y < 40) {
                    color = audio_state > 0 ? RGBA8_MAXALPHA(255, 255, 255)
                                             : RGBA8_MAXALPHA(100, 100, 100);
                }
                if (touch.count > 0 && touch.touches[0].x < Width
                        && touch.touches[0].y < Height) {
                    u32 tx = touch.touches[0].x;
                    u32 ty = touch.touches[0].y;
                    if (x >= tx && x - tx < 36 && y >= ty && y - ty < 36) {
                        color = RGBA8_MAXALPHA(255, 220, 0);
                    }
                }
                pixels[y * stride + x] = color;
            }
        }
        framebufferEnd(&fb);
    }

    if (audio_ready) {
        audoutStopAudioOut();
        audoutExit();
    }
    framebufferClose(&fb);
    return 0;
}
