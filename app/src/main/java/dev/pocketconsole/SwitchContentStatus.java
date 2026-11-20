package dev.pocketconsole;

import java.io.File;
import java.util.Locale;

/** Where user keys and firmware live under the engine's user root, and what may launch. */
public final class SwitchContentStatus {
    public static final String EXPERIMENTAL_LABEL = "실험적 실행 · 미검증 (사용자 키 필요)";

    private SwitchContentStatus() {}

    public static File userRoot(File filesDir) { return new File(filesDir, "switch2-user"); }

    public static File keysDir(File root) { return new File(root, "keys"); }

    /** The exact directory upstream's firmware installer writes to. */
    public static File registeredDir(File root) { return new File(root, "nand/system/Contents/registered"); }

    /** Installed only after the engine accepted the file, so existence means "verified at install". */
    public static boolean keysPresent(File root) {
        File prod = new File(keysDir(root), "prod.keys");
        return prod.isFile() && prod.length() > 0;
    }

    public static int firmwareFileCount(File root) {
        File[] files = registeredDir(root).listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".nca"));
        return files == null ? 0 : files.length;
    }

    private static File versionFile(File root) { return new File(root, "firmware-version.txt"); }

    /** The version the engine reported at install time; empty when unknown. Never queries the engine. */
    public static String firmwareVersion(File root) {
        File file = versionFile(root);
        if (!file.isFile() || file.length() > 64) return "";
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return text.matches("[0-9A-Za-z._ -]{1,32}") ? text : "";
        } catch (java.io.IOException error) {
            return "";
        }
    }

    public static void saveFirmwareVersion(File root, String version) throws java.io.IOException {
        if (version == null || !version.matches("[0-9A-Za-z._ -]{1,32}")) {
            java.nio.file.Files.deleteIfExists(versionFile(root).toPath());
            return;
        }
        java.nio.file.Files.write(versionFile(root).toPath(),
                version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static boolean contentLaunchable(String name, boolean keysPresent) {
        return keysPresent && ConsoleType.isSwitchContent(name);
    }

    /** {@code version} is null until the engine has read it, empty when it could not. */
    public static String describe(boolean keys, int firmwareFiles, String version) {
        String firmware = firmwareFiles == 0 ? "없음"
                : version == null ? "설치됨 · 버전 확인 중"
                : version.isEmpty() || "N/A".equals(version) ? "설치됨 · 버전 확인 불가" : version;
        return "키 " + (keys ? "설치됨" : "없음") + "  ·  펌웨어 " + firmware;
    }
}
