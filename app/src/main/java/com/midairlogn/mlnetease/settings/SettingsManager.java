package com.midairlogn.mlnetease.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;

import com.midairlogn.mlnetease.home.shortcut.AppShortcutController;
import com.midairlogn.mlnetease.download.settings.DownloadCustomizationSettings;
import com.midairlogn.mlnetease.home.model.FavouriteSong;
import com.midairlogn.mlnetease.home.model.HomeShortcut;
import com.midairlogn.mlnetease.home.shortcut.HomeShortcutIdParser;
import com.midairlogn.mlnetease.shared.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

public class SettingsManager {
    private static final String PREF_NAME = "ml_netease_prefs";
    private static final String KEY_MUSIC_U = "music_u";
    private static final String KEY_QUALITY = "quality";
    public static final String DEFAULT_QUALITY = "standard";
    private static final String KEY_SEARCH_LIMIT = "search_limit";
    private static final String KEY_FLOATING_LYRICS_ENABLED = "floating_lyrics_enabled";
    private static final String KEY_LYRIC_COLOR = "lyric_color";
    private static final String KEY_LYRIC_SIZE = "lyric_size";
    private static final String KEY_PLAY_MODE = "play_mode";
    private static final String KEY_TRANSLATION_INTEGRATION_ENABLED = "translation_integration_enabled";
    private static final String KEY_APP_VOLUME = "app_volume";
    private static final String KEY_DYNAMIC_VOLUME_ENABLED = "dynamic_volume_enabled";
    private static final String KEY_HOME_SHORTCUTS = "home_shortcuts";
    private static final String KEY_FAVOURITE_SONGS = "favourite_songs";
    private static final String KEY_APP_LANGUAGE = "app_language";
    private static final String KEY_HEARING_PROTECTION_ENABLED = "hearing_protection_enabled";
    private static final String KEY_HEARING_PROTECTION_LISTEN_MINUTES = "hearing_protection_listen_minutes";
    private static final String KEY_HEARING_PROTECTION_REST_MINUTES = "hearing_protection_rest_minutes";
    private static final String KEY_HEARING_PROTECTION_REST_ACTIVE = "hearing_protection_rest_active";
    private static final String KEY_HEARING_PROTECTION_REST_END_ELAPSED_MS = "hearing_protection_rest_end_elapsed_ms";
    private static final String KEY_HEARING_PROTECTION_REST_END_ELAPSED_REALTIME_MS = "hearing_protection_rest_end_elapsed_realtime_ms";
    private static final String KEY_HEARING_PROTECTION_REST_END_BOOT_COUNT = "hearing_protection_rest_end_boot_count";
    private static final String KEY_HEARING_PROTECTION_ACCUMULATED_DOSE_MS = "hearing_protection_accumulated_dose_ms";
    private static final String KEY_HEARING_PROTECTION_ACTIVE_START_WALL_CLOCK_MS = "hearing_protection_active_start_wall_clock_ms";
    private static final String KEY_HEARING_PROTECTION_ACTIVE_START_ELAPSED_REALTIME_MS = "hearing_protection_active_start_elapsed_realtime_ms";
    private static final String KEY_HEARING_PROTECTION_ACTIVE_START_BOOT_COUNT = "hearing_protection_active_start_boot_count";
    private static final String KEY_HEARING_PROTECTION_ACTIVE_LAST_WALL_CLOCK_MS = "hearing_protection_active_last_wall_clock_ms";
    private static final String KEY_HEARING_PROTECTION_ACTIVE_LAST_ELAPSED_REALTIME_MS = "hearing_protection_active_last_elapsed_realtime_ms";
    private static final String KEY_HEARING_PROTECTION_ACTIVE_LAST_BOOT_COUNT = "hearing_protection_active_last_boot_count";
    private static final String KEY_HEARING_PROTECTION_ACTIVE_INTENSITY = "hearing_protection_active_intensity";
    private static final String KEY_HEARING_PROTECTION_PAUSE_START_WALL_CLOCK_MS = "hearing_protection_pause_start_wall_clock_ms";
    private static final String KEY_HEARING_PROTECTION_PAUSE_START_ELAPSED_REALTIME_MS = "hearing_protection_pause_start_elapsed_realtime_ms";
    private static final String KEY_HEARING_PROTECTION_PAUSE_START_BOOT_COUNT = "hearing_protection_pause_start_boot_count";
    private static final String KEY_HEARING_PROTECTION_PAUSE_BASE_DOSE_MS = "hearing_protection_pause_base_dose_ms";
    private static final String KEY_HEARING_PROTECTION_PAUSE_INTENSITY = "hearing_protection_pause_intensity";
    private static final String KEY_HEARING_PROTECTION_BACKGROUND_PROMPT_DISMISSED = "hearing_protection_background_prompt_dismissed";
    private static final String KEY_PLAYBACK_SNAPSHOT_QUEUE = "playback_snapshot_queue";
    private static final String KEY_PLAYBACK_SNAPSHOT_INDEX = "playback_snapshot_index";
    private static final String KEY_PLAYBACK_SNAPSHOT_POSITION_MS = "playback_snapshot_position_ms";
    private static final String KEY_PLAYBACK_SNAPSHOT_WAS_PLAYING = "playback_snapshot_was_playing";
    private static final String KEY_DOWNLOAD_FILENAME_TEMPLATE = "download_filename_template";
    private static final String KEY_DOWNLOAD_FILENAME_SEPARATOR = "download_filename_separator";
    private static final String KEY_DOWNLOAD_METADATA_ENABLED = "download_metadata_enabled";
    private static final String KEY_DOWNLOAD_METADATA_TITLE = "download_metadata_title";
    private static final String KEY_DOWNLOAD_METADATA_ARTIST = "download_metadata_artist";
    private static final String KEY_DOWNLOAD_METADATA_ALBUM = "download_metadata_album";
    private static final String KEY_DOWNLOAD_METADATA_LYRICS = "download_metadata_lyrics";
    private static final String KEY_DOWNLOAD_METADATA_COVER = "download_metadata_cover";
    private static final String KEY_DOWNLOAD_METADATA_RESIZE_COVER = "download_metadata_resize_cover";
    private static final String KEY_DOWNLOAD_METADATA_EXTRA = "download_metadata_extra";
    private static final String KEY_DOWNLOAD_METADATA_VOLUME = "download_metadata_volume";
    private static final String BACKUP_FORMAT = "mlnetease-settings";
    private static final int BACKUP_VERSION = 1;
    private static final int BACKUP_SALT_LENGTH = 16;
    private static final int BACKUP_IV_LENGTH = 12;
    private static final int BACKUP_PBKDF2_ITERATIONS = 120000;
    private static final int BACKUP_KEY_LENGTH_BITS = 256;
    private static final String BACKUP_KEY_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String BACKUP_CIPHER = "AES/GCM/NoPadding";
    public static final String DEFAULT_DOWNLOAD_FILENAME_TEMPLATE = "${title}_${artist}_${album}";
    public static final String DEFAULT_DOWNLOAD_FILENAME_SEPARATOR = "_";
    public static final int DEFAULT_APP_VOLUME = 80;
    public static final int DEFAULT_HEARING_PROTECTION_LISTEN_MINUTES = 90;
    public static final int DEFAULT_HEARING_PROTECTION_REST_MINUTES = 15;

