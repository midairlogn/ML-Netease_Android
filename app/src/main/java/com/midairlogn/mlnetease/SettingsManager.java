package com.midairlogn.mlnetease;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;
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
    private static final String KEY_HOME_SHORTCUTS = "home_shortcuts";
    private static final String KEY_APP_LANGUAGE = "app_language";
    private static final String KEY_DOWNLOAD_FILENAME_TEMPLATE = "download_filename_template";
    private static final String KEY_DOWNLOAD_FILENAME_SEPARATOR = "download_filename_separator";
    private static final String KEY_DOWNLOAD_METADATA_ENABLED = "download_metadata_enabled";
    private static final String KEY_DOWNLOAD_METADATA_TITLE = "download_metadata_title";
    private static final String KEY_DOWNLOAD_METADATA_ARTIST = "download_metadata_artist";
    private static final String KEY_DOWNLOAD_METADATA_ALBUM = "download_metadata_album";
    private static final String KEY_DOWNLOAD_METADATA_LYRICS = "download_metadata_lyrics";
    private static final String KEY_DOWNLOAD_METADATA_COVER = "download_metadata_cover";
    private static final String KEY_DOWNLOAD_METADATA_EXTRA = "download_metadata_extra";
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

    private final Context appContext;
    private SharedPreferences prefs;

    public SettingsManager(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
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
        return prefs.getString(KEY_QUALITY, DEFAULT_QUALITY);
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
        settings.writeExtra = isDownloadMetadataExtraEnabled();
        return settings;
    }

    public void setDownloadCustomizationSettings(DownloadCustomizationSettings settings) {
        if (settings == null) {
            settings = new DownloadCustomizationSettings();
        }
        prefs.edit()
                .putString(KEY_DOWNLOAD_FILENAME_TEMPLATE, normalizeDownloadTemplate(settings.fileNameTemplate))
                .putString(KEY_DOWNLOAD_FILENAME_SEPARATOR, normalizeDownloadSeparator(settings.separator))
                .putBoolean(KEY_DOWNLOAD_METADATA_ENABLED, settings.metadataEnabled)
                .putBoolean(KEY_DOWNLOAD_METADATA_TITLE, settings.writeTitle)
                .putBoolean(KEY_DOWNLOAD_METADATA_ARTIST, settings.writeArtist)
                .putBoolean(KEY_DOWNLOAD_METADATA_ALBUM, settings.writeAlbum)
                .putBoolean(KEY_DOWNLOAD_METADATA_LYRICS, settings.writeLyrics)
                .putBoolean(KEY_DOWNLOAD_METADATA_COVER, settings.writeCover)
                .putBoolean(KEY_DOWNLOAD_METADATA_EXTRA, settings.writeExtra)
                .apply();
    }

    public String getDownloadFileNameTemplate() {
        return normalizeDownloadTemplate(prefs.getString(KEY_DOWNLOAD_FILENAME_TEMPLATE, DEFAULT_DOWNLOAD_FILENAME_TEMPLATE));
    }

    public void setDownloadFileNameTemplate(String template) {
        prefs.edit().putString(KEY_DOWNLOAD_FILENAME_TEMPLATE, normalizeDownloadTemplate(template)).apply();
    }

    public String getDownloadFileNameSeparator() {
        return normalizeDownloadSeparator(prefs.getString(KEY_DOWNLOAD_FILENAME_SEPARATOR, DEFAULT_DOWNLOAD_FILENAME_SEPARATOR));
    }

    public void setDownloadFileNameSeparator(String separator) {
        prefs.edit().putString(KEY_DOWNLOAD_FILENAME_SEPARATOR, normalizeDownloadSeparator(separator)).apply();
    }

    public boolean isDownloadMetadataEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_ENABLED, true);
    }

    public void setDownloadMetadataEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_ENABLED, enabled).apply();
    }

    public boolean isDownloadMetadataTitleEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_TITLE, true);
    }

    public void setDownloadMetadataTitleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_TITLE, enabled).apply();
    }

    public boolean isDownloadMetadataArtistEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_ARTIST, true);
    }

    public void setDownloadMetadataArtistEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_ARTIST, enabled).apply();
    }

    public boolean isDownloadMetadataAlbumEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_ALBUM, true);
    }

    public void setDownloadMetadataAlbumEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_ALBUM, enabled).apply();
    }

    public boolean isDownloadMetadataLyricsEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_LYRICS, true);
    }

    public void setDownloadMetadataLyricsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_LYRICS, enabled).apply();
    }

    public boolean isDownloadMetadataCoverEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_COVER, true);
    }

    public void setDownloadMetadataCoverEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_COVER, enabled).apply();
    }

    public boolean isDownloadMetadataExtraEnabled() {
        return prefs.getBoolean(KEY_DOWNLOAD_METADATA_EXTRA, true);
    }

    public void setDownloadMetadataExtraEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_METADATA_EXTRA, enabled).apply();
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
        json.put(KEY_HOME_SHORTCUTS, serializeHomeShortcuts());
        json.put(KEY_APP_LANGUAGE, getAppLanguage());
        json.put(KEY_DOWNLOAD_FILENAME_TEMPLATE, getDownloadFileNameTemplate());
        json.put(KEY_DOWNLOAD_FILENAME_SEPARATOR, getDownloadFileNameSeparator());
        json.put(KEY_DOWNLOAD_METADATA_ENABLED, isDownloadMetadataEnabled());
        json.put(KEY_DOWNLOAD_METADATA_TITLE, isDownloadMetadataTitleEnabled());
        json.put(KEY_DOWNLOAD_METADATA_ARTIST, isDownloadMetadataArtistEnabled());
        json.put(KEY_DOWNLOAD_METADATA_ALBUM, isDownloadMetadataAlbumEnabled());
        json.put(KEY_DOWNLOAD_METADATA_LYRICS, isDownloadMetadataLyricsEnabled());
        json.put(KEY_DOWNLOAD_METADATA_COVER, isDownloadMetadataCoverEnabled());
        json.put(KEY_DOWNLOAD_METADATA_EXTRA, isDownloadMetadataExtraEnabled());
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
        importHomeShortcuts(json.optJSONArray(KEY_HOME_SHORTCUTS));
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
        downloadSettings.writeExtra = json.optBoolean(KEY_DOWNLOAD_METADATA_EXTRA, true);
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
