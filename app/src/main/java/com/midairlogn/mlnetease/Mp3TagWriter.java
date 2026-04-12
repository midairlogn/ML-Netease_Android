package com.midairlogn.mlnetease;

import com.mpatric.mp3agic.ID3v24Tag;
import com.mpatric.mp3agic.Mp3File;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

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
            tag.setLyrics(tagData.lyrics);
            tag.setComment(tagData.comment);
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
}
