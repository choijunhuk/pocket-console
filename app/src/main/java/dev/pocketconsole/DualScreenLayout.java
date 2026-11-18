package dev.pocketconsole;

final class DualScreenLayout {
    enum Mode { AUTO, LARGE, SINGLE }

    static final class Bounds {
        final float left, top, right, bottom;

        Bounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float width() { return right - left; }
        float height() { return bottom - top; }
    }

    final Bounds top;
    final Bounds bottom;

    private DualScreenLayout(Bounds top, Bounds bottom) {
        this.top = top;
        this.bottom = bottom;
    }

    static DualScreenLayout calculate(int width, int height, int topWidth, int topHeight,
                                      int bottomWidth, int bottomHeight, int inset, int hinge,
                                      Mode mode, boolean swapped, boolean borderless) {
        float w = Math.max(0, width);
        float h = Math.max(0, height);
        float pad = borderless ? 0 : Math.min(inset, Math.min(w, h) / 8);
        float left = pad, upper = pad, availableW = Math.max(0, w - 2 * pad);
        float availableH = Math.max(0, h - 2 * pad);
        int primaryW = swapped ? bottomWidth : topWidth;
        int primaryH = swapped ? bottomHeight : topHeight;
        if (mode == Mode.SINGLE) {
            Bounds primary = fit(left, upper, availableW, availableH, primaryW, primaryH);
            return swapped ? new DualScreenLayout(null, primary)
                    : new DualScreenLayout(primary, null);
        }

        int secondaryW = swapped ? topWidth : bottomWidth;
        int secondaryH = swapped ? topHeight : bottomHeight;
        if (mode == Mode.AUTO) {
            float horizontalGap = borderless ? 0 : Math.min(hinge, availableW / 10);
            float verticalGap = borderless ? 0 : Math.min(hinge, availableH / 10);
            float horizontalScale = Math.max(0, Math.min(
                    (availableW - horizontalGap) / (topWidth + bottomWidth),
                    availableH / Math.max(topHeight, bottomHeight)));
            float verticalScale = Math.max(0, Math.min(
                    availableW / Math.max(topWidth, bottomWidth),
                    (availableH - verticalGap) / (topHeight + bottomHeight)));
            boolean horizontal = horizontalScale > verticalScale
                    || (horizontalScale == verticalScale && w > h);
            float scale = horizontal ? horizontalScale : verticalScale;
            Bounds primary, secondary;
            if (horizontal) {
                float x = left + (availableW - (topWidth + bottomWidth) * scale - horizontalGap) / 2;
                primary = fitAtScale(x, upper, primaryW * scale, availableH, primaryW, primaryH, scale);
                secondary = fitAtScale(x + primaryW * scale + horizontalGap, upper,
                        secondaryW * scale, availableH, secondaryW, secondaryH, scale);
            } else {
                float y = upper + (availableH - (topHeight + bottomHeight) * scale - verticalGap) / 2;
                primary = fitAtScale(left, y, availableW, primaryH * scale, primaryW, primaryH, scale);
                secondary = fitAtScale(left, y + primaryH * scale + verticalGap,
                        availableW, secondaryH * scale, secondaryW, secondaryH, scale);
            }
            return swapped ? new DualScreenLayout(secondary, primary)
                    : new DualScreenLayout(primary, secondary);
        }

        boolean sideBySide = w > h;
        float gap = borderless ? 0 : Math.min(hinge,
                (sideBySide ? availableW : availableH) / 10);
        float span = Math.max(0, (sideBySide ? availableW : availableH) - gap);
        float primarySpan = mode == Mode.LARGE ? span * 0.75f : span / 2;
        float secondarySpan = span - primarySpan;
        Bounds primary, secondary;
        if (sideBySide) {
            primary = fit(left, upper, primarySpan, availableH, primaryW, primaryH);
            secondary = fit(left + primarySpan + gap, upper, secondarySpan, availableH,
                    secondaryW, secondaryH);
        } else {
            primary = fit(left, upper, availableW, primarySpan, primaryW, primaryH);
            secondary = fit(left, upper + primarySpan + gap, availableW, secondarySpan,
                    secondaryW, secondaryH);
        }
        return swapped ? new DualScreenLayout(secondary, primary)
                : new DualScreenLayout(primary, secondary);
    }

    private static Bounds fit(float x, float y, float w, float h, int sourceW, int sourceH) {
        float scale = Math.max(0, Math.min(w / sourceW, h / sourceH));
        return fitAtScale(x, y, w, h, sourceW, sourceH, scale);
    }

    private static Bounds fitAtScale(float x, float y, float w, float h,
                                     int sourceW, int sourceH, float scale) {
        float drawW = sourceW * scale, drawH = sourceH * scale;
        float drawX = x + (w - drawW) / 2, drawY = y + (h - drawH) / 2;
        return new Bounds(drawX, drawY, drawX + drawW, drawY + drawH);
    }
}
