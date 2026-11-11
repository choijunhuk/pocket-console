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

final class DsScreenView extends View {
    interface TouchListener { void changed(int x, int y, boolean down); }

    private final Bitmap bitmap = Bitmap.createBitmap(256, 384, Bitmap.Config.ARGB_8888);
    private final AtomicReference<int[]> newest = new AtomicReference<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect topSource = new Rect(0, 0, 256, 192);
    private final Rect bottomSource = new Rect(0, 192, 256, 384);
    private final RectF top = new RectF();
    private final RectF bottom = new RectF();
    private final TouchListener touch;
    private final boolean horizontal;
    private final int inset;
    private final int hinge;
    private int activePointer = -1;

    DsScreenView(Context context, boolean horizontal, TouchListener listener) {
        super(context);
        touch = listener;
        this.horizontal = horizontal;
        float density = getResources().getDisplayMetrics().density;
        inset = Math.round(12 * density);
        hinge = Math.round(14 * density);
        setContentDescription("DS 위쪽 화면과 터치 가능한 아래쪽 화면");
    }

    void setFrame(int[] pixels) {
        newest.set(pixels.clone());
        postInvalidateOnAnimation();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.min(MeasureSpec.getSize(widthSpec),
                Math.round(360 * getResources().getDisplayMetrics().density));
        int naturalHeight = horizontal
                ? Math.round(Math.max(1, (width - 2f * inset - hinge) / 2) * 192 / 256) + 2 * inset
                : Math.round(Math.max(1, width - 2f * inset) * 384 / 256) + hinge + 2 * inset;
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? naturalHeight
                : MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightSpec)
                : Math.min(naturalHeight, MeasureSpec.getSize(heightSpec));
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int[] pixels = newest.getAndSet(null);
        if (pixels != null) bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 384);
        paint.setColor(Color.rgb(218, 221, 213));
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), inset, inset, paint);
        float widthLimit = horizontal ? (getWidth() - 2f * inset - hinge) / 2
                : getWidth() - 2f * inset;
        float heightLimit = horizontal ? getHeight() - 2f * inset
                : (getHeight() - 2f * inset - hinge) / 2;
        float displayWidth = Math.max(1, Math.min(widthLimit, heightLimit * 256 / 192));
        float displayHeight = displayWidth * 192 / 256;
        float left = horizontal ? (getWidth() - 2 * displayWidth - hinge) / 2
                : (getWidth() - displayWidth) / 2;
        float y = horizontal ? (getHeight() - displayHeight) / 2
                : (getHeight() - 2 * displayHeight - hinge) / 2;
        top.set(left, y, left + displayWidth, y + displayHeight);
        bottom.set(horizontal ? left + displayWidth + hinge : left,
                horizontal ? y : y + displayHeight + hinge,
                horizontal ? left + 2 * displayWidth + hinge : left + displayWidth,
                horizontal ? y + displayHeight : y + 2 * displayHeight + hinge);
        paint.setColor(Color.rgb(32, 40, 38));
        canvas.drawRect(top, paint);
        canvas.drawRect(bottom, paint);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(bitmap, topSource, top, paint);
        canvas.drawBitmap(bitmap, bottomSource, bottom, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!bottom.contains(event.getX(), event.getY())) return false;
            activePointer = event.getPointerId(0);
            getParent().requestDisallowInterceptTouchEvent(true);
            update(event.getX(), event.getY());
            return true;
        }
        if (activePointer < 0) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            int index = event.findPointerIndex(activePointer);
            if (index >= 0) update(event.getX(index), event.getY(index));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP
                && event.getPointerId(event.getActionIndex()) == activePointer)) {
            activePointer = -1;
            touch.changed(0, 0, false);
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    private void update(float x, float y) {
        if (!bottom.contains(x, y)) {
            touch.changed(0, 0, false);
            return;
        }
        touch.changed(Math.min(255, (int) ((x - bottom.left) * 256 / bottom.width())),
                Math.min(191, (int) ((y - bottom.top) * 192 / bottom.height())), true);
    }
}
