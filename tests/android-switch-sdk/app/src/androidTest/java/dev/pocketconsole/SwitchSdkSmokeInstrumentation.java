package dev.pocketconsole;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import org.yuzu.yuzu_emu.NativeLibrary;
import org.yuzu.yuzu_emu.features.input.NativeInput;
import org.yuzu.yuzu_emu.applets.keyboard.SoftwareKeyboard;

/** Runs only the actual packaged ARM64 JNI backend and original NRO. */
public final class SwitchSdkSmokeInstrumentation extends Instrumentation
        implements NativeLibrary.Listener {
    private static final String TAG = "PocketSwitchSdk";
    private static final String RUNNING = "Switch 2 실험적 실행 · 화면 연결됨";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        Activity player = null;
        try {
            File game = copyOriginalNro();
            require(game.equals(NativeLibrary.privatePath(getTargetContext(), game.getAbsolutePath())),
                    "private NRO path was rejected");
            NativeLibrary.initialize(this, "pocket_switch2");
            require(NativeLibrary.probeNativeBridge() == 0x50435332,
                    "Switch SDK JNI symbol dispatch returned the wrong value");
            Class.forName(NativeInput.class.getName());
            Class.forName(SoftwareKeyboard.class.getName());
            NativeLibrary.detach(this);
            Log.i(TAG, "JNI_LINK_PASS");

            Intent intent = new Intent().setClassName(getTargetContext().getPackageName(),
                    Switch2PlayerActivity.class.getName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Switch2PlayerActivity.EXTRA_GAME_PATH, game.getAbsolutePath());
            player = startActivitySync(intent);
            require(player != null, "Switch 2 player did not start");
            SurfaceView surface = awaitSurface(player, 10000);
            require(surface != null, "Switch 2 SurfaceView was not created");
            Log.i(TAG, "SURFACE_VIEW_PASS");

            DeviceCapabilities capabilities = DeviceProbe.read(player);
            DeviceCapabilities.Compatibility support = capabilities.check(ConsoleType.SWITCH_TWO);
            if (support.state != DeviceCapabilities.Compatibility.State.READY) {
                Integer vulkan = capabilities.vulkanVersion;
                String marker = vulkan != null && vulkan < 0x403000
                        ? "NRO_NOT_RUN_UNSUPPORTED_GPU" : "NRO_NOT_RUN_UNKNOWN_GPU";
                Log.i(TAG, marker + " " + support.reason);
                requestExitAndWait(player);
                result.putString("stream", "JNI_LINK_PASS SURFACE_VIEW_PASS " + marker
                        + " " + support.reason);
                finish(Activity.RESULT_OK, result);
                return;
            }

            awaitStatus(player, RUNNING, 60000);
            Bitmap first = awaitColors(surface, false, 15000);
            require(first != null, "original NRO blue/red frame was not displayed");
            save(first, "switch-sdk-first-frame.png");
            first.recycle();
            Button a = findButton(player, "A");
            require(a != null, "Switch 2 A button is missing");
            runOnMainSync(() -> dispatch(a, MotionEvent.ACTION_DOWN));
            try {
                Bitmap pressed = awaitColors(surface, true, 15000);
                require(pressed != null, "original NRO did not turn green while A was held");
                save(pressed, "switch-sdk-a-pressed.png");
                pressed.recycle();
            } finally {
                runOnMainSync(() -> dispatch(a, MotionEvent.ACTION_UP));
            }
            requestExitAndWait(player);
            Log.i(TAG, "NRO_FRAME_INPUT_PASS");
            result.putString("stream", "JNI_LINK_PASS SURFACE_VIEW_PASS NRO_FRAME_INPUT_PASS "
                    + "cache/switch-sdk-first-frame.png cache/switch-sdk-a-pressed.png");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            Log.e(TAG, "SWITCH_SDK_SMOKE_FAIL", failure);
            result.putString("stream", "SWITCH_SDK_SMOKE_FAIL: " + Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            NativeLibrary.detach(this);
            if (player != null && !player.isFinishing()) {
                Activity activity = player;
                runOnMainSync(activity::finish);
            }
        }
    }

    private File copyOriginalNro() throws Exception {
        File game = new File(getTargetContext().getFilesDir(), "smoke.nro");
        try (InputStream source = getContext().getAssets().open("smoke.nro");
             FileOutputStream target = new FileOutputStream(game)) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = source.read(chunk)) != -1) target.write(chunk, 0, count);
        }
        require(game.length() > 0, "original NRO asset is empty");
        return game.getCanonicalFile();
    }

    private SurfaceView awaitSurface(Activity player, long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            SurfaceView[] found = new SurfaceView[1];
            boolean[] valid = {false};
            runOnMainSync(() -> {
                found[0] = findSurface(player.getWindow().getDecorView());
                valid[0] = found[0] != null && found[0].getHolder().getSurface().isValid();
            });
            if (valid[0]) return found[0];
            Thread.sleep(100);
        }
        return null;
    }

    private void awaitStatus(Activity player, String expected, long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] found = {false};
            runOnMainSync(() -> found[0] = findText(player.getWindow().getDecorView(), expected) != null);
            if (found[0]) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Switch 2 did not report a first native frame");
    }

    private void requestExitAndWait(Activity player) throws Exception {
        Button exit = findButton(player, "나가기");
        require(exit != null, "Switch 2 exit button is missing");
        runOnMainSync(exit::performClick);
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline && !player.isFinishing()) Thread.sleep(100);
        require(player.isFinishing(), "Switch 2 player did not finish after exit");
    }

    private Bitmap awaitColors(SurfaceView surface, boolean green, long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            Bitmap shot = getUiAutomation().takeScreenshot();
            if (shot != null) {
                Rect bounds = new Rect();
                boolean[] visible = {false};
                runOnMainSync(() -> visible[0] = surface.getGlobalVisibleRect(bounds));
                if (visible[0] && bounds.intersect(0, 0, shot.getWidth(), shot.getHeight())) {
                    int top = 0, bottom = 0;
                    int middle = bounds.top + bounds.height() / 2;
                    for (int y = bounds.top; y < bounds.bottom; y += 2) {
                        for (int x = bounds.left; x < bounds.right; x += 2) {
                            int pixel = shot.getPixel(x, y);
                            int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
                            if (y < middle && (green ? g > r + 80 && g > b + 80
                                    : b > r + 80 && b > g + 80)) top++;
                            if (y >= middle && r > g + 80 && r > b + 80) bottom++;
                        }
                    }
                    if (top >= 250 && bottom >= 250) return shot;
                }
                shot.recycle();
            }
            Thread.sleep(100);
        }
        return null;
    }

    private void save(Bitmap image, String name) throws Exception {
        try (FileOutputStream output = new FileOutputStream(
                new File(getTargetContext().getCacheDir(), name))) {
            require(image.compress(Bitmap.CompressFormat.PNG, 100, output),
                    "cannot save Switch SDK screenshot");
        }
    }

    private Button findButton(Activity player, String label) {
        View[] found = {null};
        runOnMainSync(() -> found[0] = findText(player.getWindow().getDecorView(), label));
        return found[0] instanceof Button ? (Button) found[0] : null;
    }

    private static SurfaceView findSurface(View root) {
        if (root instanceof SurfaceView) return (SurfaceView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView found = findSurface(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findText(View root, String expected) {
        if (root instanceof TextView && expected.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), expected);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void dispatch(Button button, int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action,
                button.getWidth() / 2f, button.getHeight() / 2f, 0);
        try { button.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Override public Context context() { return getTargetContext(); }
    @Override public void onLoaded() { }
    @Override public void onFirstFramePresented() { }
    @Override public void onStopped(int result) { }
    @Override public void onProgramChanged(int index) { }
    @Override public void onShaderProgress(int stage, int progress, int maximum) { }
    @Override public void onUnsupportedApplet(String reason) { }
    @Override public void onExternalUrl(String url) { }
}
