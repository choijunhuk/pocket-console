package dev.pocketconsole;

import android.app.Activity;
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
    private int PAPER, INK, QUIET, ORANGE, SHELL;
    private ConsoleUi ui;
    private DeviceCapabilities capabilities;
    private boolean threeDsPackaged;
    private boolean threeDsPresenceChecked;
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
        } else {
            ConsoleType restored = consoleForId(uiPreferences.getString(KEY_CONSOLE, null));
            if (restored != null) selected = restored;
        }
        ui.applyBars(this);
        render();
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

    @Override protected void onResume() {
        super.onResume();
        uiPreferences.registerOnSharedPreferenceChangeListener(selectionChanged);
        libraryPreferences.registerOnSharedPreferenceChangeListener(libraryChanged);
        ConsoleType restored = consoleForId(uiPreferences.getString(KEY_CONSOLE, null));
        if (restored != null && restored != selected) { selected = restored; render(); }
        else if (!preview) render();
    }

    @Override protected void onPause() {
        uiPreferences.unregisterOnSharedPreferenceChangeListener(selectionChanged);
        libraryPreferences.unregisterOnSharedPreferenceChangeListener(libraryChanged);
        super.onPause();
    }

    @Override protected void onDestroy() {
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

    private DeviceCapabilities.Compatibility compatibility(ConsoleType type) {
        return capabilities.check(type);
    }

    private boolean launchable(ConsoleType type) {
        return (type == ConsoleType.DS || (type == ConsoleType.THREE_DS && threeDsPackaged))
                && compatibility(type).state == DeviceCapabilities.Compatibility.State.READY;
    }

    private String readiness(ConsoleType type) {
        if (type == ConsoleType.SWITCH_TWO) return "실험적 지원 · 엔진 준비 중";
        if (type == ConsoleType.SWITCH) return "엔진 준비 중";
        DeviceCapabilities.Compatibility check = compatibility(type);
        if (check.state == DeviceCapabilities.Compatibility.State.UNSUPPORTED) return "기기 조건 미충족";
        if (check.state == DeviceCapabilities.Compatibility.State.UNKNOWN) return "기기 조건 확인 중";
        return type == ConsoleType.THREE_DS && !threeDsPackaged ? "엔진 준비 중" : "실행 가능";
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString(KEY_CONSOLE, selected.id);
        out.putBoolean(KEY_PREVIEW, preview);
        super.onSaveInstanceState(out);
    }

    // API 33+ uses BackApi33; this override exists only for older Android versions.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) { super.onBackPressed(); return; }
        if (preview) { preview = false; render(); }
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
        if (Build.VERSION.SDK_INT >= 33) {
            if (preview && !backRegistered) {
                previewBack = BackApi33.register(this);
                backRegistered = true;
            } else if (!preview && backRegistered) {
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
                ? "3DS 엔진이 이 빌드에 없어요. 설치 후 실제 실행을 확인합니다."
                : (selected == ConsoleType.DS || selected == ConsoleType.THREE_DS)
                ? check.reason : "게임 실행 엔진은 아직 연결되지 않았어요.";
        page.addView(text(detail, 13, QUIET, false));
        gap(page, 12);
        Button importButton = button("파일 가져오기", ORANGE, ui.onAccent);
        importButton.setContentDescription("기기에서 " + selected.label + " 게임 파일 가져오기");
        page.addView(importButton, new LinearLayout.LayoutParams(-1, dp(54)));
        importButton.setOnClickListener(v -> importFile());
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
            message.setPadding(dp(12), dp(12), dp(12), dp(12));
            message.setBackground(shape(ui.notice, 12));
            page.addView(message);
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
        boolean canPlay = launchable(entry.console);
        Button item = button(entry.name + "  ·  " + (canPlay ? "실행" : "준비 중"), ui.raised, INK);
        item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        item.setPadding(dp(16), 0, dp(16), 0);
        item.setContentDescription(entry.name + ", " + entry.console.label + " 파일, "
                + (canPlay ? "게임 실행" : readiness(entry.console)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(60));
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
        if (launchable(entry.console)) {
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
                            : "게임 실행 엔진을 아직 사용할 수 없어요.";
                    new AlertDialog.Builder(activity).setTitle(activity.readiness(entry.console))
                            .setMessage(entry.name + "\n\n" + reason
                                    + " 조작 미리보기에서 버튼 배치만 확인할 수 있어요.")
                            .setPositiveButton("조작 미리보기", (dialog, which) -> { activity.selected = entry.console; activity.preview = true; activity.render(); })
                            .setNegativeButton("닫기", null).show();
                } else {
                    new AlertDialog.Builder(activity).setTitle("파일 접근 불가")
                            .setMessage("원본 파일이 이동했거나 읽기 권한이 해제되었어요. 목록은 유지됩니다.")
                            .setPositiveButton("파일 다시 선택", (dialog, which) -> activity.importFile())
                            .setNeutralButton("목록에서 지우기", (dialog, which) -> activity.confirmRemoval(entry))
                            .setNegativeButton("유지", null).show();
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
            OnBackInvokedCallback callback = () -> { activity.preview = false; activity.render(); };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(MainActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) callback);
        }
    }
}
