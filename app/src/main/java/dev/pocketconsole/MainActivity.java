package dev.pocketconsole;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.content.ContentResolver;
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

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;
import java.io.File;
import java.io.IOException;

public final class MainActivity extends Activity {
    private static final int PICK_FILE = 10;
    private static final int PLAY_SWITCH = 11;
    private static final int PICK_KEYS = 12;
    private static final int PICK_FIRMWARE = 13;
    /** True while a key or firmware request owns the library; only that request may clear it. */
    private static boolean contentBusy;
    private static int contentSerial;
    private static long contentDeadline;
    /** Result of the last request, shown by whichever activity is alive (or on next resume). */
    private static String pendingNotice;
    private static WeakReference<MainActivity> live = new WeakReference<>(null);
    private static final long IMPORT_STALE_MS = 15 * 60_000;
    private static final String KEY_SWITCH_NRO = "active_switch_nro";
    private static final ExecutorService SWITCH_IO = Executors.newSingleThreadExecutor();
    private static String switchProbeDiagnostic;
    private static String switchProbeWarning = "";
    private static boolean switchLaunching;
    private SwitchNroPreparation switchCopy;
    private static File switchNro;
    private int PAPER, INK, QUIET, ORANGE, SHELL;
    private ConsoleUi ui;
    private DeviceCapabilities capabilities;
    private boolean threeDsPackaged;
    private boolean threeDsPresenceChecked;
    private boolean switchPackaged;
    private boolean switchPresenceChecked;
    private String switchDiagnostic = "Switch 엔진을 확인하고 있어요.";
    private String switchWarning = "";
    private static final String KEY_CONSOLE = "console";
    private static final String KEY_PREVIEW = "preview";
    private static final String UI_PREFS = "console_ui";
    private static final String LIBRARY_PREFS = "game_library_v1";
    // Process lifetime keeps a selected document import running through activity recreation.
    private static final ExecutorService DOCUMENT_IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Boolean CORE_3DS_PRESENT;

