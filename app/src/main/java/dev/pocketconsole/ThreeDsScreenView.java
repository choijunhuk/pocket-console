package dev.pocketconsole;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.concurrent.atomic.AtomicReference;

final class ThreeDsScreenView extends View {
    interface TouchListener { void changed(int x, int y, boolean down); }

    private final Bitmap bitmap = Bitmap.createBitmap(400, 480, Bitmap.Config.ARGB_8888);
    private final AtomicReference<int[]> newest = new AtomicReference<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect topSource = new Rect(0, 0, 400, 240);
    private final Rect bottomSource = new Rect(40, 240, 360, 480);
    private final RectF top = new RectF();
    private final RectF bottom = new RectF();
    private final TouchListener touch;
    private final ConsoleUi.InputScrollGuard scrollGuard;
    private final int inset;
    private final int hinge;
    private int activePointer = -1;

    ThreeDsScreenView(Context context, ConsoleUi.InputScrollGuard scrollGuard, TouchListener listener) {
        super(context);
        touch = listener;
        this.scrollGuard = scrollGuard;
        float density = getResources().getDisplayMetrics().density;
        inset = Math.round(10 * density);
        hinge = Math.round(12 * density);
        setContentDescription("3DS 위 화면과 터치 가능한 아래 화면");
    }

    void setFrame(int[] pixels) {
        newest.set(pixels.clone());
        postInvalidateOnAnimation();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.min(MeasureSpec.getSize(widthSpec),
                Math.round(400 * getResources().getDisplayMetrics().density));
        int natural = Math.round((width - 2f * inset) * 480 / 400) + 2 * inset + hinge;
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? natural
                : MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightSpec) : Math.min(natural, MeasureSpec.getSize(heightSpec));
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int[] pixels = newest.getAndSet(null);
        if (pixels != null) bitmap.setPixels(pixels, 0, 400, 0, 0, 400, 480);
        paint.setColor(Color.rgb(65, 79, 79));
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), inset * 2, inset * 2, paint);
        float scale = Math.max(0.01f, Math.min((getWidth() - 2f * inset) / 400,
                (getHeight() - 2f * inset - hinge) / 480));
        float screenHeight = 240 * scale;
        float left = (getWidth() - 400 * scale) / 2;
        float y = (getHeight() - 2 * screenHeight - hinge) / 2;
        top.set(left, y, left + 400 * scale, y + screenHeight);
        bottom.set((getWidth() - 320 * scale) / 2, y + screenHeight + hinge,
                (getWidth() + 320 * scale) / 2, y + 2 * screenHeight + hinge);
        paint.setColor(Color.rgb(28, 37, 38));
        canvas.drawRoundRect(top.left - 3, top.top - 3, top.right + 3, top.bottom + 3, 4, 4, paint);
        canvas.drawRoundRect(bottom.left - 3, bottom.top - 3, bottom.right + 3, bottom.bottom + 3, 4, 4, paint);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(bitmap, topSource, top, paint);
        canvas.drawBitmap(bitmap, bottomSource, bottom, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!bottom.contains(event.getX(), event.getY())) return false;
            activePointer = event.getPointerId(0);
            scrollGuard.hold(this);
            update(event.getX(), event.getY());
            return true;
        }
        if (activePointer < 0) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            int index = event.findPointerIndex(activePointer);
            if (index >= 0) update(event.getX(index), event.getY(index));
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP
                || (action == MotionEvent.ACTION_POINTER_UP
                && event.getPointerId(event.getActionIndex()) == activePointer)) {
            activePointer = -1;
            touch.changed(0, 0, false);
            scrollGuard.release(this);
        }
        return true;
    }

    private void update(float x, float y) {
        if (!bottom.contains(x, y)) { touch.changed(0, 0, false); return; }
        touch.changed(Math.min(319, (int) ((x - bottom.left) * 320 / bottom.width())),
                Math.min(239, (int) ((y - bottom.top) * 240 / bottom.height())), true);
    }
}
