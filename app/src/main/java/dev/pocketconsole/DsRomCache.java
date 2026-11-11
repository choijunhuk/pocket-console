package dev.pocketconsole;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class DsRomCache {
    private static final long MAX_BYTES = 512L * 1024 * 1024;

    private DsRomCache() { }

    static synchronized File copy(InputStream source, File directory) throws IOException {
        Files.createDirectories(directory.toPath());
        Path temporary = Files.createTempFile(directory.toPath(), "ds-", ".tmp");
        try {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
            long length = 0;
            try (java.io.OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while (true) {
                    checkInterrupted();
                    count = source.read(buffer);
                    if (count == -1) break;
                    if (count == 0) {
                        int one = source.read();
                        if (one == -1) break;
                        buffer[0] = (byte) one;
                        count = 1;
                    }
                    length += count;
                    if (length > MAX_BYTES) throw new IOException("DS ROM exceeds 512 MiB");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            checkInterrupted();
            validate(temporary.toFile(), length);
            StringBuilder name = new StringBuilder(68);
            for (byte value : digest.digest()) name.append(String.format("%02x", value & 0xff));
            Path target = directory.toPath().resolve(name + ".nds");
            if (Files.exists(target) && Files.size(target) == length
                    && valid(target.toFile(), length, name.toString())) {
                checkInterrupted();
                return target.toFile();
            }
            checkInterrupted();
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target.toFile();
        } finally {
            boolean interrupted = Thread.interrupted();
            try { Files.deleteIfExists(temporary); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static boolean valid(File file, long length, String expectedDigest) throws IOException {
        try {
            checkInterrupted();
            validate(file, length);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[8192];
                int count;
                while (true) {
                    checkInterrupted();
                    count = input.read(buffer);
                    if (count == -1) break;
                    digest.update(buffer, 0, count);
                }
            }
            checkInterrupted();
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format("%02x", value & 0xff));
            return expectedDigest.contentEquals(actual);
        } catch (InterruptedIOException interrupted) { throw interrupted;
        } catch (IOException | NoSuchAlgorithmException e) { return false; }
    }

    private static void validate(File file, long length) throws IOException {
        if (length < 512 || length > MAX_BYTES) throw new IOException("Invalid DS ROM length");
        byte[] header = new byte[512];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.readFully(header);
        }
        section(header, 0x20, 0x2c, length);
        section(header, 0x30, 0x3c, length);
    }

    private static void section(byte[] header, int offsetAt, int sizeAt, long length) throws IOException {
        long offset = unsigned32(header, offsetAt);
        long size = unsigned32(header, sizeAt);
        if (offset < 512 || size == 0 || ((offset | size) & 3) != 0
                || offset + size > length) {
            throw new IOException("Invalid DS ARM section bounds");
        }
    }

    private static long unsigned32(byte[] header, int at) {
        return ((long) header[at] & 255) | (((long) header[at + 1] & 255) << 8)
                | (((long) header[at + 2] & 255) << 16) | (((long) header[at + 3] & 255) << 24);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("DS 파일 작업이 취소됐어요.");
    }
}
