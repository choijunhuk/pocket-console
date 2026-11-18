package dev.pocketconsole;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SwitchNroValidatorTest {
    private static void word(byte[] data, int offset, long value) {
        for (int i = 0; i < 4; i++) data[offset + i] = (byte) (value >>> (8 * i));
    }

    private static void longWord(byte[] data, int offset, long value) {
        word(data, offset, value);
        word(data, offset + 4, value >>> 32);
    }

    private static byte[] valid() {
        byte[] data = new byte[0x3000];
        word(data, 4, 0x80); // MOD0 lies in .text.
        data[16] = 'N'; data[17] = 'R'; data[18] = 'O'; data[19] = '0';
        word(data, 24, 0x3000);
        word(data, 32, 0); word(data, 36, 0x1000);
        word(data, 40, 0x1000); word(data, 44, 0x1000);
        word(data, 48, 0x2000); word(data, 52, 0x1000);
        word(data, 56, 0x1000);
        data[0x80] = 'M'; data[0x81] = 'O'; data[0x82] = 'D'; data[0x83] = '0';
        word(data, 0x80 + 8, 0x3000);
        word(data, 0x80 + 12, 0x4000);
        return data;
    }

    private static void check(Path directory, byte[] data, String reason) throws Exception {
        Path path = Files.createTempFile(directory, "nro-", ".nro");
        Files.write(path, data);
        try {
            SwitchNroValidator.validate(path.toFile());
            if (reason != null) throw new AssertionError("Accepted malformed NRO: " + reason);
        } catch (IOException error) {
            if (reason == null) throw new AssertionError("Rejected valid NRO", error);
            if (!error.getMessage().contains(reason)) throw new AssertionError("Wrong rejection for " + reason, error);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("pocket-nro-check-");
        try {
            check(directory, valid(), null);
            byte[] data = valid();
            word(data, 24, 0x2f00); word(data, 52, 0xf00); word(data, 56, 0x801);
            check(directory, data, null); // The loader page-aligns declared and segment/BSS sizes.
            data = new byte[0x4000];
            System.arraycopy(valid(), 0, data, 0, 0x3000);
            word(data, 24, 0x4000); word(data, 40, 0x2000); word(data, 48, 0x3000);
            check(directory, data, null); // The loader does not require contiguous segments.
            data = valid(); word(data, 4, 0);
            check(directory, data, null); // Missing MOD0 uses the NRO header BSS value.
            check(directory, new byte[0x7f], "짧");
            data = valid(); word(data, 24, 0x4000);
            check(directory, data, "크기");
            data = valid(); word(data, 48, 0x3000);
            check(directory, data, "세그먼트");
            data = valid(); word(data, 40, 0xfffff000L);
            check(directory, data, "세그먼트");
            data = valid(); word(data, 36, 0xffffffffL);
            check(directory, data, "세그먼트");
            data = valid(); word(data, 4, 0); word(data, 56, 0xfffff000L);
            check(directory, data, "BSS");
            data = valid(); word(data, 4, 0x2ff0);
            check(directory, data, "MOD");
            data = valid(); word(data, 0x80 + 8, 0x5000); word(data, 0x80 + 12, 0x4000);
            check(directory, data, "BSS");
            data = valid(); word(data, 0x80 + 8, 0); word(data, 0x80 + 12, 0xffffffffL);
            check(directory, data, "BSS");
            data = new byte[0x3040];
            System.arraycopy(valid(), 0, data, 0, 0x3000);
            data[0x3000] = 'A'; data[0x3001] = 'S'; data[0x3002] = 'E'; data[0x3003] = 'T';
            longWord(data, 0x3008, 0x38); longWord(data, 0x3010, 8);
            check(directory, data, null);
            longWord(data, 0x3008, 0x38); longWord(data, 0x3010, 0x100);
            check(directory, data, "부록");
            if (args.length != 0 && !args[0].isEmpty()) {
                SwitchNroValidator.validate(new File(args[0]));
                System.out.println("SWITCH_NRO_ORIGINAL_PASS");
            }
            System.out.println("SWITCH_NRO_VALIDATION_PASS");
        } finally {
            Files.delete(directory);
        }
    }
}
