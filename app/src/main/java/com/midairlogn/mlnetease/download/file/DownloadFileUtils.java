package com.midairlogn.mlnetease.download.file;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.midairlogn.mlnetease.download.settings.DownloadCustomizationSettings;
import com.midairlogn.mlnetease.download.model.DownloadRequest;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.Song;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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
        return buildDisplayName(song, extension, null);
    }

    public static String buildDisplayName(Song song, String extension, DownloadCustomizationSettings settings) {
        String title = song == null ? "" : song.name;
        String artist = song == null ? "" : song.artists;
        String album = song == null ? "" : song.album;
        String template = settings == null || settings.fileNameTemplate == null || settings.fileNameTemplate.trim().isEmpty()
                ? SettingsManager.DEFAULT_DOWNLOAD_FILENAME_TEMPLATE
                : settings.fileNameTemplate.trim();
        String fileName = template
                .replace("${title}", safe(title))
                .replace("${artist}", safe(artist))
                .replace("${album}", safe(album));
        fileName = sanitizeFileName(fileName);
        if (fileName.isEmpty()) {
            fileName = "netease_" + System.currentTimeMillis();
        }
        return fileName + "." + extension;
    }

    public static Uri saveAudio(Context context, File sourceFile, String displayName, String mimeType, String relativePath) throws Exception {
        Uri uri = createPendingAudio(context, displayName, mimeType, relativePath);
        boolean publishSuccess = false;
        try {
            writeAudio(context, uri, sourceFile);
            publishAudio(context, uri);
            publishSuccess = true;
            return uri;
        } finally {
            if (!publishSuccess) {
                deleteAudio(context, uri);
            }
        }
    }

    public static boolean audioExists(Context context, String displayName, String relativePath) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String normalizedDisplayName = normalizeDisplayName(displayName);
        String normalizedRelativePath = normalizeRelativePath(relativePath);

        String selection;
        String[] selectionArgs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "LOWER(" + MediaStore.Audio.Media.DISPLAY_NAME + ") = ?";
            selectionArgs = new String[]{normalizedDisplayName};
        } else {
            selection = "LOWER(" + MediaStore.Audio.Media.DISPLAY_NAME + ") = ? AND LOWER(" + MediaStore.Audio.Media.DATA + ") LIKE ?";
            selectionArgs = new String[]{
                    normalizedDisplayName,
                    ("%/" + normalizedRelativePath.replace('/', '%') + normalizedDisplayName).toLowerCase(Locale.US)
            };
        }

        try (Cursor cursor = resolver.query(
                collection,
                new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? MediaStore.Audio.Media.IS_PENDING : MediaStore.Audio.Media._ID
                },
                selection,
                selectionArgs,
                null
        )) {
            if (cursor == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int pathIndex = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH);
                int nameIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
                int pendingIndex = cursor.getColumnIndex(MediaStore.Audio.Media.IS_PENDING);
                while (cursor.moveToNext()) {
                    String existingName = nameIndex >= 0 ? normalizeDisplayName(cursor.getString(nameIndex)) : "";
                    String existingPath = pathIndex >= 0 ? normalizeRelativePath(cursor.getString(pathIndex)) : "";
                    boolean isPending = pendingIndex >= 0 && cursor.getInt(pendingIndex) == 1;
                    if (normalizedDisplayName.equals(existingName) && normalizedRelativePath.equals(existingPath)) {
                        return true;
                    }
                    if (isPending && normalizedDisplayName.equals(existingName) && normalizedRelativePath.equals(existingPath)) {
                        return true;
                    }
                }
                return false;
            }
            return cursor.moveToFirst();
        }
    }

    public static Uri createPendingAudio(Context context, String displayName, String mimeType, String relativePath) {
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
        return uri;
    }

    public static void writeAudio(Context context, Uri uri, File sourceFile) throws Exception {
        if (sourceFile == null) {
            throw new IllegalArgumentException("Source file is required");
        }
        try (InputStream inputStream = new FileInputStream(sourceFile)) {
            writeAudio(context, uri, inputStream);
        }
    }

    public static void writeAudio(Context context, Uri uri, InputStream inputStream) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        try (OutputStream outputStream = resolver.openOutputStream(uri)) {
            if (outputStream == null) {
                throw new IllegalStateException("Failed to open output stream");
            }
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
        }
    }

    public static void publishAudio(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues update = new ContentValues();
            update.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(uri, update, null, null);
        }
    }

    public static void deleteAudio(Context context, @Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        context.getContentResolver().delete(uri, null, null);
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

    private static String normalizeRelativePath(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String normalized = relativePath.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.isEmpty() && !normalized.endsWith("/")) {
            normalized += "/";
        }
        return normalized;
    }

    private static String normalizeDisplayName(String displayName) {
        return displayName == null ? "" : displayName.trim().toLowerCase(Locale.US);
    }
}
