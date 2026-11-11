package dev.pocketconsole;

import java.io.IOException;

final class ThreeDsNative {
    static { System.loadLibrary("pocket_3ds"); }

    private ThreeDsNative() { }

    static native long open(String romPath, String userRoot, String systemRoot) throws IOException;
    static native void configuration(long token, int[] metrics) throws IOException;
    static native int frame(long token, int[] pixels, short[] audio, int buttons,
                            int touchX, int touchY, boolean touching, int circleX, int circleY)
            throws IOException;
    static native void close(long token) throws IOException;
}
