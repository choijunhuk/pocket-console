package dev.pocketconsole;

import java.io.File;
import java.nio.file.Files;

public final class SwitchContentStatusTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File files = Files.createTempDirectory("pocket-status-test-").toFile();
        File root = SwitchContentStatus.userRoot(files);
        check(root.getName().equals("switch2-user"), "engine user root");
        check(SwitchContentStatus.registeredDir(root).getPath().endsWith("nand/system/Contents/registered"),
                "upstream firmware directory");
        check(!SwitchContentStatus.keysPresent(root), "no keys at start");
        check(SwitchContentStatus.firmwareFileCount(root) == 0, "no firmware at start");

        File keys = SwitchContentStatus.keysDir(root);
        check(keys.mkdirs(), "keys dir");
        File prod = new File(keys, "prod.keys");
        Files.write(prod.toPath(), new byte[0]);
        check(!SwitchContentStatus.keysPresent(root), "empty prod.keys is not present");
        Files.write(prod.toPath(), "a = 00\n".getBytes());
        check(SwitchContentStatus.keysPresent(root), "keys present");

        File registered = SwitchContentStatus.registeredDir(root);
        check(registered.mkdirs(), "registered dir");
        Files.write(new File(registered, "a.nca").toPath(), new byte[1]);
        Files.write(new File(registered, "b.txt").toPath(), new byte[1]);
        check(SwitchContentStatus.firmwareFileCount(root) == 1, "only nca counted");

        check(SwitchContentStatus.contentLaunchable("Game.NSP", true), "nsp with keys");
        check(SwitchContentStatus.contentLaunchable("game.xci", true), "xci with keys");
        check(!SwitchContentStatus.contentLaunchable("game.nsp", false), "nsp without keys");
        check(!SwitchContentStatus.contentLaunchable("game.nro", true), "nro is not content");
        check(!SwitchContentStatus.contentLaunchable(null, true), "null name");
        check(ConsoleType.isSwitchContent("a.xci") && !ConsoleType.isSwitchContent("a.3ds"), "content extensions");

        check(SwitchContentStatus.describe(false, 0, "").equals("키 없음  ·  펌웨어 없음"), "absent line");
        check(SwitchContentStatus.describe(true, 3, "17.0.1").equals("키 설치됨  ·  펌웨어 17.0.1"), "version line");
        check(SwitchContentStatus.describe(true, 3, null).contains("확인 중"), "pending version");
        check(SwitchContentStatus.describe(true, 3, "N/A").contains("확인 불가"), "unknown version");
        check(SwitchContentStatus.EXPERIMENTAL_LABEL.equals("실험적 실행 · 미검증 (사용자 키 필요)"), "label");
        for (int code = 0; code <= 5; code++)
            check(!SwitchContentService.describeKeyResult(code, "prod.keys").isEmpty(), "message for result " + code);
        check(SwitchContentService.describeKeyResult(4, "title.keys").contains("prod.keys가 먼저"), "title needs prod");
        check(SwitchContentService.describeKeyResult(4, "prod.keys").contains("필요한 키"), "incomplete keys");
        check(SwitchContentService.describeKeyResult(5, "x").contains("알 수 없는"), "unknown result");
        System.out.println("SWITCH_CONTENT_STATUS_PASS");
    }
}
