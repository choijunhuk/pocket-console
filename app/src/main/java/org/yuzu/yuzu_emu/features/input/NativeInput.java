package org.yuzu.yuzu_emu.features.input;

/** Switch 2 touch controls mapped to the pinned virtual gamepad driver. */
public final class NativeInput {
    public static final int A = 0, B = 1, X = 2, Y = 3;
    public static final int L = 6, R = 7, ZL = 8, ZR = 9;
    public static final int PLUS = 10, MINUS = 11;
    public static final int LEFT = 12, UP = 13, RIGHT = 14, DOWN = 15;
    public static final int LEFT_STICK = 0, RIGHT_STICK = 1;

    private NativeInput() {}

    private static native void setButtonParamImpl(int player, int button, String parameter);
    private static native void setStickParamImpl(int player, int stick, String parameter);
    private static native void setStyleIndexImpl(int player, int style);
    private static native void reloadInputDevices();
    private static native void connectPocketHandheld();
    public static native void resetPocketState();
    private static native void onOverlayButtonEventImpl(int port, int button, int action);
    private static native void onOverlayJoystickEventImpl(
            int port, int stick, float horizontal, float vertical);
    public static native void onTouchPressed(int id, float x, float y);
    public static native void onTouchMoved(int id, float x, float y);
    public static native void onTouchReleased(int id);

    public static void configureHandheld() {
        for (int button = A; button <= DOWN; button++) {
            setButtonParamImpl(0, button, "engine:virtual_gamepad,port:0,pad:0,button:" + button);
        }
        for (int stick = LEFT_STICK; stick <= RIGHT_STICK; stick++) {
            int axis = stick * 2;
            setStickParamImpl(0, stick, "engine:virtual_gamepad,port:0,pad:0,axis_x:"
                    + axis + ",axis_y:" + (axis + 1));
        }
        setStyleIndexImpl(0, 4); // NpadStyleIndex::Handheld in pinned hid_types.h.
        connectPocketHandheld();
        reloadInputDevices();
    }

    public static void button(int id, boolean down) {
        onOverlayButtonEventImpl(0, id, down ? 1 : 0);
    }

    public static void stick(int id, float x, float y) {
        onOverlayJoystickEventImpl(0, id, Math.max(-1f, Math.min(1f, x)),
                Math.max(-1f, Math.min(1f, y)));
    }
}
