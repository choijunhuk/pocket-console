package dev.pocketconsole;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;

public final class DsRomCacheTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("ds-cache-test").toFile();
        byte[] first = rom((byte) 1);
        byte[] second = rom((byte) 2);
        File a = DsRomCache.copy(new ByteArrayInputStream(first), dir);
        File b = DsRomCache.copy(new ByteArrayInputStream(second), dir);
        check(!a.equals(b), "different bytes with same display name need different paths");
        check(a.equals(DsRomCache.copy(new ByteArrayInputStream(first), dir)), "same bytes must dedupe");
        check(Arrays.equals(first, Files.readAllBytes(a.toPath())), "cached bytes changed");
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
        expectFailure(new ByteArrayInputStream(bad), dir);
        byte[] edge = first.clone();
        edge[0x20] = (byte) 0xff;
        edge[0x21] = (byte) 0xff;
        edge[0x22] = (byte) 0xff;
        edge[0x23] = (byte) 0xff;
        expectFailure(new ByteArrayInputStream(edge), dir);
        expectFailure(new InputStream() {
            private int count;
            @Override public int read() throws IOException {
                if (count++ == 300) throw new IOException("provider failed");
                return 0;
            }
        }, dir);
        check(dir.listFiles().length == 2, "failed copies must leave no file");
        check(Arrays.equals(first, Files.readAllBytes(a.toPath())), "failed copies must preserve cached ROM");
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
