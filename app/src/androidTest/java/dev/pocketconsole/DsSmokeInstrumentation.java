package dev.pocketconsole;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;

/** Runs the original DS homebrew against the packaged JNI and core on an emulator. */
public final class DsSmokeInstrumentation extends Instrumentation {
    private static final String TAG = "PocketDsSmoke";
    private static final int WIDTH = 256;
    private static final int HEIGHT = 384;
    private Method open;
    private Method frame;
    private Method close;
    private Bundle arguments;
    private final int[] pixels = new int[WIDTH * HEIGHT];
    private final short[] audio = new short[8192];

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
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
            Log.i(TAG, "native_checks_begin");
            testVideo(rom, root);
            testInput(rom, root);
            testAudio(rom, root);
            testMalformedRom(rom, root);
            testStaleClose(rom, root);
            Log.i(TAG, "native_checks_end");
            testPlayerUi(rom, root);
            testThreeDsDisplayUi();
            if (arguments != null && "true".equals(arguments.getString("pocketExportScreenshot"))) {
                File screenshot = new File(getTargetContext().getCacheDir(), "smoke-player.png");
                require(screenshot.length() > 8 && screenshot.length() <= 512 * 1024,
                        "DS screenshot is missing or too large to export");
                Bundle screenshotStatus = new Bundle();
                screenshotStatus.putString("stream", "DS_SCREENSHOT_BASE64:"
                        + Base64.encodeToString(Files.readAllBytes(screenshot.toPath()), Base64.NO_WRAP));
                sendStatus(0, screenshotStatus);
            }
            result.putString("stream", "DS_ANDROID_SMOKE_PASS cache/smoke-player.png "
                    + "DS_DISPLAY_LAYOUT_PASS THREEDS_DISPLAY_UI_PASS (layout only)");
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

    private void testPlayerUi(File rom, File root) throws Exception {
        getTargetContext().getSharedPreferences("player-display-ds", 0).edit().clear().commit();
        Intent intent = new Intent().setClassName(getTargetContext().getPackageName(),
                "dev.pocketconsole.DsPlayerActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(DsPlayerActivity.EXTRA_ROM_URI, Uri.fromFile(rom).toString());
        Log.i(TAG, "player_launch_begin");
        Activity activity = startActivitySync(intent);
        Log.i(TAG, "player_launch_returned");
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

            testFullscreenUi(activity);
            testPlayerExit(activity);
            testFreshSessionAfterExit(rom, root);
        } finally {
            if (a != null) {
                Button button = a;
                runOnMainSync(() -> dispatchButton(button, MotionEvent.ACTION_UP));
            }
            runOnMainSync(activity::finish);
        }
    }

    private void testFullscreenUi(Activity activity) throws Exception {
        float normalWidth = screenBounds(activity, "top").width();
        clickPlayerButton(activity, "전체화면");
        Thread.sleep(600);
        Bitmap shot = awaitColor(false, 10000, 1);
        require(shot != null, "fullscreen lost actual DS frames");
        require(screenBounds(activity, "top").width() > normalWidth * 1.2f,
                "fullscreen did not enlarge the primary display");
        requirePlayerControls(activity, shot);
        saveDisplayShot(shot, "smoke-player-fullscreen.png");

        clickPlayerButton(activity, "메뉴");
        clickPopup("버튼 숨기기");
        Thread.sleep(200);
        final View[] a = {null};
        runOnMainSync(() -> a[0] = findText(activity.getWindow().getDecorView(), "A"));
        require(a[0] == null, "fullscreen controls were not hidden");
        touchScreenCenter(activity, true);

        clickPlayerButton(activity, "메뉴");
        clickPopup("위/아래 화면 바꾸기");
        Thread.sleep(200);
        touchScreenCenter(activity, true);
        saveDisplayShot(getUiAutomation().takeScreenshot(), "smoke-player-large-bottom.png");
        clickPlayerButton(activity, "메뉴");
        clickPopup("위/아래 화면 바꾸기");

        clickPlayerButton(activity, "메뉴");
        clickPopup("화면 배치");
        clickPopup("주 화면만");
        Thread.sleep(200);
        require(screenBounds(activity, "bottom").isEmpty(), "single top still displays bottom");
        require(!screenBounds(activity, "top").isEmpty(), "single top display is absent");
        final View[] screen = {null};
        runOnMainSync(() -> {
            screen[0] = playerScreen(activity);
            MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, 1, 0);
            try { require(!screen[0].dispatchTouchEvent(event), "hidden bottom accepted touch"); }
            finally { event.recycle(); }
        });
        saveDisplayShot(getUiAutomation().takeScreenshot(), "smoke-player-single.png");

