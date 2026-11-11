package dev.pocketconsole;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Catalog metadata only: game bytes stay in the document provider. */
public final class LibraryStore {
    private final SharedPreferences preferences;

    public LibraryStore(Context context) {
        preferences = context.getSharedPreferences("game_library_v1", Context.MODE_PRIVATE);
    }

    public List<Entry> entries(ConsoleType console) {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ?> item : preferences.getAll().entrySet()) {
            if (!(item.getValue() instanceof String)) continue;
            try {
                JSONObject value = new JSONObject((String) item.getValue());
                ConsoleType type = ConsoleType.valueOf(value.getString("console"));
                String name = value.getString("name");
                if (type == console && type.accepts(name)) {
                    entries.add(new Entry(name, item.getKey(), type));
                }
            } catch (JSONException | IllegalArgumentException ignored) {
                // Preserve an unreadable record; one bad row must not erase the catalog.
            }
        }
        entries.sort(Comparator.comparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    public boolean add(String name, String uri, ConsoleType console) {
        if (console == null || !console.accepts(name) || uri == null
                || !"content".equals(Uri.parse(uri).getScheme())) {
            throw new IllegalArgumentException("올바른 게임 문서를 선택해 주세요.");
        }
        if (preferences.contains(uri)) return false;
        try {
            JSONObject value = new JSONObject().put("name", name).put("console", console.name());
            if (!preferences.edit().putString(uri, value.toString()).commit()) {
                throw new IllegalStateException("보관함을 저장하지 못했어요. 저장 공간을 확인해 주세요.");
            }
        } catch (JSONException error) {
            throw new IllegalArgumentException("파일 정보를 저장하지 못했어요.", error);
        }
        return true;
    }

    public void remove(String uri) {
        if (!preferences.edit().remove(uri).commit()) {
            throw new IllegalStateException("보관함에서 제거하지 못했어요.");
        }
    }

    public static final class Entry {
        public final String name;
        public final String uri;
        public final ConsoleType console;

        private Entry(String name, String uri, ConsoleType console) {
            this.name = name;
            this.uri = uri;
            this.console = console;
        }
    }
}
