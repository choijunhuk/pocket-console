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
    private final ConsoleUi ui;
    private final ConsoleUi.InputScrollGuard scrollGuard;
    private final boolean horizontal;
    private final int inset;
    private final int hinge;
    private int activePointer = -1;
    private DualScreenLayout.Mode layoutMode = DualScreenLayout.Mode.AUTO;
    private boolean swapped;
    private boolean borderless;

    DsScreenView(Context context, boolean horizontal, ConsoleUi.InputScrollGuard scrollGuard,
                 TouchListener listener) {
        super(context);
        touch = listener;
        this.scrollGuard = scrollGuard;
        ui = new ConsoleUi(context);
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

    void setLayoutMode(DualScreenLayout.Mode mode, boolean swapped, boolean borderless) {
        if (layoutMode == mode && this.swapped == swapped && this.borderless == borderless) return;
        releaseTouch();
        layoutMode = mode;
        this.swapped = swapped;
        this.borderless = borderless;
        updateBounds();
        requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? 2 * 256 + hinge + 2 * inset
                : MeasureSpec.getSize(widthSpec);
        int naturalHeight = horizontal ? 192 + 2 * inset : 384 + hinge + 2 * inset;
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? naturalHeight
                : MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        releaseTouch();
        updateBounds();
    }

    @Override protected void onDetachedFromWindow() {
        releaseTouch();
        super.onDetachedFromWindow();
    }

    private void updateBounds() {
        DualScreenLayout layout = DualScreenLayout.calculate(getWidth(), getHeight(),
                256, 192, 256, 192, inset, hinge, layoutMode, swapped, borderless);
        if (layout.top == null) top.setEmpty();
        else top.set(layout.top.left, layout.top.top, layout.top.right, layout.top.bottom);
        if (layout.bottom == null) bottom.setEmpty();
        else bottom.set(layout.bottom.left, layout.bottom.top, layout.bottom.right, layout.bottom.bottom);
    }

    private void releaseTouch() {
        if (activePointer < 0) return;
        activePointer = -1;
        touch.changed(0, 0, false);
        scrollGuard.release(this);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int[] pixels = newest.getAndSet(null);
        if (pixels != null) bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 384);
        if (borderless) canvas.drawColor(Color.BLACK);
        else {
            paint.setColor(ui.shell);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), inset, inset, paint);
        }
        paint.setColor(0xff1c2526);
        if (!borderless) {
            if (!top.isEmpty()) canvas.drawRoundRect(top.left - 3, top.top - 3, top.right + 3, top.bottom + 3, 4, 4, paint);
            if (!bottom.isEmpty()) canvas.drawRoundRect(bottom.left - 3, bottom.top - 3, bottom.right + 3, bottom.bottom + 3, 4, 4, paint);
            if (!top.isEmpty() && !bottom.isEmpty()) {
                paint.setColor(ui.quiet);
                if (top.right <= bottom.left || bottom.right <= top.left) {
                    float seam = (top.right + bottom.left) / 2;
                    if (swapped) seam = (bottom.right + top.left) / 2;
                    canvas.drawRoundRect(seam - 1, inset, seam + 1, getHeight() - inset, 1, 1, paint);
                } else {
                    float seam = (top.bottom + bottom.top) / 2;
                    if (swapped) seam = (bottom.bottom + top.top) / 2;
                    canvas.drawRoundRect(inset, seam - 1, getWidth() - inset, seam + 1, 1, 1, paint);
                }
            }
        }
        paint.setColor(Color.WHITE);
        if (!top.isEmpty()) canvas.drawBitmap(bitmap, topSource, top, paint);
        if (!bottom.isEmpty()) canvas.drawBitmap(bitmap, bottomSource, bottom, paint);
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
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP
                && event.getPointerId(event.getActionIndex()) == activePointer)) {
            releaseTouch();
        }
        return true;
    }

    private void update(float x, float y) {
        if (!bottom.contains(x, y)) {
            touch.changed(0, 0, false);
            return;
        }
        touch.changed(Math.max(0, Math.min(255, (int) ((x - bottom.left) * 256 / bottom.width()))),
                Math.max(0, Math.min(191, (int) ((y - bottom.top) * 192 / bottom.height()))), true);
    }
}
