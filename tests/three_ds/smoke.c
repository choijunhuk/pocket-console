/* Original 3DSX acceptance program; no game or firmware assets. */
#include <3ds.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

#define TONE_FRAMES 2048
static s16* tone;
static ndspWaveBuf wave;

static bool start_audio(void)
{
    /* libctru needs a component path; Azahar's DSP HLE accepts an empty stand-in. */
    FILE* component = fopen("sdmc:/3ds/dspfirm.cdc", "rb");
    if (component) fclose(component);
    else {
        mkdir("sdmc:/3ds", 0777);
        component = fopen("sdmc:/3ds/dspfirm.cdc", "wb");
        if (!component) return false;
        fclose(component);
    }
    if (R_FAILED(ndspInit())) return false;
    tone = linearAlloc(TONE_FRAMES * 2 * sizeof(s16));
    if (!tone) { ndspExit(); return false; }
    for (int i = 0; i < TONE_FRAMES; ++i) {
        s16 sample = (i % 64 < 32) ? 6000 : -6000;
        tone[2 * i] = sample;
        tone[2 * i + 1] = sample;
    }
    DSP_FlushDataCache(tone, TONE_FRAMES * 2 * sizeof(s16));
    ndspSetOutputMode(NDSP_OUTPUT_STEREO);
    ndspChnSetInterp(0, NDSP_INTERP_LINEAR);
    ndspChnSetRate(0, 32768);
    ndspChnSetFormat(0, NDSP_FORMAT_STEREO_PCM16);
    float mix[12] = {1.0f, 1.0f};
    ndspChnSetMix(0, mix);
    memset(&wave, 0, sizeof(wave));
    wave.data_vaddr = tone;
    wave.nsamples = TONE_FRAMES;
    wave.looping = true;
    ndspChnWaveBufAdd(0, &wave);
    return true;
}

static void fill(gfxScreen_t screen, u8 blue, u8 green, u8 red)
{
    u16 width, height;
    u8* frame = gfxGetFramebuffer(screen, GFX_LEFT, &width, &height);
    for (u32 i = 0; i < (u32)width * height; ++i) {
        frame[3 * i] = blue;
        frame[3 * i + 1] = green;
        frame[3 * i + 2] = red;
    }
}

int main(void)
{
    svcOutputDebugString("THREEDS_GFX_INIT_BEGIN", sizeof("THREEDS_GFX_INIT_BEGIN") - 1);
    gfxInitDefault();
    svcOutputDebugString("THREEDS_GFX_INIT_END", sizeof("THREEDS_GFX_INIT_END") - 1);
    int sound = 0; /* 0 awaits X, 1 plays, -1 marks DSP startup failure. */

    while (aptMainLoop()) {
        hidScanInput();
        u32 held = hidKeysHeld();
        if (held & KEY_START) break;
        if ((held & KEY_X) && sound == 0) sound = start_audio() ? 1 : -1;
        circlePosition circle;
        hidCircleRead(&circle);

        /* Top: blue normally, green while A is held. */
        fill(GFX_TOP, (held & KEY_A) ? 0 : 255, (held & KEY_A) ? 255 : 0, 0);
        /* Bottom: red, yellow on touch, cyan when the circle pad moves right. */
        if (held & KEY_TOUCH) fill(GFX_BOTTOM, 0, 255, 255);
        else if (circle.dx > 80) fill(GFX_BOTTOM, 255, 255, 0);
        else if (sound < 0) fill(GFX_BOTTOM, 255, 0, 255);
        else fill(GFX_BOTTOM, 0, 0, 255);

        gfxFlushBuffers();
        gfxSwapBuffers();
        gspWaitForVBlank();
    }

    if (sound > 0) { ndspExit(); linearFree(tone); }
    gfxExit();
    return 0;
}
