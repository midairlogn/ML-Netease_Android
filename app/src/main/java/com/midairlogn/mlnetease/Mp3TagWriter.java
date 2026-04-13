package com.midairlogn.mlnetease;

import com.mpatric.mp3agic.ID3v24Tag;
import com.mpatric.mp3agic.Mp3File;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Locale;

public final class Mp3TagWriter {
    private Mp3TagWriter() {}

    public static byte[] writeTaggedBytes(byte[] audioBytes, DownloadTagData tagData) throws Exception {
        File input = File.createTempFile("ml_song_input", ".mp3");
        File output = File.createTempFile("ml_song_output", ".mp3");
        try {
            try (FileOutputStream outputStream = new FileOutputStream(input)) {
                outputStream.write(audioBytes);
            }

            Mp3File mp3File = new Mp3File(input.getAbsolutePath());
            ID3v24Tag tag = new ID3v24Tag();
            tag.setTitle(tagData.title);
            tag.setArtist(tagData.artist);
            tag.setAlbum(tagData.album);
            tag.setComment(tagData.comment);
            if (tagData.lyrics != null && !tagData.lyrics.trim().isEmpty()) {
                tag.setLyrics(normalizeLyricsForId3(tagData.lyrics));
            }
            if (tagData.coverData != null && tagData.coverData.length > 0) {
                tag.setAlbumImage(tagData.coverData, tagData.coverMimeType);
            }
            mp3File.setId3v2Tag(tag);
            mp3File.save(output.getAbsolutePath());
            return Files.readAllBytes(output.toPath());
        } finally {
            input.delete();
            output.delete();
        }
    }

    private static String normalizeLyricsForId3(String lyrics) {
        String normalized = lyrics.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        return String.format(Locale.US, "%s", normalized);
    }
}
