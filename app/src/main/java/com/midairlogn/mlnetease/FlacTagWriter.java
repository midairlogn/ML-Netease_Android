package com.midairlogn.mlnetease;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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

        File preservedMetadata = File.createTempFile("ml_flac_metadata", ".bin");
        try {
            try (FileOutputStream preservedOutput = new FileOutputStream(preservedMetadata)) {
                copyPreservedMetadataBlocks(inputStream, preservedOutput);
            }

            try (FileInputStream preservedInput = new FileInputStream(preservedMetadata)) {
                copyStream(preservedInput, outputStream);
            }

            byte[] vorbisComment = buildVorbisComment(tagData);
            byte[] pictureBlock = tagData.coverData != null && tagData.coverData.length > 0
                    ? buildPictureBlock(tagData.coverData, tagData.coverMimeType)
                    : null;

            writeMetadataBlock(outputStream, 4, vorbisComment, pictureBlock == null);
            if (pictureBlock != null) {
                writeMetadataBlock(outputStream, 6, pictureBlock, true);
            }

            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
        } finally {
            if (preservedMetadata.exists()) {
                preservedMetadata.delete();
            }
        }
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

    private static void copyPreservedMetadataBlocks(InputStream inputStream, OutputStream outputStream) throws Exception {
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
            if (type != 4 && type != 6) {
                outputStream.write(type);
                outputStream.write(header[1] & 0xFF);
                outputStream.write(header[2] & 0xFF);
                outputStream.write(header[3] & 0xFF);
                copyExactly(inputStream, outputStream, length);
            } else {
                skipExactly(inputStream, length);
            }
        }
    }

    private static void copyStream(InputStream inputStream, OutputStream outputStream) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
    }

    private static void copyExactly(InputStream inputStream, OutputStream outputStream, int length) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        int remaining = length;
        while (remaining > 0) {
            int chunkSize = Math.min(buffer.length, remaining);
            int read = inputStream.read(buffer, 0, chunkSize);
            if (read == -1) {
                throw new IllegalArgumentException("Invalid FLAC metadata block");
            }
            outputStream.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipExactly(InputStream inputStream, int length) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        int remaining = length;
        while (remaining > 0) {
            int read = inputStream.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read == -1) {
                throw new IllegalArgumentException("Invalid FLAC metadata block");
            }
            remaining -= read;
        }
    }

    private static void writeMetadataBlock(OutputStream outputStream, int type, byte[] data, boolean isLast) throws Exception {
        outputStream.write((isLast ? 0x80 : 0) | type);
        outputStream.write((data.length >> 16) & 0xFF);
        outputStream.write((data.length >> 8) & 0xFF);
        outputStream.write(data.length & 0xFF);
        outputStream.write(data);
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
}
