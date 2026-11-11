package dev.pocketconsole;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Runs the original DS homebrew against the packaged JNI and core on an emulator. */
public final class DsSmokeInstrumentation extends Instrumentation {
    private static final String TAG = "DsSmoke";
    private static final int WIDTH = 256;
    private static final int HEIGHT = 384;
    private Method open;
    private Method frame;
    private Method close;
    private final int[] pixels = new int[WIDTH * HEIGHT];
    private final short[] audio = new short[8192];

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        try {
            Class<?> nativeClass = getTargetContext().getClassLoader()
                    .loadClass("dev.pocketconsole.DsNative");
            open = nativeClass.getDeclaredMethod("open", String.class, String.class, String.class);
            frame = nativeClass.getDeclaredMethod("frame", long.class, int[].class, short[].class,
                    int.class, int.class, int.class, boolean.class);
            close = nativeClass.getDeclaredMethod("close", long.class);
            open.setAccessible(true);
            frame.setAccessible(true);
            close.setAccessible(true);

            File root = new File(getTargetContext().getCacheDir(), "ds-smoke");
            require(root.mkdirs() || root.isDirectory(), "cannot create smoke directory");
            File rom = new File(root, "smoke.nds");
            try (InputStream source = getContext().getAssets().open("smoke.nds");
                 FileOutputStream target = new FileOutputStream(rom)) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = source.read(chunk)) != -1) target.write(chunk, 0, count);
            }
            testVideo(rom, root);
            testInput(rom, root);
            testAudio(rom, root);
            testMalformedRom(rom, root);
            testStaleClose(rom, root);
            testPlayerUi(rom);
            result.putString("stream", "DS_ANDROID_SMOKE_PASS cache/smoke-player.png");
            Log.i(TAG, "DS_ANDROID_SMOKE_PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            Log.e(TAG, "DS_ANDROID_SMOKE_FAIL", failure);
            result.putString("stream", "DS_ANDROID_SMOKE_FAIL: " + Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private long open(File rom, File root) throws Exception {
        return (long) invoke(open, rom.getAbsolutePath(), root.getAbsolutePath(), root.getAbsolutePath());
    }

    private int frame(long token, int mask) throws Exception {
        return (int) invoke(frame, token, pixels, audio, mask, 0, 0, false);
    }

    private void close(long token) throws Exception {
        invoke(close, token);
    }

    private Object invoke(Method method, Object... args) throws Exception {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure;
        }
    }

    private void testVideo(File rom, File root) throws Exception {
        long token = open(rom, root);
        try {
            boolean blue = false;
            for (int i = 0; i < 120 && !blue; i++) {
                frame(token, 0);
                blue = visible(false);
            }
            require(blue, "ARM9 did not display blue pixels");
        } finally {
            close(token);
        }
    }

    private void testInput(File rom, File root) throws Exception {
        long token = open(rom, root);
        try {
            boolean green = false;
            for (int i = 0; i < 120 && !green; i++) {
                frame(token, 1 << 8);
                green = visible(true);
            }
            require(green, "A input did not change pixels to green");
        } finally {
            close(token);
        }
    }

    private boolean visible(boolean expectGreen) {
        return colorMatches(pixels[96 * WIDTH + 128], expectGreen) ||
               colorMatches(pixels[288 * WIDTH + 128], expectGreen);
    }

    private static boolean colorMatches(int color, boolean expectGreen) {
        int red = (color >>> 16) & 255;
        int green = (color >>> 8) & 255;
        int blue = color & 255;
        return expectGreen ? green > red + 80 && green > blue + 80
                           : blue > red + 80 && blue > green + 80;
    }

    private void testAudio(File rom, File root) throws Exception {
        long token = open(rom, root);
        try {
            boolean frames = false;
            boolean samples = false;
            for (int i = 0; i < 120 && !samples; i++) {
                Arrays.fill(audio, (short) 0);
                int count = frame(token, 0);
                require(count >= 0 && count <= audio.length / 2, "invalid stereo frame count");
                frames |= count > 0;
                for (int j = 0; j < count * 2; j++) samples |= audio[j] != 0;
            }
            require(frames, "core produced no stereo audio frames");
            require(samples, "ARM7 PSG did not produce nonzero PCM");
        } finally {
            close(token);
        }
    }

    private void testMalformedRom(File rom, File root) throws Exception {
        File malformed = new File(root, "truncated.nds");
        try (DataInputStream source = new DataInputStream(new FileInputStream(rom));
             FileOutputStream target = new FileOutputStream(malformed)) {
            byte[] header = new byte[0x200];
            source.readFully(header);
            target.write(header);
        }
        boolean rejected = false;
        try {
            long token = open(malformed, root);
            close(token);
        } catch (IOException expected) {
            rejected = true;
        }
        require(rejected, "truncated ROM was accepted");
    }

    private void testStaleClose(File rom, File root) throws Exception {
        long old = open(rom, root);
        close(old);
        long current = open(rom, root);
        try {
            close(old);
            frame(current, 0);
        } finally {
            close(current);
        }
        boolean rejected = false;
        try {
            frame(current, 0);
        } catch (IOException expected) {
            rejected = true;
        }
        require(rejected, "closed session token was accepted");
    }

    private void testPlayerUi(File rom) throws Exception {
        Intent intent = new Intent().setClassName(getTargetContext().getPackageName(),
                "dev.pocketconsole.DsPlayerActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(DsPlayerActivity.EXTRA_ROM_URI, Uri.fromFile(rom).toString());
        Activity activity = startActivitySync(intent);
        require(activity != null, "DS player did not start");
        Button a = null;
        try {
            runOnMainSync(() -> activity.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            long deadline = SystemClock.uptimeMillis() + 15000;
            boolean running = false;
            while (SystemClock.uptimeMillis() < deadline && !running) {
                final boolean[] found = {false};
                runOnMainSync(() -> found[0] = findText(activity.getWindow().getDecorView(),
                        "DS 실행 중") != null);
                running = found[0];
                if (!running) Thread.sleep(100);
            }
            require(running, "DS player never reached the running state");

            final Button[] buttons = new Button[2];
            runOnMainSync(() -> {
                buttons[0] = (Button) findText(activity.getWindow().getDecorView(), "A");
                buttons[1] = (Button) findText(activity.getWindow().getDecorView(), "B");
            });
            a = buttons[0];
            require(a != null && buttons[1] != null, "A/B controls missing");
            final Button aButton = a;

            Bitmap screenshot = awaitColor(false, 10000, -1);
            require(screenshot != null, "player screenshot has no blue DS pixels");
            requireFullyVisible(a, screenshot, "A control is off-screen");
            requireFullyVisible(buttons[1], screenshot, "B control is off-screen");
            requirePlayerControls(activity, screenshot);
            screenshot.recycle();

            runOnMainSync(aButton::performClick);
            Thread.sleep(150); // The click pulse lasts 100 ms; then hold A for the screenshot poll.
            runOnMainSync(() -> dispatchButton(aButton, MotionEvent.ACTION_DOWN));
            screenshot = awaitColor(true, 10000, -1);
            require(screenshot != null, "player screenshot did not turn green after A");
            File output = new File(getTargetContext().getCacheDir(), "smoke-player.png");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                require(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream),
                        "cannot save player screenshot");
            }
            screenshot.recycle();

            runOnMainSync(() -> dispatchButton(aButton, MotionEvent.ACTION_UP));

            runOnMainSync(() -> activity.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            Bitmap landscape = awaitColor(false, 10000, 1);
            require(landscape != null, "landscape player did not show blue DS pixels");
            runOnMainSync(() -> {
                buttons[0] = (Button) findText(activity.getWindow().getDecorView(), "A");
                buttons[1] = (Button) findText(activity.getWindow().getDecorView(), "B");
            });
            require(buttons[0] != null && buttons[1] != null, "landscape A/B controls missing");
            a = buttons[0];
            requireFullyVisible(buttons[0], landscape, "A control is off-screen in landscape");
            requireFullyVisible(buttons[1], landscape, "B control is off-screen in landscape");
            requirePlayerControls(activity, landscape);
            landscape.recycle();
        } finally {
            if (a != null) {
                Button button = a;
                runOnMainSync(() -> dispatchButton(button, MotionEvent.ACTION_UP));
            }
            runOnMainSync(activity::finish);
        }
    }

    private void requirePlayerControls(Activity activity, Bitmap screenshot) {
        for (String label : new String[]{"A", "B", "X", "Y", "L", "R", "SELECT", "START", "↑", "↓", "←", "→"}) {
            final View[] control = {null};
            runOnMainSync(() -> control[0] = findText(activity.getWindow().getDecorView(), label));
            require(control[0] != null, "missing player control: " + label);
            requireFullyVisible(control[0], screenshot, "player control is clipped: " + label);
        }
    }

    private static void dispatchButton(Button button, int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action,
                button.getWidth() / 2f, button.getHeight() / 2f, 0);
        try { button.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private Bitmap awaitColor(boolean green, long timeoutMs, int orientation)
            throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            Bitmap screenshot = getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                int count = 0;
                if (orientation == 0 || (orientation < 0 && screenshot.getHeight() > screenshot.getWidth()) ||
                        (orientation > 0 && screenshot.getWidth() > screenshot.getHeight())) {
                    for (int y = 0; y < screenshot.getHeight(); y += 2) {
                        for (int x = 0; x < screenshot.getWidth(); x += 2) {
                            if (colorMatches(screenshot.getPixel(x, y), green)) count++;
                        }
                    }
                }
                if (count >= 250) return screenshot;
                screenshot.recycle();
            }
            Thread.sleep(100);
        }
        return null;
    }

    private static View findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void requireFullyVisible(View view, Bitmap screenshot, String message) {
        final Rect[] bounds = {new Rect()};
        final boolean[] visible = {false};
        runOnMainSync(() -> visible[0] = view.getGlobalVisibleRect(bounds[0]));
        require(visible[0] && bounds[0].width() == view.getWidth() &&
                bounds[0].height() == view.getHeight() && bounds[0].left >= 0 &&
                bounds[0].top >= 0 && bounds[0].right <= screenshot.getWidth() &&
                bounds[0].bottom <= screenshot.getHeight(), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
