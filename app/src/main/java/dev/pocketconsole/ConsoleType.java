package dev.pocketconsole;

import java.util.Locale;

public enum ConsoleType {
    DS("ds", "DS", "두 화면의 작은 모험", "nds"),
    THREE_DS("3ds", "3DS", "더 넓어진 휴대용 세계", "3ds", "cci", "cxi", "3dsx", "elf"),
    SWITCH("switch", "Switch", "손안에 들어온 큰 화면", "nro", "nsp", "xci"),
    SWITCH_TWO("switch2", "Switch 2", "새로운 휴대용 세계", "nsp", "xci");

    public final String id;
    public final String label;
    public final String subtitle;
    private final String[] extensions;

    ConsoleType(String id, String label, String subtitle, String... extensions) {
        this.id = id;
        this.label = label;
        this.subtitle = subtitle;
        this.extensions = extensions;
    }

    public boolean accepts(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return false;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String candidate : extensions) {
            if (candidate.equals(extension)) return true;
        }
        return false;
    }

    public static boolean isSwitchHomebrew(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".nro")
                && SWITCH.accepts(name);
    }

    public static ConsoleType fromFileName(String name) {
        for (ConsoleType console : values()) {
            if (console.accepts(name)) return console;
        }
        return null;
    }

    public static ConsoleType fromImport(String name, ConsoleType selected) {
        if (selected == SWITCH_TWO && SWITCH_TWO.accepts(name)) return SWITCH_TWO;
        return fromFileName(name);
    }
}