    private LibraryStore library;
    private ConsoleType selected = ConsoleType.DS;
    private boolean preview;
    private String notice;
    private LinearLayout page;
    private TextView pressedStatus;
    private TextView noticeView;
    private final Set<String> pressedButtons = new LinkedHashSet<>();
    private SharedPreferences uiPreferences;
    private SharedPreferences libraryPreferences;
    private boolean backRegistered;
    private Object previewBack;
    private final SharedPreferences.OnSharedPreferenceChangeListener libraryChanged = (prefs, key) ->
            MAIN.post(() -> { if (!isFinishing() && !isDestroyed() && !preview) render(); });
    private final SharedPreferences.OnSharedPreferenceChangeListener selectionChanged = (prefs, key) ->
            MAIN.post(() -> {
                if (isFinishing() || isDestroyed() || !KEY_CONSOLE.equals(key)) return;
                ConsoleType restored = consoleForId(uiPreferences.getString(KEY_CONSOLE, null));
                if (restored != null) { selected = restored; render(); }
            });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new ConsoleUi(this);
        PAPER = ui.paper; INK = ui.ink; QUIET = ui.quiet; ORANGE = ui.accent; SHELL = ui.shell;
        capabilities = DeviceProbe.read(this);
        library = new LibraryStore(this);
        uiPreferences = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        libraryPreferences = getSharedPreferences(LIBRARY_PREFS, MODE_PRIVATE);
        if (state != null) {
            ConsoleType restored = consoleForId(state.getString(KEY_CONSOLE));
            if (restored != null) selected = restored;
            preview = state.getBoolean(KEY_PREVIEW);
            String active = state.getString(KEY_SWITCH_NRO);
            if (active != null) {
                switchNro = new File(active);
                switchLaunching = true;
            }
        } else {
            ConsoleType restored = consoleForId(uiPreferences.getString(KEY_CONSOLE, null));
            if (restored != null) selected = restored;
        }
        sweepSwitchNros();
        render();
        checkSwitchBackend();
        Context app = getApplicationContext();
        DOCUMENT_IO.execute(() -> {
            boolean packaged = hasThreeDsCore(app);
            MAIN.post(() -> {
                if (!isDestroyed() && !isFinishing()) {
                    threeDsPackaged = packaged;
                    threeDsPresenceChecked = true;
                    if (!preview) render();
                }
            });
        });
    }

    private void checkSwitchBackend() {
        switchPackaged = hasSwitchCore(getApplicationContext());
        DeviceCapabilities.Compatibility support = capabilities.check(ConsoleType.SWITCH);
        if (!switchPackaged || support.state != DeviceCapabilities.Compatibility.State.READY) {
            switchDiagnostic = !switchPackaged ? "Switch 엔진이 이 빌드에 없어요." : support.reason;
            switchPresenceChecked = true;
            render();
            return;
        }
        if (switchProbeDiagnostic != null) {
            switchDiagnostic = switchProbeDiagnostic;
            switchWarning = switchProbeWarning;
            switchPresenceChecked = true;
            render();
            return;
        }
        WeakReference<MainActivity> target = new WeakReference<>(this);
        final boolean[] completed = {false};
        Runnable deadline = () -> {
            if (completed[0]) return;
            completed[0] = true;
            MainActivity activity = target.get();
            if (activity == null || activity.isDestroyed() || activity.isFinishing()) return;
            activity.switchDiagnostic = "Switch 그래픽 진단이 응답하지 않아 중단했어요. 다시 실행해 주세요.";
            activity.switchPresenceChecked = true;
            if (!activity.preview) activity.render();
        };
        MAIN.postDelayed(deadline, 12_000);
        ResultReceiver receiver = new ResultReceiver(MAIN) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (completed[0]) return;
                completed[0] = true;
                MAIN.removeCallbacks(deadline);
                String diagnostic = data == null ? null : data.getString(SwitchProbeService.DIAGNOSTIC);
                String warning = data == null ? "" : data.getString(SwitchProbeService.WARNING, "");
                if (diagnostic == null) diagnostic = "Switch 그래픽 진단 결과를 확인하지 못했어요.";
                if (code == 0) {
                    switchProbeDiagnostic = diagnostic;
                    switchProbeWarning = warning;
                }
                MainActivity activity = target.get();
                if (activity == null || activity.isDestroyed() || activity.isFinishing()) return;
                activity.switchDiagnostic = diagnostic;
                activity.switchWarning = warning;
                activity.switchPresenceChecked = true;
                if (!activity.preview) activity.render();
            }
        };
        try { SwitchProbeService.start(getApplicationContext(), receiver); }
        catch (RuntimeException error) {
            Bundle data = new Bundle();
            data.putString(SwitchProbeService.DIAGNOSTIC, "Switch 그래픽 진단 프로세스를 시작하지 못했어요.");
            receiver.send(1, data);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        live = new WeakReference<>(this);
        if (pendingNotice != null) { notice = pendingNotice; pendingNotice = null; }
        uiPreferences.registerOnSharedPreferenceChangeListener(selectionChanged);
        libraryPreferences.registerOnSharedPreferenceChangeListener(libraryChanged);
        ConsoleType restored = consoleForId(uiPreferences.getString(KEY_CONSOLE, null));
        if (restored != null && restored != selected) { selected = restored; render(); }
        else if (!preview) render();
    }

    @Override protected void onPause() {
        if (live.get() == this) live = new WeakReference<>(null);
        uiPreferences.unregisterOnSharedPreferenceChangeListener(selectionChanged);
        libraryPreferences.unregisterOnSharedPreferenceChangeListener(libraryChanged);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (switchCopy != null) switchCopy.cancel();
        if (Build.VERSION.SDK_INT >= 33 && backRegistered) {
            BackApi33.unregister(this, previewBack);
            backRegistered = false;
        }
        super.onDestroy();
    }

    private ConsoleType consoleForId(String id) {
        if (id != null) for (ConsoleType type : ConsoleType.values()) if (type.id.equals(id)) return type;
        return null;
    }

    private static boolean hasThreeDsCore(Context app) {
        Boolean cached = CORE_3DS_PRESENT;
        if (cached != null) return cached;
        String nativeDir = app.getApplicationInfo().nativeLibraryDir;
        if (nativeDir != null && new File(nativeDir, "libpocket_3ds.so").isFile()
                && new File(nativeDir, "libazahar_libretro.so").isFile()) {
            CORE_3DS_PRESENT = true;
            return true;
        }
        String[] apks = app.getApplicationInfo().splitSourceDirs;
        String[] paths = new String[(apks == null ? 0 : apks.length) + 1];
        paths[0] = app.getApplicationInfo().sourceDir;
        if (apks != null) System.arraycopy(apks, 0, paths, 1, apks.length);
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0];
        boolean frontend = false, core = false;
        for (String path : paths) {
            if (path == null) continue;
            try (ZipFile zip = new ZipFile(path)) {
                frontend |= zip.getEntry("lib/" + abi + "/libpocket_3ds.so") != null;
                core |= zip.getEntry("lib/" + abi + "/libazahar_libretro.so") != null;
            } catch (IOException ignored) { }
        }
        CORE_3DS_PRESENT = frontend && core;
        return frontend && core;
    }

    private static boolean hasSwitchCore(Context app) {
        String directory = app.getApplicationInfo().nativeLibraryDir;
        if (directory == null) return false;
        for (String name : new String[]{"libpocket_switch2.so", "libhook_impl.so",
                "libmain_hook.so", "libc++_shared.so"}) {
            if (!new File(directory, name).isFile()) return false;
        }
        return true;
    }

    private DeviceCapabilities.Compatibility compatibility(ConsoleType type) {
        return capabilities.check(type);
    }

    private boolean launchable(ConsoleType type) {
        return (type == ConsoleType.DS || (type == ConsoleType.THREE_DS && threeDsPackaged))
                && compatibility(type).state == DeviceCapabilities.Compatibility.State.READY;
    }

    private boolean launchable(LibraryStore.Entry entry) {
        if (entry.console == ConsoleType.SWITCH || entry.console == ConsoleType.SWITCH_TWO) {
            boolean engine = switchPackaged && switchPresenceChecked && switchDiagnostic.isEmpty()
                    && compatibility(ConsoleType.SWITCH).state
                    == DeviceCapabilities.Compatibility.State.READY;
            // .nro keeps its original behavior: Switch tab only, no keys involved.
            if (ConsoleType.isSwitchHomebrew(entry.name)) return entry.console == ConsoleType.SWITCH && engine;
            return engine && !contentBusy && SwitchContentStatus.contentLaunchable(entry.name, keysPresent());
        }
        return launchable(entry.console);
    }

    private static boolean isSwitchContent(LibraryStore.Entry entry) {
        return (entry.console == ConsoleType.SWITCH || entry.console == ConsoleType.SWITCH_TWO)
                && ConsoleType.isSwitchContent(entry.name);
    }

    private File switchRoot() { return SwitchContentStatus.userRoot(getFilesDir()); }

    private boolean keysPresent() { return SwitchContentStatus.keysPresent(switchRoot()); }

    /** Why a library file cannot open right now, in the user's terms. */
    private String unavailableReason(LibraryStore.Entry entry) {
        if (isSwitchContent(entry)) {
            if (!switchPackaged) return "Switch 엔진이 이 빌드에 없어요.";
            if (!keysPresent()) return "NSP/XCI를 열려면 본인 콘솔에서 덤프한 키가 필요해요. 키 가져오기를 먼저 하세요.";
            if (contentBusy) return "키 또는 펌웨어를 처리하는 중이에요. 잠시 뒤 다시 시도하세요.";
            return switchPresenceChecked ? switchDiagnostic.isEmpty() ? compatibility(ConsoleType.SWITCH).reason
                    : switchDiagnostic : "기기 조건을 확인하는 중이에요.";
        }
        return entryReadiness(entry);
    }

    /** A .nro in the Switch 2 tab stays registration-only; the key label applies to NSP/XCI only. */
    private String entryReadiness(LibraryStore.Entry entry) {
        return entry.console == ConsoleType.SWITCH_TWO && !isSwitchContent(entry)
                ? "목록 등록만 지원" : readiness(entry.console);
    }

    private String readiness(ConsoleType type) {
        if (type == ConsoleType.SWITCH_TWO) {
            if (!switchPresenceChecked) return "기기 조건 확인 중";
            if (!switchPackaged) return "엔진 준비 중";
            return switchDiagnostic.isEmpty() ? "실험적 실행 · 사용자 키 필요" : "기기 조건 미충족";
        }
        if (type == ConsoleType.SWITCH) {
            if (!switchPresenceChecked) return "기기 조건 확인 중";
            if (!switchPackaged) return "엔진 준비 중";
            return switchDiagnostic.isEmpty() ? "실험적 실행 · 호환성 미확인" : "기기 조건 미충족";
        }
        DeviceCapabilities.Compatibility check = compatibility(type);
        if (check.state == DeviceCapabilities.Compatibility.State.UNSUPPORTED) return "기기 조건 미충족";
        if (check.state == DeviceCapabilities.Compatibility.State.UNKNOWN) return "기기 조건 확인 불가";
        if (type == ConsoleType.THREE_DS && !threeDsPresenceChecked) return "엔진 확인 중";
        if (type == ConsoleType.THREE_DS) return threeDsPackaged
                ? "실험적 실행 · 호환성 미확인" : "현재 실행 불가 · 엔진 없음";
        return "실행 가능";
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString(KEY_CONSOLE, selected.id);
        out.putBoolean(KEY_PREVIEW, preview);
        if (switchNro != null) out.putString(KEY_SWITCH_NRO, switchNro.getAbsolutePath());
        super.onSaveInstanceState(out);
    }

    // API 33+ uses BackApi33; this override exists only for older Android versions.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) { super.onBackPressed(); return; }
        if (switchCopy != null) cancelSwitchPreparation();
        else if (preview) { preview = false; render(); }
        else super.onBackPressed();
    }

    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int color, int radius) {
        return ui.shape(color, radius);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void gap(LinearLayout parent, int height) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setBackground(ui.buttonSurface(background, 14));
        return button;
    }

    private void render() {
        noticeView = null;
        if (Build.VERSION.SDK_INT >= 33) {
            if ((preview || switchCopy != null) && !backRegistered) {
                previewBack = BackApi33.register(this);
                backRegistered = true;
            } else if (!preview && switchCopy == null && backRegistered) {
                BackApi33.unregister(this, previewBack);
                previewBack = null;
                backRegistered = false;
            }
        }
        LinearLayout root = column();
        root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        page = column();
        page.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
        ui.applySystemBars(getWindow());
        root.requestApplyInsets();
        if (preview) drawPreview(); else drawLibrary();
    }

    private void drawHeader(String title, String description) {
        page.addView(text(title, 30, INK, true));
        gap(page, 8);
        page.addView(text(description, 14, QUIET, false));
        gap(page, 16);
    }

    private void drawLibrary() {
        drawHeader("내 라이브러리", "내 파일을 선택해 휴대용 화면에서 이어가세요.");
        ConsoleType[] choices = ConsoleType.values();
        for (int y = 0; y < choices.length; y += 2) {
            LinearLayout tabs = row();
            for (int x = y; x < Math.min(y + 2, choices.length); x++) {
                ConsoleType type = choices[x];
                Button tab = button(type.label + (type == selected ? "  선택" : ""),
                        type == selected ? INK : SHELL, type == selected ? PAPER : INK);
                tab.setContentDescription(type.label + ", " + readiness(type)
                        + (type == selected ? ", 선택됨" : ", 선택"));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
                if (x % 2 == 0) params.rightMargin = dp(8);
                tabs.addView(tab, params);
                tab.setOnClickListener(v -> {
                    selected = type;
                    uiPreferences.edit().putString(KEY_CONSOLE, type.id).apply();
                    notice = null;
                    render();
                });
            }
            page.addView(tabs);
            if (y + 2 < choices.length) gap(page, 8);
        }
        gap(page, 16);
        TextView ready = text(selected.label + "  ·  " + readiness(selected), 16, INK, true);
        page.addView(ready);
        gap(page, 4);
        DeviceCapabilities.Compatibility check = compatibility(selected);
        String detail = selected == ConsoleType.THREE_DS && !threeDsPresenceChecked
                ? "3DS 엔진 패키지를 확인하고 있어요."
                : selected == ConsoleType.THREE_DS && !threeDsPackaged
                && check.state == DeviceCapabilities.Compatibility.State.READY
                ? "3DS 엔진이 이 빌드에 없어요. 기다려도 실행되지 않으며, 엔진이 포함된 빌드가 필요해요."
                : selected == ConsoleType.SWITCH ? check.state == DeviceCapabilities.Compatibility.State.READY
                        ? switchDiagnostic.isEmpty() ? switchWarning : switchDiagnostic : check.reason
                : (selected == ConsoleType.DS || selected == ConsoleType.THREE_DS)
                ? check.reason : switchPackaged && switchPresenceChecked && switchDiagnostic.isEmpty()
                ? "Switch 2 파일은 본인 콘솔에서 덤프한 키로 실험적으로 열어볼 수 있어요. 호환성은 확인되지 않았어요."
                : "게임 실행 엔진을 사용할 수 없어요. 기다려도 실행되지 않으며, 현재는 파일 등록과 조작 미리보기만 가능해요.";
        page.addView(text(detail, 13, QUIET, false));
        gap(page, 12);
        Button importButton = button("파일 가져오기", ORANGE, ui.onAccent);
        importButton.setContentDescription("기기에서 " + selected.label + " 게임 파일 가져오기");
        page.addView(importButton, new LinearLayout.LayoutParams(-1, dp(54)));
        importButton.setOnClickListener(v -> importFile());
        if (selected == ConsoleType.SWITCH || selected == ConsoleType.SWITCH_TWO) drawSwitchContent();
        gap(page, 20);

        LinearLayout heading = row();
        TextView fileTitle = text("가져온 파일", 20, INK, true);
        heading.addView(fileTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = text(String.valueOf(library.entries(selected).size()) + "개", 13, QUIET, true);
        heading.addView(count);
        page.addView(heading);
        gap(page, 12);
        if (notice != null) {
            TextView message = text(notice, 13, ORANGE, false);
            noticeView = message;
            message.setPadding(dp(12), dp(12), dp(12), dp(12));
            message.setBackground(shape(ui.notice, 12));
            page.addView(message);
            if (switchCopy != null) {
                Button cancel = button("준비 취소", SHELL, INK);
                cancel.setOnClickListener(v -> cancelSwitchPreparation());
                page.addView(cancel, new LinearLayout.LayoutParams(-1, dp(48)));
            }
            gap(page, 12);
        }
        List<LibraryStore.Entry> entries = library.entries(selected);
        if (entries.isEmpty()) {
            TextView empty = text("아직 가져온 파일이 없어요. 위의 파일 가져오기를 눌러 시작하세요.", 14, QUIET, false);
            empty.setPadding(dp(16), dp(22), dp(16), dp(22));
            empty.setBackground(shape(ui.raised, 16));
            page.addView(empty);
        } else {
            for (LibraryStore.Entry entry : entries) drawEntry(entry);
        }
        gap(page, 24);
        LinearLayout previewRow = row();
        TextView previewText = text("조작 미리보기\n게임 화면이 아닌 버튼 배치 확인", 13, QUIET, false);
        previewRow.addView(previewText, new LinearLayout.LayoutParams(0, -2, 1));
        Button open = button("열기", SHELL, INK);
        open.setContentDescription(selected.label + " 조작 미리보기 열기");
        previewRow.addView(open, new LinearLayout.LayoutParams(dp(72), dp(48)));
        open.setOnClickListener(v -> { preview = true; render(); });
        page.addView(previewRow);
        ConsolePreviewView art = new ConsolePreviewView(this, selected);
        page.addView(art, new LinearLayout.LayoutParams(-1, dp(105)));
        gap(page, 8);
        page.addView(text("원본 파일은 기기에 그대로 보관됩니다.", 12, QUIET, false));
        if (selected == ConsoleType.THREE_DS) {
            gap(page, 8);
            page.addView(text("기기 확인 정보  ·  " + capabilities.summary(), 12, QUIET, false));
        }
    }

    private void drawEntry(LibraryStore.Entry entry) {
        boolean canPlay = launchable(entry);
        boolean content = isSwitchContent(entry);
        boolean experimental = entry.console == ConsoleType.THREE_DS || entry.console == ConsoleType.SWITCH;
        String action = canPlay ? (content ? SwitchContentStatus.EXPERIMENTAL_LABEL
                : experimental ? "실험적 실행" : "실행") : "실행 불가";
        String accessibleAction = canPlay ? (content || experimental
                ? "실험적 실행, 게임 호환성 미확인" : "게임 실행")
                : "실행 불가, " + unavailableReason(entry);
        Button item = button(entry.name + (content && canPlay ? "\n" : "  ·  ") + action, ui.raised, INK);
        item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        item.setPadding(dp(16), 0, dp(16), 0);
        item.setContentDescription(entry.name + ", " + entry.console.label + " 파일, " + accessibleAction);
        item.setMinHeight(dp(60));
        item.setMinimumHeight(dp(60));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        page.addView(item, params);
        item.setOnClickListener(v -> openEntry(entry));
        item.setOnLongClickListener(v -> { confirmRemoval(entry); return true; });
    }

    private void importFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_FILE); }
        catch (RuntimeException e) { showNotice("파일 선택기를 열 수 없어요."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PLAY_SWITCH) {
            deleteSwitchNro();
            switchLaunching = false;
            if (data != null) showNotice(data.getStringExtra(Switch2PlayerActivity.EXTRA_FAILURE));
            // A probe refused while a player was live is not cached; retry it now that play ended.
            if (!switchDiagnostic.isEmpty()) checkSwitchBackend();
            return;
        }
        if (request == PICK_KEYS || request == PICK_FIRMWARE) {
            handleContentPick(request, result, data);
            return;
        }
        if (request != PICK_FILE) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            showNotice("파일 가져오기를 취소했어요.");
            return;
        }
        Uri uri = data.getData();
        ConsoleType requested = selected;
        Context app = getApplicationContext();
        WeakReference<MainActivity> target = new WeakReference<>(this);
        showNotice("파일을 확인하고 있어요…");
        DOCUMENT_IO.execute(() -> {
            ImportResult resultValue = importDocument(app, uri, requested);
            MAIN.post(() -> {
                MainActivity activity = target.get();
                if (activity == null || activity.isDestroyed() || activity.isFinishing()) return;
                if (resultValue.console != null) activity.selected = resultValue.console;
                activity.showNotice(resultValue.message);
            });
        });
    }

    private static final class ImportResult {
        final ConsoleType console;
        final String message;
        ImportResult(ConsoleType console, String message) { this.console = console; this.message = message; }
    }

    private static ImportResult importDocument(Context app, Uri uri, ConsoleType selected) {
        ContentResolver resolver = app.getContentResolver();
        String name = displayName(resolver, uri);
        ConsoleType type = ConsoleType.fromImport(name, selected);
        if (type == null) return new ImportResult(null, "지원할 파일 형식을 인식하지 못했어요.");
        try {
            try (ParcelFileDescriptor file = resolver.openFileDescriptor(uri, "r")) {
                if (file == null) throw new FileNotFoundException();
            }
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            boolean added = new LibraryStore(app).add(name, uri.toString(), type);
            app.getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(KEY_CONSOLE, type.id).commit();
            return new ImportResult(type, added ? "파일을 목록에 추가했어요. 파일을 눌러 상태를 확인하세요."
                    : "이미 가져온 파일이에요.");
        } catch (IllegalStateException e) {
            return new ImportResult(null, "파일 목록을 저장할 수 없어요. 다시 시도해 주세요.");
        } catch (Exception e) {
            return new ImportResult(null, "파일을 읽을 수 없어요. 파일 접근 권한을 확인해 주세요.");
        }
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (RuntimeException ignored) { }
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    private void openEntry(LibraryStore.Entry entry) {
        if (launchable(entry)) {
            if (isSwitchContent(entry)) {
                launchSwitchContent(entry);
                return;
            }
            if (entry.console == ConsoleType.SWITCH) {
                launchSwitchHomebrew(entry);
                return;
            }
            Intent player = new Intent(this, entry.console == ConsoleType.DS
                    ? DsPlayerActivity.class : ThreeDsPlayerActivity.class);
            player.putExtra("rom_uri", entry.uri);
            startActivity(player);
            return;
        }
        Context app = getApplicationContext();
        WeakReference<MainActivity> target = new WeakReference<>(this);
        showNotice("파일 접근을 확인하고 있어요…");
        DOCUMENT_IO.execute(() -> {
            boolean readable;
            try (ParcelFileDescriptor file = app.getContentResolver().openFileDescriptor(Uri.parse(entry.uri), "r")) {
                readable = file != null;
            } catch (Exception e) { readable = false; }
            boolean canRead = readable;
            MAIN.post(() -> {
                MainActivity activity = target.get();
                if (activity == null || activity.isDestroyed() || activity.isFinishing() || activity.preview) return;
                activity.notice = null;
                activity.render();
                if (canRead) {
                    String reason = activity.compatibility(entry.console).state
                            == DeviceCapabilities.Compatibility.State.UNSUPPORTED
                            ? activity.compatibility(entry.console).reason
                            : isSwitchContent(entry) ? activity.unavailableReason(entry)
                            : entry.console == ConsoleType.SWITCH && ConsoleType.isSwitchHomebrew(entry.name)
                            ? activity.switchDiagnostic
                            : "게임 실행 엔진을 아직 사용할 수 없어요.";
                    new AlertDialog.Builder(activity).setTitle(activity.entryReadiness(entry))
                            .setMessage(entry.name + "\n\n" + reason
                                    + " 기다려도 자동으로 실행되지 않아요. 조작 미리보기에서 버튼 배치만 확인할 수 있어요.")
                            .setPositiveButton("조작 미리보기", (dialog, which) -> { activity.selected = entry.console; activity.preview = true; activity.render(); })
                            .setNegativeButton("닫기", null).show();
                } else {
                    new AlertDialog.Builder(activity).setTitle("파일 접근 불가")
                            .setMessage("원본 파일이 이동했거나 읽기 권한이 해제되었어요. 목록은 유지됩니다.")
                            .setPositiveButton("새 파일 가져오기", (dialog, which) -> activity.importFile())
                            .setNeutralButton("목록에서 지우기", (dialog, which) -> activity.confirmRemoval(entry))
                            .setNegativeButton("유지", null).show();
                }
            });
        });
    }

    private void cancelSwitchPreparation() {
        if (switchCopy == null) return;
        switchCopy.cancel();
        showNotice("Switch 파일 준비를 취소하고 있어요.");
    }

    private void deleteSwitchNro() {
        if (switchNro != null) switchNro.delete();
        switchNro = null;
    }

    private void sweepSwitchNros() {
        sweepImports();
        // A second library instance must not unlink a live player's private copy.
        ActivityManager manager = getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) return;
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if ((getPackageName() + ":switch2").equals(process.processName)) return;
        }
        File[] stale = getCacheDir().listFiles((directory, name) ->
                name.startsWith("switch-nro-") && name.endsWith(".nro"));
        if (stale != null) for (File file : stale) {
            if (!file.equals(switchNro)) file.delete();
        }
    }

    /** Leftover key or firmware imports hold user files; stale ones go even while :switch2 is alive. */
    private void sweepImports() {
        if (contentBusy) return;
        File[] imports = getCacheDir().listFiles((directory, name) -> name.startsWith("switch-import-"));
        if (imports == null) return;
        long now = System.currentTimeMillis();
        for (File dir : imports) {
            long newest = dir.lastModified();
            File[] children = dir.listFiles();
            if (children != null) for (File child : children) newest = Math.max(newest, child.lastModified());
            if (now - newest > IMPORT_STALE_MS) deleteTemp(dir);
        }
    }

    private void launchSwitchContent(LibraryStore.Entry entry) {
        if (switchLaunching) return;
        switchLaunching = true;
        // No copy: the player reads the document in place through the provider.
        Intent player = new Intent(this, Switch2PlayerActivity.class)
                .putExtra(Switch2PlayerActivity.EXTRA_GAME_URI, entry.uri);
        try {
            startActivityForResult(player, PLAY_SWITCH);
        } catch (RuntimeException launchError) {
            switchLaunching = false;
            showNotice("Switch 실행 화면을 열 수 없어요.");
        }
    }

    private void drawSwitchContent() {
        if (!switchPackaged) return;
        gap(page, 16);
        page.addView(text("내 콘솔 키와 펌웨어", 16, INK, true));
        gap(page, 4);
        boolean keys = keysPresent();
        int files = SwitchContentStatus.firmwareFileCount(switchRoot());
        TextView state = text(SwitchContentStatus.describe(keys, files,
                files == 0 ? "" : SwitchContentStatus.firmwareVersion(switchRoot())), 14, INK, false);
        state.setContentDescription("키와 펌웨어 상태, " + state.getText());
        page.addView(state);
        gap(page, 4);
        page.addView(text("본인 콘솔에서 직접 덤프한 prod.keys(와 title.keys)와 펌웨어 zip만 쓰세요. "
                + "파일은 이 기기 앱 안에만 보관되고 화면·로그·백업·네트워크로 내보내지 않아요. "
                + "NSP/XCI 실행은 실험적이며 호환성은 확인되지 않았어요.", 12, QUIET, false));
        gap(page, 8);
        // Firmware is verified by the engine, which needs the same device conditions as a game.
        boolean engineOk = switchPresenceChecked && switchDiagnostic.isEmpty();
        LinearLayout add = row();
        Button importKeys = button("키 가져오기", SHELL, INK);
        importKeys.setEnabled(!contentBusy);
        importKeys.setContentDescription("prod.keys 또는 title.keys 가져오기");
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.rightMargin = dp(8);
        add.addView(importKeys, left);
        Button importFirmware = button("펌웨어 가져오기", SHELL, INK);
        importFirmware.setEnabled(!contentBusy && engineOk);
        importFirmware.setContentDescription(engineOk ? "펌웨어 zip 가져오기"
                : "펌웨어 zip 가져오기, 기기 조건을 확인한 뒤 사용할 수 있어요");
        add.addView(importFirmware, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(add);
        importKeys.setOnClickListener(v -> pickContent(PICK_KEYS));
        importFirmware.setOnClickListener(v -> pickContent(PICK_FIRMWARE));
        if (keys || files > 0) {
            gap(page, 8);
            LinearLayout remove = row();
            if (keys) {
                Button deleteKeys = button("키 삭제", SHELL, INK);
                deleteKeys.setEnabled(!contentBusy);
                deleteKeys.setOnClickListener(v -> confirmContentDelete(true));
                LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
                if (files > 0) first.rightMargin = dp(8);
                remove.addView(deleteKeys, first);
            }
            if (files > 0) {
                Button deleteFirmware = button("펌웨어 삭제", SHELL, INK);
                deleteFirmware.setEnabled(!contentBusy);
                deleteFirmware.setOnClickListener(v -> confirmContentDelete(false));
                remove.addView(deleteFirmware, new LinearLayout.LayoutParams(0, -2, 1));
            }
            page.addView(remove);
        }
    }

    private void pickContent(int request) {
        if (contentBusy) { showNotice(BUSY_MESSAGE); return; }
        if (request == PICK_FIRMWARE && !(switchPresenceChecked && switchDiagnostic.isEmpty())) {
            showNotice("펌웨어는 기기 조건 확인이 끝난 뒤에 가져올 수 있어요.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        if (request == PICK_FIRMWARE) intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        if (request == PICK_KEYS) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, request); }
        catch (RuntimeException e) { showNotice("파일 선택기를 열 수 없어요."); }
    }

    private static final String BUSY_MESSAGE = "다른 키·펌웨어 작업이 끝나기를 기다려 주세요.";

    private void confirmContentDelete(boolean keys) {
        new AlertDialog.Builder(this).setTitle(keys ? "키를 삭제할까요?" : "펌웨어를 삭제할까요?")
                .setMessage(keys ? "이 기기에 가져온 키를 모두 지웁니다. 원본 파일은 삭제되지 않아요."
                        : "이 기기에 가져온 펌웨어를 지웁니다. 원본 zip은 삭제되지 않아요.")
                .setPositiveButton("삭제", (dialog, which) -> {
                    if (contentBusy) { showNotice(BUSY_MESSAGE); return; }
                    showNotice("처리하고 있어요…");
                    startContent(getApplicationContext(), keys ? SwitchContentService.DELETE_KEYS
                            : SwitchContentService.DELETE_FIRMWARE, new String[0], beginContent());
                })
                .setNegativeButton("유지", null).show();
    }

    private void handleContentPick(int request, int result, Intent data) {
        if (result != RESULT_OK || data == null) {
            showNotice("가져오기를 취소했어요.");
            return;
        }
        java.util.ArrayList<Uri> picked = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                picked.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (contentBusy) { showNotice(BUSY_MESSAGE); return; }
        if (picked.isEmpty() || (request == PICK_FIRMWARE && picked.size() != 1)) {
            showNotice(request == PICK_FIRMWARE ? "펌웨어 zip은 하나만 선택해 주세요." : "가져올 키 파일을 선택해 주세요.");
            return;
        }
        int id = beginContent();
        showNotice(request == PICK_KEYS ? "키 파일을 확인하고 있어요…"
                : "펌웨어 zip을 복사하고 있어요. 몇 분 걸릴 수 있어요.");
        // Application context only: a rotation or back press mid-copy must not drop the import.
        Context app = getApplicationContext();
        DOCUMENT_IO.execute(() -> {
            File dir = null;
            String error = null;
            java.util.List<String> paths = new java.util.ArrayList<>();
            try {
                dir = Files.createTempDirectory(app.getCacheDir().toPath(), "switch-import-").toFile();
                ContentResolver resolver = app.getContentResolver();
                for (Uri uri : picked) {
                    String name = displayName(resolver, uri);
                    File copy;
                    if (request == PICK_KEYS) {
                        if (!SwitchKeyFile.isAcceptedName(name)) {
                            error = "prod.keys 또는 title.keys 파일만 가져올 수 있어요. 파일 이름을 확인하세요.";
                            break;
                        }
                        copy = new File(dir, name.toLowerCase(java.util.Locale.ROOT));
                        if (copy.exists()) { error = "같은 키 파일을 두 번 골랐어요."; break; }
                        try (InputStream in = resolver.openInputStream(uri)) {
                            if (in == null) throw new IOException();
                            SwitchKeyFile.Check check = SwitchKeyFile.copyChecked(in, copy);
                            if (!check.ok()) { error = check.message(); break; }
                        }
                    } else {
                        copy = new File(dir, "firmware.zip");
                        error = copyFirmware(resolver, uri, copy);
                        if (error != null) break;
                    }
                    paths.add(copy.getAbsolutePath());
                }
            } catch (IOException | RuntimeException failure) {
                error = "파일을 읽을 수 없어요. 파일 접근 권한과 저장 공간을 확인하세요.";
            }
            File temp = dir;
            String problem = error;
            MAIN.post(() -> {
                if (problem != null) {
                    if (temp != null) deleteTemp(temp);
                    finishContent(id, problem);
                    return;
                }
                startContent(app, request == PICK_KEYS ? SwitchContentService.INSTALL_KEYS
                        : SwitchContentService.INSTALL_FIRMWARE, paths.toArray(new String[0]), id);
            });
        });
    }

    /** Copies at most the zip cap; returns a Korean reason on failure. */
    private static String copyFirmware(ContentResolver resolver, Uri uri, File copy) throws IOException {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException();
            long size = 0;
            byte[] buffer = new byte[256 * 1024];
            try (OutputStream out = Files.newOutputStream(copy.toPath())) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    size += read;
                    if (size > SwitchFirmwareZip.MAX_TOTAL_BYTES) return "펌웨어 zip이 너무 커요.";
                    out.write(buffer, 0, read);
                }
            }
            if (size == 0) return "비어 있는 파일이에요.";
            // The extracted NCAs need about the same space again.
            if (copy.getUsableSpace() < size + (256L << 20)) return "저장 공간이 부족해요. 펌웨어 크기만큼 비워 주세요.";
        }
        return null;
    }

    private static void deleteTemp(File dir) {
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) child.delete();
        dir.delete();
    }

    /** Marks the library busy and returns the id of the request that now owns the flag. */
    private static int beginContent() {
        contentBusy = true;
        int id = ++contentSerial;
        armContentDeadline(id, 10 * 60_000L);
        return id;
    }

    /** A stalled provider or a dead :switch2 must not lock the library; only the owning request is released. */
    private static void armContentDeadline(int id, long delayMs) {
        // Re-arming moves the deadline; an older timer that fires early sees it and does nothing.
        contentDeadline = android.os.SystemClock.uptimeMillis() + delayMs;
        MAIN.postDelayed(() -> {
            if (id == contentSerial && contentBusy && android.os.SystemClock.uptimeMillis() >= contentDeadline)
                finishContent(id, "작업이 오래 걸려 중단했어요. 다시 시도하세요.");
        }, delayMs);
    }

    /** Clears busy only for the request that set it, then shows the outcome to the live activity. */
    private static void finishContent(int id, String message) {
        if (id == contentSerial) contentBusy = false;
        MainActivity activity = live.get();
        if (message != null) {
            if (activity != null && !activity.isDestroyed() && !activity.isFinishing()) activity.showNotice(message);
            else pendingNotice = message;
        } else if (activity != null && !activity.isDestroyed() && !activity.isFinishing() && !activity.preview) {
            activity.render();
        }
    }

    private static void startContent(Context app, String action, String[] paths, int id) {
        boolean firmware = SwitchContentService.INSTALL_FIRMWARE.equals(action);
        armContentDeadline(id, firmware ? 16 * 60_000L : 90_000L);
        try {
            SwitchContentService.start(app, action, paths, new ContentReceiver(id));
        } catch (RuntimeException error) {
            for (String path : paths) deleteTemp(new File(path).getParentFile());
            finishContent(id, "Switch 프로세스를 시작하지 못했어요.");
        }
    }

    /** Static so no activity is retained; the result goes to whichever activity is live. */
    private static final class ContentReceiver extends ResultReceiver {
        private final int id;

        ContentReceiver(int id) {
            super(MAIN);
            this.id = id;
        }

        @Override protected void onReceiveResult(int code, Bundle data) {
            String message = data == null ? null : data.getString(SwitchContentService.MESSAGE);
            if (message == null) message = "결과를 확인하지 못했어요.";
            // The service process exits shortly after key changes; hold off launching meanwhile.
            String shown = message;
            MAIN.postDelayed(() -> finishContent(id, shown), 1_500);
        }
    }

    private void launchSwitchHomebrew(LibraryStore.Entry entry) {
        if (switchLaunching) return;
        switchLaunching = true;
        SwitchNroPreparation copy = new SwitchNroPreparation();
        switchCopy = copy;
        Context app = getApplicationContext();
        WeakReference<MainActivity> target = new WeakReference<>(this);
        PreparationProgress preparation = new PreparationProgress();
        showNotice(preparation.message());
        Runnable loading = new Runnable() {
            @Override public void run() {
                MainActivity activity = target.get();
                if (activity == null || activity.isDestroyed() || activity.isFinishing()
                        || activity.switchCopy != copy) return;
                activity.notice = preparation.message();
                TextView message = activity.noticeView;
                if (message != null) message.setText(activity.notice);
                MAIN.postDelayed(this, 500);
            }
        };
        MAIN.post(loading);
        SWITCH_IO.execute(() -> {
            File copied = null;
            String error = null;
            try (InputStream source = app.getContentResolver().openInputStream(Uri.parse(entry.uri))) {
                copy.checkCancelled();
                if (source == null) throw new IOException("파일을 열 수 없어요.");
                Path path = Files.createTempFile(app.getCacheDir().toPath(), "switch-nro-", ".nro");
                copied = path.toFile();
                try (OutputStream output = Files.newOutputStream(path)) {
                    copy.copy(source, output, preparation);
                }
                preparation.update("NRO 헤더 확인", 0, 0);
                SwitchNroValidator.validate(copied);
                copy.checkCancelled();
            } catch (Exception failure) {
                error = failure.getMessage() == null ? "NRO 파일을 준비하지 못했어요." : failure.getMessage();
            }
            File ready = copied;
            String failure = error;
            MAIN.post(() -> {
                MAIN.removeCallbacks(loading);
                MainActivity activity = target.get();
                if (activity != null) activity.switchCopy = null;
                if (activity == null || activity.isDestroyed() || activity.isFinishing()
                        || failure != null || copy.isCancelled()) {
                    if (ready != null) ready.delete();
                    // Static: a collected activity must not leave every later launch locked.
                    switchLaunching = false;
                    if (activity != null) {
                        if (!activity.isDestroyed() && !activity.isFinishing())
                            activity.showNotice(copy.isCancelled() ? "Switch 파일 준비를 취소했어요." : failure);
                    }
                    return;
                }
                if (!activity.launchable(entry)) {
                    ready.delete();
                    activity.switchLaunching = false;
                    activity.showNotice("기기 조건이 바뀌어 Switch 홈브루를 실행할 수 없어요.");
                    return;
                }
                Intent player = new Intent(activity, Switch2PlayerActivity.class);
                player.putExtra(Switch2PlayerActivity.EXTRA_GAME_PATH, ready.getAbsolutePath());
                player.putExtra("delete_cached_nro", true);
                activity.switchNro = ready;
                try {
                    activity.startActivityForResult(player, PLAY_SWITCH);
                } catch (RuntimeException launchError) {
                    activity.deleteSwitchNro();
                    activity.switchLaunching = false;
                    activity.showNotice("Switch 실행 화면을 열 수 없어요.");
                }
            });
        });
    }

    private void confirmRemoval(LibraryStore.Entry entry) {
        new AlertDialog.Builder(this).setTitle("목록에서 지울까요?")
                .setMessage(entry.name + "의 목록 항목만 지웁니다. 원본 파일은 삭제되지 않아요.")
                .setPositiveButton("목록에서 지우기", (dialog, which) -> {
                    try { library.remove(entry.uri); render(); }
                    catch (RuntimeException error) { showNotice("파일 목록을 저장할 수 없어요. 다시 시도해 주세요."); }
                })
                .setNegativeButton("유지", null).show();
    }

    private void showNotice(String message) { notice = message; render(); }

    private void drawPreview() {
        Button back = button("‹  라이브러리", SHELL, INK);
        back.setContentDescription("라이브러리로 돌아가기");
        page.addView(back, new LinearLayout.LayoutParams(dp(142), dp(48)));
        back.setOnClickListener(v -> { preview = false; render(); });
        gap(page, 22);
        drawHeader("조작 미리보기", selected.label + " 화면 배치와 버튼 입력을 확인하세요.");
        ConsolePreviewView art = new ConsolePreviewView(this, selected);
        page.addView(art, new LinearLayout.LayoutParams(-1,
                dp(selected == ConsoleType.SWITCH || selected == ConsoleType.SWITCH_TWO ? 245 : 340)));
        gap(page, 18);
        TextView status = text("조작 테스트 · 게임 화면 아님", 14, ORANGE, true);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(shape(ui.notice, 12));
        page.addView(status);
        gap(page, 8);
        page.addView(text("이 화면은 조작 테스트입니다. 게임을 실행하거나 저장하지 않습니다.", 13, QUIET, false));
        gap(page, 22);
        pressedStatus = text("누른 버튼: 없음", 16, INK, true);
        pressedStatus.setContentDescription("누른 버튼: 없음");
        page.addView(pressedStatus);
        gap(page, 14);
        boolean narrow = getResources().getConfiguration().screenWidthDp < 360;
        LinearLayout controls = narrow ? column() : row();
        controls.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        LinearLayout directions = column();
        directions.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout upper = row();
        upper.setGravity(Gravity.CENTER);
        upper.addView(control("위", "↑"));
        directions.addView(upper);
        LinearLayout middle = row();
        middle.addView(control("왼쪽", "←"));
        middle.addView(control("아래", "↓"));
        middle.addView(control("오른쪽", "→"));
        directions.addView(middle);
        controls.addView(directions, narrow ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout actions = column();
        actions.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout xy = row();
        xy.addView(control("X", "X"));
        xy.addView(control("Y", "Y"));
        actions.addView(xy);
        LinearLayout ab = row();
        ab.addView(control("A", "A"));
        ab.addView(control("B", "B"));
        actions.addView(ab);
        if (narrow) gap(controls, 8);
        controls.addView(actions, narrow ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(controls);
        gap(page, 10);
        TextView hint = text("버튼을 누르는 동안 색이 바뀌고 짧게 진동합니다.", 12, QUIET, false);
        page.addView(hint);
    }

    private Button control(String name, String symbol) {
        Button button = button(symbol, INK, PAPER);
        button.setContentDescription(name + " 버튼");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        button.setLayoutParams(params);
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pressedButtons.add(name);
                updatePressedStatus();
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pressedButtons.remove(name);
                updatePressedStatus();
            }
            return false;
        });
        button.setOnClickListener(v -> {
            if (pressedButtons.isEmpty()) {
                pressedStatus.setText("마지막 버튼: " + name);
                pressedStatus.setContentDescription("마지막 버튼: " + name);
            }
        });
        return button;
    }

    private void updatePressedStatus() {
        String value = pressedButtons.isEmpty() ? "누른 버튼: 없음" : "누른 버튼: " + String.join(", ", pressedButtons);
        pressedStatus.setText(value);
        pressedStatus.setContentDescription(value);
    }

    @android.annotation.TargetApi(33)
    private static final class BackApi33 {
        static OnBackInvokedCallback register(MainActivity activity) {
            OnBackInvokedCallback callback = () -> {
                if (activity.switchCopy != null) activity.cancelSwitchPreparation();
                else { activity.preview = false; activity.render(); }
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(MainActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) callback);
        }
    }
}
