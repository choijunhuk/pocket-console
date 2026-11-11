package dev.pocketconsole;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public final class ThreeDsPlayerActivity extends Activity {
    static final String EXTRA_ROM_URI = "rom_uri";
    private int PAPER, INK, SHELL;
    private ConsoleUi ui;
    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger buttons = new AtomicInteger();
    private final Object lifecycle = new Object();
    private volatile boolean paused = true;
    private volatile boolean stopped;
    private volatile boolean exitRequested;
    private volatile boolean playerDone;
    private volatile boolean touchDown;
    private volatile int touchX, touchY, circleX, circleY;
    private volatile boolean playerStarted;
    private Future<?> playerFuture;
    private volatile ThreeDsScreenView screens;
    private ConsoleUi.InputScrollGuard inputGuard;
    private String statusText = "파일을 확인하고 있어요…";
    private TextView status;
    private OnBackInvokedCallback backCallback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new ConsoleUi(this);
        PAPER = ui.paper; INK = ui.ink; SHELL = ui.shell;
        ui.applyBars(this);
        buildUi();
        String uri = getIntent().getStringExtra(EXTRA_ROM_URI);
        if (uri == null) { showStatus("3DS 파일 주소가 없어요."); playerDone = true; return; }
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::requestExit;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        playerFuture = PLAYER.submit(() -> play(Uri.parse(uri)));
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) { super.onBackPressed(); return; }
        requestExit();
    }

    @Override protected void onResume() {
        super.onResume();
        paused = false;
        synchronized (lifecycle) { lifecycle.notifyAll(); }
    }

    @Override protected void onPause() {
        paused = true;
        buttons.set(0);
        touchDown = false;
        circleX = circleY = 0;
        if (inputGuard != null) inputGuard.reset();
        synchronized (lifecycle) { lifecycle.notifyAll(); }
        super.onPause();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        buttons.set(0);
        touchDown = false;
        circleX = circleY = 0;
        if (inputGuard != null) inputGuard.reset();
        buildUi();
    }

    @Override protected void onDestroy() {
        stopped = true;
        buttons.set(0);
        touchDown = false;
        circleX = circleY = 0;
        if (inputGuard != null) inputGuard.reset();
        synchronized (lifecycle) { lifecycle.notifyAll(); }
        if (playerFuture != null) playerFuture.cancel(true);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        super.onDestroy();
    }

    private void requestExit() {
        if (exitRequested) return;
        if (playerDone) { finish(); return; }
        exitRequested = true;
        stopped = true;
        buttons.set(0);
        touchDown = false;
        circleX = circleY = 0;
        if (inputGuard != null) inputGuard.reset();
        showStatus("종료 중… 저장을 반영하고 있어요.");
        synchronized (lifecycle) { lifecycle.notifyAll(); }
        if (playerStarted && playerFuture != null) playerFuture.cancel(true);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Button button(String name) {
        Button button = new Button(this);
        button.setText(name);
        button.setTextSize(12);
        button.setTextColor(INK);
        button.setAllCaps(false);
        button.setMinWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setBackground(ui.buttonSurface(SHELL, 14));
        return button;
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER);
        line.setMotionEventSplittingEnabled(true);
        return line;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setMotionEventSplittingEnabled(true);
        scroll.setBackgroundColor(PAPER);
        inputGuard = new ConsoleUi.InputScrollGuard(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28) {
                    DisplayCutout cutout = insets.getDisplayCutout();
                    if (cutout != null) {
                        left = Math.max(left, cutout.getSafeInsetLeft());
                        top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight());
                        bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    }
                }
                view.setPadding(left, top, right, bottom);
            }
            return insets;
        });
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setMotionEventSplittingEnabled(true);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(page);
        setContentView(scroll);
        scroll.requestApplyInsets();

        LinearLayout header = row();
        Button exit = button("‹  라이브러리");
        exit.setContentDescription("3DS 게임을 종료하고 라이브러리로 돌아가기");
        exit.setOnClickListener(v -> requestExit());
        header.addView(exit, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView title = new TextView(this);
        title.setText("3DS  /  PLAY");
        title.setTextSize(14);
        title.setTextColor(ui.accent);
        title.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(header);
        status = new TextView(this);
        status.setText(statusText);
        status.setTextSize(13);
        status.setTextColor(INK);
        status.setPadding(0, dp(4), 0, dp(4));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);

        screens = new ThreeDsScreenView(this, inputGuard, (x, y, down) -> {
            touchX = x; touchY = y; touchDown = down;
        });
        ThreeDsCirclePadView circle = new ThreeDsCirclePadView(this, inputGuard, (x, y) -> {
            circleX = x; circleY = y;
        });
        LinearLayout dpad = pad(new String[][]{{"", "↑", ""}, {"←", "", "→"}, {"", "↓", ""}},
                new int[][]{{-1, 4, -1}, {6, -1, 7}, {-1, 5, -1}});
        LinearLayout abxy = pad(new String[][]{{"", "X", ""}, {"Y", "", "A"}, {"", "B", ""}},
                new int[][]{{-1, 9, -1}, {1, -1, 8}, {-1, 0, -1}});
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.setMotionEventSplittingEnabled(true);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && getResources().getConfiguration().screenWidthDp >= 600;
        if (landscape) {
            LinearLayout hands = row();
            hands.addView(circle, new LinearLayout.LayoutParams(dp(96), dp(96)));
            hands.addView(dpad);
            hands.addView(abxy);
            controls.addView(hands);
        } else {
            LinearLayout hands = row();
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setGravity(Gravity.CENTER_HORIZONTAL);
            left.setMotionEventSplittingEnabled(true);
            left.addView(circle, new LinearLayout.LayoutParams(dp(96), dp(96)));
            left.addView(dpad);
            hands.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            hands.addView(abxy, new LinearLayout.LayoutParams(0, -2, 1));
            controls.addView(hands);
        }
        LinearLayout utility = row();
        for (String name : new String[]{"L", "SELECT", "START", "R"}) {
            int bit = switch (name) { case "L" -> 10; case "R" -> 11;
                case "SELECT" -> 2; default -> 3; };
            utility.addView(control(name, bit), new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        controls.addView(utility);
        if (landscape) {
            LinearLayout content = row();
            content.addView(screens, new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(controls, new LinearLayout.LayoutParams(dp(384), -2));
            page.addView(content);
        } else {
            page.addView(screens, new LinearLayout.LayoutParams(-1, -2));
            page.addView(controls);
        }
        ThreeDsScreenView screen = screens;
        page.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            int viewport = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
            int occupied = page.getPaddingTop() + page.getPaddingBottom()
                    + header.getMeasuredHeight() + status.getMeasuredHeight()
                    + (landscape ? 0 : controls.getMeasuredHeight());
            int height = Math.max(dp(130), viewport - occupied);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) screen.getLayoutParams();
            if (params.height != height) { params.height = height; screen.setLayoutParams(params); }
        });
    }

    private LinearLayout pad(String[][] labels, int[][] bits) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER);
        group.setMotionEventSplittingEnabled(true);
        for (int y = 0; y < 3; y++) {
            LinearLayout line = row();
            for (int x = 0; x < 3; x++) {
                View cell = bits[y][x] < 0 ? new View(this) : control(labels[y][x], bits[y][x]);
                line.addView(cell, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            group.addView(line);
        }
        return group;
    }

    private Button control(String name, int bit) {
        Button button = button(name);
        ConsoleUi.InputScrollGuard guard = inputGuard;
        button.setContentDescription("3DS " + name + " 버튼");
        int flag = 1 << bit;
        boolean[] fromTouch = {false};
        button.setOnClickListener(view -> {
            if (fromTouch[0]) return;
            buttons.updateAndGet(value -> value | flag);
            main.postDelayed(() -> buttons.updateAndGet(value -> value & ~flag), 100);
        });
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    guard.hold(button);
                    fromTouch[0] = true;
                    buttons.updateAndGet(value -> value | flag);
                    button.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    buttons.updateAndGet(value -> value & ~flag);
                    button.setPressed(false);
                    button.performClick();
                    guard.release(button);
                    fromTouch[0] = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    buttons.updateAndGet(value -> value & ~flag);
                    button.setPressed(false);
                    guard.release(button);
                    fromTouch[0] = false;
                    return true;
                default: return true;
            }
        });
        return button;
    }

    private void showStatus(String message) {
        statusText = message;
        status.setText(message);
    }

    private void play(Uri uri) {
        playerStarted = true;
        long token = 0;
        AudioTrack output = null;
        IOException closeError = null;
        try {
            if (stopped) return;
            File rom;
            InputStream opened;
            try { opened = getContentResolver().openInputStream(uri); }
            catch (FileNotFoundException | SecurityException error) {
                throw new IOException("파일에 접근할 수 없어요. 파일을 다시 선택해 주세요.", error);
            }
            try (InputStream source = opened) {
                if (source == null) throw new IOException("파일에 접근할 수 없어요. 파일을 다시 선택해 주세요.");
                rom = ThreeDsRomCache.copy(source, new File(getCacheDir(), "three-ds-roms"));
            }
            if (stopped) return;
            main.post(() -> { if (!stopped && !isDestroyed()) showStatus("게임을 여는 중…"); });
            File userRoot = new File(getFilesDir(), "three-ds-user");
            File systemRoot = new File(getFilesDir(), "three-ds-system");
            if ((!userRoot.isDirectory() && !userRoot.mkdirs())
                    || (!systemRoot.isDirectory() && !systemRoot.mkdirs())) {
                throw new IOException("3DS 저장 공간을 만들 수 없어요.");
            }
            token = ThreeDsNative.open(rom.getAbsolutePath(), userRoot.getAbsolutePath(),
                    systemRoot.getAbsolutePath());
            if (token == 0) throw new IOException("3DS 코어가 게임을 열지 못했어요.");
            int[] metrics = new int[4];
            ThreeDsNative.configuration(token, metrics);
            if (metrics[0] != 400 || metrics[1] != 480 || metrics[2] < 8000
                    || metrics[2] > 192000 || metrics[3] < 1000 || metrics[3] > 240000) {
                throw new IOException("지원하지 않는 3DS 화면 또는 오디오 설정입니다.");
            }
            int minimum = AudioTrack.getMinBufferSize(metrics[2],
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("3DS 오디오 장치를 열 수 없어요.");
            int bufferBytes = Math.max(minimum, 8192);
            output = new AudioTrack(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    new AudioFormat.Builder().setSampleRate(metrics[2])
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    bufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (output.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("3DS 오디오 장치를 열 수 없어요.");
            AudioTrack audio = output;
            int[] pixels = new int[400 * 480];
            short[] samples = new short[16384];
            long frameNanos = 1_000_000_000_000L / metrics[3];
            long nextFrame = System.nanoTime();
            boolean audioPlaying = false, firstVideo = false;
            while (!stopped) {
                if (paused) {
                    if (audioPlaying) { audio.pause(); audio.flush(); audioPlaying = false; }
                    synchronized (lifecycle) { while (paused && !stopped) lifecycle.wait(); }
                    nextFrame = System.nanoTime();
                    continue;
                }
                if (!audioPlaying) { audio.play(); audioPlaying = true; }
                int frames = ThreeDsNative.frame(token, pixels, samples, buttons.get(), touchX, touchY,
                        touchDown, circleX, circleY);
                if (frames < -1 || frames > samples.length / 2) {
                    throw new IOException("3DS 코어 오디오 길이가 잘못됐어요.");
                }
                if (frames >= 0) {
                    screens.setFrame(pixels);
                    if (!firstVideo) {
                        firstVideo = true;
                        main.post(() -> { if (!stopped && !isDestroyed()) showStatus("3DS 실행 중"); });
                    }
                    int remaining = frames * 2, offset = 0;
                    while (remaining > 0 && !paused && !stopped) {
                        int wrote = audio.write(samples, offset, Math.min(remaining, bufferBytes / 2),
                                AudioTrack.WRITE_NON_BLOCKING);
                        if (wrote < 0) throw new IOException("3DS 오디오 출력 실패 (" + wrote + ")");
                        if (wrote == 0) { LockSupport.parkNanos(1_000_000); continue; }
                        offset += wrote; remaining -= wrote;
                    }
                }
                nextFrame += frameNanos;
                long delay = nextFrame - System.nanoTime();
                if (delay > 0) LockSupport.parkNanos(delay);
                else if (delay < -2 * frameNanos) nextFrame = System.nanoTime();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException | LinkageError error) {
            main.post(() -> { if (!isDestroyed() && !exitRequested) showStatus("3DS 실행 실패: " + error.getMessage()); });
        } finally {
            try {
                if (output != null) {
                    try { output.pause(); output.flush(); }
                    catch (RuntimeException ignored) { }
                    output.release();
                }
            } catch (RuntimeException error) {
                closeError = new IOException("오디오 장치를 닫지 못했어요.", error);
            }
            if (token != 0) {
                try { ThreeDsNative.close(token); }
                catch (IOException error) { closeError = error; }
            }
            playerDone = true;
            IOException failure = closeError;
            main.post(() -> {
                if (isDestroyed()) return;
                if (failure != null) {
                    exitRequested = false;
                    showStatus("3DS 종료 중 저장 반영에 실패했어요: " + failure.getMessage());
                } else if (exitRequested) {
                    finish();
                }
            });
        }
    }
}
