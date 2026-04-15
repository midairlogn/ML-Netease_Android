package com.midairlogn.mlnetease;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FlacTagWriter {
    private FlacTagWriter() {}

    public static void writeTaggedFile(File input, File output, DownloadTagData tagData) throws Exception {
        if (input == null || output == null) {
            throw new IllegalArgumentException("Input and output files are required");
        }

        try (FileInputStream inputStream = new FileInputStream(input);
             FileOutputStream outputStream = new FileOutputStream(output)) {
            writeTaggedFile(inputStream, outputStream, tagData);
        }
    }

    private static void writeTaggedFile(InputStream inputStream, FileOutputStream outputStream, DownloadTagData tagData) throws Exception {
        byte[] signature = new byte[4];
        if (readFully(inputStream, signature, 0, signature.length) != signature.length
                || signature[0] != 'f'
                || signature[1] != 'L'
                || signature[2] != 'a'
                || signature[3] != 'C') {
            throw new IllegalArgumentException("Invalid FLAC file");
        }

        outputStream.write(signature);

        List<MetadataBlock> keptBlocks = new ArrayList<>();
        byte[] header = new byte[4];
        boolean reachedLastBlock = false;
        while (!reachedLastBlock) {
            if (readFully(inputStream, header, 0, header.length) != header.length) {
                throw new IllegalArgumentException("Invalid FLAC metadata header");
            }
            int blockHeader = header[0] & 0xFF;
            reachedLastBlock = (blockHeader & 0x80) != 0;
            int type = blockHeader & 0x7F;
            int length = ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            byte[] data = new byte[length];
            if (readFully(inputStream, data, 0, length) != length) {
                throw new IllegalArgumentException("Invalid FLAC metadata block");
            }
            if (type != 4 && type != 6) {
                keptBlocks.add(new MetadataBlock(type, data));
            }
        }

        keptBlocks.add(new MetadataBlock(4, buildVorbisComment(tagData)));
        if (tagData.coverData != null && tagData.coverData.length > 0) {
            keptBlocks.add(new MetadataBlock(6, buildPictureBlock(tagData.coverData, tagData.coverMimeType)));
        }

        for (int i = 0; i < keptBlocks.size(); i++) {
            MetadataBlock block = keptBlocks.get(i);
            boolean isLast = i == keptBlocks.size() - 1;
            outputStream.write((isLast ? 0x80 : 0) | block.type);
            outputStream.write((block.data.length >> 16) & 0xFF);
            outputStream.write((block.data.length >> 8) & 0xFF);
            outputStream.write(block.data.length & 0xFF);
            outputStream.write(block.data);
        }

        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        outputStream.flush();
    }

    private static int readFully(InputStream inputStream, byte[] buffer, int offset, int length) throws Exception {
        int totalRead = 0;
        while (totalRead < length) {
            int read = inputStream.read(buffer, offset + totalRead, length - totalRead);
            if (read == -1) {
                break;
            }
            totalRead += read;
        }
        return totalRead;
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