    private final Context appContext;
    private SharedPreferences prefs;
    private String lastPlaybackSnapshotQueue = null;
    private int lastPlaybackSnapshotIndex = Integer.MIN_VALUE;
    private int lastPlaybackSnapshotPositionMs = Integer.MIN_VALUE;
    private boolean lastPlaybackSnapshotWasPlaying = false;
    private boolean hasLastPlaybackSnapshotWasPlaying = false;

    public SettingsManager(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        lastPlaybackSnapshotQueue = prefs.getString(KEY_PLAYBACK_SNAPSHOT_QUEUE, null);
        lastPlaybackSnapshotIndex = prefs.getInt(KEY_PLAYBACK_SNAPSHOT_INDEX, -1);
        lastPlaybackSnapshotPositionMs = prefs.getInt(KEY_PLAYBACK_SNAPSHOT_POSITION_MS, 0);
        lastPlaybackSnapshotWasPlaying = prefs.getBoolean(KEY_PLAYBACK_SNAPSHOT_WAS_PLAYING, false);
        hasLastPlaybackSnapshotWasPlaying = prefs.contains(KEY_PLAYBACK_SNAPSHOT_WAS_PLAYING);
    }

    public void setMusicU(String musicU) {
        String value = musicU == null ? "" : musicU;
        if (value.equals(getMusicU())) {
            return;
        }
        prefs.edit().putString(KEY_MUSIC_U, value).apply();
    }

    public String getMusicU() {
        return prefs.getString(KEY_MUSIC_U, "");
    }

    public void setQuality(String quality) {
        String value = quality == null ? DEFAULT_QUALITY : quality;
        if (value.equals(getQuality())) {
            return;
        }
        prefs.edit().putString(KEY_QUALITY, value).apply();
    }

    public String getQuality() {
        return prefs.getString(KEY_QUALITY, DEFAULT_QUALITY);
    }

    public void setSearchLimit(int limit) {
        if (limit == getSearchLimit()) {
            return;
        }
        prefs.edit().putInt(KEY_SEARCH_LIMIT, limit).apply();
    }

    public int getSearchLimit() {
        return prefs.getInt(KEY_SEARCH_LIMIT, 10);
    }

    public void setFloatingLyricsEnabled(boolean enabled) {
        if (enabled == isFloatingLyricsEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_FLOATING_LYRICS_ENABLED, enabled).apply();
    }

    public boolean isFloatingLyricsEnabled() {
        return prefs.getBoolean(KEY_FLOATING_LYRICS_ENABLED, false);
    }

    public void setLyricColor(int color) {
        if (color == getLyricColor()) {
            return;
        }
        prefs.edit().putInt(KEY_LYRIC_COLOR, color).apply();
    }

    public int getLyricColor() {
        // Default color 0 means use theme color (handled in logic)
        return prefs.getInt(KEY_LYRIC_COLOR, 0);
    }

    public void setLyricSize(float size) {
        if (Float.compare(size, getLyricSize()) == 0) {
            return;
        }
        prefs.edit().putFloat(KEY_LYRIC_SIZE, size).apply();
    }

    public float getLyricSize() {
        return prefs.getFloat(KEY_LYRIC_SIZE, 16f); // Default 16sp
    }

    public void setPlayMode(int mode) {
        if (mode == getPlayMode()) {
            return;
        }
        prefs.edit().putInt(KEY_PLAY_MODE, mode).apply();
    }

    public int getPlayMode() {
        return prefs.getInt(KEY_PLAY_MODE, 0); // Default to MODE_ORDER (0)
    }

    public void setTranslationIntegrationEnabled(boolean enabled) {
        if (enabled == isTranslationIntegrationEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_TRANSLATION_INTEGRATION_ENABLED, enabled).apply();
    }

    public boolean isTranslationIntegrationEnabled() {
        return prefs.getBoolean(KEY_TRANSLATION_INTEGRATION_ENABLED, false);
    }

    public void setAppVolume(int volumePercent) {
        int clamped = Math.max(0, Math.min(volumePercent, 100));
        if (clamped == getAppVolume()) {
            return;
        }
        prefs.edit().putInt(KEY_APP_VOLUME, clamped).apply();
    }

    public int getAppVolume() {
        return prefs.getInt(KEY_APP_VOLUME, DEFAULT_APP_VOLUME);
    }

    public void setDynamicVolumeEnabled(boolean enabled) {
        if (enabled == isDynamicVolumeEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_DYNAMIC_VOLUME_ENABLED, enabled).apply();
    }

    public boolean isDynamicVolumeEnabled() {
        return prefs.getBoolean(KEY_DYNAMIC_VOLUME_ENABLED, true);
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
        AppShortcutController.refresh(appContext);
    }

    public void normalizeShortcutSequences(List<HomeShortcut> shortcuts) {
        for (int i = 0; i < shortcuts.size(); i++) {
            shortcuts.get(i).sequence = i;
        }
    }

