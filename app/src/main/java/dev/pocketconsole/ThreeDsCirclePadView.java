package dev.pocketconsole;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

final class ThreeDsCirclePadView extends View {
    interface AxisListener { void changed(int x, int y); }

    private final AxisListener axes;
    private final ConsoleUi.InputScrollGuard scrollGuard;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float x;
    private float y;
    private int pointer = -1;

    ThreeDsCirclePadView(Context context, ConsoleUi.InputScrollGuard scrollGuard, AxisListener axes) {
        super(context);
        this.axes = axes;
        this.scrollGuard = scrollGuard;
        setContentDescription("3DS 원형 슬라이드 패드");
        setFocusable(true);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) * .45f;
        paint.setColor(Color.rgb(45, 59, 59));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(Color.rgb(133, 150, 146));
        canvas.drawCircle(cx + x * radius * .42f, cy + y * radius * .42f, radius * .55f, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pointer = event.getPointerId(0);
            scrollGuard.hold(this);
            update(event.getX(), event.getY());
            return true;
        }
        if (pointer < 0) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            int index = event.findPointerIndex(pointer);
            if (index >= 0) update(event.getX(index), event.getY(index));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP
                && event.getPointerId(event.getActionIndex()) == pointer)) {
            pointer = -1;
            x = y = 0;
            axes.changed(0, 0);
            scrollGuard.release(this);
            invalidate();
            if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void update(float atX, float atY) {
        float radius = Math.max(1, Math.min(getWidth(), getHeight()) * .45f);
        float dx = (atX - getWidth() / 2f) / radius;
        float dy = (atY - getHeight() / 2f) / radius;
        float length = (float) Math.hypot(dx, dy);
        if (length > 1) { dx /= length; dy /= length; }
        x = dx; y = dy;
        axes.changed(Math.round(dx * 32767), Math.round(dy * 32767));
        invalidate();
    }
}
