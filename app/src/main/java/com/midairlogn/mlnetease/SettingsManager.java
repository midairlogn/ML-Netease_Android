package com.midairlogn.mlnetease;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class SettingsManager {
    private static final String PREF_NAME = "ml_netease_prefs";
    private static final String KEY_MUSIC_U = "music_u";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_SEARCH_LIMIT = "search_limit";
    private static final String KEY_FLOATING_LYRICS_ENABLED = "floating_lyrics_enabled";
    private static final String KEY_LYRIC_COLOR = "lyric_color";
    private static final String KEY_LYRIC_SIZE = "lyric_size";
    private static final String KEY_PLAY_MODE = "play_mode";
    private static final String KEY_TRANSLATION_INTEGRATION_ENABLED = "translation_integration_enabled";
    private static final String KEY_APP_VOLUME = "app_volume";
    private static final String KEY_HOME_SHORTCUTS = "home_shortcuts";
    private static final String KEY_APP_LANGUAGE = "app_language";
    public static final int DEFAULT_APP_VOLUME = 80;

    private SharedPreferences prefs;

    public SettingsManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void setMusicU(String musicU) {
        prefs.edit().putString(KEY_MUSIC_U, musicU).apply();
    }

    public String getMusicU() {
        return prefs.getString(KEY_MUSIC_U, "");
    }

    public void setQuality(String quality) {
        prefs.edit().putString(KEY_QUALITY, quality).apply();
    }

    public String getQuality() {
        return prefs.getString(KEY_QUALITY, "standard");
    }

    public void setSearchLimit(int limit) {
        prefs.edit().putInt(KEY_SEARCH_LIMIT, limit).apply();
    }

    public int getSearchLimit() {
        return prefs.getInt(KEY_SEARCH_LIMIT, 10);
    }

    public void setFloatingLyricsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FLOATING_LYRICS_ENABLED, enabled).apply();
    }

    public boolean isFloatingLyricsEnabled() {
        return prefs.getBoolean(KEY_FLOATING_LYRICS_ENABLED, false);
    }

    public void setLyricColor(int color) {
        prefs.edit().putInt(KEY_LYRIC_COLOR, color).apply();
    }

    public int getLyricColor() {
        // Default color 0 means use theme color (handled in logic)
        return prefs.getInt(KEY_LYRIC_COLOR, 0);
    }

    public void setLyricSize(float size) {
        prefs.edit().putFloat(KEY_LYRIC_SIZE, size).apply();
    }

    public float getLyricSize() {
        return prefs.getFloat(KEY_LYRIC_SIZE, 16f); // Default 16sp
    }

    public void setPlayMode(int mode) {
        prefs.edit().putInt(KEY_PLAY_MODE, mode).apply();
    }

    public int getPlayMode() {
        return prefs.getInt(KEY_PLAY_MODE, 0); // Default to MODE_ORDER (0)
    }

    public void setTranslationIntegrationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TRANSLATION_INTEGRATION_ENABLED, enabled).apply();
    }

    public boolean isTranslationIntegrationEnabled() {
        return prefs.getBoolean(KEY_TRANSLATION_INTEGRATION_ENABLED, false);
    }

    public void setAppVolume(int volumePercent) {
        int clamped = Math.max(0, Math.min(volumePercent, 100));
        prefs.edit().putInt(KEY_APP_VOLUME, clamped).apply();
    }

    public int getAppVolume() {
        return prefs.getInt(KEY_APP_VOLUME, DEFAULT_APP_VOLUME);
    }

    public List<HomeShortcut> getHomeShortcuts() {
        List<HomeShortcut> shortcuts = new ArrayList<>();
        String raw = prefs.getString(KEY_HOME_SHORTCUTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }

                String title = item.optString("title", "").trim();
                String id = HomeShortcutIdParser.normalizeId(item.optString("id", ""));
                String type = item.optString("type", "").trim();
                int sequence = item.optInt("sequence", i);

                if (title.isEmpty() || !id.matches("\\d+")) {
                    continue;
                }
                if (!HomeShortcut.TYPE_PLAYLIST.equals(type) && !HomeShortcut.TYPE_ALBUM.equals(type)) {
                    continue;
                }

                shortcuts.add(new HomeShortcut(title, id, type, sequence));
            }
        } catch (Exception ignored) {
        }

        Collections.sort(shortcuts, Comparator.comparingInt(shortcut -> shortcut.sequence));
        normalizeShortcutSequences(shortcuts);
        return shortcuts;
    }

    public void setHomeShortcuts(List<HomeShortcut> shortcuts) {
        List<HomeShortcut> normalized = new ArrayList<>();
        if (shortcuts != null) {
            for (HomeShortcut shortcut : shortcuts) {
                if (shortcut == null) {
                    continue;
                }
                String title = shortcut.title == null ? "" : shortcut.title.trim();
                String id = HomeShortcutIdParser.normalizeId(shortcut.id);
                String type = shortcut.type == null ? "" : shortcut.type.trim();

                if (title.isEmpty() || !id.matches("\\d+")) {
                    continue;
                }
                if (!HomeShortcut.TYPE_PLAYLIST.equals(type) && !HomeShortcut.TYPE_ALBUM.equals(type)) {
                    continue;
                }

                normalized.add(new HomeShortcut(title, id, type, shortcut.sequence));
            }
        }

        Collections.sort(normalized, Comparator.comparingInt(shortcut -> shortcut.sequence));
        normalizeShortcutSequences(normalized);

        JSONArray array = new JSONArray();
        for (HomeShortcut shortcut : normalized) {
            JSONObject item = new JSONObject();
            try {
                item.put("title", shortcut.title);
                item.put("id", shortcut.id);
                item.put("type", shortcut.type);
                item.put("sequence", shortcut.sequence);
                array.put(item);
            } catch (Exception ignored) {
            }
        }

        prefs.edit().putString(KEY_HOME_SHORTCUTS, array.toString()).apply();
    }

    public void normalizeShortcutSequences(List<HomeShortcut> shortcuts) {
        for (int i = 0; i < shortcuts.size(); i++) {
            shortcuts.get(i).sequence = i;
        }
    }

    public void setAppLanguage(String language) {
        prefs.edit().putString(KEY_APP_LANGUAGE, language).apply();
    }

    public String getAppLanguage() {
        return prefs.getString(KEY_APP_LANGUAGE, "system"); // Default to system language
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }
}
