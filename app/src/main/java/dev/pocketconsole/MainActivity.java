package dev.pocketconsole;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
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

public final class MainActivity extends Activity {
    private static final int PICK_FILE = 10;
    private static final int PAPER = Color.rgb(243, 240, 232);
    private static final int INK = Color.rgb(32, 40, 38);
    private static final int QUIET = Color.rgb(96, 107, 102);
    private static final int ORANGE = Color.rgb(204, 99, 63);
    private static final int SHELL = Color.rgb(218, 221, 213);
    private static final String KEY_CONSOLE = "console";
    private static final String KEY_PREVIEW = "preview";
    private static final String UI_PREFS = "console_ui";
    private static final String LIBRARY_PREFS = "game_library_v1";
    // Process lifetime keeps a selected document import running through activity recreation.
    private static final ExecutorService DOCUMENT_IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        render();
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

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString(KEY_CONSOLE, selected.id);
        out.putBoolean(KEY_PREVIEW, preview);
        super.onSaveInstanceState(out);
    }

    @Override public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) { super.onBackPressed(); return; }
        if (preview) { preview = false; render(); }
        else super.onBackPressed();
    }

    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
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
        button.setBackground(shape(background, 14));
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

    private void drawHeader(String eyebrow, String title, String description) {
        TextView overline = text(eyebrow, 11, ORANGE, true);
        overline.setLetterSpacing(.18f);
        page.addView(overline);
        gap(page, 8);
        page.addView(text(title, 30, INK, true));
        gap(page, 8);
        page.addView(text(description, 14, QUIET, false));
        gap(page, 24);
    }

    private void drawLibrary() {
        drawHeader("POCKET CONSOLE  /  LIBRARY", "내 라이브러리", "내 기기에 있는 파일을 정리하고 조작감을 미리 볼 수 있어요.");
        LinearLayout tabs = row();
        for (ConsoleType type : ConsoleType.values()) {
            Button tab = button(type.label, type == selected ? INK : SHELL, type == selected ? Color.WHITE : INK);
            tab.setContentDescription(type.label + (type == selected ? ", 선택됨" : ", 선택"));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), 1);
            params.setMargins(0, 0, dp(6), 0);
            tabs.addView(tab, params);
            tab.setOnClickListener(v -> {
                selected = type;
                uiPreferences.edit().putString(KEY_CONSOLE, type.id).apply();
                notice = null;
                render();
            });
        }
        page.addView(tabs);
        gap(page, 22);

        LinearLayout hero = column();
        hero.setPadding(dp(18), dp(20), dp(18), dp(20));
        hero.setBackground(shape(SHELL, 24));
        TextView badge = text("조작 미리보기  ·  " + selected.label, 12, ORANGE, true);
        badge.setLetterSpacing(.06f);
        hero.addView(badge);
        gap(hero, 10);
        hero.addView(text(selected.subtitle, 18, INK, true));
        gap(hero, 8);
        hero.addView(text("화면과 버튼 반응을 살펴보세요. 게임 실행 기능은 아직 없습니다.", 13, QUIET, false));
        gap(hero, 16);
        ConsolePreviewView art = new ConsolePreviewView(this, selected);
        hero.addView(art, new LinearLayout.LayoutParams(-1, dp(190)));
        gap(hero, 14);
        Button open = button("조작 미리보기 열기  ↗", INK, Color.WHITE);
        open.setContentDescription(selected.label + " 조작 미리보기 열기");
        hero.addView(open, new LinearLayout.LayoutParams(-1, dp(50)));
        open.setOnClickListener(v -> { preview = true; render(); });
        page.addView(hero);
        gap(page, 26);

        LinearLayout heading = row();
        TextView fileTitle = text("가져온 파일", 20, INK, true);
        heading.addView(fileTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = text(String.valueOf(library.entries(selected).size()), 13, QUIET, true);
        heading.addView(count);
        page.addView(heading);
        gap(page, 12);
        if (notice != null) {
            TextView message = text(notice, 13, ORANGE, false);
            message.setPadding(dp(12), dp(12), dp(12), dp(12));
            message.setBackground(shape(Color.rgb(250, 229, 217), 12));
            page.addView(message);
            gap(page, 12);
        }
        List<LibraryStore.Entry> entries = library.entries(selected);
        if (entries.isEmpty()) {
            TextView empty = text("아직 파일이 없어요. 아래 버튼으로 내 파일을 가져오세요.", 14, QUIET, false);
            empty.setPadding(dp(16), dp(22), dp(16), dp(22));
            empty.setBackground(shape(Color.WHITE, 16));
            page.addView(empty);
        } else {
            for (LibraryStore.Entry entry : entries) drawEntry(entry);
        }
        gap(page, 16);
        Button importButton = button("＋  파일 가져오기", ORANGE, Color.WHITE);
        importButton.setContentDescription("기기에서 게임 파일 가져오기");
        page.addView(importButton, new LinearLayout.LayoutParams(-1, dp(54)));
        importButton.setOnClickListener(v -> importFile());
        gap(page, 14);
        page.addView(text("코어 연결 예정  ·  파일은 기기에 그대로 보관됩니다.", 12, QUIET, false));
    }

    private void drawEntry(LibraryStore.Entry entry) {
        Button item = button("▣    " + entry.name + "     ›", Color.WHITE, INK);
        item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        item.setPadding(dp(16), 0, dp(16), 0);
        item.setContentDescription(entry.name + ", " + entry.console.label + " 파일, 코어 연결 예정");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(60));
        params.bottomMargin = dp(8);
        page.addView(item, params);
        item.setOnClickListener(v -> openEntry(entry));
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
        Context app = getApplicationContext();
        WeakReference<MainActivity> target = new WeakReference<>(this);
        showNotice("파일을 확인하고 있어요…");
        DOCUMENT_IO.execute(() -> {
            ImportResult resultValue = importDocument(app, uri);
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

    private static ImportResult importDocument(Context app, Uri uri) {
        ContentResolver resolver = app.getContentResolver();
        String name = displayName(resolver, uri);
        ConsoleType type = ConsoleType.fromFileName(name);
        if (type == null) return new ImportResult(null, "지원할 파일 형식을 인식하지 못했어요.");
        try {
            try (ParcelFileDescriptor file = resolver.openFileDescriptor(uri, "r")) {
                if (file == null) throw new FileNotFoundException();
            }
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            boolean added = new LibraryStore(app).add(name, uri.toString(), type);
            app.getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(KEY_CONSOLE, type.id).commit();
            return new ImportResult(type, added ? "파일을 목록에 추가했어요. 실행은 코어 연결 후 가능해요." : "이미 가져온 파일이에요.");
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
                    new AlertDialog.Builder(activity).setTitle("코어 연결 예정")
                            .setMessage(entry.name + "\n\n파일을 읽을 수 있어요. 게임 실행 코어는 아직 연결되지 않았습니다. 조작 미리보기에서 버튼 반응만 확인할 수 있어요.")
                            .setPositiveButton("조작 미리보기", (dialog, which) -> { activity.selected = entry.console; activity.preview = true; activity.render(); })
                            .setNegativeButton("닫기", null).show();
                } else {
                    new AlertDialog.Builder(activity).setTitle("파일 접근 불가")
                            .setMessage("파일을 읽을 수 없어요. 이동하거나 접근 권한이 해제되었을 수 있습니다. 목록에서 지울 수 있어요.")
                            .setPositiveButton("목록에서 지우기", (dialog, which) -> {
                                try { activity.library.remove(entry.uri); activity.render(); }
                                catch (RuntimeException failure) { activity.showNotice("파일 목록을 저장할 수 없어요. 다시 시도해 주세요."); }
                            })
                            .setNegativeButton("유지", null).show();
                }
            });
        });
    }

    private void showNotice(String message) { notice = message; render(); }

    private void drawPreview() {
        Button back = button("‹  라이브러리", SHELL, INK);
        back.setContentDescription("라이브러리로 돌아가기");
        page.addView(back, new LinearLayout.LayoutParams(dp(142), dp(48)));
        back.setOnClickListener(v -> { preview = false; render(); });
        gap(page, 22);
        drawHeader("POCKET CONSOLE  /  " + selected.label, "조작 미리보기", "화면 배치와 버튼 입력을 확인하세요.");
        ConsolePreviewView art = new ConsolePreviewView(this, selected);
        page.addView(art, new LinearLayout.LayoutParams(-1, dp(selected == ConsoleType.SWITCH ? 245 : 340)));
        gap(page, 18);
        TextView status = text("코어 연결 예정", 14, ORANGE, true);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(shape(Color.rgb(250, 229, 217), 12));
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
        Button button = button(symbol, INK, Color.WHITE);
        button.setContentDescription(name + " 버튼");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        button.setLayoutParams(params);
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                button.setBackground(shape(ORANGE, 14));
                pressedButtons.add(name);
                updatePressedStatus();
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                button.setBackground(shape(INK, 14));
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
