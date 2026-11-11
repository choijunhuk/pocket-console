package dev.pocketconsole;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Exercises the packaged 3DS core and the separate-process player with original homebrew. */
public final class ThreeDsSmokeInstrumentation extends Instrumentation {
    private static final String TAG = "ThreeDsSmoke";
    private static final int WIDTH = 400;
    private static final int HEIGHT = 480;
    private final int[] pixels = new int[WIDTH * HEIGHT];
    private final short[] audio = new short[16384];
    private Method open, configuration, frame, close;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        try {
            File root = new File(getTargetContext().getCacheDir(), "three-ds-smoke");
            require(root.mkdirs() || root.isDirectory(), "cannot create 3DS smoke directory");
            File rom = new File(root, "smoke.3dsx");
            try (InputStream source = getContext().getAssets().open("smoke.3dsx");
                 FileOutputStream target = new FileOutputStream(rom)) {
                byte[] chunk = new byte[8192];
                int count;
                while ((count = source.read(chunk)) != -1) target.write(chunk, 0, count);
            }
            bindNative();
            testNative(rom, root);
            testPlayer(rom);
            result.putString("stream", "THREEDS_ANDROID_SMOKE_PASS " +
                    "cache/three-ds-player-portrait.png cache/three-ds-player-landscape.png");
            Log.i(TAG, "THREEDS_ANDROID_SMOKE_PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            Log.e(TAG, "THREEDS_ANDROID_SMOKE_FAIL", failure);
            result.putString("stream", "THREEDS_ANDROID_SMOKE_FAIL: " +
                    Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void bindNative() throws Exception {
        Class<?> type = getTargetContext().getClassLoader()
                .loadClass("dev.pocketconsole.ThreeDsNative");
        open = type.getDeclaredMethod("open", String.class, String.class, String.class);
        configuration = type.getDeclaredMethod("configuration", long.class, int[].class);
        frame = type.getDeclaredMethod("frame", long.class, int[].class, short[].class,
                int.class, int.class, int.class, boolean.class, int.class, int.class);
        close = type.getDeclaredMethod("close", long.class);
        for (Method method : new Method[]{open, configuration, frame, close}) method.setAccessible(true);
    }

    private Object invoke(Method method, Object... args) throws Exception {
        try { return method.invoke(null, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure;
        }
    }

    private int run(long token, int buttons, boolean touch, int circleX) throws Exception {
        Arrays.fill(audio, (short) 0);
        int count = (int) invoke(frame, token, pixels, audio, buttons,
                160, 120, touch, circleX, 0);
        require(count >= -1 && count <= audio.length / 2, "invalid 3DS audio frame count");
        return count;
    }

    private void testNative(File rom, File root) throws Exception {
        long token = (long) invoke(open, rom.getAbsolutePath(), root.getAbsolutePath(),
                root.getAbsolutePath());
        try {
            int[] metrics = new int[4];
            invoke(configuration, token, metrics);
            require(metrics[0] == WIDTH && metrics[1] == HEIGHT &&
                    metrics[2] > 0 && metrics[3] > 0, "unexpected 3DS geometry or timing");
            awaitNativeColor(token, 'b', false, 0, false, 0, 90000);
            require(color(pixels[360 * WIDTH + 200], 'r'),
                    "lower 3DS screen is not red; CSND init may have failed");
            awaitNativeColor(token, 'g', false, 1 << 8, false, 0, 15000);
            awaitNativeColor(token, 'y', true, 0, true, 0, 15000);
            awaitNativeColor(token, 'c', true, 0, false, 32767, 15000);

            long deadline = SystemClock.uptimeMillis() + 30000;
            boolean callback = false, pcm = false;
            while (SystemClock.uptimeMillis() < deadline && !pcm) {
                int count = run(token, 1 << 9, false, 0);
                callback |= count > 0;
                for (int i = 0; i < count * 2; i++) pcm |= audio[i] != 0;
                require(!color(pixels[360 * WIDTH + 200], 'm'), "guest NDSP startup failed");
            }
            require(callback, "3DS core produced no stereo audio frames");
            require(pcm, "original NDSP PCM tone produced no nonzero samples");
        } finally {
            invoke(close, token);
        }
    }

    private void awaitNativeColor(long token, char expected, boolean lower,
                                  int buttons, boolean touch, int circleX, long timeoutMs)
            throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (run(token, buttons, touch, circleX) >= 0 &&
                    color(pixels[(lower ? 360 : 120) * WIDTH + 200], expected)) return;
        }
        throw new AssertionError("3DS native fixture color missing: " + expected);
    }

    private void testPlayer(File rom) throws Exception {
        Intent intent = new Intent().setClassName(getTargetContext().getPackageName(),
                "dev.pocketconsole.ThreeDsPlayerActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(ThreeDsPlayerActivity.EXTRA_ROM_URI, Uri.fromFile(rom).toString());
        getTargetContext().startActivity(intent);

        UiAutomation ui = getUiAutomation();
        String oldRotate = shell("settings get system accelerometer_rotation").trim();
        String oldOrientation = shell("settings get system user_rotation").trim();
        try {
            shell("settings put system accelerometer_rotation 0");
            shell("settings put system user_rotation 0");
            awaitStatus("3DS 실행 중", 90000);
            Bitmap portrait = awaitScreenshot('b', -1, 15000);
            require(portrait != null, "portrait 3DS player has no blue homebrew screen");
            requireScreenshotColor(portrait, 'r', "portrait lower red screen missing");
            checkControls(portrait);
            save(portrait, "three-ds-player-portrait.png");
            int screenHeight = portrait.getHeight();
            portrait.recycle();

            Rect a = bounds(findNode("A"));
            int drag = Math.round(64 * getTargetContext().getResources().getDisplayMetrics().density);
            int draggedY = a.centerY() + (a.centerY() + drag < screenHeight ? drag : -drag);
            long downTime = SystemClock.uptimeMillis();
            inject(ui, MotionEvent.ACTION_DOWN, a.centerX(), a.centerY(), downTime);
            try {
                Bitmap pressed = awaitScreenshot('g', -1, 15000);
                require(pressed != null, "A control did not turn 3DS player screen green");
                pressed.recycle();
                inject(ui, MotionEvent.ACTION_MOVE, a.centerX(), draggedY, downTime);
                Thread.sleep(250); // Let several emulated frames observe any parent interception.
                Bitmap dragged = awaitScreenshot('g', -1, 3000);
                require(dragged != null, "A hold was lost after moving beyond touch slop");
                dragged.recycle();
            } finally {
                inject(ui, MotionEvent.ACTION_UP, a.centerX(), draggedY, downTime);
            }
            Bitmap released = awaitScreenshot('b', -1, 15000);
            require(released != null, "A release did not restore the blue 3DS screen");
            released.recycle();

            shell("settings put system user_rotation 1");
            Bitmap landscape = awaitScreenshot('b', 1, 15000);
            require(landscape != null, "landscape 3DS player has no blue homebrew screen");
            requireScreenshotColor(landscape, 'r', "landscape lower red screen missing");
            checkControls(landscape); // Re-query nodes after the activity rebuilds its view tree.
            save(landscape, "three-ds-player-landscape.png");
            landscape.recycle();
        } finally {
            restoreSetting("user_rotation", oldOrientation);
            restoreSetting("accelerometer_rotation", oldRotate);
            injectBack(ui);
        }
    }

    private void awaitStatus(String expected, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (findNode(expected) != null) return;
            Thread.sleep(100);
        }
        throw new AssertionError("3DS player never reached status: " + expected);
    }

    private Bitmap awaitScreenshot(char expected, int orientation, long timeoutMs)
            throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            Bitmap shot = getUiAutomation().takeScreenshot();
            if (shot != null) {
                boolean oriented = orientation < 0 ? shot.getHeight() > shot.getWidth()
                        : shot.getWidth() > shot.getHeight();
                if (oriented && countScreenColor(shot, expected) >= 250) return shot;
                shot.recycle();
            }
            Thread.sleep(100);
        }
        return null;
    }

