package dev.pocketconsole;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

final class PlayerDisplayUi {
    private final Activity activity;
    private final SharedPreferences preferences;
    private final Runnable rebuild;
    private final Runnable clearInput;
    private final Runnable exit;
    private boolean isFullscreen;
    private boolean controlsVisible;
    private boolean swapped;
    private boolean explicitMode;
    private DualScreenLayout.Mode mode;

    PlayerDisplayUi(Activity activity, String system, Runnable rebuild,
                    Runnable clearInput, Runnable exit) {
        this.activity = activity;
        this.rebuild = rebuild;
        this.clearInput = clearInput;
        this.exit = exit;
        preferences = activity.getSharedPreferences("player-display-" + system, Context.MODE_PRIVATE);
        isFullscreen = preferences.getBoolean("fullscreen", false);
        controlsVisible = preferences.getBoolean("controls", true);
        swapped = preferences.getBoolean("swapped", false);
        explicitMode = preferences.contains("mode");
        mode = explicitMode ? DualScreenLayout.Mode.valueOf(preferences.getString("mode", "AUTO"))
                : isFullscreen ? DualScreenLayout.Mode.LARGE : DualScreenLayout.Mode.AUTO;
    }

    boolean isFullscreen() { return isFullscreen; }
    DualScreenLayout.Mode mode() { return mode; }
    boolean swapped() { return swapped; }

    void toggleFullscreen() {
        clearInput.run();
        isFullscreen = !isFullscreen;
        if (!explicitMode) mode = isFullscreen ? DualScreenLayout.Mode.LARGE : DualScreenLayout.Mode.AUTO;
        preferences.edit().putBoolean("fullscreen", isFullscreen).apply();
        rebuild.run();
    }

    void showMenu(View anchor, java.util.function.BiConsumer<DualScreenLayout.Mode, Boolean> layout) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(isFullscreen ? "전체화면 나가기" : "전체화면").setOnMenuItemClickListener(item -> {
            menu.dismiss();
            activity.getWindow().getDecorView().post(this::toggleFullscreen);
            return true;
        });
        android.view.SubMenu modes = menu.getMenu().addSubMenu("화면 배치");
        for (DualScreenLayout.Mode choice : DualScreenLayout.Mode.values()) {
            String label = switch (choice) {
                case AUTO -> "화면 둘 다";
                case LARGE -> "큰 주 화면 + 보조 화면";
                case SINGLE -> "주 화면만";
            };
            modes.add(label).setCheckable(true).setChecked(mode == choice).setOnMenuItemClickListener(item -> {
                clearInput.run();
                mode = choice;
                explicitMode = true;
                preferences.edit().putString("mode", choice.name()).apply();
                layout.accept(mode, swapped);
                return true;
            });
        }
        menu.getMenu().add("위/아래 화면 바꾸기").setOnMenuItemClickListener(item -> {
            clearInput.run();
            swapped = !swapped;
            preferences.edit().putBoolean("swapped", swapped).apply();
            layout.accept(mode, swapped);
            return true;
        });
        if (isFullscreen) menu.getMenu().add(controlsVisible ? "버튼 숨기기" : "버튼 보이기")
                .setOnMenuItemClickListener(item -> {
                    clearInput.run();
                    controlsVisible = !controlsVisible;
                    preferences.edit().putBoolean("controls", controlsVisible).apply();
                    menu.dismiss();
                    activity.getWindow().getDecorView().post(rebuild);
                    return true;
                });
        menu.getMenu().add("라이브러리로 나가기").setOnMenuItemClickListener(item -> {
            exit.run(); return true;
        });
        menu.show();
    }

    FrameLayout fullscreenView(View screen, View leftControls, View rightControls,
                               View utility, TextView status, Button menuButton) {
        int edge = dp(12);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        root.setMotionEventSplittingEnabled(true);
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setMotionEventSplittingEnabled(true);
        overlay.setPadding(edge, edge, edge, edge);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x99000000);
        updateStatus(status);
        FrameLayout.LayoutParams statusPlacement = new FrameLayout.LayoutParams(-1, -2,
                Gravity.TOP | Gravity.START);
        statusPlacement.rightMargin = dp(84);
        overlay.addView(status, statusPlacement);
        menuButton.setTextColor(Color.WHITE);
        menuButton.setBackgroundColor(0xff26312f);
        overlay.addView(menuButton, new FrameLayout.LayoutParams(dp(76), dp(48),
                Gravity.TOP | Gravity.END));
        if (controlsVisible) {
            leftControls.setMinimumWidth(dp(144));
            rightControls.setMinimumWidth(dp(144));
            if (utility instanceof LinearLayout buttons) {
                for (int index = 0; index < buttons.getChildCount(); index++)
                    buttons.getChildAt(index).setPadding(dp(4), 0, dp(4), 0);
            }
            FrameLayout.LayoutParams left = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.BOTTOM | Gravity.START);
            left.bottomMargin = dp(52);
            overlay.addView(leftControls, left);
            FrameLayout.LayoutParams right = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.BOTTOM | Gravity.END);
            right.bottomMargin = dp(52);
            overlay.addView(rightControls, right);
            overlay.addView(utility, new FrameLayout.LayoutParams(dp(256), dp(48),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = 0, topInset = 0, right = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.mandatorySystemGestures());
                left = safe.left; topInset = safe.top; right = safe.right; bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); topInset = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                    topInset = Math.max(topInset, insets.getDisplayCutout().getSafeInsetTop());
                    right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                    bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
                }
            }
            overlay.setPadding(Math.max(edge, left), Math.max(edge, topInset),
                    Math.max(edge, right), Math.max(edge, bottom));
            return insets;
        });
        root.requestApplyInsets();
        return root;
    }

    void updateStatus(TextView status) {
        status.setVisibility(isFullscreen && ("DS 실행 중".contentEquals(status.getText())
                || "3DS 실행 중".contentEquals(status.getText())) ? View.GONE : View.VISIBLE);
    }

    void applyBars(ConsoleUi ui) {
        if (!isFullscreen) {
            activity.getWindow().getDecorView().setSystemUiVisibility(0);
            ui.applySystemBars(activity.getWindow());
            if (Build.VERSION.SDK_INT >= 30 && activity.getWindow().getInsetsController() != null)
                activity.getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
            return;
        }
        activity.getWindow().setStatusBarColor(Color.BLACK);
        activity.getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(0, lightBars);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
