package dev.pocketconsole;

import java.util.Locale;

public enum ConsoleType {
    DS("ds", "DS", "두 화면의 작은 모험", "nds"),
    THREE_DS("3ds", "3DS", "더 넓어진 휴대용 세계", "3ds", "cci", "cxi"),
    SWITCH("switch", "Switch", "손안에 들어온 큰 화면", "nro", "nsp", "xci");

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

    public static ConsoleType fromFileName(String name) {
        for (ConsoleType console : values()) {
            if (console.accepts(name)) return console;
        }
        return null;
    }
}
