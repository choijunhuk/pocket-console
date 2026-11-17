package dev.pocketconsole;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;

public final class DsRomCacheTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("ds-cache-test").toFile();
        byte[] first = rom((byte) 1);
        byte[] second = rom((byte) 2);
        PreparationProgress progress = new PreparationProgress();
        File a = DsRomCache.copy(new ByteArrayInputStream(first), dir, first.length, progress);
        check(progress.message().contains("파일 검사"), "new file validation stage missing");
        File b = DsRomCache.copy(new ByteArrayInputStream(second), dir);
        check(!a.equals(b), "different bytes with same display name need different paths");
        check(a.equals(DsRomCache.copy(new ByteArrayInputStream(first), dir, first.length, progress)), "same bytes must dedupe");
        check(progress.message().contains("기존 캐시 확인 100%"), "existing cache verification progress missing");
        check(Arrays.equals(first, Files.readAllBytes(a.toPath())), "cached bytes changed");
        PreparationProgress copying = new PreparationProgress();
        File observed = DsRomCache.copy(new ByteArrayInputStream(first) {
            private boolean checked;
            @Override public synchronized int read(byte[] bytes, int offset, int size) {
                if (checked && available() == 512) {
                    check(copying.message().contains("파일 복사 50%"), "mid-copy progress missing");
                    checked = false;
                }
                int count = super.read(bytes, offset, Math.min(size, 512));
                if (count > 0) checked = true;
                return count;
            }
        }, dir, first.length, copying);
        check(a.equals(observed), "observed copy did not dedupe");
        byte[] corrupted = first.clone();
        corrupted[0] = 99;
        Files.write(a.toPath(), corrupted);
        check(a.equals(DsRomCache.copy(new ByteArrayInputStream(first), dir)), "corrupt cached content should be repaired");
        check(Arrays.equals(first, Files.readAllBytes(a.toPath())), "repaired cached content differs");
        byte[] bad = first.clone();
        bad[0x2c] = 0;
        bad[0x2d] = 0;
        bad[0x2e] = 0;
        bad[0x2f] = 0;
        bad[0x3c] = (byte) 0xff;
        expectFailure(new ByteArrayInputStream(new byte[511]), dir);
        try { DsRomCache.copy(new ByteArrayInputStream(new byte[511]), dir, 511, progress);
            throw new AssertionError("invalid progress copy was accepted"); }
        catch (IOException expected) { checks++; }
        check(progress.message().contains("파일 검사"), "failed copy did not reach validation");
        expectFailure(new ByteArrayInputStream(bad), dir);
        byte[] edge = first.clone();
        edge[0x20] = (byte) 0xff;
        edge[0x21] = (byte) 0xff;
        edge[0x22] = (byte) 0xff;
        edge[0x23] = (byte) 0xff;
        expectFailure(new ByteArrayInputStream(edge), dir);
        byte[] unalignedOffset = new byte[32768];
        System.arraycopy(first, 0, unalignedOffset, 0, first.length);
        put32(unalignedOffset, 0x30, 32767);
        put32(unalignedOffset, 0x3c, 1);
        expectFailure(new ByteArrayInputStream(unalignedOffset), dir);
        byte[] unalignedWordOffset = first.clone();
        put32(unalignedWordOffset, 0x30, 641);
        expectFailure(new ByteArrayInputStream(unalignedWordOffset), dir);
        byte[] partialTail = Arrays.copyOf(first, 1023);
        put32(partialTail, 0x30, 1020);
        put32(partialTail, 0x3c, 3);
        expectFailure(new ByteArrayInputStream(partialTail), dir);
        expectFailure(new InputStream() {
            private int count;
            @Override public int read() throws IOException {
                if (count++ == 300) throw new IOException("provider failed");
                return 0;
            }
        }, dir);
        check(dir.listFiles().length == 2, "failed copies must leave no file");
        check(Arrays.equals(first, Files.readAllBytes(a.toPath())), "failed copies must preserve cached ROM");
        try {
            Thread.currentThread().interrupt();
            try { DsRomCache.copy(new ByteArrayInputStream(first), dir);
                throw new AssertionError("interrupted copy was accepted"); }
            catch (InterruptedIOException expected) { checks++; }
        } finally { Thread.interrupted(); }
        check(dir.listFiles().length == 2, "interrupted copy left a temporary file");
        try {
            try { DsRomCache.copy(new ByteArrayInputStream(first) {
                private boolean interrupted;
                @Override public synchronized int read(byte[] bytes, int offset, int size) {
                    int count = super.read(bytes, offset, size);
                    if (count > 0 && !interrupted) {
                        interrupted = true;
                        Thread.currentThread().interrupt();
                    }
                    return count;
                }
            }, dir, first.length, progress); throw new AssertionError("midstream interruption was accepted"); }
            catch (InterruptedIOException expected) { checks++; }
        } finally { Thread.interrupted(); }
        check(dir.listFiles().length == 2, "midstream interruption left a temporary file");
        check(Arrays.equals(first, Files.readAllBytes(a.toPath())), "interrupted copy replaced valid cache");
        Method existing = DsRomCache.class.getDeclaredMethod("valid", File.class, long.class, String.class);
        existing.setAccessible(true);
        try {
            Thread.currentThread().interrupt();
            try {
                existing.invoke(null, a, (long) first.length, a.getName().substring(0, 64));
                throw new AssertionError("interrupted existing-cache hash was accepted");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof InterruptedIOException,
                        "existing-cache interruption was swallowed");
            }
        } finally { Thread.interrupted(); }
        System.out.println("PASS: " + checks + " DS ROM cache checks");
    }

    private static byte[] rom(byte marker) {
        byte[] bytes = new byte[1024];
        bytes[0] = marker;
        put32(bytes, 0x20, 512); put32(bytes, 0x2c, 128);
        put32(bytes, 0x30, 640); put32(bytes, 0x3c, 128);
        return bytes;
    }

    private static void put32(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (8 * i));
    }

    private static void expectFailure(InputStream source, File dir) throws Exception {
        try (source) {
            try { DsRomCache.copy(source, dir); throw new AssertionError("invalid ROM was accepted"); }
            catch (IOException expected) { checks++; }
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
