package dev.pocketconsole;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class SwitchKeyFileTest {
    private static String hex(int chars) {
        StringBuilder out = new StringBuilder();
        while (out.length() < chars) out.append("0a1B");
        return out.substring(0, chars);
    }

    private static SwitchKeyFile.Check check(String name, String text) throws Exception {
        return SwitchKeyFile.check(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)), name);
    }

    private static void expect(SwitchKeyFile.Status status, String text) throws Exception {
        expect("prod.keys", status, text);
    }

    private static void expect(String file, SwitchKeyFile.Status status, String text) throws Exception {
        SwitchKeyFile.Check result = check(file, text);
        if (result.status != status) throw new AssertionError(file + " " + status + " expected, got "
                + result.status + " for a " + text.length() + " char file");
    }

    public static void main(String[] args) throws Exception {
        expect(SwitchKeyFile.Status.OK, "# comment\r\nmaster_key_00 = " + hex(32) + "\n\naes_kek_generation_source=" + hex(32) + "\n");
        expect(SwitchKeyFile.Status.EMPTY, "# only a comment\n\n");
        expect(SwitchKeyFile.Status.EMPTY, "");
        expect(SwitchKeyFile.Status.BAD_LINE, "master_key_00 = " + hex(32) + "\nnot a key line\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "key = 012\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "key = zz11\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "= 0011\n");
        expect(SwitchKeyFile.Status.NOT_TEXT, "key = 00\u0000\n");

        // Engine lengths (key_manager.cpp): a short value makes HexStringToArray over-read.
        expect(SwitchKeyFile.Status.BAD_LINE, "aes_kek_generation_source=00ff\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "aes_kek_generation_source=" + hex(30) + "\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "aes_kek_generation_source=" + hex(34) + "\n");
        expect(SwitchKeyFile.Status.OK, "header_key = " + hex(64) + "\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "header_key = " + hex(32) + "\n");
        expect(SwitchKeyFile.Status.OK, "keyblob_1f = " + hex(288) + "\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "keyblob_1f = " + hex(32) + "\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "keyblob_20 = " + hex(288) + "\n");
        expect(SwitchKeyFile.Status.OK, "encrypted_keyblob_01 = " + hex(352) + "\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "encrypted_keyblob_ff = " + hex(352) + "\n");
        expect(SwitchKeyFile.Status.OK, "eticket_rsa_keypair = " + hex(1056) + "\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "eticket_rsa_keypair = " + hex(64) + "\n");
        expect(SwitchKeyFile.Status.OK, "eticket_extended_kek = " + hex(1152) + "\n");
        expect(SwitchKeyFile.Status.OK, "key_area_key_system_0A = " + hex(32) + "\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "master_key_0a = " + hex(30) + "\n");
        expect(SwitchKeyFile.Status.BAD_LINE, "KEYBLOB_KEY_SOURCE_00 = " + hex(8) + "\n");
        expect(SwitchKeyFile.Status.OK, "some_unknown_name = " + hex(8) + "\n");
        // title.keys: 32 hex rights id and 32 hex key.
        expect("title.keys", SwitchKeyFile.Status.OK, hex(32) + " = " + hex(32) + "\n");
        expect("title.keys", SwitchKeyFile.Status.BAD_LINE, hex(32) + " = " + hex(8) + "\n");
        expect("title.keys", SwitchKeyFile.Status.BAD_LINE, hex(16) + " = " + hex(32) + "\n");
        expect("title.keys", SwitchKeyFile.Status.BAD_LINE, "master_key_00 = " + hex(32) + "\n");
        // A UTF-8 BOM must not break the first line.
        expect(SwitchKeyFile.Status.OK, "\u00EF\u00BB\u00BFmaster_key_00 = " + hex(32) + "\n");
        StringBuilder big = new StringBuilder();
        while (big.length() <= SwitchKeyFile.MAX_BYTES) big.append("# padding padding padding padding\n");
        expect(SwitchKeyFile.Status.TOO_LARGE, big.toString());
        SwitchKeyFile.Check bad = check("prod.keys", "master_key_00 = " + hex(32) + "\nSECRETVALUE\n");
        if (bad.line != 2) throw new AssertionError("line number expected");
        if (bad.message().contains("SECRETVALUE")) throw new AssertionError("message echoed file content");
        if (!SwitchKeyFile.isAcceptedName("PROD.keys") || !SwitchKeyFile.isAcceptedName("title.keys")
                || SwitchKeyFile.isAcceptedName("prod.keys.txt") || SwitchKeyFile.isAcceptedName("../prod.keys")
                || SwitchKeyFile.isAcceptedName(null)) throw new AssertionError("accepted names");

        File dir = Files.createTempDirectory("pocket-key-test-").toFile();
        File good = new File(dir, "prod.keys");
        if (!SwitchKeyFile.copyChecked(new ByteArrayInputStream(("\u00EF\u00BB\u00BFmaster_key_00 = " + hex(32) + "\n").getBytes(StandardCharsets.ISO_8859_1)), good).ok()
                || good.length() != 49 || Files.readAllBytes(good.toPath())[0] != 'm') throw new AssertionError("valid file must be copied");
        File rejected = new File(dir, "rejected.keys");
        if (SwitchKeyFile.copyChecked(new ByteArrayInputStream("nonsense".getBytes(StandardCharsets.US_ASCII)), rejected).ok()
                || rejected.exists()) throw new AssertionError("invalid file must not be written");
        good.delete();
        dir.delete();
        System.out.println("SWITCH_KEY_FILE_PASS");
    }
}