    public List<FavouriteSong> getFavouriteSongs() {
        List<FavouriteSong> favourites = new ArrayList<>();
        String raw = prefs.getString(KEY_FAVOURITE_SONGS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }

                String id = item.optString("id", "").trim();
                String name = item.optString("name", "").trim();
                String artists = item.optString("artists", "").trim();
                String album = item.optString("album", "").trim();
                String sourceType = item.optString("sourceType", Song.SOURCE_REMOTE).trim();
                String mediaUri = item.optString("mediaUri", "").trim();
                String mimeType = item.optString("mimeType", "").trim();
                long durationMs = Math.max(0L, item.optLong("durationMs", 0L));
                int sequence = item.optInt("sequence", i);

                if (id.isEmpty() || name.isEmpty()) {
                    continue;
                }
                if (!Song.SOURCE_REMOTE.equals(sourceType) && !Song.isLocalSourceType(sourceType)) {
                    continue;
                }
                if (Song.isLocalSourceType(sourceType) && mediaUri.isEmpty()) {
                    continue;
                }

                favourites.add(new FavouriteSong(id, name, artists, album, sourceType, mediaUri, mimeType, durationMs, sequence));
            }
        } catch (Exception ignored) {
        }

        Collections.sort(favourites, Comparator.comparingInt(song -> song.sequence));
        normalizeFavouriteSequences(favourites);
        boolean removedMissingLocalFiles = removeUnavailableLocalFavourites(favourites);
        if (removedMissingLocalFiles) {
            setFavouriteSongs(favourites);
        }
        return favourites;
    }

    public void setFavouriteSongs(List<FavouriteSong> favourites) {
        List<FavouriteSong> normalized = new ArrayList<>();
        if (favourites != null) {
            for (FavouriteSong favourite : favourites) {
                if (favourite == null) {
                    continue;
                }
                String id = favourite.id == null ? "" : favourite.id.trim();
                String name = favourite.name == null ? "" : favourite.name.trim();
                String artists = favourite.artists == null ? "" : favourite.artists.trim();
                String album = favourite.album == null ? "" : favourite.album.trim();
                String sourceType = favourite.sourceType == null ? Song.SOURCE_REMOTE : favourite.sourceType.trim();
                String mediaUri = favourite.mediaUri == null ? "" : favourite.mediaUri.trim();
                String mimeType = favourite.mimeType == null ? "" : favourite.mimeType.trim();
                long durationMs = Math.max(0L, favourite.durationMs);

                if (id.isEmpty() || name.isEmpty()) {
                    continue;
                }
                if (!Song.SOURCE_REMOTE.equals(sourceType) && !Song.isLocalSourceType(sourceType)) {
                    continue;
                }
                if (Song.isLocalSourceType(sourceType) && mediaUri.isEmpty()) {
                    continue;
                }

                normalized.add(new FavouriteSong(id, name, artists, album, sourceType, mediaUri, mimeType, durationMs, favourite.sequence));
            }
        }

        Collections.sort(normalized, Comparator.comparingInt(song -> song.sequence));
        normalizeFavouriteSequences(normalized);

        JSONArray array = new JSONArray();
        for (FavouriteSong favourite : normalized) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", favourite.id);
                item.put("name", favourite.name);
                item.put("artists", favourite.artists);
                item.put("album", favourite.album);
                item.put("sourceType", favourite.sourceType);
                item.put("mediaUri", favourite.mediaUri);
                item.put("mimeType", favourite.mimeType);
                item.put("durationMs", favourite.durationMs);
                item.put("sequence", favourite.sequence);
                array.put(item);
            } catch (Exception ignored) {
            }
        }

        prefs.edit().putString(KEY_FAVOURITE_SONGS, array.toString()).apply();
        AppShortcutController.refresh(appContext);
    }

    public void normalizeFavouriteSequences(List<FavouriteSong> favourites) {
        for (int i = 0; i < favourites.size(); i++) {
            favourites.get(i).sequence = i;
        }
    }

    private boolean removeUnavailableLocalFavourites(List<FavouriteSong> favourites) {
        boolean changed = false;
        for (int i = favourites.size() - 1; i >= 0; i--) {
            FavouriteSong favourite = favourites.get(i);
            if (favourite != null && !favourite.isPlayable(appContext)) {
                favourites.remove(i);
                changed = true;
            }
        }
        if (changed) {
            normalizeFavouriteSequences(favourites);
        }
        return changed;
    }

    public boolean isFavouriteSong(Song song) {
        if (song == null || song.id == null || song.id.trim().isEmpty()) {
            return false;
        }
        List<FavouriteSong> favourites = getFavouriteSongs();
        for (FavouriteSong favourite : favourites) {
            if (favourite.matchesSong(song)) {
                return true;
            }
        }
        return false;
    }

    public boolean addFavouriteSong(Song song) {
        if (song == null || song.id == null || song.id.trim().isEmpty()) {
            return false;
        }
        List<FavouriteSong> favourites = new ArrayList<>(getFavouriteSongs());
        for (FavouriteSong favourite : favourites) {
            if (favourite.matchesSong(song)) {
                return false;
            }
        }
        FavouriteSong favouriteSong = FavouriteSong.fromSong(song, favourites.size());
        if (favouriteSong == null) {
            return false;
        }
        favourites.add(favouriteSong);
        setFavouriteSongs(favourites);
        return true;
    }

    public boolean removeFavouriteSong(Song song) {
        if (song == null) {
            return false;
        }
        List<FavouriteSong> favourites = new ArrayList<>(getFavouriteSongs());
        for (int i = 0; i < favourites.size(); i++) {
            if (favourites.get(i).matchesSong(song)) {
                favourites.remove(i);
                setFavouriteSongs(favourites);
                return true;
            }
        }
        return false;
    }

    public void setAppLanguage(String language) {
        String value = language == null ? "system" : language;
        if (value.equals(getAppLanguage())) {
            return;
        }
        prefs.edit().putString(KEY_APP_LANGUAGE, value).apply();
    }

    public String getAppLanguage() {
        return prefs.getString(KEY_APP_LANGUAGE, "system"); // Default to system language
    }

    public void setHearingProtectionEnabled(boolean enabled) {
        if (enabled == isHearingProtectionEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_HEARING_PROTECTION_ENABLED, enabled).apply();
    }

    public boolean isHearingProtectionEnabled() {
        return prefs.getBoolean(KEY_HEARING_PROTECTION_ENABLED, false);
    }

    public void setHearingProtectionListenMinutes(int minutes) {
        int clamped = clampHearingProtectionListenMinutes(minutes);
        if (clamped == getHearingProtectionListenMinutes()) {
            return;
        }
        prefs.edit().putInt(KEY_HEARING_PROTECTION_LISTEN_MINUTES, clamped).apply();
    }

    public int getHearingProtectionListenMinutes() {
        return clampHearingProtectionListenMinutes(
                prefs.getInt(KEY_HEARING_PROTECTION_LISTEN_MINUTES, DEFAULT_HEARING_PROTECTION_LISTEN_MINUTES)
        );
    }

    public void setHearingProtectionRestMinutes(int minutes) {
        int clamped = clampHearingProtectionRestMinutes(minutes);
        if (clamped == getHearingProtectionRestMinutes()) {
            return;
        }
        prefs.edit().putInt(KEY_HEARING_PROTECTION_REST_MINUTES, clamped).apply();
    }

    public int getHearingProtectionRestMinutes() {
        return clampHearingProtectionRestMinutes(
                prefs.getInt(KEY_HEARING_PROTECTION_REST_MINUTES, DEFAULT_HEARING_PROTECTION_REST_MINUTES)
        );
    }

    public void setHearingProtectionRestState(boolean active,
                                               long restEndWallClockMs,
                                               long restEndElapsedRealtimeMs,
                                               int restEndBootCount) {
        prefs.edit()
                .putBoolean(KEY_HEARING_PROTECTION_REST_ACTIVE, active)
                .putLong(KEY_HEARING_PROTECTION_REST_END_ELAPSED_MS, Math.max(0L, restEndWallClockMs))
                .putLong(KEY_HEARING_PROTECTION_REST_END_ELAPSED_REALTIME_MS, Math.max(0L, restEndElapsedRealtimeMs))
                .putInt(KEY_HEARING_PROTECTION_REST_END_BOOT_COUNT, restEndBootCount)
                .apply();
    }

    public boolean isHearingProtectionRestActive() {
        return prefs.getBoolean(KEY_HEARING_PROTECTION_REST_ACTIVE, false);
    }

    public long getHearingProtectionRestEndWallClockMs() {
        return Math.max(0L, prefs.getLong(KEY_HEARING_PROTECTION_REST_END_ELAPSED_MS, 0L));
    }

    public long getHearingProtectionRestEndElapsedRealtimeMs() {
        return Math.max(0L, prefs.getLong(KEY_HEARING_PROTECTION_REST_END_ELAPSED_REALTIME_MS, 0L));
    }

    public int getHearingProtectionRestEndBootCount() {
        return prefs.getInt(KEY_HEARING_PROTECTION_REST_END_BOOT_COUNT, -1);
    }

    public void clearHearingProtectionRestState() {
        prefs.edit()
                .remove(KEY_HEARING_PROTECTION_REST_ACTIVE)
                .remove(KEY_HEARING_PROTECTION_REST_END_ELAPSED_MS)
                .remove(KEY_HEARING_PROTECTION_REST_END_ELAPSED_REALTIME_MS)
                .remove(KEY_HEARING_PROTECTION_REST_END_BOOT_COUNT)
                .apply();
    }

    public void setHearingProtectionAccumulatedDoseMs(long doseMs) {
        prefs.edit()
                .putLong(KEY_HEARING_PROTECTION_ACCUMULATED_DOSE_MS, Math.max(0L, doseMs))
                .apply();
    }

    public long getHearingProtectionAccumulatedDoseMs() {
        return Math.max(0L, prefs.getLong(KEY_HEARING_PROTECTION_ACCUMULATED_DOSE_MS, 0L));
    }

    public void clearHearingProtectionAccumulatedDose() {
        prefs.edit().remove(KEY_HEARING_PROTECTION_ACCUMULATED_DOSE_MS).apply();
    }

    public void setHearingProtectionActiveSession(long startWallClockMs,
                                                  long startElapsedRealtimeMs,
                                                  int startBootCount,
                                                  long lastWallClockMs,
                                                  long lastElapsedRealtimeMs,
                                                  int lastBootCount,
                                                  float intensityMultiplier) {
        prefs.edit()
                .putLong(KEY_HEARING_PROTECTION_ACTIVE_START_WALL_CLOCK_MS, Math.max(0L, startWallClockMs))
                .putLong(KEY_HEARING_PROTECTION_ACTIVE_START_ELAPSED_REALTIME_MS, Math.max(0L, startElapsedRealtimeMs))
                .putInt(KEY_HEARING_PROTECTION_ACTIVE_START_BOOT_COUNT, startBootCount)
                .putLong(KEY_HEARING_PROTECTION_ACTIVE_LAST_WALL_CLOCK_MS, Math.max(0L, lastWallClockMs))
                .putLong(KEY_HEARING_PROTECTION_ACTIVE_LAST_ELAPSED_REALTIME_MS, Math.max(0L, lastElapsedRealtimeMs))
                .putInt(KEY_HEARING_PROTECTION_ACTIVE_LAST_BOOT_COUNT, lastBootCount)
                .putFloat(KEY_HEARING_PROTECTION_ACTIVE_INTENSITY, Math.max(0f, intensityMultiplier))
                .apply();
    }

    public long getHearingProtectionActiveSessionStartWallClockMs() {
        return Math.max(0L, prefs.getLong(KEY_HEARING_PROTECTION_ACTIVE_START_WALL_CLOCK_MS, 0L));
    }

    public long getHearingProtectionActiveSessionStartElapsedRealtimeMs() {
        return Math.max(0L, prefs.getLong(KEY_HEARING_PROTECTION_ACTIVE_START_ELAPSED_REALTIME_MS, 0L));
    }

    public int getHearingProtectionActiveSessionStartBootCount() {
        return prefs.getInt(KEY_HEARING_PROTECTION_ACTIVE_START_BOOT_COUNT, -1);
    }

    public long getHearingProtectionActiveSessionLastWallClockMs() {
        return Math.max(0L, prefs.getLong(KEY_HEARING_PROTECTION_ACTIVE_LAST_WALL_CLOCK_MS, 0L));
    }

    public long getHearingProtectionActiveSessionLastElapsedRealtimeMs() {
        return Math.max(0L, prefs.getLong(KEY_HEARING_PROTECTION_ACTIVE_LAST_ELAPSED_REALTIME_MS, 0L));
    }

    public int getHearingProtectionActiveSessionLastBootCount() {
        return prefs.getInt(KEY_HEARING_PROTECTION_ACTIVE_LAST_BOOT_COUNT, -1);
    }

    public float getHearingProtectionActiveSessionIntensity() {
        return Math.max(0f, prefs.getFloat(KEY_HEARING_PROTECTION_ACTIVE_INTENSITY, 0f));
    }

    public void clearHearingProtectionActiveSession() {
        prefs.edit()
                .remove(KEY_HEARING_PROTECTION_ACTIVE_START_WALL_CLOCK_MS)
                .remove(KEY_HEARING_PROTECTION_ACTIVE_START_ELAPSED_REALTIME_MS)
                .remove(KEY_HEARING_PROTECTION_ACTIVE_START_BOOT_COUNT)
                .remove(KEY_HEARING_PROTECTION_ACTIVE_LAST_WALL_CLOCK_MS)
                .remove(KEY_HEARING_PROTECTION_ACTIVE_LAST_ELAPSED_REALTIME_MS)
                .remove(KEY_HEARING_PROTECTION_ACTIVE_LAST_BOOT_COUNT)
                .remove(KEY_HEARING_PROTECTION_ACTIVE_INTENSITY)
                .apply();
    }

    public void setHearingProtectionPauseSession(long pauseStartWallClockMs,
                                                  long pauseStartElapsedRealtimeMs,
                                                  int pauseStartBootCount,
                                                  long baseDoseMs,
                                                  float intensityMultiplier) {
        prefs.edit()
                .putLong(KEY_HEARING_PROTECTION_PAUSE_START_WALL_CLOCK_MS, Math.max(0L, pauseStartWallClockMs))
                .putLong(KEY_HEARING_PROTECTION_PAUSE_START_ELAPSED_REALTIME_MS, Math.max(0L, pauseStartElapsedRealtimeMs))
                .putInt(KEY_HEARING_PROTECTION_PAUSE_START_BOOT_COUNT, pauseStartBootCount)
                .putLong(KEY_HEARING_PROTECTION_PAUSE_BASE_DOSE_MS, Math.max(0L, baseDoseMs))
                .putFloat(KEY_HEARING_PROTECTION_PAUSE_INTENSITY, Math.max(0f, intensityMultiplier))
                .apply();
    }

    public long getHearingProtectionPauseStartWallClockMs() {
        return Math.max(0L, prefs.getLong(KEY_HEARING_PROTECTION_PAUSE_START_WALL_CLOCK_MS, 0L));
    }

    public long getHearingProtectionPauseStartElapsedRealtimeMs() {
        return Math.max(0L, prefs.getLong(KEY_HEARING_PROTECTION_PAUSE_START_ELAPSED_REALTIME_MS, 0L));
    }

    public int getHearingProtectionPauseStartBootCount() {
        return prefs.getInt(KEY_HEARING_PROTECTION_PAUSE_START_BOOT_COUNT, -1);
    }

    public long getHearingProtectionPauseBaseDoseMs() {
        return Math.max(0L, prefs.getLong(KEY_HEARING_PROTECTION_PAUSE_BASE_DOSE_MS, 0L));
    }

    public float getHearingProtectionPauseIntensity() {
        return Math.max(0f, prefs.getFloat(KEY_HEARING_PROTECTION_PAUSE_INTENSITY, 0f));
    }

    public void clearHearingProtectionPauseSession() {
        prefs.edit()
                .remove(KEY_HEARING_PROTECTION_PAUSE_START_WALL_CLOCK_MS)
                .remove(KEY_HEARING_PROTECTION_PAUSE_START_ELAPSED_REALTIME_MS)
                .remove(KEY_HEARING_PROTECTION_PAUSE_START_BOOT_COUNT)
                .remove(KEY_HEARING_PROTECTION_PAUSE_BASE_DOSE_MS)
                .remove(KEY_HEARING_PROTECTION_PAUSE_INTENSITY)
                .apply();
    }

    public boolean isHearingProtectionBackgroundPromptDismissed() {
        return prefs.getBoolean(KEY_HEARING_PROTECTION_BACKGROUND_PROMPT_DISMISSED, false);
    }

    public void setHearingProtectionBackgroundPromptDismissed(boolean dismissed) {
        if (dismissed == isHearingProtectionBackgroundPromptDismissed()) {
            return;
        }
        prefs.edit().putBoolean(KEY_HEARING_PROTECTION_BACKGROUND_PROMPT_DISMISSED, dismissed).apply();
    }

    public void setPlaybackSnapshot(List<Song> songs, int currentIndex, int positionMs, boolean wasPlaying) {
        JSONArray array = new JSONArray();
        if (songs != null) {
            for (Song song : songs) {
                if (song == null) {
                    continue;
                }
                array.put(serializePlaybackSong(song));
            }
        }
        String serializedQueue = array.toString();
        int safePositionMs = Math.max(0, positionMs);
        if (serializedQueue.equals(lastPlaybackSnapshotQueue)
                && currentIndex == lastPlaybackSnapshotIndex
                && safePositionMs == lastPlaybackSnapshotPositionMs
                && wasPlaying == lastPlaybackSnapshotWasPlaying
                && hasLastPlaybackSnapshotWasPlaying) {
            return;
        }
        prefs.edit()
                .putString(KEY_PLAYBACK_SNAPSHOT_QUEUE, serializedQueue)
                .putInt(KEY_PLAYBACK_SNAPSHOT_INDEX, currentIndex)
                .putInt(KEY_PLAYBACK_SNAPSHOT_POSITION_MS, safePositionMs)
                .putBoolean(KEY_PLAYBACK_SNAPSHOT_WAS_PLAYING, wasPlaying)
                .apply();
        lastPlaybackSnapshotQueue = serializedQueue;
        lastPlaybackSnapshotIndex = currentIndex;
        lastPlaybackSnapshotPositionMs = safePositionMs;
        lastPlaybackSnapshotWasPlaying = wasPlaying;
        hasLastPlaybackSnapshotWasPlaying = true;
    }

    public PlaybackSnapshot getPlaybackSnapshot() {
        List<Song> songs = new ArrayList<>();
        String raw = prefs.getString(KEY_PLAYBACK_SNAPSHOT_QUEUE, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                songs.add(deserializePlaybackSong(item));
            }
        } catch (Exception ignored) {
        }
        int savedIndex = prefs.getInt(KEY_PLAYBACK_SNAPSHOT_INDEX, -1);
        int normalizedIndex = songs.isEmpty() ? -1 : clamp(savedIndex, -1, songs.size() - 1);
        int positionMs = Math.max(0, prefs.getInt(KEY_PLAYBACK_SNAPSHOT_POSITION_MS, 0));
        boolean wasPlaying = prefs.getBoolean(KEY_PLAYBACK_SNAPSHOT_WAS_PLAYING, false);
        return new PlaybackSnapshot(songs, normalizedIndex, positionMs, wasPlaying);
    }

    public void clearPlaybackSnapshot() {
        if (lastPlaybackSnapshotQueue == null
                && lastPlaybackSnapshotIndex == -1
                && lastPlaybackSnapshotPositionMs <= 0
                && !lastPlaybackSnapshotWasPlaying
                && !hasLastPlaybackSnapshotWasPlaying) {
            return;
        }
        prefs.edit()
                .remove(KEY_PLAYBACK_SNAPSHOT_QUEUE)
                .remove(KEY_PLAYBACK_SNAPSHOT_INDEX)
                .remove(KEY_PLAYBACK_SNAPSHOT_POSITION_MS)
                .remove(KEY_PLAYBACK_SNAPSHOT_WAS_PLAYING)
                .apply();
        lastPlaybackSnapshotQueue = null;
        lastPlaybackSnapshotIndex = -1;
        lastPlaybackSnapshotPositionMs = 0;
        lastPlaybackSnapshotWasPlaying = false;
        hasLastPlaybackSnapshotWasPlaying = false;
    }

    private int clampHearingProtectionListenMinutes(int minutes) {
        return Math.max(15, Math.min(minutes, 240));
    }

    private int clampHearingProtectionRestMinutes(int minutes) {
        return Math.max(5, Math.min(minutes, 60));
    }

    public DownloadCustomizationSettings getDownloadCustomizationSettings() {
        DownloadCustomizationSettings settings = new DownloadCustomizationSettings();
        settings.fileNameTemplate = getDownloadFileNameTemplate();
        settings.separator = getDownloadFileNameSeparator();
        settings.metadataEnabled = isDownloadMetadataEnabled();
        settings.writeTitle = isDownloadMetadataTitleEnabled();
        settings.writeArtist = isDownloadMetadataArtistEnabled();
        settings.writeAlbum = isDownloadMetadataAlbumEnabled();
        settings.writeLyrics = isDownloadMetadataLyricsEnabled();
        settings.writeCover = isDownloadMetadataCoverEnabled();
        settings.resizeCover = isDownloadMetadataResizeCoverEnabled();
        settings.writeExtra = isDownloadMetadataExtraEnabled();
        settings.writeVolumeMetadata = isDownloadMetadataVolumeEnabled();
        return settings;
    }

    public void setDownloadCustomizationSettings(DownloadCustomizationSettings settings) {
        if (settings == null) {
            settings = new DownloadCustomizationSettings();
        }
        String fileNameTemplate = normalizeDownloadTemplate(settings.fileNameTemplate);
        String separator = normalizeDownloadSeparator(settings.separator);
        if (fileNameTemplate.equals(getDownloadFileNameTemplate())
                && separator.equals(getDownloadFileNameSeparator())
                && settings.metadataEnabled == isDownloadMetadataEnabled()
                && settings.writeTitle == isDownloadMetadataTitleEnabled()
                && settings.writeArtist == isDownloadMetadataArtistEnabled()
                && settings.writeAlbum == isDownloadMetadataAlbumEnabled()
                && settings.writeLyrics == isDownloadMetadataLyricsEnabled()
                && settings.writeCover == isDownloadMetadataCoverEnabled()
                && settings.resizeCover == isDownloadMetadataResizeCoverEnabled()
                && settings.writeExtra == isDownloadMetadataExtraEnabled()
                && settings.writeVolumeMetadata == isDownloadMetadataVolumeEnabled()) {
            return;
        }
        prefs.edit()
                .putString(KEY_DOWNLOAD_FILENAME_TEMPLATE, fileNameTemplate)
                .putString(KEY_DOWNLOAD_FILENAME_SEPARATOR, separator)
                .putBoolean(KEY_DOWNLOAD_METADATA_ENABLED, settings.metadataEnabled)
                .putBoolean(KEY_DOWNLOAD_METADATA_TITLE, settings.writeTitle)
                .putBoolean(KEY_DOWNLOAD_METADATA_ARTIST, settings.writeArtist)
                .putBoolean(KEY_DOWNLOAD_METADATA_ALBUM, settings.writeAlbum)
                .putBoolean(KEY_DOWNLOAD_METADATA_LYRICS, settings.writeLyrics)
                .putBoolean(KEY_DOWNLOAD_METADATA_COVER, settings.writeCover)
                .putBoolean(KEY_DOWNLOAD_METADATA_RESIZE_COVER, settings.resizeCover)
                .putBoolean(KEY_DOWNLOAD_METADATA_EXTRA, settings.writeExtra)
                .putBoolean(KEY_DOWNLOAD_METADATA_VOLUME, settings.writeVolumeMetadata)
                .apply();
    }

    public String getDownloadFileNameTemplate() {
        return normalizeDownloadTemplate(prefs.getString(KEY_DOWNLOAD_FILENAME_TEMPLATE, DEFAULT_DOWNLOAD_FILENAME_TEMPLATE));
    }

    public void setDownloadFileNameTemplate(String template) {
        String value = normalizeDownloadTemplate(template);
        if (value.equals(getDownloadFileNameTemplate())) {
            return;
        }
        prefs.edit().putString(KEY_DOWNLOAD_FILENAME_TEMPLATE, value).apply();
    }

    public String getDownloadFileNameSeparator() {
        return normalizeDownloadSeparator(prefs.getString(KEY_DOWNLOAD_FILENAME_SEPARATOR, DEFAULT_DOWNLOAD_FILENAME_SEPARATOR));
    }

    public void setDownloadFileNameSeparator(String separator) {
        String value = normalizeDownloadSeparator(separator);
        if (value.equals(getDownloadFileNameSeparator())) {
            return;
        }
        prefs.edit().putString(KEY_DOWNLOAD_FILENAME_SEPARATOR, value).apply();
    }

    public boolean isDownloadMetadataEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_ENABLED, true);
    }

    public void setDownloadMetadataEnabled(boolean enabled) {
        if (enabled == isDownloadMetadataEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_ENABLED, enabled).apply();
    }

    public boolean isDownloadMetadataTitleEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_TITLE, true);
    }

    public void setDownloadMetadataTitleEnabled(boolean enabled) {
        if (enabled == isDownloadMetadataTitleEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_TITLE, enabled).apply();
    }

    public boolean isDownloadMetadataArtistEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_ARTIST, true);
    }

    public void setDownloadMetadataArtistEnabled(boolean enabled) {
        if (enabled == isDownloadMetadataArtistEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_ARTIST, enabled).apply();
    }

    public boolean isDownloadMetadataAlbumEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_ALBUM, true);
    }

    public void setDownloadMetadataAlbumEnabled(boolean enabled) {
        if (enabled == isDownloadMetadataAlbumEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_ALBUM, enabled).apply();
    }

    public boolean isDownloadMetadataLyricsEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_LYRICS, true);
    }

    public void setDownloadMetadataLyricsEnabled(boolean enabled) {
        if (enabled == isDownloadMetadataLyricsEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_LYRICS, enabled).apply();
    }

    public boolean isDownloadMetadataCoverEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_COVER, true);
    }

    public void setDownloadMetadataCoverEnabled(boolean enabled) {
        if (enabled == isDownloadMetadataCoverEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_COVER, enabled).apply();
    }

    public boolean isDownloadMetadataResizeCoverEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_RESIZE_COVER, true);
    }

    public void setDownloadMetadataResizeCoverEnabled(boolean enabled) {
        if (enabled == isDownloadMetadataResizeCoverEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_RESIZE_COVER, enabled).apply();
    }

    public boolean isDownloadMetadataExtraEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_EXTRA, true);
    }

    public void setDownloadMetadataExtraEnabled(boolean enabled) {
        if (enabled == isDownloadMetadataExtraEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_EXTRA, enabled).apply();
    }

    public boolean isDownloadMetadataVolumeEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_VOLUME, true);
    }

    public void setDownloadMetadataVolumeEnabled(boolean enabled) {
        if (enabled == isDownloadMetadataVolumeEnabled()) {
            return;
        }
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_VOLUME, enabled).apply();
    }

    private String normalizeDownloadTemplate(String template) {
        if (template == null || template.trim().isEmpty()) {
            return DEFAULT_DOWNLOAD_FILENAME_TEMPLATE;
        }
        return template.trim();
    }

    private String normalizeDownloadSeparator(String separator) {
        return "-".equals(separator) ? "-" : DEFAULT_DOWNLOAD_FILENAME_SEPARATOR;
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }

    public byte[] exportEncryptedData(String password) throws Exception {
        String normalizedPassword = normalizeBackupPassword(password);
        JSONObject data = exportAppDataJson();

        byte[] salt = new byte[BACKUP_SALT_LENGTH];
        byte[] iv = new byte[BACKUP_IV_LENGTH];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(salt);
        secureRandom.nextBytes(iv);

        SecretKey key = deriveBackupKey(normalizedPassword.toCharArray(), salt);
        Cipher cipher = Cipher.getInstance(BACKUP_CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        JSONObject backup = new JSONObject();
        backup.put("format", BACKUP_FORMAT);
        backup.put("version", BACKUP_VERSION);
        backup.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        backup.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        backup.put("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        return backup.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean importEncryptedData(byte[] fileBytes, String password) throws Exception {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("Empty data file");
        }
        String normalizedPassword = normalizeBackupPassword(password);
        JSONObject backup = new JSONObject(new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8));
        if (!BACKUP_FORMAT.equals(backup.optString("format")) || backup.optInt("version", -1) != BACKUP_VERSION) {
            throw new IllegalArgumentException("Unsupported data file");
        }

        byte[] salt = Base64.decode(backup.getString("salt"), Base64.DEFAULT);
        byte[] iv = Base64.decode(backup.getString("iv"), Base64.DEFAULT);
        byte[] payload = Base64.decode(backup.getString("payload"), Base64.DEFAULT);
        if (salt.length != BACKUP_SALT_LENGTH || iv.length != BACKUP_IV_LENGTH || payload.length == 0) {
            throw new IllegalArgumentException("Corrupted data file");
        }

        SecretKey key = deriveBackupKey(normalizedPassword.toCharArray(), salt);
        Cipher cipher = Cipher.getInstance(BACKUP_CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] decrypted = cipher.doFinal(payload);
        JSONObject data = new JSONObject(new String(decrypted, java.nio.charset.StandardCharsets.UTF_8));
        return importAppDataJson(data);
    }

    private JSONObject exportAppDataJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put(KEY_MUSIC_U, getMusicU());
        json.put(KEY_QUALITY, getQuality());
        json.put(KEY_SEARCH_LIMIT, getSearchLimit());
        json.put(KEY_FLOATING_LYRICS_ENABLED, isFloatingLyricsEnabled());
        json.put(KEY_LYRIC_COLOR, getLyricColor());
        json.put(KEY_LYRIC_SIZE, getLyricSize());
        json.put(KEY_PLAY_MODE, getPlayMode());
        json.put(KEY_TRANSLATION_INTEGRATION_ENABLED, isTranslationIntegrationEnabled());
        json.put(KEY_APP_VOLUME, getAppVolume());
        json.put(KEY_DYNAMIC_VOLUME_ENABLED, isDynamicVolumeEnabled());
        json.put(KEY_HEARING_PROTECTION_ENABLED, isHearingProtectionEnabled());
        json.put(KEY_HEARING_PROTECTION_LISTEN_MINUTES, getHearingProtectionListenMinutes());
        json.put(KEY_HEARING_PROTECTION_REST_MINUTES, getHearingProtectionRestMinutes());
        json.put(KEY_HOME_SHORTCUTS, serializeHomeShortcuts());
        json.put(KEY_FAVOURITE_SONGS, serializeFavouriteSongs());
        json.put(KEY_APP_LANGUAGE, getAppLanguage());
        json.put(KEY_DOWNLOAD_FILENAME_TEMPLATE, getDownloadFileNameTemplate());
        json.put(KEY_DOWNLOAD_FILENAME_SEPARATOR, getDownloadFileNameSeparator());
        json.put(KEY_DOWNLOAD_METADATA_ENABLED, isDownloadMetadataEnabled());
        json.put(KEY_DOWNLOAD_METADATA_TITLE, isDownloadMetadataTitleEnabled());
        json.put(KEY_DOWNLOAD_METADATA_ARTIST, isDownloadMetadataArtistEnabled());
        json.put(KEY_DOWNLOAD_METADATA_ALBUM, isDownloadMetadataAlbumEnabled());
        json.put(KEY_DOWNLOAD_METADATA_LYRICS, isDownloadMetadataLyricsEnabled());
        json.put(KEY_DOWNLOAD_METADATA_COVER, isDownloadMetadataCoverEnabled());
        json.put(KEY_DOWNLOAD_METADATA_RESIZE_COVER, isDownloadMetadataResizeCoverEnabled());
        json.put(KEY_DOWNLOAD_METADATA_EXTRA, isDownloadMetadataExtraEnabled());
        json.put(KEY_DOWNLOAD_METADATA_VOLUME, isDownloadMetadataVolumeEnabled());
        return json;
    }

    private boolean importAppDataJson(JSONObject json) {
        if (json == null) {
            throw new IllegalArgumentException("Missing data payload");
        }

        setMusicU(json.optString(KEY_MUSIC_U, ""));
        setQuality(normalizeQuality(json.optString(KEY_QUALITY, DEFAULT_QUALITY)));
        setSearchLimit(clamp(json.optInt(KEY_SEARCH_LIMIT, 10), 1, 100));
        boolean requestedFloatingLyrics = json.optBoolean(KEY_FLOATING_LYRICS_ENABLED, false);
        boolean canEnableFloatingLyrics = !requestedFloatingLyrics || Settings.canDrawOverlays(appContext);
        setFloatingLyricsEnabled(canEnableFloatingLyrics && requestedFloatingLyrics);
        setLyricColor(json.optInt(KEY_LYRIC_COLOR, 0));
        setLyricSize(clampFloat((float) json.optDouble(KEY_LYRIC_SIZE, 16f), 10f, 30f));
        setPlayMode(normalizePlayMode(json.optInt(KEY_PLAY_MODE, 0)));
        setTranslationIntegrationEnabled(json.optBoolean(KEY_TRANSLATION_INTEGRATION_ENABLED, false));
        setAppVolume(clamp(json.optInt(KEY_APP_VOLUME, DEFAULT_APP_VOLUME), 0, 100));
        setDynamicVolumeEnabled(json.optBoolean(KEY_DYNAMIC_VOLUME_ENABLED, true));
        setHearingProtectionEnabled(json.optBoolean(KEY_HEARING_PROTECTION_ENABLED, false));
        setHearingProtectionListenMinutes(json.optInt(
                KEY_HEARING_PROTECTION_LISTEN_MINUTES,
                DEFAULT_HEARING_PROTECTION_LISTEN_MINUTES
        ));
        setHearingProtectionRestMinutes(json.optInt(
                KEY_HEARING_PROTECTION_REST_MINUTES,
                DEFAULT_HEARING_PROTECTION_REST_MINUTES
        ));
        importHomeShortcuts(json.optJSONArray(KEY_HOME_SHORTCUTS));
        importFavouriteSongs(json.optJSONArray(KEY_FAVOURITE_SONGS));
        setAppLanguage(normalizeLanguage(json.optString(KEY_APP_LANGUAGE, "system")));

        DownloadCustomizationSettings downloadSettings = new DownloadCustomizationSettings();
        downloadSettings.fileNameTemplate = json.optString(KEY_DOWNLOAD_FILENAME_TEMPLATE, DEFAULT_DOWNLOAD_FILENAME_TEMPLATE);
        downloadSettings.separator = json.optString(KEY_DOWNLOAD_FILENAME_SEPARATOR, DEFAULT_DOWNLOAD_FILENAME_SEPARATOR);
        downloadSettings.metadataEnabled = json.optBoolean(KEY_DOWNLOAD_METADATA_ENABLED, true);
        downloadSettings.writeTitle = json.optBoolean(KEY_DOWNLOAD_METADATA_TITLE, true);
        downloadSettings.writeArtist = json.optBoolean(KEY_DOWNLOAD_METADATA_ARTIST, true);
        downloadSettings.writeAlbum = json.optBoolean(KEY_DOWNLOAD_METADATA_ALBUM, true);
        downloadSettings.writeLyrics = json.optBoolean(KEY_DOWNLOAD_METADATA_LYRICS, true);
        downloadSettings.writeCover = json.optBoolean(KEY_DOWNLOAD_METADATA_COVER, true);
        downloadSettings.resizeCover = json.optBoolean(KEY_DOWNLOAD_METADATA_RESIZE_COVER, true);
        downloadSettings.writeExtra = json.optBoolean(KEY_DOWNLOAD_METADATA_EXTRA, true);
        downloadSettings.writeVolumeMetadata = json.optBoolean(KEY_DOWNLOAD_METADATA_VOLUME, true);
        setDownloadCustomizationSettings(downloadSettings);
        return requestedFloatingLyrics && !canEnableFloatingLyrics;
    }

    private JSONArray serializeHomeShortcuts() {
        JSONArray array = new JSONArray();
        List<HomeShortcut> shortcuts = getHomeShortcuts();
        for (HomeShortcut shortcut : shortcuts) {
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
        return array;
    }

    private JSONArray serializeFavouriteSongs() {
        JSONArray array = new JSONArray();
        List<FavouriteSong> favourites = getFavouriteSongs();
        for (FavouriteSong favourite : favourites) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", favourite.id);
                item.put("name", favourite.name);
                item.put("artists", favourite.artists);
                item.put("album", favourite.album);
                item.put("sourceType", favourite.sourceType);
                item.put("mediaUri", favourite.mediaUri);
                item.put("mimeType", favourite.mimeType);
                item.put("durationMs", favourite.durationMs);
                item.put("sequence", favourite.sequence);
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        return array;
    }

    private void importHomeShortcuts(JSONArray array) {
        List<HomeShortcut> shortcuts = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                shortcuts.add(new HomeShortcut(
                        item.optString("title", ""),
                        item.optString("id", ""),
                        item.optString("type", ""),
                        item.optInt("sequence", i)
                ));
            }
        }
        setHomeShortcuts(shortcuts);
    }

    private void importFavouriteSongs(JSONArray array) {
        List<FavouriteSong> favourites = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                favourites.add(new FavouriteSong(
                        item.optString("id", ""),
                        item.optString("name", ""),
                        item.optString("artists", ""),
                        item.optString("album", ""),
                        item.optString("sourceType", Song.SOURCE_REMOTE),
                        item.optString("mediaUri", ""),
                        item.optString("mimeType", ""),
                        item.optLong("durationMs", 0L),
                        item.optInt("sequence", i)
                ));
            }
        }
        setFavouriteSongs(favourites);
    }

    private JSONObject serializePlaybackSong(Song song) {
        JSONObject item = new JSONObject();
        try {
            item.put("id", song.id == null ? "" : song.id);
            item.put("name", song.name == null ? "" : song.name);
            item.put("artists", song.artists == null ? "" : song.artists);
            item.put("album", song.album == null ? "" : song.album);
            item.put("picUrl", song.picUrl == null ? "" : song.picUrl);
            item.put("sourceType", song.sourceType == null ? Song.SOURCE_REMOTE : song.sourceType);
            item.put("mediaUri", song.mediaUri == null ? "" : song.mediaUri);
            item.put("mimeType", song.mimeType == null ? "" : song.mimeType);
            item.put("durationMs", Math.max(0L, song.durationMs));
        } catch (Exception ignored) {
        }
        return item;
    }

    private Song deserializePlaybackSong(JSONObject item) {
        return new Song(
                item.optString("id", ""),
                item.optString("name", ""),
                item.optString("artists", ""),
                item.optString("album", ""),
                item.optString("picUrl", ""),
                item.optString("sourceType", Song.SOURCE_REMOTE),
                item.optString("mediaUri", ""),
                item.optString("mimeType", ""),
                item.optLong("durationMs", 0L)
        );
    }

    public static final class PlaybackSnapshot {
        public final List<Song> songs;
        public final int currentIndex;
        public final int positionMs;
        public final boolean wasPlaying;

        PlaybackSnapshot(List<Song> songs, int currentIndex, int positionMs, boolean wasPlaying) {
            this.songs = songs;
            this.currentIndex = currentIndex;
            this.positionMs = Math.max(0, positionMs);
            this.wasPlaying = wasPlaying;
        }
    }

    private SecretKey deriveBackupKey(char[] passwordChars, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passwordChars, salt, BACKUP_PBKDF2_ITERATIONS, BACKUP_KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(BACKUP_KEY_ALGORITHM);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
            Arrays.fill(passwordChars, '\0');
        }
    }

    private String normalizeBackupPassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        return password;
    }

    private String normalizeQuality(String quality) {
        switch (quality) {
            case "standard":
            case "exhigh":
            case "lossless":
            case "hires":
            case "jyeffect":
            case "sky":
            case "jymaster":
                return quality;
            default:
                return DEFAULT_QUALITY;
        }
    }

    private int normalizePlayMode(int mode) {
        return clamp(mode, 0, 3);
    }

    private String normalizeLanguage(String language) {
        if ("en".equals(language) || "zh".equals(language)) {
            return language;
        }
        return "system";
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
