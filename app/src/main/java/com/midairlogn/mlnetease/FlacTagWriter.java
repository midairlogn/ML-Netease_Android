package com.midairlogn.mlnetease;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FlacTagWriter {
    private FlacTagWriter() {}

    public static byte[] writeTaggedBytes(byte[] audioBytes, DownloadTagData tagData) throws Exception {
        if (audioBytes == null || audioBytes.length < 4 || audioBytes[0] != 'f' || audioBytes[1] != 'L' || audioBytes[2] != 'a' || audioBytes[3] != 'C') {
            return audioBytes;
        }

        List<MetadataBlock> keptBlocks = new ArrayList<>();
        int offset = 4;
        int audioStart = 4;
        while (offset < audioBytes.length) {
            int header = audioBytes[offset] & 0xFF;
            boolean isLast = (header & 0x80) != 0;
            int type = header & 0x7F;
            int length = ((audioBytes[offset + 1] & 0xFF) << 16) | ((audioBytes[offset + 2] & 0xFF) << 8) | (audioBytes[offset + 3] & 0xFF);
            int dataStart = offset + 4;
            int end = dataStart + length;
            if (type != 4 && type != 6) {
                byte[] data = new byte[length];
                System.arraycopy(audioBytes, dataStart, data, 0, length);
                keptBlocks.add(new MetadataBlock(type, data));
            }
            offset = end;
            if (isLast) {
                audioStart = end;
                break;
            }
        }

        keptBlocks.add(new MetadataBlock(4, buildVorbisComment(tagData)));
        if (tagData.coverData != null && tagData.coverData.length > 0) {
            keptBlocks.add(new MetadataBlock(6, buildPictureBlock(tagData.coverData, tagData.coverMimeType)));
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(audioBytes, 0, 4);
        for (int i = 0; i < keptBlocks.size(); i++) {
            MetadataBlock block = keptBlocks.get(i);
            boolean last = i == keptBlocks.size() - 1;
            output.write((last ? 0x80 : 0) | block.type);
            output.write((block.data.length >> 16) & 0xFF);
            output.write((block.data.length >> 8) & 0xFF);
            output.write(block.data.length & 0xFF);
            output.write(block.data);
        }
        output.write(audioBytes, audioStart, audioBytes.length - audioStart);
        return output.toByteArray();
    }

    private static byte[] buildVorbisComment(DownloadTagData tagData) throws Exception {
        Map<String, String> comments = new LinkedHashMap<>();
        putIfNotEmpty(comments, "TITLE", tagData.title);
        putIfNotEmpty(comments, "ARTIST", tagData.artist);
        putIfNotEmpty(comments, "ALBUM", tagData.album);
        putIfNotEmpty(comments, "LYRICS", tagData.lyrics);
        putIfNotEmpty(comments, "COMMENT", tagData.comment);
        putIfNotEmpty(comments, "QUALITY", tagData.quality);
        putIfNotEmpty(comments, "NETEASE_SONG_ID", tagData.songId);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] vendor = "ML-Netease Android".getBytes(StandardCharsets.UTF_8);
        writeIntLE(output, vendor.length);
        output.write(vendor);
        writeIntLE(output, comments.size());
        for (Map.Entry<String, String> entry : comments.entrySet()) {
            byte[] value = (entry.getKey() + "=" + entry.getValue()).getBytes(StandardCharsets.UTF_8);
            writeIntLE(output, value.length);
            output.write(value);
        }
        return output.toByteArray();
    }

    private static byte[] buildPictureBlock(byte[] coverData, String mimeType) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] mime = (mimeType == null ? "image/jpeg" : mimeType).getBytes(StandardCharsets.UTF_8);
        byte[] description = "Cover".getBytes(StandardCharsets.UTF_8);
        writeIntBE(output, 3);
        writeIntBE(output, mime.length);
        output.write(mime);
        writeIntBE(output, description.length);
        output.write(description);
        writeIntBE(output, 0);
        writeIntBE(output, 0);
        writeIntBE(output, 0);
        writeIntBE(output, 0);
        writeIntBE(output, coverData.length);
        output.write(coverData);
        return output.toByteArray();
    }

    private static void putIfNotEmpty(Map<String, String> map, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            map.put(key, value);
        }
    }

    private static void writeIntLE(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write((value >> 16) & 0xFF);
        output.write((value >> 24) & 0xFF);
    }

    private static void writeIntBE(ByteArrayOutputStream output, int value) {
        output.write((value >> 24) & 0xFF);
        output.write((value >> 16) & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static final class MetadataBlock {
        final int type;
        final byte[] data;

        MetadataBlock(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }
}
