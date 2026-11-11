package dev.pocketconsole;

public final class ConsoleTypeTest {
    private static int checks;

    public static void main(String[] args) {
        expect(ConsoleType.DS, "my game.nds");
        for (String suffix : new String[]{"3ds", "cci", "cxi"}) expect(ConsoleType.THREE_DS, "game." + suffix);
        for (String suffix : new String[]{"nro", "nsp", "xci"}) expect(ConsoleType.SWITCH, "game." + suffix);
        expect(ConsoleType.DS, "모험.NDS");
        expect(ConsoleType.THREE_DS, "GAME.CXI");
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
}
