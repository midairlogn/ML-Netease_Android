package com.midairlogn.mlnetease;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

public final class LocalAudioMetadataReader {
    private static final int METADATA_KEY_LYRIC = resolveLyricMetadataKey();

    private LocalAudioMetadataReader() {}

    public static LocalAudioMetadata read(Context context, Uri uri) {
        LocalAudioMetadata metadata = new LocalAudioMetadata();
        if (context == null || uri == null) {
            return metadata;
        }

        ContentResolver resolver = context.getContentResolver();
        metadata.mimeType = safe(resolver.getType(uri));

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            metadata.title = safe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            metadata.artist = safe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            metadata.album = safe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            metadata.durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));

            String rawLyrics = safe(extractLyrics(retriever));
            LyricsUtils.SplitLyricsResult splitLyrics = LyricsUtils.splitInlineTranslatedLyrics(rawLyrics);
            metadata.lyric = splitLyrics.lyric;
            metadata.translatedLyric = splitLyrics.translatedLyric;
            metadata.artworkData = retriever.getEmbeddedPicture();
        } catch (RuntimeException ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }

        String displayName = queryDisplayName(resolver, uri);
        if (metadata.title.isEmpty()) {
            metadata.title = stripExtension(displayName);
        }
        if (metadata.artist.isEmpty()) {
            metadata.artist = context.getString(R.string.unknown_artist);
        }
        if (metadata.album.isEmpty()) {
            metadata.album = "";
        }
        return metadata;
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return safe(cursor.getString(index));
                }
            }
        } catch (Exception ignored) {
        }
        String segment = uri.getLastPathSegment();
        return safe(segment);
    }

    private static String stripExtension(String value) {
        if (value == null) {
            return "";
        }
        int dotIndex = value.lastIndexOf('.');
        if (dotIndex <= 0) {
            return value;
        }
        return value.substring(0, dotIndex);
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String extractLyrics(MediaMetadataRetriever retriever) {
        if (retriever == null || METADATA_KEY_LYRIC < 0) {
            return "";
        }
        try {
            return retriever.extractMetadata(METADATA_KEY_LYRIC);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int resolveLyricMetadataKey() {
        try {
            java.lang.reflect.Field field = MediaMetadataRetriever.class.getField("METADATA_KEY_LYRIC");
            return field.getInt(null);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
