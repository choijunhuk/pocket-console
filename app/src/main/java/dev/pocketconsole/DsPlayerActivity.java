package dev.pocketconsole;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.DisplayCutout;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public final class DsPlayerActivity extends Activity {
    static final String EXTRA_ROM_URI = "rom_uri";
    private int PAPER, INK, SHELL, ORANGE;
    private ConsoleUi ui;
    private static final long FRAME_NANOS = 1_000_000_000_000L / 59826L;
    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger buttons = new AtomicInteger();
    private final Object lifecycle = new Object();
    private volatile boolean paused = true;
    private volatile boolean stopped;
    private volatile boolean exitRequested;
    private volatile boolean playerStarted;
    private volatile boolean playerDone;
    private volatile boolean touchDown;
    private volatile int touchX;
    private volatile int touchY;
    private Future<?> player;
    private String statusText = "게임을 불러오고 있어요…";
    private TextView status;
    private volatile DsScreenView screens;
    private ConsoleUi.InputScrollGuard inputGuard;
    private OnBackInvokedCallback backCallback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new ConsoleUi(this);
        PAPER = ui.paper; INK = ui.ink; SHELL = ui.shell; ORANGE = ui.accent;
        ui.applyBars(this);
        buildUi();
        String uri = getIntent().getStringExtra(EXTRA_ROM_URI);
        if (uri == null) { showStatus("DS 파일 주소가 없어요."); playerDone = true; return; }
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::requestExit;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        player = PLAYER.submit(() -> play(Uri.parse(uri)));
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { requestExit(); }

    @Override protected void onResume() {
        super.onResume();
        paused = false;
        synchronized (lifecycle) { lifecycle.notifyAll(); }
    }

    @Override protected void onPause() {
        paused = true;
        buttons.set(0);
        touchDown = false;
        if (inputGuard != null) inputGuard.reset();
        synchronized (lifecycle) { lifecycle.notifyAll(); }
        super.onPause();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        buttons.set(0);
        touchDown = false;
        if (inputGuard != null) inputGuard.reset();
        buildUi();
    }

    @Override protected void onDestroy() {
        stopped = true;
        buttons.set(0);
        touchDown = false;
        if (inputGuard != null) inputGuard.reset();
        synchronized (lifecycle) { lifecycle.notifyAll(); }
        if (player != null) player.cancel(true);
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
        if (inputGuard != null) inputGuard.reset();
        showStatus("종료 중 · 게임 내 저장 마무리");
        synchronized (lifecycle) { lifecycle.notifyAll(); }
        if (playerStarted && player != null) player.cancel(true);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(INK);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER);
        layout.setMotionEventSplittingEnabled(true);
        return layout;
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
        Button exit = plainButton("‹  라이브러리", SHELL);
        exit.setContentDescription("DS 게임을 종료하고 라이브러리로 돌아가기");
        exit.setOnClickListener(v -> requestExit());
        header.addView(exit, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView title = text("DS  /  PLAY", 14, true);
        title.setTextColor(ORANGE);
        title.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(header);
        status = text(statusText, 13, false);
        status.setPadding(0, dp(4), 0, dp(4));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        screens = new DsScreenView(this, landscape, inputGuard, (x, y, down) -> {
            touchX = x;
            touchY = y;
            touchDown = down;
        });
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setMotionEventSplittingEnabled(true);
        LinearLayout hands = row();
        hands.setPadding(0, dp(4), 0, dp(4));
        hands.addView(pad(new String[][]{{"", "↑", ""}, {"←", "", "→"}, {"", "↓", ""}},
                new int[][]{{-1, 4, -1}, {6, -1, 7}, {-1, 5, -1}}),
                new LinearLayout.LayoutParams(0, -2, 1));
        hands.addView(pad(new String[][]{{"", "X", ""}, {"Y", "", "A"}, {"", "B", ""}},
                new int[][]{{-1, 9, -1}, {1, -1, 8}, {-1, 0, -1}}),
                new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(hands);
        LinearLayout utility = row();
        for (String label : new String[]{"L", "SELECT", "START", "R"}) {
            int bit = switch (label) { case "L" -> 10; case "R" -> 11;
                case "SELECT" -> 2; default -> 3; };
            utility.addView(control(label, bit), new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        controls.addView(utility);
        if (landscape) {
            LinearLayout content = row();
            content.addView(screens, new LinearLayout.LayoutParams(0, -2, 1));
            // Two 3x48dp pads must fit even with a landscape navigation bar.
            content.addView(controls, new LinearLayout.LayoutParams(dp(296), -2));
            page.addView(content);
        } else {
            page.addView(screens, new LinearLayout.LayoutParams(-1, -2));
            page.addView(controls);
        }
        DsScreenView screen = screens;
        page.addOnLayoutChangeListener((view, left, top, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) -> {
            int viewport = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
            int occupied = page.getPaddingTop() + page.getPaddingBottom()
                    + header.getMeasuredHeight() + status.getMeasuredHeight()
                    + (landscape ? 0 : controls.getMeasuredHeight());
            int height = Math.max(dp(120), viewport - occupied);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) screen.getLayoutParams();
            if (params.height != height) {
                params.height = height;
                screen.setLayoutParams(params);
            }
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

    private Button plainButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(INK);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setBackground(ui.buttonSurface(color, 14));
        return button;
    }

    private Button control(String label, int bit) {
        Button button = plainButton(label, SHELL);
        ConsoleUi.InputScrollGuard guard = inputGuard;
        button.setContentDescription("DS " + label + " 버튼");
        int flag = 1 << bit;
        boolean[] fromTouch = {false};
        button.setOnClickListener(v -> {
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
        try {
            if (stopped) return;
            File rom;
            try (InputStream source = getContentResolver().openInputStream(uri)) {
                if (source == null) throw new IOException("파일을 열 수 없어요.");
                rom = DsRomCache.copy(source, new File(getCacheDir(), "ds-roms"));
            }
            if (stopped) return;
            File system = new File(getFilesDir(), "ds-system");
            File saves = new File(getFilesDir(), "ds-saves");
            if ((!system.isDirectory() && !system.mkdirs()) || (!saves.isDirectory() && !saves.mkdirs())) {
                throw new IOException("DS 저장 공간을 만들 수 없어요.");
            }
            token = DsNative.open(rom.getAbsolutePath(), system.getAbsolutePath(), saves.getAbsolutePath());
            if (token == 0) throw new IOException("DS 코어가 게임을 열지 못했어요.");
            if (stopped) return;
            int minimum = AudioTrack.getMinBufferSize(32768,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("오디오 장치를 열 수 없어요.");
            int bufferBytes = Math.max(minimum, 4096);
            output = new AudioTrack(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    new AudioFormat.Builder().setSampleRate(32768)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    bufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (output.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("오디오 장치를 열 수 없어요.");
            AudioTrack audio = output;
            main.post(() -> { if (!isDestroyed() && !stopped) showStatus("DS 실행 중"); });
            int[] pixels = new int[256 * 384];
            short[] samples = new short[8192];
            long nextFrame = System.nanoTime();
            boolean audioPlaying = false;
            while (!stopped) {
                if (paused) {
                    if (audioPlaying) { audio.pause(); audio.flush(); audioPlaying = false; }
                    synchronized (lifecycle) {
                        while (paused && !stopped) lifecycle.wait();
                    }
                    nextFrame = System.nanoTime();
                    continue;
                }
                if (!audioPlaying) { audio.play(); audioPlaying = true; }
                int frames = DsNative.frame(token, pixels, samples, buttons.get(), touchX, touchY, touchDown);
                if (frames < 0 || frames > samples.length / 2) throw new IOException("DS 코어 오디오 길이가 잘못됐어요.");
                screens.setFrame(pixels);
                int remaining = frames * 2;
                int offset = 0;
                int chunk = bufferBytes / 2;
                while (remaining > 0 && !paused && !stopped) {
                    int wrote = audio.write(samples, offset, Math.min(remaining, chunk), AudioTrack.WRITE_NON_BLOCKING);
                    if (wrote < 0) throw new IOException("오디오 출력 실패 (" + wrote + ")");
                    if (wrote == 0) { LockSupport.parkNanos(1_000_000); continue; }
                    offset += wrote;
                    remaining -= wrote;
                }
                nextFrame += FRAME_NANOS;
                long delay = nextFrame - System.nanoTime();
                if (delay > 0) LockSupport.parkNanos(delay);
                else if (delay < -2 * FRAME_NANOS) nextFrame = System.nanoTime();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException | LinkageError error) {
            main.post(() -> {
                if (!isDestroyed() && !exitRequested) showStatus("DS 실행 실패: " + error.getMessage());
            });
        } finally {
            String shutdownError = null;
            try {
                if (output != null) {
                    try { output.pause(); output.flush(); }
                    catch (RuntimeException ignored) { }
                    output.release();
                }
            } catch (RuntimeException error) {
                shutdownError = "오디오 장치를 정리하지 못했어요: " + error.getMessage();
            }
            if (token != 0) {
                try { DsNative.close(token); }
                catch (Exception | LinkageError error) {
                    shutdownError = "DS 종료 중 저장 반영에 실패했어요: " + error.getMessage();
                }
            }
            String failure = shutdownError;
            main.post(() -> {
                if (isDestroyed()) return;
                if (failure != null) {
                    exitRequested = false;
                    showStatus(failure + " 다시 나가려면 라이브러리를 누르세요.");
                    playerDone = true;
                } else {
                    playerDone = true;
                    if (!exitRequested) return;
                    finish();
                }
            });
        }
    }
}
