package dev.pocketconsole;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts only flat .nca files from a firmware zip, rejecting anything that could escape. */
public final class SwitchFirmwareZip {
    public static final int MAX_ENTRIES = 4096;
    public static final long MAX_ENTRY_BYTES = 2L << 30;
    public static final long MAX_TOTAL_BYTES = 4L << 30;

    public static final class Rejected extends IOException {
        Rejected(String message) { super(message); }
    }

    private SwitchFirmwareZip() {}

    /** Returns the number of extracted NCA files, all written directly inside {@code destination}. */
    public static int extract(File zip, File destination) throws IOException {
        return extract(zip, destination, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES);
    }

    static int extract(File zip, File destination, long maxEntry, long maxTotal) throws IOException {
        return extract(zip, destination, maxEntry, maxTotal, SPACE_RESERVE);
    }

    /** Bytes that must stay free on the destination volume while extracting. */
    private static final long SPACE_RESERVE = 256L << 20;
    private static final long MAX_DIRECTORY_BYTES = 64L << 20;

    static int extract(File zip, File destination, long maxEntry, long maxTotal, long reserve)
            throws IOException {
        try {
            return extractChecked(zip, destination, maxEntry, maxTotal, reserve);
        } catch (IOException | RuntimeException error) {
            // A half-extracted firmware must never be left where a swap could pick it up.
            deleteFlat(destination);
            if (error instanceof Rejected) throw error;
            if (error instanceof IOException) throw (IOException) error;
            throw new Rejected("올바른 zip 파일이 아니에요.");
        }
    }

    private static void deleteFlat(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }

    private static int extractChecked(File zip, File destination, long maxEntry, long maxTotal, long reserve)
            throws IOException {
        scanCentralDirectory(zip);
        if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("펌웨어 폴더를 만들지 못했어요.");
        String base = destination.getCanonicalPath();
        Set<String> written = new HashSet<>();
        long total = 0;
        try (ZipFile archive = new ZipFile(zip)) {
            // ZipFile's own view of the archive is what gets extracted, so bound that too.
            if (archive.size() > MAX_ENTRIES) throw new Rejected("zip 안의 파일이 너무 많아요.");
            int seen = 0;
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++seen > MAX_ENTRIES) throw new Rejected("zip 안의 파일이 너무 많아요.");
                String name = entry.getName();
                checkName(name);
                if (entry.isDirectory() || name.startsWith("__MACOSX/")) continue;
                String leaf = name.substring(name.lastIndexOf('/') + 1);
                if (leaf.startsWith(".")) continue;
                if (!leaf.toLowerCase(Locale.ROOT).endsWith(".nca")) continue;
                if (!leaf.matches("[A-Za-z0-9._-]{1,128}")) throw new Rejected("펌웨어 파일 이름이 올바르지 않아요.");
                if (!written.add(leaf.toLowerCase(Locale.ROOT))) throw new Rejected("같은 이름의 NCA가 두 번 들어 있어요.");
                if (entry.getSize() > maxEntry) throw new Rejected("펌웨어 안의 파일이 너무 커요.");
                File target = new File(destination, leaf);
                if (!target.getCanonicalPath().startsWith(base + File.separator)) throw new Rejected("펌웨어 경로가 올바르지 않아요.");
                try (InputStream in = archive.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(target.toPath())) {
                    byte[] buffer = new byte[64 * 1024];
                    long size = 0;
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        size += read;
                        total += read;
                        if (size > maxEntry || total > maxTotal) throw new Rejected("펌웨어가 허용 크기를 넘었어요.");
                        if (destination.getUsableSpace() < reserve)
                            throw new Rejected("저장 공간이 부족해요. 공간을 비운 뒤 다시 시도하세요.");
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        if (written.isEmpty()) throw new Rejected("zip 안에 NCA 파일이 없어요. 펌웨어 zip을 선택하세요.");
        return written.size();
    }

    private static void checkName(String name) throws Rejected {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.matches("^[A-Za-z]:.*")) throw new Rejected("zip 안에 허용되지 않는 경로가 있어요.");
        for (String part : name.split("/", -1)) {
            if (part.equals("..") || part.equals(".")) throw new Rejected("zip 안에 허용되지 않는 경로가 있어요.");
        }
    }

    /** ZipFile hides unix modes, so read them from the central directory to refuse symlinks. */
    private static void scanCentralDirectory(File zip) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(zip, "r")) {
            long length = file.length();
            int tail = (int) Math.min(length, 22 + 65535);
            byte[] end = new byte[tail];
            file.seek(length - tail);
            file.readFully(end);
            int at = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (u32(end, i) == 0x06054b50L) { at = i; break; }
            }
            if (at < 0) throw new Rejected("올바른 zip 파일이 아니에요.");
            int count = u16(end, at + 10);
            long size = u32(end, at + 12);
            long offset = u32(end, at + 16);
            if (count == 0xFFFF || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL)
                throw new Rejected("지원하지 않는 zip 형식이에요.");
            if (count > MAX_ENTRIES) throw new Rejected("zip 안의 파일이 너무 많아요.");
            // A central-directory entry is at most 46 + 3 * 65535 bytes; never trust the EOCD size beyond that.
            if (offset + size > length || size > MAX_DIRECTORY_BYTES || size > (long) count * (46 + 3 * 65535L))
                throw new Rejected("올바른 zip 파일이 아니에요.");
            byte[] directory = new byte[(int) size];
            file.seek(offset);
            file.readFully(directory);
            int p = 0;
            for (int i = 0; i < count; i++) {
                if (p + 46 > directory.length || u32(directory, p) != 0x02014b50L)
                    throw new Rejected("올바른 zip 파일이 아니에요.");
                int made = u16(directory, p + 4);
                long attributes = u32(directory, p + 38);
                int nameLength = u16(directory, p + 28);
                int extra = u16(directory, p + 30);
                int comment = u16(directory, p + 32);
                if (p + 46 + nameLength > directory.length) throw new Rejected("올바른 zip 파일이 아니에요.");
                String name = new String(directory, p + 46, nameLength, StandardCharsets.UTF_8);
                if ((made >> 8) == 3 && ((attributes >> 16) & 0170000) == 0120000)
                    throw new Rejected("zip 안에 바로가기(심볼릭 링크)가 있어 거부했어요.");
                checkName(name);
                p += 46 + nameLength + extra + comment;
            }
        }
    }

    private static int u16(byte[] b, int i) { return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8); }

    private static long u32(byte[] b, int i) {
        return (u16(b, i) | ((long) u16(b, i + 2) << 16)) & 0xFFFFFFFFL;
    }
}
