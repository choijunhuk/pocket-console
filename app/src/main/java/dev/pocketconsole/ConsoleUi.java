package dev.pocketconsole;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.ScrollView;

import java.util.HashSet;
import java.util.Set;

final class ConsoleUi {
    final int paper;
    final int ink;
    final int quiet;
    final int shell;
    final int raised;
    final int accent;
    final int onAccent;
    final int notice;
    final boolean dark;
    private final float density;

    ConsoleUi(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        paper = dark ? 0xff171e1d : 0xfff3f0e8;
        ink = dark ? 0xfff3f0e8 : 0xff202826;
        quiet = dark ? 0xffbac6bf : 0xff58655e;
        shell = dark ? 0xff33403d : 0xffdaddd5;
        raised = dark ? 0xff26312f : Color.WHITE;
        accent = dark ? 0xffef9b79 : 0xffa34d31;
        onAccent = dark ? 0xff202826 : Color.WHITE;
        notice = dark ? 0xff49362f : 0xfffae5d9;
    }

    GradientDrawable shape(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * density);
        return drawable;
    }

    RippleDrawable buttonSurface(int color, int radiusDp) {
        StateListDrawable states = new StateListDrawable();
        int pressed = color == accent ? (dark ? 0xffd98565 : 0xff863a25)
                : color == ink ? (dark ? 0xffd2d9d0 : 0xff455651)
                : (dark ? 0xff52625e : 0xffb9c1b7);
        states.addState(new int[]{android.R.attr.state_pressed}, shape(pressed, radiusDp));
        GradientDrawable focused = shape(color, radiusDp);
        focused.setStroke(Math.max(2, Math.round(2 * density)),
                color == accent ? onAccent : color == ink ? paper : ink);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, shape(color, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x66ffffff : 0x33202826), states, null);
    }

    void applyBars(Activity activity) {
        activity.getWindow().setStatusBarColor(paper);
        activity.getWindow().setNavigationBarColor(paper);
        activity.getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    static final class InputScrollGuard {
        private final ScrollView scroll;
        private final Set<View> holders = new HashSet<>();

        InputScrollGuard(ScrollView scroll) { this.scroll = scroll; }

        void hold(View view) {
            if (holders.add(view) && holders.size() == 1) scroll.requestDisallowInterceptTouchEvent(true);
        }

        void release(View view) {
            if (holders.remove(view) && holders.isEmpty()) scroll.requestDisallowInterceptTouchEvent(false);
        }

        void reset() {
            holders.clear();
            scroll.requestDisallowInterceptTouchEvent(false);
        }
    }
}
