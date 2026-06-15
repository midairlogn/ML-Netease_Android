package com.midairlogn.mlnetease.local.media;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.playback.lyrics.LyricsUtils;
import com.midairlogn.mlnetease.shared.metadata.VolumeMetadataTags;
import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.ID3v2Frame;
import com.mpatric.mp3agic.ID3v2FrameSet;
import com.mpatric.mp3agic.Mp3File;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LocalAudioMetadataReader {

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
        readTaggedMetadata(context, uri, metadata.mimeType, displayName, metadata);
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

    private static void readTaggedMetadata(Context context, Uri uri, String mimeType, String displayName, LocalAudioMetadata metadata) {
        File tempFile = null;
        try {
            tempFile = copyUriToTempFile(context, uri, displayName);
            if (tempFile == null) {
                return;
            }
            String extension = resolveExtension(mimeType, displayName);
            if ("mp3".equals(extension)) {
                readMp3TaggedMetadata(tempFile, metadata);
                return;
            }
            if ("flac".equals(extension)) {
                readFlacTaggedMetadata(tempFile, metadata);
            }
        } catch (Exception ignored) {
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
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

    private static void readMp3TaggedMetadata(File file, LocalAudioMetadata metadata) {
        try {
            Mp3File mp3File = new Mp3File(file.getAbsolutePath());
            if (!mp3File.hasId3v2Tag()) {
                return;
            }
            ID3v2 id3v2Tag = mp3File.getId3v2Tag();
            if (id3v2Tag == null) {
                return;
            }
            if (metadata.lyric.isEmpty()) {
                applyLyrics(metadata, safe(id3v2Tag.getLyrics()));
            }
            applyVolumeComments(metadata, readMp3UserTextFrames(id3v2Tag));
        } catch (Exception ignored) {
        }
    }

    private static void readFlacTaggedMetadata(File file, LocalAudioMetadata metadata) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < 4) {
                return;
            }
            byte[] signature = new byte[4];
            raf.readFully(signature);
            if (signature[0] != 'f' || signature[1] != 'L' || signature[2] != 'a' || signature[3] != 'C') {
                return;
            }

            boolean lastBlock = false;
            while (!lastBlock && raf.getFilePointer() < raf.length()) {
                int header = raf.readUnsignedByte();
                lastBlock = (header & 0x80) != 0;
                int type = header & 0x7F;
                int length = (raf.readUnsignedByte() << 16) | (raf.readUnsignedByte() << 8) | raf.readUnsignedByte();
                if (length < 0 || raf.getFilePointer() + length > raf.length()) {
                    return;
                }
                if (type == 4) {
                    byte[] block = new byte[length];
                    raf.readFully(block);
                    Map<String, String> comments = parseVorbisComments(block);
                    if (metadata.lyric.isEmpty()) {
                        applyLyrics(metadata, firstComment(comments, "LYRICS", "UNSYNCEDLYRICS"));
                    }
                    applyVolumeComments(metadata, comments);
                    return;
                }
                raf.seek(raf.getFilePointer() + length);
            }
        } catch (Exception ignored) {
        }
    }

    private static Map<String, String> readMp3UserTextFrames(ID3v2 tag) {
        Map<String, String> comments = new HashMap<>();
        try {
            ID3v2FrameSet frameSet = tag.getFrameSets().get("TXXX");
            if (frameSet == null) {
                return comments;
            }
            List<ID3v2Frame> frames = frameSet.getFrames();
            if (frames == null) {
                return comments;
            }
            for (ID3v2Frame frame : frames) {
                parseMp3UserTextFrame(frame == null ? null : frame.getData(), comments);
            }
        } catch (Exception ignored) {
        }
        return comments;
    }

    private static void parseMp3UserTextFrame(byte[] data, Map<String, String> comments) {
        if (data == null || data.length < 3 || comments == null) {
            return;
        }
        int encoding = data[0] & 0xFF;
        int separatorIndex = findTextSeparator(data, 1, encoding);
        if (separatorIndex <= 1 || separatorIndex >= data.length - 1) {
            return;
        }
        String description = decodeId3Text(data, 1, separatorIndex - 1, encoding);
        int valueOffset = separatorIndex + (usesTwoByteSeparator(encoding) ? 2 : 1);
        String value = decodeId3Text(data, valueOffset, data.length - valueOffset, encoding);
        if (!description.isEmpty() && !value.isEmpty()) {
            comments.put(description.toUpperCase(Locale.US), value);
        }
    }

    private static int findTextSeparator(byte[] data, int offset, int encoding) {
        if (usesTwoByteSeparator(encoding)) {
            for (int i = offset; i < data.length - 1; i += 2) {
                if (data[i] == 0 && data[i + 1] == 0) {
                    return i;
                }
            }
            return -1;
        }
        for (int i = offset; i < data.length; i++) {
            if (data[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean usesTwoByteSeparator(int encoding) {
        return encoding == 1 || encoding == 2;
    }

    private static String decodeId3Text(byte[] data, int offset, int length, int encoding) {
        if (data == null || offset < 0 || length <= 0 || offset + length > data.length) {
            return "";
        }
        Charset charset;
        switch (encoding) {
            case 1:
                charset = StandardCharsets.UTF_16;
                break;
            case 2:
                charset = StandardCharsets.UTF_16BE;
                break;
            case 3:
                charset = StandardCharsets.UTF_8;
                break;
            default:
                charset = StandardCharsets.ISO_8859_1;
                break;
        }
        return safe(new String(data, offset, length, charset));
    }

    private static Map<String, String> parseVorbisComments(byte[] block) {
        Map<String, String> comments = new HashMap<>();
        if (block == null || block.length < 8) {
            return comments;
        }
        try {
            int offset = 0;
            int vendorLength = readLeInt(block, offset);
            offset += 4 + vendorLength;
            if (offset + 4 > block.length) {
                return comments;
            }
            int commentCount = readLeInt(block, offset);
            offset += 4;
            for (int i = 0; i < commentCount && offset + 4 <= block.length; i++) {
                int length = readLeInt(block, offset);
                offset += 4;
                if (length < 0 || offset + length > block.length) {
                    return comments;
                }
                String comment = new String(block, offset, length, java.nio.charset.StandardCharsets.UTF_8);
                offset += length;
                int separatorIndex = comment.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }
                String key = comment.substring(0, separatorIndex).trim();
                String value = safe(comment.substring(separatorIndex + 1));
                if (!key.isEmpty() && !value.isEmpty()) {
                    comments.put(key.toUpperCase(Locale.US), value);
                }
            }
        } catch (Exception ignored) {
        }
        return comments;
    }

    private static String firstComment(Map<String, String> comments, String... keys) {
        if (comments == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            String value = comments.get(key == null ? "" : key.toUpperCase(Locale.US));
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static void applyVolumeComments(LocalAudioMetadata metadata, Map<String, String> comments) {
        if (metadata == null || comments == null || comments.isEmpty()) {
            return;
        }
        boolean hasNeteaseGain = comments.containsKey(VolumeMetadataTags.NETEASE_GAIN);
        boolean hasNeteasePeak = comments.containsKey(VolumeMetadataTags.NETEASE_PEAK);
        boolean hasClosedGain = comments.containsKey(VolumeMetadataTags.NETEASE_CLOSED_GAIN);
        boolean hasClosedPeak = comments.containsKey(VolumeMetadataTags.NETEASE_CLOSED_PEAK);

        if (hasNeteaseGain) {
            metadata.gainDb = VolumeMetadataTags.parseFloat(comments.get(VolumeMetadataTags.NETEASE_GAIN), 0f);
        }
        if (hasNeteasePeak) {
            metadata.peak = Math.max(0f, VolumeMetadataTags.parseFloat(comments.get(VolumeMetadataTags.NETEASE_PEAK), 0f));
        }
        if (hasClosedGain) {
            metadata.closedGainDb = VolumeMetadataTags.parseFloat(comments.get(VolumeMetadataTags.NETEASE_CLOSED_GAIN), 0f);
        }
        if (hasClosedPeak) {
            metadata.closedPeak = Math.max(0f, VolumeMetadataTags.parseFloat(comments.get(VolumeMetadataTags.NETEASE_CLOSED_PEAK), 0f));
        }

        if (!hasNeteaseGain && comments.containsKey(VolumeMetadataTags.REPLAYGAIN_TRACK_GAIN)) {
            metadata.gainDb = VolumeMetadataTags.parseFloat(comments.get(VolumeMetadataTags.REPLAYGAIN_TRACK_GAIN), 0f);
            hasNeteaseGain = true;
        }
        if (!hasNeteasePeak && comments.containsKey(VolumeMetadataTags.REPLAYGAIN_TRACK_PEAK)) {
            metadata.peak = Math.max(0f, VolumeMetadataTags.parseFloat(comments.get(VolumeMetadataTags.REPLAYGAIN_TRACK_PEAK), 0f));
            hasNeteasePeak = metadata.peak > 0f;
        }
        metadata.hasLoudnessNormalization = hasClosedGain || hasNeteaseGain;
    }

    private static int readLeInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
