package dev.pocketconsole;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Pre-check for a user-supplied Switch key file. It reports only a status and a line number:
 * key names and values are never returned, logged or echoed.
 */
public final class SwitchKeyFile {
    public static final int MAX_BYTES = 1 << 20;
    private static final int MAX_HEX_CHARS = 1152;

    public enum Status { OK, EMPTY, TOO_LARGE, NOT_TEXT, BAD_LINE }

    public static final class Check {
        public final Status status;
        public final int line;

        Check(Status status, int line) {
            this.status = status;
            this.line = line;
        }

        public boolean ok() { return status == Status.OK; }

        public String message() {
            switch (status) {
                case OK: return "키 파일 형식을 확인했어요.";
                case EMPTY: return "키가 들어 있지 않은 파일이에요.";
                case TOO_LARGE: return "키 파일이 너무 커요. 1 MiB 이하의 prod.keys나 title.keys를 선택하세요.";
                case NOT_TEXT: return "텍스트 키 파일이 아니에요. 본인 콘솔에서 덤프한 prod.keys를 선택하세요.";
                default: return "키 파일 " + line + "번째 줄 형식이 올바르지 않아요.";
            }
        }
    }

    private SwitchKeyFile() {}

    /** Only the two names the engine reads; anything else is refused before a byte is copied. */
    public static boolean isAcceptedName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("prod.keys") || lower.equals("title.keys");
    }

    public static Check check(InputStream input, String fileName) throws IOException {
        return check(readBounded(input), fileName);
    }

    /** Reads at most MAX_BYTES + 1 bytes; callers compare against MAX_BYTES. */
    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        while (total <= MAX_BYTES) {
            int read = input.read(chunk, 0, Math.min(chunk.length, MAX_BYTES + 1 - total));
            if (read < 0) break;
            buffer.write(chunk, 0, read);
            total += read;
        }
        return buffer.toByteArray();
    }

    private static byte[] withoutBom(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF)
            return java.util.Arrays.copyOfRange(data, 3, data.length);
        return data;
    }

    static Check check(byte[] data, String fileName) {
        if (data.length > MAX_BYTES) return new Check(Status.TOO_LARGE, 0);
        data = withoutBom(data);
        boolean title = "title.keys".equalsIgnoreCase(fileName);
        int keys = 0;
        int line = 1;
        int start = 0;
        for (int i = 0; i <= data.length; i++) {
            boolean end = i == data.length;
            if (!end && data[i] == 0) return new Check(Status.NOT_TEXT, 0);
            if (!end && data[i] != '\n') continue;
            int result = checkLine(data, start, i, title);
            if (result < 0) return new Check(Status.BAD_LINE, line);
            keys += result;
            start = i + 1;
            line++;
        }
        return keys == 0 ? new Check(Status.EMPTY, 0) : new Check(Status.OK, 0);
    }

    private static final String[] S128 = {"eticket_rsa_kek", "eticket_rsa_kek_source", "eticket_rsa_kekek_source",
        "rsa_kek_mask_0", "rsa_kek_seed_3", "rsa_oaep_kek_generation_source", "sd_card_kek_source",
        "aes_kek_generation_source", "aes_key_generation_source", "package2_key_source", "master_key_source",
        "header_kek_source", "key_area_key_application_source", "key_area_key_ocean_source",
        "key_area_key_system_source", "titlekek_source", "keyblob_mac_key_source", "tsec_key", "secure_boot_key",
        "sd_seed", "bis_key_0_crypt", "bis_key_0_tweak", "bis_key_1_crypt", "bis_key_1_tweak", "bis_key_2_crypt",
        "bis_key_2_tweak", "bis_key_3_crypt", "bis_key_3_tweak", "header_kek", "sd_card_kek"};
    private static final String[] S256 = {"header_key", "sd_card_save_key_source", "sd_card_nca_key_source",
        "header_key_source", "sd_card_save_key", "sd_card_nca_key"};
    private static final String[] S128_PREFIX = {"master_key_", "package1_key_", "package2_key_", "titlekek_",
        "keyblob_key_source_", "keyblob_key_", "keyblob_mac_key_", "key_area_key_application_",
        "key_area_key_ocean_", "key_area_key_system_"};

    private static boolean in(String[] names, String name) {
        for (String candidate : names) if (candidate.equals(name)) return true;
        return false;
    }

    private static boolean hexAt(String name, int from) {
        return name.length() >= from + 2 && isHex((byte) name.charAt(from)) && isHex((byte) name.charAt(from + 1));
    }

    /**
     * Hex characters the engine's HexStringToArray reads for this key name (key_manager.cpp
     * LoadFromFile), or 0 when the engine ignores the name, or -1 when it would index past
     * its 0x20-entry keyblob arrays.
     */
    static int requiredHexChars(String lowerName) {
        if (in(S128, lowerName)) return 32;
        if (in(S256, lowerName)) return 64;
        if (lowerName.startsWith("keyblob_") && !lowerName.startsWith("keyblob_k")) {
            if (!hexAt(lowerName, 8)) return 0;
            return Integer.parseInt(lowerName.substring(8, 10), 16) < 0x20 ? 0x90 * 2 : -1;
        }
        if (lowerName.startsWith("encrypted_keyblob_")) {
            if (!hexAt(lowerName, 18)) return 0;
            return Integer.parseInt(lowerName.substring(18, 20), 16) < 0x20 ? 0xB0 * 2 : -1;
        }
        if (lowerName.startsWith("eticket_extended_kek")) return 576 * 2;
        if (lowerName.startsWith("eticket_rsa_keypair")) return 528 * 2;
        for (String prefix : S128_PREFIX) {
            boolean unchecked = prefix.startsWith("key_area_key_");
            if (lowerName.startsWith(prefix) && (unchecked || hexAt(lowerName, prefix.length()))) return 32;
        }
        return 0;
    }

    /** Returns 1 for a key line, 0 for blank or comment, -1 for a malformed line. */
    private static int checkLine(byte[] data, int from, int to, boolean title) {
        while (to > from && (data[to - 1] == '\r' || data[to - 1] == ' ' || data[to - 1] == '\t')) to--;
        while (from < to && (data[from] == ' ' || data[from] == '\t')) from++;
        if (from == to || data[from] == '#') return 0;
        int i = from;
        while (i < to && (isNameChar(data[i]))) i++;
        if (i == from) return -1;
        String name = new String(data, from, i - from, java.nio.charset.StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        while (i < to && (data[i] == ' ' || data[i] == '\t')) i++;
        if (i >= to || data[i] != '=') return -1;
        i++;
        while (i < to && (data[i] == ' ' || data[i] == '\t')) i++;
        int hex = to - i;
        if (hex == 0 || hex > MAX_HEX_CHARS || (hex & 1) != 0) return -1;
        for (int j = i; j < to; j++) if (!isHex(data[j])) return -1;
        if (title) {
            // Rights id and title key are each read as exactly 16 bytes.
            return name.length() == 32 && isAllHex(name) && hex == 32 ? 1 : -1;
        }
        int required = requiredHexChars(name);
        if (required < 0 || (required > 0 && hex != required)) return -1;
        return 1;
    }

    private static boolean isAllHex(String text) {
        for (int i = 0; i < text.length(); i++) if (!isHex((byte) text.charAt(i))) return false;
        return true;
    }

    private static boolean isNameChar(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '_';
    }

    private static boolean isHex(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
    }

    /** Copies a picked document to {@code destination} only if it passes the pre-check. */
    public static Check copyChecked(InputStream input, File destination) throws IOException {
        byte[] data = readBounded(input);
        Check result = check(data, destination.getName());
        if (!result.ok()) return result;
        data = withoutBom(data);
        try (OutputStream output = Files.newOutputStream(destination.toPath())) {
            output.write(data);
        }
        return result;
    }
}
