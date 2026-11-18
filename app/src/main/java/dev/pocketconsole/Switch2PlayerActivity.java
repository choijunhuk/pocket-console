package dev.pocketconsole;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.yuzu.yuzu_emu.NativeLibrary;
import org.yuzu.yuzu_emu.applets.keyboard.SoftwareKeyboard;
import org.yuzu.yuzu_emu.features.input.NativeInput;

/** Experimental native Surface player. A loaded core is not a proven game frame. */
public final class Switch2PlayerActivity extends Activity
        implements SurfaceHolder.Callback, NativeLibrary.Listener {
    public static final String EXTRA_GAME_PATH = "switch2_game_path";
    public static final String EXTRA_FAILURE = "switch_launch_failure";
    // The original smoke allows 60s; successful local first frames arrive within a few seconds.
    private static final long FIRST_FRAME_TIMEOUT_MS = 45_000;
    private static final long FIRST_FRAME_TOTAL_CAP_MS = 120_000;
    private static final long STOP_TIMEOUT_MS = 8_000;
    private static final long SURFACE_RELEASE_WAIT_MS = 500;
    private static final ExecutorService SESSION = Executors.newSingleThreadExecutor();
    private static final ExecutorService CONTROL = Executors.newSingleThreadExecutor();
    private static final AtomicInteger ACTIVE_PLAYERS = new AtomicInteger();
    private static boolean systemInitialized;

    private ConsoleUi ui;
    private LinearLayout root;
    private final List<Button> buttons = new ArrayList<>();
    private TextView status;
    private TextView gpuWarning;
    private SurfaceView display;
    private File game;
    private File cachedNro;
    private File userRoot;
    private volatile boolean backendReady;
    private volatile boolean inputReady;
    private volatile boolean ending;
    private volatile boolean userExit;
    private volatile boolean presented;
    private volatile boolean surfaceReady;
    private volatile boolean sessionDone;
    private volatile String failure;
    private volatile String gpuNote = "";
    private volatile boolean firstFrameTimedOut;
    private long firstFrameStartedAt;
    private final AtomicBoolean stopWatchdogArmed = new AtomicBoolean();
    private final AtomicBoolean activityCounted = new AtomicBoolean(true);
    private CountDownLatch surfaceReleased = new CountDownLatch(0);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PreparationProgress preparation = new PreparationProgress();
    private final Runnable loadingStatus = new Runnable() {
        @Override public void run() {
            if (presented || ending || sessionDone || isDestroyed()) return;
            message(preparation.message());
            main.postDelayed(this, 500);
        }
    };
    private final SwitchSessionStop stop = new SwitchSessionStop(
            CONTROL, NativeLibrary::requestPocketStop, NativeLibrary::haltPocketSession);
    private final Runnable stopWatchdog = () -> {
        if (sessionDone) return;
        if (failure == null) failure = "게임 종료가 지연되어 Switch 프로세스를 종료했어요.";
        android.util.Log.e("PocketSwitchPlayer", firstFrameTimedOut
                ? "FIRST_FRAME_WATCHDOG_FORCE_STOP" : "SWITCH_STOP_TIMEOUT_FORCE_STOP");
        returnWatchdogFailure();
        // A wedged Vulkan driver cannot unwind; only terminate the isolated Switch process.
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":switch2")) {
            main.postDelayed(() -> {
                if (sessionDone) return;
                deleteCachedNro();
                android.os.Process.killProcess(android.os.Process.myPid());
            }, 300);
        }
    };
    private final Runnable firstFrameWatchdog = () -> {
        if (presented || ending || sessionDone) return;
        firstFrameTimedOut = true;
        failure = "첫 화면이 나오지 않아 실행을 중단했습니다";
        if (!gpuNote.isEmpty()) failure += "\n" + gpuNote;
        ending = true;
        requestStopWithWatchdog();
        releaseInputs();
        SoftwareKeyboard.cancelOutstanding(this);
        message(failure);
        android.util.Log.w("PocketSwitchPlayer", "FIRST_FRAME_WATCHDOG_FIRED");
    };
    private boolean started;
    private volatile int nextProgram = -1;
    private final Set<Integer> held = new HashSet<>();
    private final Set<Integer> touches = new HashSet<>();
    private final Set<View> pressedControls = new HashSet<>();
    private final Set<ConsoleUi.InputScrollGuard> inputGuards = new HashSet<>();
    private StickView leftStick;
    private StickView rightStick;
    private OnBackInvokedCallback backCallback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ACTIVE_PLAYERS.incrementAndGet();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        ui = new ConsoleUi(this);
        buildScreen();
        ui.applySystemBars(getWindow());
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::requestExit;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        game = NativeLibrary.privatePath(this, getIntent().getStringExtra(EXTRA_GAME_PATH));
        if (game != null && getIntent().getBooleanExtra("delete_cached_nro", false)) {
            try {
                if (game.getParentFile().equals(getCacheDir().getCanonicalFile())) cachedNro = game;
            } catch (IOException error) {
                android.util.Log.w("PocketSwitchPlayer", "Temporary NRO path check failed", error);
            }
        }
        if (game == null || !game.isFile() || game.length() == 0) {
            message("가져온 Switch 홈브루 파일을 찾을 수 없어요.");
            return;
        }
        DeviceCapabilities.Compatibility capability = DeviceProbe.read(this).check(ConsoleType.SWITCH);
        if (capability.state != DeviceCapabilities.Compatibility.State.READY) {
            message(capability.reason);
            return;
        }
        userRoot = new File(getFilesDir(), "switch2-user");
        if (!userRoot.isDirectory() && !userRoot.mkdirs()) {
            message("Switch 2 저장 공간을 준비하지 못했어요.");
            return;
        }
        display.getHolder().addCallback(this);
        preparation.update("기기 그래픽 확인", 0, 0);
        main.post(loadingStatus);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        releaseInputs();
        ui = new ConsoleUi(this);
        root.setBackgroundColor(ui.paper);
        status.setTextColor(ui.ink);
        gpuWarning.setTextColor(ui.ink);
        for (Button button : buttons) {
            button.setTextColor(ui.ink);
            button.setBackground(ui.buttonSurface(ui.shell, 14));
        }
        if (leftStick != null) leftStick.invalidate();
        if (rightStick != null) rightStick.invalidate();
        ui.applySystemBars(getWindow());
    }

    @Override protected void onPause() {
        releaseInputs();
        if (backendReady && !ending) stop.control(NativeLibrary::pausePocketSession);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (backendReady && surfaceReady && !ending)
            stop.control(NativeLibrary::unpausePocketSession);
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(loadingStatus);
        main.removeCallbacks(firstFrameWatchdog);
        ending = true;
        userExit = true;
        SoftwareKeyboard.cancelOutstanding(this);
        requestStopWithWatchdog();
        releaseInputs();
        NativeLibrary.detach(this);
        if (!started) {
            deleteCachedNro();
            clearProcessSessionActive();
        }
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (Build.VERSION.SDK_INT < 33 && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) requestExit();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void requestExit() {
        if (ending) {
            userExit = true;
            SoftwareKeyboard.cancelOutstanding(this);
            requestStopWithWatchdog();
            if (sessionDone || !started) finish();
            return;
        }
        ending = true;
        userExit = true;
        main.removeCallbacks(loadingStatus);
        main.removeCallbacks(firstFrameWatchdog);
        requestStopWithWatchdog();
        releaseInputs();
        SoftwareKeyboard.cancelOutstanding(this);
        message("종료 중 · 게임 내 저장 마무리");
        if (!started) finish();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (game == null || ending || started) return;
        started = true;
        surfaceReleased = new CountDownLatch(1);
        armFirstFrameWatchdog();
        SESSION.execute(() -> runSession(holder.getSurface()));
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (backendReady && !ending) stop.control(NativeLibrary::refreshPocketSurface);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        releaseInputs();
        SoftwareKeyboard.cancelOutstanding(this);
        if (started && !ending) {
            // shortcut: surface loss ends play, implement a GPU-safe swap before resuming a session.
            ending = true;
            failure = "화면 연결이 끊겨 게임을 종료했어요. 다시 실행해 주세요.";
            message(failure);
        }
        if (started) {
            main.removeCallbacks(firstFrameWatchdog);
            requestStopWithWatchdog();
            try {
                if (!surfaceReleased.await(SURFACE_RELEASE_WAIT_MS, TimeUnit.MILLISECONDS))
                    android.util.Log.w("PocketSwitchPlayer", "SURFACE_RELEASE_WAIT_TIMEOUT");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void requestStopWithWatchdog() {
        armStopWatchdog();
        stop.request();
    }

    private void armStopWatchdog() {
        if (started && !sessionDone && stopWatchdogArmed.compareAndSet(false, true)) {
            main.postDelayed(stopWatchdog, STOP_TIMEOUT_MS);
            android.util.Log.i("PocketSwitchPlayer", "SWITCH_STOP_WATCHDOG_ARMED");
        }
    }

    static boolean isProcessSessionActive() { return ACTIVE_PLAYERS.get() > 0; }

    private void clearProcessSessionActive() {
        if (activityCounted.compareAndSet(true, false)) ACTIVE_PLAYERS.decrementAndGet();
    }

    private void armFirstFrameWatchdog() {
        firstFrameStartedAt = SystemClock.uptimeMillis();
        main.removeCallbacks(firstFrameWatchdog);
        main.postDelayed(firstFrameWatchdog, FIRST_FRAME_TIMEOUT_MS);
    }

    private void extendFirstFrameWatchdog() {
        long elapsed = SystemClock.uptimeMillis() - firstFrameStartedAt;
        long remaining = Math.min(FIRST_FRAME_TIMEOUT_MS,
                Math.max(0, FIRST_FRAME_TOTAL_CAP_MS - elapsed));
        main.removeCallbacks(firstFrameWatchdog);
        main.postDelayed(firstFrameWatchdog, remaining);
    }

    private void runSession(Surface surface) {
        boolean nativeSurface = false;
        try {
            String diagnostic = NativeLibrary.switchVulkanDiagnostic();
            if (ending || !surfaceReady || !surface.isValid()) return;
            if (!diagnostic.isEmpty()) {
                failure = diagnostic;
                return;
            }
            gpuNote = NativeLibrary.switchVulkanWarning();
            preparation.update("코어 초기화", 0, 0);
            runOnUiThread(() -> {
                if (ending || sessionDone || isDestroyed()) return;
                gpuWarning.setText(gpuNote);
                gpuWarning.setVisibility(gpuNote.isEmpty() ? View.GONE : View.VISIBLE);
            });
            NativeLibrary.initialize(this, "pocket_switch2");
            NativeLibrary.setAppDirectory(userRoot.getAbsolutePath());
            if (!stop.arm(NativeLibrary::resetPocketStopRequest)) return;
            backendReady = true;
            if (ending || !surfaceReady || !surface.isValid()) return;
            NativeLibrary.initializeGpuDriver(
                    getApplicationInfo().nativeLibraryDir, "", "", "");
            NativeLibrary.initializeSystem(systemInitialized);
            systemInitialized = true;
            NativeInput.configureHandheld();
            inputReady = true;
            if (ending || !surfaceReady || !surface.isValid()) return;
            NativeLibrary.surfaceChanged(surface);
            nativeSurface = true;
            int program = 0;
            boolean frontend = true;
            do {
                preparation.update("첫 화면 대기", 0, 0);
                presented = false;
                nextProgram = -1;
                NativeLibrary.run(game.getAbsolutePath(), program, frontend);
                program = nextProgram;
                frontend = false;
                if (program >= 0 && !ending && surfaceReady) {
                    runOnUiThread(() -> {
                        if (ending || sessionDone || isDestroyed()) return;
                        message("프로그램을 바꾸고 있어요.");
                        armFirstFrameWatchdog();
                    });
                }
            } while (program >= 0 && !ending && surfaceReady);
        } catch (Exception | LinkageError error) {
            ending = true;
            requestStopWithWatchdog();
            if (!firstFrameTimedOut) {
                failure = "Switch 2 코어를 시작하지 못했어요. 기기와 파일을 확인하세요.";
                runOnUiThread(() -> { if (!isDestroyed()) message(failure); });
            }
        } finally {
            ending = true;
            armStopWatchdog();
            SoftwareKeyboard.cancelOutstanding(this);
            boolean cleaned = false;
            boolean released = !nativeSurface;
            try {
                try {
                    if (inputReady) NativeInput.resetPocketState();
                } finally {
                    if (nativeSurface) {
                        NativeLibrary.surfaceDestroyed();
                        released = true;
                    }
                }
                cleaned = true;
            } catch (RuntimeException | LinkageError error) {
                android.util.Log.e("PocketSwitchPlayer", "Native cleanup failed", error);
                if (failure == null) failure = "Switch 코어 정리에 실패해 프로세스를 종료합니다.";
            } finally {
                if (released) surfaceReleased.countDown();
                inputReady = false;
                backendReady = false;
                if (cleaned) {
                    stop.finish();
                    deleteCachedNro();
                    sessionDone = true;
                    clearProcessSessionActive();
                    main.removeCallbacks(stopWatchdog);
                }
            }
            if (!cleaned) return;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                main.removeCallbacks(loadingStatus);
                main.removeCallbacks(firstFrameWatchdog);
                releaseInputs();
                if (firstFrameTimedOut) {
                    android.util.Log.i("PocketSwitchPlayer", "FIRST_FRAME_WATCHDOG_STOPPED");
                    returnWatchdogFailure();
                } else if (userExit) finish();
                else if (failure != null) message(failure);
                else if (!presented) message("게임 화면이 표시되지 않았어요. 파일과 그래픽을 확인하세요.");
                else message("게임 실행이 종료됐어요.");
            });
        }
    }

    private void deleteCachedNro() {
        if (cachedNro != null && cachedNro.exists() && !cachedNro.delete())
            android.util.Log.w("PocketSwitchPlayer", "Temporary NRO cleanup failed");
    }

    private void returnWatchdogFailure() {
        setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_FAILURE, failure));
        finish();
    }

    @Override public Context context() { return this; }
    @Override public boolean isEnding() { return ending; }

    @Override public void onLoaded() {
        runOnUiThread(() -> {
            if (!presented && !ending) preparation.update("첫 화면 대기", 0, 0);
        });
    }

    @Override public void onFirstFramePresented() {
        runOnUiThread(() -> {
            if (ending) return;
            presented = true;
            main.removeCallbacks(loadingStatus);
            main.removeCallbacks(firstFrameWatchdog);
            if (!ending) message("Switch 2 실험적 실행 · 화면 연결됨");
        });
    }

    @Override public void onStopped(int result) {
        if (result != 0 && !firstFrameTimedOut) {
            failure = "게임 실행이 멈췄어요. 파일과 기기를 확인하세요.";
            nextProgram = -1;
            ending = true;
            SoftwareKeyboard.cancelOutstanding(this);
            requestStopWithWatchdog();
        }
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            if (userExit) finish();
            else if (failure != null) message(failure);
            else if (nextProgram >= 0) message("프로그램을 바꾸고 있어요.");
        });
    }

    @Override public void onProgramChanged(int index) { nextProgram = index; }

    @Override public void onShaderProgress(int stage, int progress, int maximum) {
        runOnUiThread(() -> {
            if (!presented && !ending) {
                preparation.update("그래픽 자료 준비", progress, maximum);
                extendFirstFrameWatchdog();
            }
        });
    }

    @Override public void onUnsupportedApplet(String reason) {
        runOnUiThread(() -> {
            if (isDestroyed() || sessionDone || firstFrameTimedOut) return;
            failure = reason;
            message(reason);
            ending = true;
            SoftwareKeyboard.cancelOutstanding(this);
            requestStopWithWatchdog();
        });
    }

    @Override public void onExternalUrl(String url) {
        onUnsupportedApplet("이 게임의 외부 브라우저 화면은 아직 지원되지 않아요.");
    }

    private void message(String value) { status.setText(value); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.paper);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextColor(ui.ink);
        status.setTextSize(15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText("Switch 2 기기 확인 중");
        status.setPadding(dp(12), dp(8), dp(8), dp(8));
        header.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button exit = button("나가기", -1, null);
        exit.setOnClickListener(view -> requestExit());
        header.addView(exit, new LinearLayout.LayoutParams(dp(96), dp(48)));
        root.addView(header);
        gpuWarning = new TextView(this);
        gpuWarning.setTextColor(ui.ink);
        gpuWarning.setTextSize(13);
        gpuWarning.setPadding(dp(12), 0, dp(12), dp(8));
        gpuWarning.setVisibility(View.GONE);
        root.addView(gpuWarning, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(this);
        boolean compact = getResources().getConfiguration().screenWidthDp < 600;
        body.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        display = new SurfaceView(this);
        display.getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
        display.setOnTouchListener(this::surfaceTouch);
        if (compact) {
            body.addView(display, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout controls = new LinearLayout(this);
            controls.setGravity(Gravity.CENTER);
            controls.addView(sideControls(true), new LinearLayout.LayoutParams(dp(152), -1));
            controls.addView(sideControls(false), new LinearLayout.LayoutParams(dp(152), -1));
            body.addView(controls, new LinearLayout.LayoutParams(-1, dp(152)));
        } else {
            body.addView(sideControls(true), new LinearLayout.LayoutParams(dp(152), -1));
            body.addView(display, new LinearLayout.LayoutParams(0, -1, 1));
            body.addView(sideControls(false), new LinearLayout.LayoutParams(dp(152), -1));
        }
        setContentView(root);
    }

    private View sideControls(boolean left) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        ConsoleUi.InputScrollGuard inputGuard = new ConsoleUi.InputScrollGuard(scroll);
        inputGuards.add(inputGuard);
        LinearLayout column = new LinearLayout(this);
        column.setGravity(Gravity.CENTER);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);
        LinearLayout shoulders = new LinearLayout(this);
        shoulders.addView(button(left ? "ZL" : "ZR", left ? NativeInput.ZL : NativeInput.ZR,
                        inputGuard),
                new LinearLayout.LayoutParams(dp(72), dp(48)));
        shoulders.addView(button(left ? "L" : "R", left ? NativeInput.L : NativeInput.R,
                        inputGuard),
                new LinearLayout.LayoutParams(dp(72), dp(48)));
        column.addView(shoulders);
        if (left) {
            leftStick = new StickView(NativeInput.LEFT_STICK, "왼쪽 스틱", inputGuard);
            column.addView(leftStick);
        }
        GridLayout pad = new GridLayout(this);
        pad.setColumnCount(3);
        for (int cell = 0; cell < 9; cell++) {
            int id = left ? new int[]{-1, NativeInput.UP, -1, NativeInput.LEFT, -1,
                    NativeInput.RIGHT, -1, NativeInput.DOWN, -1}[cell]
                    : new int[]{-1, NativeInput.X, -1, NativeInput.Y, -1,
                    NativeInput.A, -1, NativeInput.B, -1}[cell];
            View item = id < 0 ? new View(this) : button(left
                    ? new String[]{"", "↑", "", "←", "", "→", "", "↓", ""}[cell]
                    : new String[]{"", "X", "", "Y", "", "A", "", "B", ""}[cell], id,
                    inputGuard);
            pad.addView(item, new GridLayout.LayoutParams(
                    GridLayout.spec(cell / 3), GridLayout.spec(cell % 3)));
            item.getLayoutParams().width = dp(48);
            item.getLayoutParams().height = dp(48);
        }
        column.addView(pad);
        if (!left) {
            LinearLayout actions = new LinearLayout(this);
            actions.addView(button("−", NativeInput.MINUS, inputGuard),
                    new LinearLayout.LayoutParams(dp(72), dp(48)));
            actions.addView(button("+", NativeInput.PLUS, inputGuard),
                    new LinearLayout.LayoutParams(dp(72), dp(48)));
            column.addView(actions);
            rightStick = new StickView(NativeInput.RIGHT_STICK, "오른쪽 스틱", inputGuard);
            column.addView(rightStick);
        }
        return scroll;
    }

    private Button button(String label, int id, ConsoleUi.InputScrollGuard inputGuard) {
        Button button = new Button(this);
        buttons.add(button);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(ui.ink);
        button.setMinWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setBackground(ui.buttonSurface(ui.shell, 14));
        String description = switch (id) {
            case NativeInput.UP -> "위 방향 버튼";
            case NativeInput.DOWN -> "아래 방향 버튼";
            case NativeInput.LEFT -> "왼쪽 방향 버튼";
            case NativeInput.RIGHT -> "오른쪽 방향 버튼";
            case NativeInput.PLUS -> "플러스 버튼";
            case NativeInput.MINUS -> "마이너스 버튼";
            default -> id < 0 ? "라이브러리로 돌아가기" : label + " 버튼";
        };
        button.setContentDescription(description);
        if (id >= 0) {
            button.setOnClickListener(view -> {
                if (!presented || ending || !inputReady || sessionDone || held.contains(id)) return;
                NativeInput.button(id, true);
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                view.postDelayed(() -> {
                    if (!held.contains(id) && inputReady && !sessionDone && !ending) {
                        NativeInput.button(id, false);
                    }
                }, 80);
            });
            button.setOnTouchListener((view, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    if (!presented || ending || !inputReady || sessionDone) return true;
                    held.add(id);
                    pressedControls.add(view);
                    view.setPressed(true);
                    inputGuard.hold(view);
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    NativeInput.button(id, true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (held.remove(id)) NativeInput.button(id, false);
                    pressedControls.remove(view);
                    view.setPressed(false);
                    inputGuard.release(view);
                }
                return true;
            });
        }
        return button;
    }

    private boolean surfaceTouch(View view, MotionEvent event) {
        if (!presented || ending || !inputReady || sessionDone) return true;
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int id = event.getPointerId(index);
            touches.add(id);
            NativeInput.onTouchPressed(id, event.getX(index), event.getY(index));
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int id = event.getPointerId(i);
                if (touches.contains(id)) NativeInput.onTouchMoved(id, event.getX(i), event.getY(i));
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int id = event.getPointerId(index);
            if (touches.remove(id)) NativeInput.onTouchReleased(id);
        } else if (action == MotionEvent.ACTION_CANCEL) releaseInputs();
        return true;
    }

    private void releaseInputs() {
        if (inputReady && !sessionDone) {
            for (int id : held) NativeInput.button(id, false);
            for (int id : touches) NativeInput.onTouchReleased(id);
            NativeInput.stick(NativeInput.LEFT_STICK, 0f, 0f);
            NativeInput.stick(NativeInput.RIGHT_STICK, 0f, 0f);
        }
        for (View control : pressedControls) control.setPressed(false);
        if (leftStick != null) leftStick.reset();
        if (rightStick != null) rightStick.reset();
        held.clear();
        touches.clear();
        pressedControls.clear();
        for (ConsoleUi.InputScrollGuard guard : inputGuards) guard.reset();
    }

    private final class StickView extends View {
        private final int stick;
        private final ConsoleUi.InputScrollGuard guard;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x;
        private float y;
        private boolean active;

        StickView(int stick, String name, ConsoleUi.InputScrollGuard guard) {
            super(Switch2PlayerActivity.this);
            this.stick = stick;
            this.guard = guard;
            setContentDescription(name);
            setFocusable(true);
            setMinimumHeight(dp(80));
            setMinimumWidth(dp(80));
            setLayoutParams(new LinearLayout.LayoutParams(dp(80), dp(80)));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * 0.43f;
            paint.setColor(ui.shell);
            canvas.drawCircle(centerX, centerY, radius, paint);
            paint.setColor(ui.ink);
            canvas.drawCircle(centerX + x * radius * .45f,
                    centerY + y * radius * .45f, radius * .49f, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!presented || ending || !inputReady || sessionDone) return true;
                active = true;
                guard.hold(this);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (!active) return true;
                active = false;
                guard.release(this);
                x = y = 0f;
                NativeInput.stick(stick, 0f, 0f);
                invalidate();
                return true;
            } else if (action != MotionEvent.ACTION_MOVE || !active) return true;
            x = Math.max(-1f, Math.min(1f, (event.getX() - getWidth() / 2f) / (getWidth() * .43f)));
            y = Math.max(-1f, Math.min(1f, (event.getY() - getHeight() / 2f) / (getHeight() * .43f)));
            NativeInput.stick(stick, x, y);
            invalidate();
            return true;
        }

        void reset() {
            active = false;
            x = y = 0f;
            guard.release(this);
            invalidate();
        }
    }
}
