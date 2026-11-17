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

public final class ThreeDsRomCacheTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("3ds-cache-test").toFile();
        byte[] x = threeDsx();
        byte[] elf = elf();
        byte[] ncch = ncch();
        byte[] ncsd = ncsd();
        PreparationProgress progress = new PreparationProgress();
        File xFile = ThreeDsRomCache.copy(new ByteArrayInputStream(x), dir, x.length, progress);
        check(progress.message().contains("파일 검사"), "new file validation stage missing");
        check(Arrays.equals(x, Files.readAllBytes(xFile.toPath())), "cached bytes changed");
        PreparationProgress copying = new PreparationProgress();
        File observed = ThreeDsRomCache.copy(new ByteArrayInputStream(x) {
            private boolean checked;
            @Override public synchronized int read(byte[] bytes, int offset, int size) {
                if (checked && available() == 64) {
                    check(copying.message().contains("파일 복사 50%"), "mid-copy progress missing");
                    checked = false;
                }
                int count = super.read(bytes, offset, Math.min(size, 64));
                if (count > 0) checked = true;
                return count;
            }
        }, dir, x.length, copying);
        check(xFile.equals(observed), "observed copy did not dedupe");
        copy(elf, dir, ".elf");
        copy(ncch, dir, ".cxi");
        copy(ncsd, dir, ".cci");
        check(xFile.equals(copy(x, dir, ".3dsx")), "same bytes must dedupe");
        check(xFile.equals(ThreeDsRomCache.copy(new ByteArrayInputStream(x), dir, x.length, progress)),
                "progress-enabled cache must dedupe");
        check(progress.message().contains("기존 캐시 확인 100%"), "existing cache verification progress missing");
        byte[] changed = x.clone(); changed[100]++;
        check(!xFile.equals(copy(changed, dir, ".3dsx")), "different bytes must not collide");
        byte[] corrupted = x.clone(); corrupted[100]++;
        Files.write(xFile.toPath(), corrupted);
        copy(x, dir, ".3dsx");
        check(Arrays.equals(x, Files.readAllBytes(xFile.toPath())), "corrupt cache must be repaired");
        byte[] badX = x.clone(); put32(badX, 16, 1000);
        expectFailure(badX, dir);
        byte[] badElf = elf.clone(); put32(badElf, 52 + 4, 120); put32(badElf, 52 + 16, 16);
        expectFailure(badElf, dir);
        byte[] encrypted = ncch.clone(); encrypted[0x18f] = 0;
        expectFailure(encrypted, dir);
        byte[] oversizedNcch = ncch.clone(); put32(oversizedNcch, 0x104, 2);
        expectFailure(oversizedNcch, dir);
        byte[] badNcsd = ncsd.clone(); put32(badNcsd, 0x124, 100);
        expectFailure(badNcsd, dir);
        byte[] encryptedNcsd = ncsd.clone(); encryptedNcsd[512 + 0x18f] = 0;
        expectFailure(encryptedNcsd, dir);
        expectFailure(new byte[511], dir);
        try { ThreeDsRomCache.copy(new ByteArrayInputStream(new byte[511]), dir, 511, progress);
            throw new AssertionError("invalid progress copy was accepted"); }
        catch (IOException expected) { checks++; }
        check(progress.message().contains("파일 검사"), "failed copy did not reach validation");
        try { ThreeDsRomCache.copy(new InputStream() {
            private int reads;
            @Override public int read() throws IOException {
                if (reads++ == 50) throw new IOException("provider failed");
                return 0;
            }
        }, dir); throw new AssertionError("failed read accepted"); }
        catch (IOException expected) { checks++; }
        check(dir.listFiles().length == 5, "failed copies left temporary files");
        check(Arrays.equals(x, Files.readAllBytes(xFile.toPath())), "failed copies changed valid cache");
        try {
            Thread.currentThread().interrupt();
            try { ThreeDsRomCache.copy(new ByteArrayInputStream(x), dir);
                throw new AssertionError("interrupted copy was accepted"); }
            catch (InterruptedIOException expected) { checks++; }
        } finally { Thread.interrupted(); }
        check(dir.listFiles().length == 5, "interrupted copy left a temporary file");
        check(Arrays.equals(x, Files.readAllBytes(xFile.toPath())), "interrupted copy replaced valid cache");
        try {
            try { ThreeDsRomCache.copy(new ByteArrayInputStream(x) {
                private boolean interrupted;
                @Override public synchronized int read(byte[] bytes, int offset, int size) {
                    int count = super.read(bytes, offset, size);
                    if (count > 0 && !interrupted) {
                        interrupted = true;
                        Thread.currentThread().interrupt();
                    }
                    return count;
                }
            }, dir, x.length, progress); throw new AssertionError("midstream interruption was accepted"); }
            catch (InterruptedIOException expected) { checks++; }
        } finally { Thread.interrupted(); }
        check(dir.listFiles().length == 5, "midstream interruption left a temporary file");
        Method existing = ThreeDsRomCache.class.getDeclaredMethod("validExisting",
                java.nio.file.Path.class, long.class, String.class, String.class);
        existing.setAccessible(true);
        try {
            Thread.currentThread().interrupt();
            try {
                existing.invoke(null, xFile.toPath(), (long) x.length, ".3dsx",
                        xFile.getName().substring(0, 64));
                throw new AssertionError("interrupted existing-cache hash was accepted");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof InterruptedIOException,
                        "existing-cache interruption was swallowed");
            }
        } finally { Thread.interrupted(); }
        System.out.println("PASS: " + checks + " 3DS ROM cache checks");
    }

    private static File copy(byte[] bytes, File dir, String suffix) throws Exception {
        File file = ThreeDsRomCache.copy(new ByteArrayInputStream(bytes), dir);
        check(file.getName().endsWith(suffix), "wrong cached extension: " + file);
        check(Arrays.equals(bytes, Files.readAllBytes(file.toPath())), "cached bytes changed");
        return file;
    }

    private static void expectFailure(byte[] bytes, File dir) throws Exception {
        try { ThreeDsRomCache.copy(new ByteArrayInputStream(bytes), dir); throw new AssertionError("bad image accepted"); }
        catch (IOException expected) { checks++; }
    }

    private static byte[] threeDsx() {
        byte[] b = new byte[128];
        magic(b, 0, "3DSX"); put16(b, 4, 32); put16(b, 6, 8);
        put32(b, 16, 16); put32(b, 20, 8); put32(b, 24, 8);
        return b;
    }

    private static byte[] elf() {
        byte[] b = new byte[128];
        b[0] = 0x7f; magic(b, 1, "ELF"); b[4] = 1; b[5] = 1;
        put16(b, 18, 40); put32(b, 28, 52); put16(b, 42, 32); put16(b, 44, 1);
        put32(b, 52, 1); put32(b, 56, 96); put32(b, 68, 16);
        return b;
    }

    private static byte[] ncch() {
        byte[] b = new byte[512];
        magic(b, 0x100, "NCCH"); put32(b, 0x104, 1); b[0x18f] = 4;
        return b;
    }

    private static byte[] ncsd() {
        byte[] b = new byte[1024];
        magic(b, 0x100, "NCSD"); put32(b, 0x104, 2);
        put32(b, 0x120, 1); put32(b, 0x124, 1);
        byte[] child = ncch(); System.arraycopy(child, 0, b, 512, 512);
        return b;
    }

    private static void magic(byte[] b, int at, String value) {
        for (int i = 0; i < value.length(); i++) b[at + i] = (byte) value.charAt(i);
    }

    private static void put16(byte[] b, int at, int value) {
        b[at] = (byte) value; b[at + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] b, int at, int value) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) (value >>> (8 * i));
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
}
