package com.midairlogn.mlnetease.local.media;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.playback.lyrics.LyricsUtils;
import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.Mp3File;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;

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

            applyLyrics(metadata, extractLyrics(retriever));
            metadata.artworkData = retriever.getEmbeddedPicture();
            metadata.isPlayable = isReadableAudio(metadata);
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
        if (metadata.lyric.isEmpty()) {
            applyLyrics(metadata, readLyricsFromTaggedFile(context, uri, metadata.mimeType, displayName));
        }
        if (metadata.artist.isEmpty()) {
            metadata.artist = context.getString(R.string.unknown_artist);
        }
        if (metadata.album.isEmpty()) {
            metadata.album = "";
        }
        return metadata;
    }

    private static boolean isReadableAudio(LocalAudioMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        if (metadata.durationMs > 0L) {
            return true;
        }
        return !safe(metadata.title).isEmpty()
                || !safe(metadata.artist).isEmpty()
                || !safe(metadata.album).isEmpty()
                || metadata.artworkData != null;
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

    private static String readLyricsFromTaggedFile(Context context, Uri uri, String mimeType, String displayName) {
        File tempFile = null;
        try {
            tempFile = copyUriToTempFile(context, uri, displayName);
            if (tempFile == null) {
                return "";
            }
            String extension = resolveExtension(mimeType, displayName);
            if ("mp3".equals(extension)) {
                return readMp3Lyrics(tempFile);
            }
            if ("flac".equals(extension)) {
                return readFlacLyrics(tempFile);
            }
        } catch (Exception ignored) {
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
        return "";
    }

    private static void applyLyrics(LocalAudioMetadata metadata, String rawLyrics) {
        String normalizedLyrics = safe(rawLyrics);
        if (normalizedLyrics.isEmpty()) {
            metadata.lyric = "";
            metadata.translatedLyric = "";
            return;
        }

        if (LyricsUtils.hasTimestampedLyrics(normalizedLyrics)) {
            LyricsUtils.SplitLyricsResult splitLyrics = LyricsUtils.splitInlineTranslatedLyrics(normalizedLyrics);
            metadata.lyric = splitLyrics.lyric;
            metadata.translatedLyric = splitLyrics.translatedLyric;
            return;
        }

        metadata.lyric = LyricsUtils.normalizePlainLyrics(normalizedLyrics);
        metadata.translatedLyric = "";
    }

    private static File copyUriToTempFile(Context context, Uri uri, String displayName) throws Exception {
        if (context == null || uri == null) {
            return null;
        }
        String suffix = resolveTempSuffix(displayName);
        File tempFile = File.createTempFile("local_audio_metadata", suffix, context.getCacheDir());
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            if (inputStream == null) {
                return null;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
            return tempFile;
        }
    }

    private static String resolveTempSuffix(String displayName) {
        String safeName = safe(displayName);
        int dotIndex = safeName.lastIndexOf('.');
        if (dotIndex >= 0) {
            return safeName.substring(dotIndex);
        }
        return ".tmp";
    }

    private static String resolveExtension(String mimeType, String displayName) {
        String safeMimeType = safe(mimeType).toLowerCase();
        if (safeMimeType.contains("mpeg") || safeMimeType.endsWith("mp3")) {
            return "mp3";
        }
        if (safeMimeType.contains("flac")) {
            return "flac";
        }
        String safeName = safe(displayName).toLowerCase();
        if (safeName.endsWith(".mp3")) {
            return "mp3";
        }
        if (safeName.endsWith(".flac")) {
            return "flac";
        }
        return "";
    }

    private static String readMp3Lyrics(File file) {
        try {
            Mp3File mp3File = new Mp3File(file.getAbsolutePath());
            if (!mp3File.hasId3v2Tag()) {
                return "";
            }
            ID3v2 id3v2Tag = mp3File.getId3v2Tag();
            return safe(id3v2Tag == null ? "" : id3v2Tag.getLyrics());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readFlacLyrics(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < 4) {
                return "";
            }
            byte[] signature = new byte[4];
            raf.readFully(signature);
            if (signature[0] != 'f' || signature[1] != 'L' || signature[2] != 'a' || signature[3] != 'C') {
                return "";
            }

            boolean lastBlock = false;
            while (!lastBlock && raf.getFilePointer() < raf.length()) {
                int header = raf.readUnsignedByte();
                lastBlock = (header & 0x80) != 0;
                int type = header & 0x7F;
                int length = (raf.readUnsignedByte() << 16) | (raf.readUnsignedByte() << 8) | raf.readUnsignedByte();
                if (length < 0 || raf.getFilePointer() + length > raf.length()) {
                    return "";
                }
                if (type == 4) {
                    byte[] block = new byte[length];
                    raf.readFully(block);
                    return parseVorbisLyrics(block);
                }
                raf.seek(raf.getFilePointer() + length);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String parseVorbisLyrics(byte[] block) {
        if (block == null || block.length < 8) {
            return "";
        }
        try {
            int offset = 0;
            int vendorLength = readLeInt(block, offset);
            offset += 4 + vendorLength;
            if (offset + 4 > block.length) {
                return "";
            }
            int commentCount = readLeInt(block, offset);
            offset += 4;
            for (int i = 0; i < commentCount && offset + 4 <= block.length; i++) {
                int length = readLeInt(block, offset);
                offset += 4;
                if (length < 0 || offset + length > block.length) {
                    return "";
                }
                String comment = new String(block, offset, length, java.nio.charset.StandardCharsets.UTF_8);
                offset += length;
                int separatorIndex = comment.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }
                String key = comment.substring(0, separatorIndex).trim();
                if ("LYRICS".equalsIgnoreCase(key) || "UNSYNCEDLYRICS".equalsIgnoreCase(key)) {
                    return safe(comment.substring(separatorIndex + 1));
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static int readLeInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
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
