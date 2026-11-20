package dev.pocketconsole;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SwitchFirmwareZipTest {
    private static byte[] zip(String[] names, int size) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) out.write(new byte[size]);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Marks the first central-directory entry as a unix symlink (mode 0120777). */
    private static byte[] asSymlink(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + 46 < data.length; i++) {
            if (buffer.getInt(i) == 0x02014b50) {
                buffer.putShort(i + 4, (short) ((3 << 8) | 20));
                buffer.putInt(i + 38, 0120777 << 16);
                return data;
            }
        }
        throw new AssertionError("no central directory");
    }

    private static File write(File dir, String name, byte[] data) throws IOException {
        File file = new File(dir, name);
        Files.write(file.toPath(), data);
        return file;
    }

    private static void rejects(File zip, File destination, long entry, long total, String why) throws Exception {
        try {
            SwitchFirmwareZip.extract(zip, destination, entry, total);
        } catch (SwitchFirmwareZip.Rejected expected) {
            if (destination.exists()) throw new AssertionError(why + " left a partial extraction behind");
            return;
        }
        throw new AssertionError(why + " must be rejected");
    }

    private static void rejectsAndCleans(File zip, File destination, String why) throws Exception {
        try {
            SwitchFirmwareZip.extract(zip, destination);
        } catch (IOException expected) {
            if (destination.exists()) throw new AssertionError(why + " left a partial extraction behind");
            return;
        }
        throw new AssertionError(why + " must be rejected");
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("pocket-zip-test-").toFile();
        File out = new File(dir, "out");
        long entry = 1024, total = 4096;

        File ok = write(dir, "ok.zip", zip(new String[]{"a.nca", "Firmware 1/b.cnmt.nca", "readme.txt", "nested/"}, 100));
        int count = SwitchFirmwareZip.extract(ok, out, entry, total);
        if (count != 2 || !new File(out, "a.nca").isFile() || !new File(out, "b.cnmt.nca").isFile()
                || new File(out, "readme.txt").exists() || new File(out, "nested").exists())
            throw new AssertionError("only flat .nca files may be extracted");

        File[] attacks = {
            write(dir, "dots.zip", zip(new String[]{"../evil.nca"}, 1)),
            write(dir, "mid.zip", zip(new String[]{"a/../../evil.nca"}, 1)),
            write(dir, "abs.zip", zip(new String[]{"/etc/evil.nca"}, 1)),
            write(dir, "win.zip", zip(new String[]{"..\\evil.nca"}, 1)),
            write(dir, "drive.zip", zip(new String[]{"C:evil.nca"}, 1)),
            write(dir, "dup.zip", zip(new String[]{"a.nca", "x/a.nca"}, 1)),
            write(dir, "ignored-but-unsafe.zip", zip(new String[]{"../notes.txt", "a.nca"}, 1)),
            write(dir, "link.zip", asSymlink(zip(new String[]{"a.nca"}, 1))),
            write(dir, "empty.zip", zip(new String[]{"readme.txt"}, 1)),
        };
        for (File attack : attacks) {
            File target = new File(dir, "target-" + attack.getName());
            rejects(attack, target, entry, total, attack.getName());
        }
        if (new File(dir, "evil.nca").exists() || new File(dir.getParentFile(), "evil.nca").exists())
            throw new AssertionError("escaped the destination");
        rejects(write(dir, "big.zip", zip(new String[]{"a.nca"}, 2000)), new File(dir, "t1"), entry, total, "oversize entry");
        rejects(write(dir, "sum.zip", zip(new String[]{"a.nca", "b.nca", "c.nca", "d.nca", "e.nca"}, 1000)),
                new File(dir, "t2"), entry, total, "oversize total");
        File garbage = write(dir, "garbage.zip", new byte[200]);
        rejects(garbage, new File(dir, "t3"), entry, total, "non-zip");

        // Macos metadata and dot files are skipped, not extracted and not an error.
        File mac = write(dir, "mac.zip", zip(new String[]{"__MACOSX/._a.nca", ".hidden.nca", "x/.b.nca", "a.nca"}, 10));
        File macOut = new File(dir, "mac-out");
        if (SwitchFirmwareZip.extract(mac, macOut, entry, total) != 1 || !new File(macOut, "a.nca").isFile()
                || new File(macOut, "._a.nca").exists() || new File(macOut, ".hidden.nca").exists())
            throw new AssertionError("__MACOSX and dot files must be skipped");

        // The entry cap applies to what ZipFile will extract, not only to the central directory scan.
        String[] many = new String[SwitchFirmwareZip.MAX_ENTRIES + 1];
        for (int i = 0; i < many.length; i++) many[i] = "n" + i + ".nca";
        File crowded = write(dir, "crowded.zip", zip(many, 0));
        rejectsAndCleans(crowded, new File(dir, "t4"), "too many entries");
        // EOCD claiming one entry while the directory holds more must not slip past the cap.
        byte[] lying = zip(many, 0);
        ByteBuffer eocd = ByteBuffer.wrap(lying).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = lying.length - 22; i >= 0; i--) {
            if (eocd.getInt(i) == 0x06054b50) { eocd.putShort(i + 8, (short) 1); eocd.putShort(i + 10, (short) 1); break; }
        }
        File lyingZip = write(dir, "lying.zip", lying);
        try {
            int extracted = SwitchFirmwareZip.extract(lyingZip, new File(dir, "t5"), entry, total);
            if (extracted > SwitchFirmwareZip.MAX_ENTRIES) throw new AssertionError("cap bypassed");
        } catch (IOException | RuntimeException expected) {
            // Rejecting the inconsistent archive is equally fine.
        }
        // A forged EOCD directory size cannot force a huge allocation.
        byte[] forged = zip(new String[]{"a.nca"}, 1);
        ByteBuffer forgedEocd = ByteBuffer.wrap(forged).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = forged.length - 22; i >= 0; i--) {
            if (forgedEocd.getInt(i) == 0x06054b50) { forgedEocd.putInt(i + 12, 0x7fffff00); break; }
        }
        rejects(write(dir, "forged.zip", forged), new File(dir, "t6"), entry, total, "forged directory size");
        // Not enough free space aborts and cleans up.
        try {
            SwitchFirmwareZip.extract(ok, new File(dir, "t7"), entry, total, Long.MAX_VALUE);
            throw new AssertionError("low space must abort");
        } catch (SwitchFirmwareZip.Rejected expected) {
            if (new File(dir, "t7").exists()) throw new AssertionError("low space left files");
        }
        System.out.println("SWITCH_FIRMWARE_ZIP_PASS");
    }
}
