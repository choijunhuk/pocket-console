package dev.pocketconsole;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Original, non-interactive hardware artwork. Real controls are native Buttons in MainActivity. */
public final class ConsolePreviewView extends View {
    private static final int INK = Color.rgb(32, 40, 38);
    private static final int LCD = Color.rgb(200, 214, 163);
    private final ConsoleType console;
    private final ConsoleUi ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ConsolePreviewView(Context context, ConsoleType console) {
        super(context);
        this.console = console;
        ui = new ConsoleUi(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float designHeight = console == ConsoleType.SWITCH || console == ConsoleType.SWITCH_TWO ? 220
                : console == ConsoleType.DS ? 330 : 310;
        float scale = Math.min(getWidth() / 360f, getHeight() / designHeight);
        canvas.save();
        canvas.translate((getWidth() - 360 * scale) / 2, (getHeight() - designHeight * scale) / 2);
        canvas.scale(scale, scale);
        if (console == ConsoleType.DS) drawDs(canvas);
        else if (console == ConsoleType.THREE_DS) draw3ds(canvas);
        else drawSwitch(canvas);
        canvas.restore();
    }

    private void round(Canvas canvas, float left, float top, float right, float bottom, float radius, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(left, top, right, bottom), radius, radius, paint);
    }

    private void circle(Canvas canvas, float x, float y, float radius, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x, y, radius, paint);
    }

    private void label(Canvas canvas, String text, float x, float y, int size, int color) {
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(text, x, y, paint);
    }

    private void screen(Canvas canvas, float left, float top, float right, float bottom, String text) {
        round(canvas, left - 6, top - 6, right + 6, bottom + 6, 9, INK);
        round(canvas, left, top, right, bottom, 5, LCD);
        paint.setColor(0x2A405044);
        paint.setStrokeWidth(1);
        for (int i = 1; i < 5; i++) {
            float y = top + i * (bottom - top) / 5;
            canvas.drawLine(left + 10, y, right - 10, y, paint);
        }
        label(canvas, text, left + 10, top + 20, 10, INK);
        label(canvas, "LAYOUT PREVIEW", left + 10, bottom - 10, 9, 0xFF51664A);
    }

    private void cross(Canvas canvas, float x, float y) {
        round(canvas, x - 7, y - 19, x + 7, y + 19, 3, INK);
        round(canvas, x - 19, y - 7, x + 19, y + 7, 3, INK);
        circle(canvas, x, y, 4, 0xFF66716C);
    }

    private void faceButtons(Canvas canvas, float x, float y) {
        circle(canvas, x, y - 16, 8, INK);
        circle(canvas, x - 16, y, 8, INK);
        circle(canvas, x + 16, y, 8, INK);
        circle(canvas, x, y + 16, 8, INK);
    }

    private void speaker(Canvas canvas, float x, float y) {
        for (int i = 0; i < 5; i++) circle(canvas, x + i * 5, y, 1.5f, 0xFF78817B);
    }

    private void drawDs(Canvas canvas) {
        int body = 0xFFDADDD5;
        round(canvas, 50, 4, 310, 137, 20, 0xFF9CA69E);
        round(canvas, 53, 4, 307, 132, 18, body);
        screen(canvas, 108, 16, 252, 124, "DS / UPPER");
        speaker(canvas, 61, 66);
        speaker(canvas, 278, 66);
        round(canvas, 52, 132, 308, 155, 8, 0xFF9CA69E);
        round(canvas, 57, 143, 303, 152, 4, 0xFFC5CAC1);
        round(canvas, 50, 155, 310, 328, 22, 0xFF9CA69E);
        round(canvas, 53, 155, 307, 324, 20, body);
        screen(canvas, 108, 164, 252, 272, "DS / TOUCH");
        cross(canvas, 72, 296);
        faceButtons(canvas, 288, 296);
        round(canvas, 167, 313, 181, 316, 2, 0xFF78817B);
        round(canvas, 185, 313, 199, 316, 2, 0xFF78817B);
    }

    private void draw3ds(Canvas canvas) {
        round(canvas, 20, 4, 340, 145, 21, 0xFF485758);
        round(canvas, 24, 6, 336, 139, 18, 0xFF536263);
        screen(canvas, 80, 12, 280, 132, "3DS / WIDE");
        circle(canvas, 180, 13, 2, 0xFF1E2928);
        circle(canvas, 169, 13, 2, 0xFF1E2928);
        circle(canvas, 191, 13, 2, 0xFF1E2928);
        round(canvas, 47, 140, 313, 157, 8, 0xFF344345);
        round(canvas, 36, 155, 324, 304, 24, 0xFF344345);
        round(canvas, 40, 155, 320, 299, 20, 0xFF536263);
        screen(canvas, 100, 170, 260, 290, "TOUCH");
        circle(canvas, 72, 205, 23, 0xFF263435);
        circle(canvas, 72, 205, 18, 0xFF879593);
        cross(canvas, 73, 267);
        faceButtons(canvas, 286, 230);
        speaker(canvas, 271, 277);
    }

    private void drawSwitch(Canvas canvas) {
        round(canvas, 8, 26, 352, 195, 32, INK);
        round(canvas, 8, 26, 73, 195, 30, console == ConsoleType.SWITCH_TWO ? 0xFF6B8589 : 0xFF4C8A9B);
        round(canvas, 287, 26, 352, 195, 30, console == ConsoleType.SWITCH_TWO ? 0xFF9E7468 : 0xFFCD7851);
        round(canvas, 68, 31, 292, 190, 7, 0xFF151F20);
        screen(canvas, 79, 44, 281, 177,
                console == ConsoleType.SWITCH_TWO ? "SWITCH 2 / DISPLAY" : "SWITCH / DISPLAY");
        circle(canvas, 40, 78, 18, 0xFF263435);
        circle(canvas, 40, 78, 12, 0xFF728F92);
        cross(canvas, 40, 143);
        faceButtons(canvas, 322, 78);
        circle(canvas, 322, 146, 18, 0xFF263435);
        circle(canvas, 322, 146, 12, 0xFFAF8A79);
        label(canvas, console == ConsoleType.SWITCH_TWO ? "SWITCH 2 / PREVIEW" : "SWITCH / PREVIEW",
                94, 212, 10, ui.quiet);
    }
}
