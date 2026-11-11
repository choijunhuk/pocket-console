package dev.pocketconsole;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ThreeDsRomCache {
    private static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ThreeDsRomCache() { }

    static synchronized File copy(InputStream source, File directory) throws IOException {
        Files.createDirectories(directory.toPath());
        Path temporary = Files.createTempFile(directory.toPath(), "3ds-", ".tmp");
        try {
            MessageDigest digest = sha256();
            long length = 0;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
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
                    if (count > MAX_BYTES - length) throw new IOException("3DS 파일이 8 GiB를 넘습니다.");
                    length += count;
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            checkInterrupted();
            String extension = validate(temporary.toFile(), length);
            String name = hex(digest.digest()) + extension;
            Path target = directory.toPath().resolve(name);
            if (Files.isRegularFile(target) && Files.size(target) == length
                    && validExisting(target, length, extension, name.substring(0, 64))) {
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

    private static boolean validExisting(Path file, long length, String extension, String digest)
            throws IOException {
        try {
            checkInterrupted();
            if (!validate(file.toFile(), length).equals(extension)) return false;
            MessageDigest hash = sha256();
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while (true) {
                    checkInterrupted();
                    count = input.read(buffer);
                    if (count == -1) break;
                    hash.update(buffer, 0, count);
                }
            }
            checkInterrupted();
            return digest.equals(hex(hash.digest()));
        } catch (InterruptedIOException interrupted) { throw interrupted;
        } catch (IOException ignored) { return false; }
    }

    private static String validate(File file, long length) throws IOException {
        if (length < 32 || length > MAX_BYTES) throw new IOException("3DS 파일이 너무 짧거나 큽니다.");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[(int) Math.min(512, length)];
            input.readFully(header);
            if (magic(header, 0, "3DSX")) {
                long headerSize = u16(header, 4), relocSize = u16(header, 6);
                long code = u32(header, 16), rodata = u32(header, 20), data = u32(header, 24);
                long bss = u32(header, 28);
                if (headerSize < 32 || relocSize < 8 || code == 0 || bss > data
                        || headerSize + 3 * relocSize + code + rodata + data - bss > length) {
                    throw new IOException("3DSX 구간이 파일 범위를 벗어납니다.");
                }
                return ".3dsx";
            }
            if (magic(header, 0, "\u007fELF")) {
                if (length < 52 || header[4] != 1 || header[5] != 1 || u16(header, 18) != 40) {
                    throw new IOException("지원하지 않는 3DS ELF 형식입니다.");
                }
                long table = u32(header, 28);
                int entrySize = u16(header, 42), count = u16(header, 44);
                if (entrySize < 32 || count == 0 || table + (long) entrySize * count > length) {
                    throw new IOException("ELF 프로그램 표가 파일 범위를 벗어납니다.");
                }
                byte[] entry = new byte[32];
                boolean hasLoad = false;
                for (int i = 0; i < count; i++) {
                    checkInterrupted();
                    input.seek(table + (long) i * entrySize);
                    input.readFully(entry);
                    if (u32(entry, 0) == 1) {
                        long offset = u32(entry, 4), size = u32(entry, 16);
                        if (size == 0 || offset + size > length) {
                            throw new IOException("ELF 적재 구간이 파일 범위를 벗어납니다.");
                        }
                        hasLoad = true;
                    }
                }
                if (!hasLoad) throw new IOException("ELF에 적재 구간이 없습니다.");
                return ".elf";
            }
            if (length < 512) throw new IOException("지원하지 않는 3DS 파일 형식입니다.");
            if (magic(header, 0x100, "NCCH")) {
                validateNcch(header, length);
                return ".cxi";
            }
            if (magic(header, 0x100, "NCSD")) {
                long media = u32(header, 0x104) * 512;
                if (media < 1024 || media > length) throw new IOException("NCSD 크기가 파일 범위를 벗어납니다.");
                boolean hasPartition = false;
                for (int i = 0; i < 8; i++) {
                    checkInterrupted();
                    long offset = u32(header, 0x120 + 8 * i) * 512;
                    long size = u32(header, 0x124 + 8 * i) * 512;
                    if (size == 0) continue;
                    if (offset < 512 || size < 512 || offset + size > media) {
                        throw new IOException("NCSD 파티션이 파일 범위를 벗어납니다.");
                    }
                    byte[] child = new byte[512];
                    input.seek(offset);
                    input.readFully(child);
                    if (!magic(child, 0x100, "NCCH")) throw new IOException("NCSD 파티션 헤더가 잘못됐습니다.");
                    validateNcch(child, size);
                    hasPartition = true;
                }
                if (!hasPartition) throw new IOException("NCSD 파티션이 없습니다.");
                return ".cci";
            }
            throw new IOException("지원하지 않는 3DS 파일 형식입니다. CIA 설치 파일이나 압축 파일은 열 수 없어요.");
        }
    }

    private static void validateNcch(byte[] header, long length) throws IOException {
        long media = u32(header, 0x104) * 512;
        if (media < 512 || media > length) throw new IOException("NCCH 크기가 파일 범위를 벗어납니다.");
        if ((header[0x18f] & 4) == 0) throw new IOException("암호화된 3DS 게임은 열 수 없어요.");
        for (int at : new int[]{0x1a0, 0x1b0}) {
            long offset = u32(header, at) * 512;
            long size = u32(header, at + 4) * 512;
            if (size != 0 && (offset < 512 || offset + size > media)) {
                throw new IOException("NCCH 구간이 파일 범위를 벗어납니다.");
            }
        }
    }

    private static boolean magic(byte[] bytes, int at, String value) {
        if (at + value.length() > bytes.length) return false;
        for (int i = 0; i < value.length(); i++) if (bytes[at + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8);
    }

    private static long u32(byte[] bytes, int at) {
        return (bytes[at] & 255L) | ((bytes[at + 1] & 255L) << 8)
                | ((bytes[at + 2] & 255L) << 16) | ((bytes[at + 3] & 255L) << 24);
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256를 사용할 수 없어요.", error); }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("3DS 파일 작업이 취소됐어요.");
    }

    private static String hex(byte[] bytes) {
        char[] digits = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            digits[i * 2] = HEX[(bytes[i] >>> 4) & 15];
            digits[i * 2 + 1] = HEX[bytes[i] & 15];
        }
        return new String(digits);
    }
}
