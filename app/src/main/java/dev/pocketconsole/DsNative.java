package dev.pocketconsole;

import java.io.IOException;

final class DsNative {
    static { System.loadLibrary("pocket_ds"); }

    private DsNative() { }

    static native long open(String romPath, String systemDir, String savesDir) throws IOException;
    static native int frame(long token, int[] pixels, short[] audio, int buttonMask,
                            int touchX, int touchY, boolean down) throws IOException;
    static native void close(long token);
}
