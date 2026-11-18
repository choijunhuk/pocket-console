package dev.pocketconsole;

public final class ConsoleTypeTest {
    private static int checks;

    public static void main(String[] args) {
        expect(ConsoleType.DS, "my game.nds");
        for (String suffix : new String[]{"3ds", "cci", "cxi", "3dsx", "elf"}) expect(ConsoleType.THREE_DS, "game." + suffix);
        for (String suffix : new String[]{"nro", "nsp", "xci"}) expect(ConsoleType.SWITCH, "game." + suffix);
        expect(ConsoleType.DS, "모험.NDS");
        expect(ConsoleType.THREE_DS, "GAME.CXI");
        for (String suffix : new String[]{"nsp", "xci", "NSP", "XCI"}) {
            expectImport(ConsoleType.SWITCH_TWO, "game." + suffix, ConsoleType.SWITCH_TWO);
            expectImport(ConsoleType.SWITCH, "game." + suffix, ConsoleType.SWITCH);
            expectImport(ConsoleType.SWITCH, "game." + suffix, null);
        }
        expectImport(ConsoleType.DS, "game.nds", ConsoleType.SWITCH_TWO);
        expectImport(ConsoleType.SWITCH, "game.nro", ConsoleType.SWITCH_TWO);
        expectImport(null, "game.zip", ConsoleType.SWITCH_TWO);
        expectImport(null, null, ConsoleType.SWITCH_TWO);
        if (!ConsoleType.isSwitchHomebrew("game.NRO")
                || ConsoleType.isSwitchHomebrew("game.nsp")
                || ConsoleType.isSwitchHomebrew("game.xci")) {
            throw new AssertionError("Only NRO may enter the Switch homebrew launch path");
        }
        checks += 3;
        for (String name : new String[]{null, "", ".nds", "firmware.bin", "prod.keys", "game.zip", "game.nds.exe", "game", "installer.cia"}) expect(null, name);
        if (ConsoleType.DS.accepts("game.3ds") || ConsoleType.THREE_DS.accepts("game.nds")) {
            throw new AssertionError("Console formats must stay distinct");
        }
        if (!ConsoleType.DS.accepts("game.nds")) throw new AssertionError("DS must accept a DS game");
        checks += 2;
        System.out.println("PASS: " + checks + " console/file classification checks");
    }

    private static void expect(ConsoleType expected, String fileName) {
        ConsoleType actual = ConsoleType.fromFileName(fileName);
        if (actual != expected) throw new AssertionError(fileName + ": expected " + expected + ", got " + actual);
        checks++;
    }

    private static void expectImport(ConsoleType expected, String fileName, ConsoleType selected) {
        ConsoleType actual = ConsoleType.fromImport(fileName, selected);
        if (actual != expected) throw new AssertionError(fileName + " (" + selected + "): expected " + expected + ", got " + actual);
        checks++;
    }
}
