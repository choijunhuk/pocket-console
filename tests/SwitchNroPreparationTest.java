package dev.pocketconsole;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class SwitchNroPreparationTest {
    public static void main(String[] args) throws Exception {
        PreparationProgress progress = new PreparationProgress();
        SwitchNroPreparation copy = new SwitchNroPreparation();
        byte[] original = new byte[]{1, 2, 3};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy.copy(new ByteArrayInputStream(original), output, progress);
        if (!java.util.Arrays.equals(original, output.toByteArray())) throw new AssertionError("bytes");
        copy.cancel();
        rejected(() -> copy.copy(new ByteArrayInputStream(original), output, progress), "취소");

        SwitchNroPreparation during = new SwitchNroPreparation();
        InputStream zeroThenCancel = new InputStream() {
            @Override public int read(byte[] b) { during.cancel(); return 0; }
            @Override public int read() { return 1; }
        };
        rejected(() -> during.copy(zeroThenCancel, output, progress), "취소");

        SwitchNroPreparation limited = new SwitchNroPreparation();
        InputStream oversized = new InputStream() {
            long remaining = 2L * 1024 * 1024 * 1024 + 1;
            @Override public int read(byte[] b) {
                if (remaining == 0) return -1;
                int count = (int) Math.min(b.length, remaining);
                remaining -= count;
                return count;
            }
            @Override public int read() { throw new AssertionError("single read"); }
        };
        rejected(() -> limited.copy(oversized, OutputStream.nullOutputStream(), progress), "2 GiB");
        System.out.println("SWITCH_NRO_COPY_PASS bytes=true cancel_before=true cancel_during=true limit=true");
    }

    interface Checked { void run() throws Exception; }
    static void rejected(Checked action, String reason) throws Exception {
        try { action.run(); throw new AssertionError("accepted " + reason); }
        catch (IOException expected) {
            if (!expected.getMessage().contains(reason)) throw new AssertionError(expected);
        }
    }
}
