package com.midairlogn.mlnetease;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {
    private static final String PREF_NAME = "ml_netease_prefs";
    private static final String KEY_MUSIC_U = "music_u";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_SEARCH_LIMIT = "search_limit";
    private static final String KEY_FLOATING_LYRICS_ENABLED = "floating_lyrics_enabled";
    private static final String KEY_LYRIC_COLOR = "lyric_color";
    private static final String KEY_LYRIC_SIZE = "lyric_size";

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

    public static final String[] QUALITY_OPTIONS = {
        "standard", "exhigh", "lossless", "hires", "sky", "jyeffect", "jymaster"
    };

    public static final String[] QUALITY_LABELS = {
        "标准音质", "极高音质", "无损音质", "Hires音质", "沉浸环绕声", "高清环绕声", "超清母带"
    };
}
