package com.midairlogn.mlnetease.download.tag;

import com.midairlogn.mlnetease.download.model.DownloadTagData;
import com.midairlogn.mlnetease.shared.metadata.VolumeMetadataTags;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class Mp3TagWriter {
    private static final int ID3_HEADER_LENGTH = 10;
    private static final int ID3V1_TAG_LENGTH = 128;
    private static final int MAX_SYNCSAFE_INT = 0x0FFFFFFF;
    private static final byte TEXT_ENCODING_UTF_8 = 3;

    private Mp3TagWriter() {}

    public static void writeTaggedFile(File input, File output, DownloadTagData tagData) throws Exception {
        if (input == null || output == null) {
            throw new IllegalArgumentException("Input and output files are required");
        }

        long audioStart = findAudioStart(input);
        long audioEnd = findAudioEnd(input);
        if (audioEnd < audioStart) {
            throw new IllegalArgumentException("Invalid MP3 file");
        }

        byte[] id3Tag = buildId3Tag(tagData);
        try (FileOutputStream outputStream = new FileOutputStream(output)) {
            outputStream.write(id3Tag);
            copyRange(input, outputStream, audioStart, audioEnd);
            outputStream.flush();
        }
    }

    private static byte[] buildId3Tag(DownloadTagData tagData) {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        if (tagData != null) {
            writeTextFrame(frames, "TIT2", tagData.title);
            writeTextFrame(frames, "TPE1", tagData.artist);
            writeTextFrame(frames, "TALB", tagData.album);
            writeCommentFrame(frames, tagData.comment);
            writeLyricsFrame(frames, tagData.lyrics);
            writePictureFrame(frames, tagData.coverData, tagData.coverMimeType);
            writeVolumeFrames(frames, tagData);
        }

        byte[] frameBytes = frames.toByteArray();
        if (frameBytes.length > MAX_SYNCSAFE_INT) {
            throw new IllegalArgumentException("ID3 tag is too large");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(ID3_HEADER_LENGTH + frameBytes.length);
        output.write('I');
        output.write('D');
        output.write('3');
        output.write(4);
        output.write(0);
        output.write(0);
        writeSynchsafeInt(output, frameBytes.length);
        output.write(frameBytes, 0, frameBytes.length);
        return output.toByteArray();
    }

    private static void writeVolumeFrames(ByteArrayOutputStream frames, DownloadTagData tagData) {
        if (frames == null || tagData == null) {
            return;
        }
        if (tagData.hasClosedGain) {
            writeUserTextFrame(frames, VolumeMetadataTags.REPLAYGAIN_TRACK_GAIN, VolumeMetadataTags.formatGainDb(tagData.closedGainDb));
            writeUserTextFrame(frames, VolumeMetadataTags.NETEASE_CLOSED_GAIN, VolumeMetadataTags.formatNumber(tagData.closedGainDb));
        } else if (tagData.hasGain) {
            writeUserTextFrame(frames, VolumeMetadataTags.REPLAYGAIN_TRACK_GAIN, VolumeMetadataTags.formatGainDb(tagData.gainDb));
        }
        if (tagData.hasClosedPeak && tagData.closedPeak > 0f) {
            writeUserTextFrame(frames, VolumeMetadataTags.REPLAYGAIN_TRACK_PEAK, VolumeMetadataTags.formatNumber(tagData.closedPeak));
            writeUserTextFrame(frames, VolumeMetadataTags.NETEASE_CLOSED_PEAK, VolumeMetadataTags.formatNumber(tagData.closedPeak));
        } else if (tagData.hasPeak && tagData.peak > 0f) {
            writeUserTextFrame(frames, VolumeMetadataTags.REPLAYGAIN_TRACK_PEAK, VolumeMetadataTags.formatNumber(tagData.peak));
        }
        if (tagData.hasGain) {
            writeUserTextFrame(frames, VolumeMetadataTags.NETEASE_GAIN, VolumeMetadataTags.formatNumber(tagData.gainDb));
        }
        if (tagData.hasPeak && tagData.peak > 0f) {
            writeUserTextFrame(frames, VolumeMetadataTags.NETEASE_PEAK, VolumeMetadataTags.formatNumber(tagData.peak));
        }
    }

    private static void writeTextFrame(ByteArrayOutputStream frames, String frameId, String value) {
        if (isBlank(frameId) || isBlank(value)) {
            return;
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(TEXT_ENCODING_UTF_8);
        writeUtf8(data, value.trim());
        writeFrame(frames, frameId, data.toByteArray());
    }

    private static void writeCommentFrame(ByteArrayOutputStream frames, String value) {
        if (isBlank(value)) {
            return;
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(TEXT_ENCODING_UTF_8);
        writeLanguage(data);
        data.write(0);
        writeUtf8(data, value.trim());
        writeFrame(frames, "COMM", data.toByteArray());
    }

    private static void writeLyricsFrame(ByteArrayOutputStream frames, String lyrics) {
        String normalized = normalizeLyricsForId3(lyrics);
        if (normalized.isEmpty()) {
            return;
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(TEXT_ENCODING_UTF_8);
        writeLanguage(data);
        data.write(0);
        writeUtf8(data, normalized);
        writeFrame(frames, "USLT", data.toByteArray());
    }

    private static void writeUserTextFrame(ByteArrayOutputStream frames, String description, String value) {
        if (isBlank(description) || isBlank(value)) {
            return;
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(TEXT_ENCODING_UTF_8);
        writeUtf8(data, description.trim());
        data.write(0);
        writeUtf8(data, value.trim());
        writeFrame(frames, "TXXX", data.toByteArray());
    }

    private static void writePictureFrame(ByteArrayOutputStream frames, byte[] imageData, String mimeType) {
        if (imageData == null || imageData.length == 0) {
            return;
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(TEXT_ENCODING_UTF_8);
        writeUtf8(data, isBlank(mimeType) ? "image/jpeg" : mimeType.trim());
        data.write(0);
        data.write(3); // Front cover.
        data.write(0);
        data.write(imageData, 0, imageData.length);
        writeFrame(frames, "APIC", data.toByteArray());
    }

    private static void writeFrame(ByteArrayOutputStream output, String frameId, byte[] data) {
        if (output == null || frameId == null || frameId.length() != 4 || data == null || data.length == 0) {
            return;
        }
        if (data.length > MAX_SYNCSAFE_INT) {
            throw new IllegalArgumentException("ID3 frame is too large: " + frameId);
        }
        byte[] frameIdBytes = frameId.getBytes(StandardCharsets.ISO_8859_1);
        output.write(frameIdBytes, 0, frameIdBytes.length);
        writeSynchsafeInt(output, data.length);
        output.write(0);
        output.write(0);
        output.write(data, 0, data.length);
    }

    private static void writeLanguage(ByteArrayOutputStream output) {
        output.write('e');
        output.write('n');
        output.write('g');
    }

    private static void writeUtf8(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(bytes, 0, bytes.length);
    }

    private static void writeSynchsafeInt(ByteArrayOutputStream output, int value) {
        if (value < 0 || value > MAX_SYNCSAFE_INT) {
            throw new IllegalArgumentException("Invalid synchsafe integer: " + value);
        }
        output.write((value >> 21) & 0x7F);
        output.write((value >> 14) & 0x7F);
        output.write((value >> 7) & 0x7F);
        output.write(value & 0x7F);
    }

    private static long findAudioStart(File input) throws Exception {
        if (input.length() < ID3_HEADER_LENGTH) {
            return 0L;
        }
        byte[] header = new byte[ID3_HEADER_LENGTH];
        try (FileInputStream inputStream = new FileInputStream(input)) {
            if (inputStream.read(header) != header.length) {
                return 0L;
            }
        }
        if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
            return 0L;
        }
        int tagSize = readSynchsafeInt(header, 6);
        long audioStart = ID3_HEADER_LENGTH + tagSize;
        if ((header[5] & 0x10) != 0) {
            audioStart += ID3_HEADER_LENGTH;
        }
        return Math.min(audioStart, input.length());
    }

    private static long findAudioEnd(File input) throws Exception {
        long length = input.length();
        if (length < ID3V1_TAG_LENGTH) {
            return length;
        }
        byte[] footer = new byte[3];
        try (FileInputStream inputStream = new FileInputStream(input)) {
            long skipped = 0L;
            long toSkip = length - ID3V1_TAG_LENGTH;
            while (skipped < toSkip) {
                long current = inputStream.skip(toSkip - skipped);
                if (current <= 0L) {
                    return length;
                }
                skipped += current;
            }
            if (inputStream.read(footer) != footer.length) {
                return length;
            }
        }
        return footer[0] == 'T' && footer[1] == 'A' && footer[2] == 'G'
                ? length - ID3V1_TAG_LENGTH
                : length;
    }

    private static int readSynchsafeInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0x7F) << 21)
                | ((bytes[offset + 1] & 0x7F) << 14)
                | ((bytes[offset + 2] & 0x7F) << 7)
                | (bytes[offset + 3] & 0x7F);
    }

    private static void copyRange(File input, OutputStream output, long start, long end) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(input)) {
            long skipped = 0L;
            while (skipped < start) {
                long current = inputStream.skip(start - skipped);
                if (current <= 0L) {
                    throw new IllegalArgumentException("Invalid MP3 file");
                }
                skipped += current;
            }

            byte[] buffer = new byte[32 * 1024];
            long remaining = end - start;
            while (remaining > 0L) {
                int read = inputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) {
                    throw new IllegalArgumentException("Invalid MP3 file");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static String normalizeLyricsForId3(String lyrics) {
        if (lyrics == null) {
            return "";
        }
        String normalized = lyrics.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        return String.format(Locale.US, "%s", normalized);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
