package com.midairlogn.mlnetease;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.util.Locale;

public final class DownloadFileUtils {
    private DownloadFileUtils() {}

    public static String getAudioExtensionForQuality(String quality) {
        if ("standard".equals(quality) || "exhigh".equals(quality)) {
            return "mp3";
        }
        return "flac";
    }

    public static String buildRelativePath(String requestType, String title) {
        String base = Environment.DIRECTORY_MUSIC + "/ML Netease";
        String sanitizedTitle = sanitizeFileName(title);
        if (sanitizedTitle.isEmpty()) {
            return base;
        }
        if (DownloadRequest.TYPE_PLAYLIST.equals(requestType)) {
            return base + "/Playlists/" + sanitizedTitle;
        }
        if (DownloadRequest.TYPE_ALBUM.equals(requestType)) {
            return base + "/Albums/" + sanitizedTitle;
        }
        return base;
    }

    public static String buildDisplayName(Song song, String extension) {
        String title = song == null ? "" : song.name;
        String artist = song == null ? "" : song.artists;
        String album = song == null ? "" : song.album;
        String fileName = sanitizeFileName(String.format(Locale.getDefault(), "%s_%s_%s", safe(title), safe(artist), safe(album)));
        if (fileName.isEmpty()) {
            fileName = "netease_" + System.currentTimeMillis();
        }
        return fileName + "." + extension;
    }

    public static Uri saveAudio(Context context, byte[] data, String displayName, String mimeType, String relativePath) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, mimeType);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        }

        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IllegalStateException("Failed to create media store entry");
        }

        try (OutputStream outputStream = resolver.openOutputStream(uri)) {
            if (outputStream == null) {
                throw new IllegalStateException("Failed to open output stream");
            }
            outputStream.write(data);
            outputStream.flush();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues update = new ContentValues();
            update.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(uri, update, null, null);
        }

        return uri;
    }

    public static String sanitizeFileName(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