    private void checkControls(Bitmap shot) {
        for (String label : new String[]{"A", "B", "X", "Y", "L", "R", "SELECT", "START",
                "↑", "↓", "←", "→", "3DS 원형 슬라이드 패드"}) {
            AccessibilityNodeInfo node = findNode(label);
            require(node != null && node.isVisibleToUser(), "missing or hidden 3DS control: " + label);
            Rect box = bounds(node);
            require(box.width() > 0 && box.height() > 0 && box.left >= 0 && box.top >= 0 &&
                    box.right <= shot.getWidth() && box.bottom <= shot.getHeight(),
                    "clipped 3DS control: " + label);
        }
    }

    private AccessibilityNodeInfo findNode(String wanted) {
        return findNode(getUiAutomation().getRootInActiveWindow(), wanted);
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, String wanted) {
        if (node == null) return null;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (wanted.contentEquals(text == null ? "" : text) ||
                wanted.contentEquals(description == null ? "" : description)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(node.getChild(i), wanted);
            if (found != null) return found;
        }
        return null;
    }

    private static Rect bounds(AccessibilityNodeInfo node) {
        require(node != null, "required 3DS control missing");
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds;
    }

    private static void inject(UiAutomation ui, int action, int x, int y, long downTime) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        try { require(ui.injectInputEvent(event, true), "cannot inject A touch"); }
        finally { event.recycle(); }
    }

    private static void injectBack(UiAutomation ui) {
        long now = SystemClock.uptimeMillis();
        ui.injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true);
        ui.injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true);
    }

    private void restoreSetting(String name, String value) throws Exception {
        if (value.isEmpty() || value.equals("null")) shell("settings delete system " + name);
        else shell("settings put system " + name + " " + value);
    }

    private String shell(String command) throws Exception {
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand(command));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[1024];
            int count;
            while ((count = stream.read(chunk)) != -1) output.write(chunk, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void save(Bitmap screenshot, String name) throws Exception {
        File file = new File(getTargetContext().getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            require(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output),
                    "cannot save 3DS player screenshot");
        }
    }

    private void requireScreenshotColor(Bitmap shot, char expected, String message) {
        require(countScreenColor(shot, expected) >= 250, message);
    }

    private int countScreenColor(Bitmap shot, char expected) {
        AccessibilityNodeInfo screen = findNode("3DS 위 화면과 터치 가능한 아래 화면");
        if (screen == null) return 0;
        Rect area = bounds(screen);
        if (!area.intersect(0, 0, shot.getWidth(), shot.getHeight())) return 0;
        int count = 0;
        for (int y = area.top; y < area.bottom; y += 2)
            for (int x = area.left; x < area.right; x += 2)
                if (color(shot.getPixel(x, y), expected)) count++;
        return count;
    }

    private static boolean color(int argb, char wanted) {
        int r = (argb >>> 16) & 255, g = (argb >>> 8) & 255, b = argb & 255;
        if (wanted == 'b') return b > r + 80 && b > g + 80;
        if (wanted == 'r') return r > b + 80 && r > g + 80;
        if (wanted == 'g') return g > r + 80 && g > b + 80;
        if (wanted == 'y') return r > b + 80 && g > b + 80;
        if (wanted == 'c') return b > r + 80 && g > r + 80;
        if (wanted == 'm') return r > g + 80 && b > g + 80;
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
