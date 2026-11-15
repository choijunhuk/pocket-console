package dev.pocketconsole;

public final class DualScreenLayoutTest {
    public static void main(String[] args) {
        for (int[] size : new int[][] {{390, 780}, {900, 430}, {1200, 1600}, {1600, 900}, {24, 18}}) {
            for (int[] console : new int[][] {{256, 192, 256, 192}, {400, 240, 320, 240}}) {
                for (DualScreenLayout.Mode mode : DualScreenLayout.Mode.values()) {
                    for (boolean swapped : new boolean[] {false, true}) {
                        DualScreenLayout result = DualScreenLayout.calculate(size[0], size[1],
                                console[0], console[1], console[2], console[3], 12, 14,
                                mode, swapped, false);
                        check(result.top, size[0], size[1], console[0], console[1]);
                        check(result.bottom, size[0], size[1], console[2], console[3]);
                        if (mode == DualScreenLayout.Mode.SINGLE) {
                            require(swapped == (result.top == null), "single screen selection");
                            require(swapped != (result.bottom == null), "single secondary hidden");
                        } else {
                            require(result.top != null && result.bottom != null, "both visible");
                            require(!overlap(result.top, result.bottom), "screen overlap");
                            float primary = swapped ? result.bottom.width() : result.top.width();
                            float secondary = swapped ? result.top.width() : result.bottom.width();
                            if (mode == DualScreenLayout.Mode.AUTO)
                                require(Math.abs(result.top.height() / console[1]
                                        - result.bottom.height() / console[3]) < 0.001f, "auto equal scale");
                            if (mode == DualScreenLayout.Mode.LARGE)
                                require(primary * (swapped ? result.bottom.height() : result.top.height())
                                        > secondary * (swapped ? result.top.height() : result.bottom.height()),
                                        "primary larger");
                            boolean horizontal = result.top.right <= result.bottom.left
                                    || result.bottom.right <= result.top.left;
                            require(swapped == (horizontal ? result.bottom.left < result.top.left
                                    : result.bottom.top < result.top.top), "swapped order");
                        }
                    }
                }
            }
        }
        DualScreenLayout borderless = DualScreenLayout.calculate(400, 800, 256, 192,
                256, 192, 12, 14, DualScreenLayout.Mode.SINGLE, false, true);
        require(borderless.top.left == 0 && borderless.top.right == 400, "borderless fills width");
        DualScreenLayout compactDs = DualScreenLayout.calculate(328, 300, 256, 192,
                256, 192, 12, 14, DualScreenLayout.Mode.AUTO, false, false);
        require(compactDs.top.bottom < compactDs.bottom.top && compactDs.top.width() > 170,
                "compact DS viewport stacks at useful scale");
        DualScreenLayout compact3ds = DualScreenLayout.calculate(328, 300, 400, 240,
                320, 240, 10, 12, DualScreenLayout.Mode.AUTO, false, false);
        require(compact3ds.top.bottom < compact3ds.bottom.top && compact3ds.top.width() > 200,
                "compact 3DS viewport stacks at useful scale");
        DualScreenLayout wide3ds = DualScreenLayout.calculate(900, 430, 400, 240,
                320, 240, 10, 12, DualScreenLayout.Mode.AUTO, false, false);
        require(wide3ds.top.right < wide3ds.bottom.left
                && Math.abs(wide3ds.top.width() / wide3ds.bottom.width() - 1.25f) < 0.001f,
                "wide 3DS viewport uses native width proportions");
        System.out.println("PASS: dual screen layout geometry");
    }

    private static void check(DualScreenLayout.Bounds b, int width, int height, int sourceW, int sourceH) {
        if (b == null) return;
        require(b.left >= -0.001f && b.top >= -0.001f && b.right <= width + 0.001f
                && b.bottom <= height + 0.001f, "bounds within viewport");
        require(b.width() >= 0 && b.height() >= 0, "nonnegative bounds");
        if (b.height() > 0) require(Math.abs(b.width() / b.height()
                - (float) sourceW / sourceH) < 0.001f, "native aspect");
    }

    private static boolean overlap(DualScreenLayout.Bounds a, DualScreenLayout.Bounds b) {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