        clickPlayerButton(activity, "메뉴");
        clickPopup("위/아래 화면 바꾸기");
        shot = awaitColor(false, 10000, 1);
        require(shot != null && screenBounds(activity, "top").isEmpty(),
                "swapping a single display did not reveal bottom");
        saveDisplayShot(shot, "smoke-player-bottom.png");
        touchScreenCenter(activity, true);

        clickPlayerButton(activity, "메뉴");
        clickPopup("버튼 보이기");
        Thread.sleep(200);
        Button[] button = {null};
        runOnMainSync(() -> button[0] = (Button) findText(activity.getWindow().getDecorView(), "A"));
        require(button[0] != null, "fullscreen controls were not restored");
        testSplitTouch(activity);
        runOnMainSync(() -> dispatchButton(button[0], MotionEvent.ACTION_DOWN));
        shot = awaitColor(true, 10000, 1);
        require(shot != null, "fullscreen A input did not reach the real core");
        shot.recycle();
        waitForIdleSync();
        Thread.sleep(500);
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        Thread.sleep(500);
        require(!activity.isFinishing(), "Back exited the game instead of fullscreen");
        require(!getTargetContext().getSharedPreferences("player-display-ds", 0)
                .getBoolean("fullscreen", true), "real Back did not leave fullscreen");
        clickPlayerButton(activity, "화면 배치");
        clickPopup("화면 배치");
        clickPopup("화면 둘 다");
        clickPlayerButton(activity, "화면 배치");
        clickPopup("위/아래 화면 바꾸기");
        Field field = activity.getClass().getDeclaredField("buttons");
        field.setAccessible(true);
        require(((java.util.concurrent.atomic.AtomicInteger) field.get(activity)).get() == 0,
                "held input survived a layout transition");
        Log.i(TAG, "DS_DISPLAY_LAYOUT_PASS fullscreen/single/swap/touch/back");
    }

    private void testSplitTouch(Activity activity) throws Exception {
        View[] controls = new View[2];
        int[][] location = {new int[2], new int[2]};
        runOnMainSync(() -> {
            controls[0] = findText(activity.getWindow().getDecorView(), "↑");
            controls[1] = findText(activity.getWindow().getDecorView(), "A");
            for (int i = 0; i < 2; i++) if (controls[i] != null) controls[i].getLocationOnScreen(location[i]);
        });
        require(controls[0] != null && controls[1] != null, "split-touch controls missing");
        float[] x = {location[0][0] + controls[0].getWidth() / 2f,
                location[1][0] + controls[1].getWidth() / 2f};
        float[] y = {location[0][1] + controls[0].getHeight() / 2f,
                location[1][1] + controls[1].getHeight() / 2f};
        long time = SystemClock.uptimeMillis();
        injectTouch(time, MotionEvent.ACTION_DOWN, x[0], y[0]);
        try {
            injectPointers(time, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y);
            Field field = activity.getClass().getDeclaredField("buttons");
            field.setAccessible(true);
            int mask = ((java.util.concurrent.atomic.AtomicInteger) field.get(activity)).get();
            require((mask & ((1 << 4) | (1 << 8))) == ((1 << 4) | (1 << 8)),
                    "separate fullscreen pads did not retain simultaneous input");
            Bitmap image = awaitColor(true, 10000, 1);
            require(image != null, "simultaneous A input did not reach actual core");
            image.recycle();
            injectPointers(time, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y);
        } finally { injectTouch(time, MotionEvent.ACTION_UP, x[0], y[0]); }
    }

    private void injectPointers(long time, int action, float[] x, float[] y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = x[i]; coordinates[i].y = y[i];
            coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, 2,
                properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { require(getUiAutomation().injectInputEvent(event, true), "multi-touch injection failed"); }
        finally { event.recycle(); }
    }

    private View playerScreen(Activity activity) {
        try {
            Field field = activity.getClass().getDeclaredField("screens");
            field.setAccessible(true);
            return (View) field.get(activity);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private RectF screenBounds(Activity activity, String name) throws Exception {
        RectF[] bounds = {null};
        runOnMainSync(() -> {
            try {
                View screen = playerScreen(activity);
                Field field = screen.getClass().getDeclaredField(name);
                field.setAccessible(true);
                bounds[0] = new RectF((RectF) field.get(screen));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        return bounds[0];
    }

    private void touchScreenCenter(Activity activity, boolean lower) throws Exception {
        RectF bounds = screenBounds(activity, lower ? "bottom" : "top");
        require(!bounds.isEmpty(), "touch test display is hidden");
        int[] location = new int[2];
        runOnMainSync(() -> playerScreen(activity).getLocationOnScreen(location));
        long now = SystemClock.uptimeMillis();
        injectTouch(now, MotionEvent.ACTION_DOWN, location[0] + bounds.centerX(), location[1] + bounds.centerY());
        try {
            Field x = activity.getClass().getDeclaredField("touchX");
            Field y = activity.getClass().getDeclaredField("touchY");
            x.setAccessible(true); y.setAccessible(true);
            require(Math.abs(x.getInt(activity) - 128) <= 1
                    && Math.abs(y.getInt(activity) - 96) <= 1,
                    "root-dispatched bottom touch did not map to native center");
        } finally {
            injectTouch(now, MotionEvent.ACTION_UP, location[0] + bounds.centerX(), location[1] + bounds.centerY());
        }
    }

    private void injectTouch(long downTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(getUiAutomation().injectInputEvent(event, true), "touch injection failed"); }
        finally { event.recycle(); }
    }

    private void clickPlayerButton(Activity activity, String label) {
        View[] target = {null};
        runOnMainSync(() -> target[0] = findText(activity.getWindow().getDecorView(), label));
        require(target[0] != null, "missing display action: " + label);
        runOnMainSync(target[0]::performClick);
    }

    private void clickPopup(String label) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 4000;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                if (!label.contentEquals(node.getText() == null ? "" : node.getText())) continue;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) {
                    long now = SystemClock.uptimeMillis();
                    injectTouch(now, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY());
                    injectTouch(now, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY());
                    Thread.sleep(300);
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("display menu action missing: " + label);
    }

    private void saveDisplayShot(Bitmap shot, String name) throws Exception {
        require(shot != null, "display screenshot unavailable");
        shot.recycle();
        Thread.sleep(400); // Capture the settled layout after native popup/inset animations.
        shot = getUiAutomation().takeScreenshot();
        require(shot != null, "settled display screenshot unavailable");
        try (FileOutputStream output = new FileOutputStream(new File(getTargetContext().getCacheDir(), name))) {
            require(shot.compress(Bitmap.CompressFormat.PNG, 100, output), "display screenshot write failed");
        } finally { shot.recycle(); }
    }

    private void testThreeDsDisplayUi() throws Exception {
        getTargetContext().getSharedPreferences("player-display-3ds", 0).edit().clear().commit();
        Intent intent = new Intent().setClassName(getTargetContext().getPackageName(),
                "dev.pocketconsole.ThreeDsPlayerActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        runOnMainSync(() -> getTargetContext().startActivity(intent));
        Thread.sleep(500);
        clickPopup("전체화면");
        Thread.sleep(600);
        Bitmap shot = getUiAutomation().takeScreenshot();
        require(shot != null, "3DS layout screenshot missing");
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        require(root != null, "3DS layout window missing");
        for (String label : new String[]{"메뉴", "A", "B", "X", "Y", "L", "R", "SELECT", "START", "↑", "↓", "←", "→"}) {
            boolean visible = false;
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                if (!label.contentEquals(node.getText() == null ? "" : node.getText())) continue;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                int target = Math.round(48 * getTargetContext().getResources().getDisplayMetrics().density);
                visible = node.isVisibleToUser() && bounds.width() >= target - 1 && bounds.height() >= target - 1
                        && bounds.left >= 0 && bounds.top >= 0
                        && bounds.right <= shot.getWidth() && bounds.bottom <= shot.getHeight();
                if (visible) break;
            }
            require(visible, "3DS fullscreen control is clipped: " + label);
        }
        saveDisplayShot(shot, "smoke-three-ds-layout.png");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        Thread.sleep(300);
        root = getUiAutomation().getRootInActiveWindow();
        boolean normal = false;
        if (root != null) for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("전체화면")) {
            if ("전체화면".contentEquals(node.getText() == null ? "" : node.getText())
                    && node.isVisibleToUser()) normal = true;
        }
        require(normal, "3DS Back did not return to the normal display");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        Log.i(TAG, "THREEDS_DISPLAY_UI_PASS layout-only; emulation NOT_RUN");
    }

    private void testPlayerExit(Activity activity) throws Exception {
        runOnMainSync(() -> {
            Button exit = (Button) findText(activity.getWindow().getDecorView(), "‹  라이브러리");
            require(exit != null, "DS library exit button missing");
            exit.performClick();
            Log.i(TAG, "exit_requested");
            require(findText(activity.getWindow().getDecorView(),
                    "종료 중 · 게임 내 저장 마무리") != null,
                    "DS closing status was not shown before leaving the player");
            require(!activity.isFinishing(), "DS player finished before its closing status was visible");
        });
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline && !activity.isFinishing()) {
            Thread.sleep(100);
        }
        require(activity.isFinishing(), "DS player did not finish after native close");
        Log.i(TAG, "exit_finish_observed");
    }

    private void testFreshSessionAfterExit(File rom, File root) throws Exception {
        Log.i(TAG, "fresh_native_open_begin");
        long fresh = open(rom, root);
        Log.i(TAG, "fresh_native_open_success");
        try {
            frame(fresh, 0);
            Log.i(TAG, "fresh_native_frame_success");
        } finally {
            close(fresh);
            Log.i(TAG, "fresh_native_open_end");
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
