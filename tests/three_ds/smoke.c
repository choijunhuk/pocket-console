/* Original 3DSX acceptance program; no game or firmware assets. */
#include <3ds.h>

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
    gfxInitDefault();
    bool sound = R_SUCCEEDED(csndInit());
    if (sound) {
        /* CSND PSG needs no dumped DSP firmware. */
        u32 flags = SOUND_CHANNEL(8) | SOUND_FORMAT_PSG | SOUND_REPEAT |
                    SOUND_ENABLE | (CSND_TIMER(8192) << 16);
        u32 volume = CSND_VOL(0.8f, 0.0f);
        CSND_SetChnRegsPSG(flags, volume, volume, DutyCycle_50);
        sound = R_SUCCEEDED(csndExecCmds(true));
    }

    while (aptMainLoop()) {
        hidScanInput();
        u32 held = hidKeysHeld();
        if (held & KEY_START) break;
        circlePosition circle;
        hidCircleRead(&circle);

        /* Top: blue normally, green while A is held. */
        fill(GFX_TOP, (held & KEY_A) ? 0 : 255, (held & KEY_A) ? 255 : 0, 0);
        /* Bottom: red, yellow on touch, cyan when the circle pad moves right. */
        if (held & KEY_TOUCH) fill(GFX_BOTTOM, 0, 255, 255);
        else if (circle.dx > 80) fill(GFX_BOTTOM, 255, 255, 0);
        else if (sound) fill(GFX_BOTTOM, 0, 0, 255);
        else fill(GFX_BOTTOM, 255, 0, 255); /* Magenta marks CSND init failure. */

        gfxFlushBuffers();
        gfxSwapBuffers();
        gspWaitForVBlank();
    }

    if (sound) csndExit();
    gfxExit();
    return 0;
}
