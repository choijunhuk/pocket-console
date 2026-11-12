package org.yuzu.yuzu_emu.applets.keyboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.yuzu.yuzu_emu.NativeLibrary;

/** Plain Android text entry for the pinned native software-keyboard applet. */
public final class SoftwareKeyboard {
    public static final class KeyboardConfig {
        public String ok_text, header_text, sub_text, guide_text, initial_text;
        public short left_optional_symbol_key, right_optional_symbol_key;
        public int max_text_length, min_text_length, initial_cursor_position;
        public int type, password_mode, text_draw_type, key_disable_flags;
        public boolean use_blur_background, enable_backspace_button;
        public boolean enable_return_button, disable_cancel_button;
    }

    public static final class KeyboardData {
        public int result;
        public String text;

        KeyboardData(int result, String text) {
            this.result = result;
            this.text = text;
        }
    }

    static final class Pending {
        final Object owner;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile KeyboardData answer = new KeyboardData(1, "");

        Pending(Object owner) { this.owner = owner; }

        boolean mayShow() { return !done.get(); }

        boolean complete(int result, String text) {
            if (!done.compareAndSet(false, true)) return false;
            answer = new KeyboardData(result, text);
            finished.countDown();
            return true;
        }

        boolean cancel() { return complete(1, ""); }

        KeyboardData await() throws InterruptedException {
            finished.await();
            return answer;
        }
    }

    private static final class Showing {
        final Object owner;
        final AlertDialog dialog;

        Showing(Object owner, AlertDialog dialog) {
            this.owner = owner;
            this.dialog = dialog;
        }
    }

    static final AtomicReference<Pending> activeRequest = new AtomicReference<>();
    private static final AtomicReference<Showing> activeDialog = new AtomicReference<>();
    private SoftwareKeyboard() {}

    public static KeyboardData executeNormal(KeyboardConfig config) {
        NativeLibrary.Listener owner = NativeLibrary.activeListener();
        KeyboardData cancelled = new KeyboardData(1, "");
        if (owner == null || owner.isEnding() || !(owner.context() instanceof Activity))
            return cancelled;
        Activity activity = (Activity) owner.context();
        if (activity.isFinishing() || activity.isDestroyed()
                || Looper.myLooper() == Looper.getMainLooper()) return cancelled;
        if (config.min_text_length < 0 || config.max_text_length < 0
                || (config.max_text_length > 0
                    && config.min_text_length > config.max_text_length)) {
            NativeLibrary.unsupportedApplet("잘못된 게임 키보드 요청");
            return cancelled;
        }

        Pending pending = new Pending(owner);
        Pending previous = activeRequest.getAndSet(pending);
        if (previous != null) previous.cancel();
        Showing previousDialog = activeDialog.getAndSet(null);
        if (previousDialog != null) activity.runOnUiThread(previousDialog.dialog::dismiss);
        if (owner.isEnding()) pending.cancel();
        activity.runOnUiThread(() -> {
            if (activeRequest.get() != pending || !pending.mayShow() || owner.isEnding()
                    || activity.isFinishing() || activity.isDestroyed()) {
                pending.cancel();
                return;
            }
            int maximum = config.max_text_length > 0
                    ? Math.min(config.max_text_length, 4096) : 4096;
            if (config.min_text_length > maximum) {
                NativeLibrary.unsupportedApplet("게임 키보드 입력 길이 제한");
                pending.cancel();
                return;
            }
            EditText input = new EditText(activity);
            input.setSingleLine(true);
            input.setInputType(config.type == 1 ? InputType.TYPE_CLASS_NUMBER
                    : config.password_mode == 1
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    : InputType.TYPE_CLASS_TEXT);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maximum)});
            input.setText(config.initial_text == null ? "" : config.initial_text);
            input.setSelection(Math.min(Math.max(0, config.initial_cursor_position),
                    input.length()));

            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(config.header_text == null || config.header_text.isEmpty()
                            ? "글자 입력" : config.header_text)
                    .setMessage(config.guide_text == null ? "" : config.guide_text)
                    .setView(input)
                    .setPositiveButton(config.ok_text == null || config.ok_text.isEmpty()
                            ? "확인" : config.ok_text, null);
            if (config.disable_cancel_button) {
                builder.setNeutralButton("게임 종료", (dialog, which) -> {
                    if (pending.cancel())
                        NativeLibrary.unsupportedApplet("사용자가 게임을 종료했어요");
                });
            } else {
                builder.setNegativeButton("취소", (dialog, which) -> pending.cancel());
            }
            AlertDialog dialog = builder.create();
            dialog.setCancelable(!config.disable_cancel_button);
            dialog.setOnCancelListener(ignored -> pending.cancel());
            dialog.setOnDismissListener(ignored -> {
                pending.cancel();
                Showing showing = activeDialog.get();
                if (showing != null && showing.dialog == dialog)
                    activeDialog.compareAndSet(showing, null);
            });
            dialog.setOnShowListener(ignored ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                        String text = input.getText().toString();
                        if (text.length() < config.min_text_length) {
                            input.setError("최소 " + config.min_text_length + "자");
                            return;
                        }
                        pending.complete(0, text);
                        dialog.dismiss();
                    }));
            Showing showing = new Showing(owner, dialog);
            activeDialog.set(showing);
            if (activeRequest.get() != pending || !pending.mayShow() || owner.isEnding()) {
                activeDialog.compareAndSet(showing, null);
                pending.cancel();
                return;
            }
            dialog.show();
        });
        try {
            return pending.await();
        } catch (InterruptedException interrupted) {
            pending.cancel();
            Thread.currentThread().interrupt();
            return cancelled;
        } finally {
            activeRequest.compareAndSet(pending, null);
        }
    }

    public static void executeInline(KeyboardConfig config) {
        NativeLibrary.unsupportedApplet("이 게임의 화면 내 키보드는 아직 지원되지 않아요");
    }

    public static void cancelOutstanding(Object owner) {
        Pending pending = activeRequest.get();
        if (pending != null && pending.owner == owner
                && activeRequest.compareAndSet(pending, null)) pending.cancel();
        Showing showing = activeDialog.get();
        if (showing == null || showing.owner != owner
                || !activeDialog.compareAndSet(showing, null)) return;
        if (Looper.myLooper() == Looper.getMainLooper()) showing.dialog.dismiss();
        else new Handler(Looper.getMainLooper()).post(showing.dialog::dismiss);
    }
}
