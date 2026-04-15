package com.midairlogn.mlnetease;

import java.io.Serializable;

public class FavouriteSong implements Serializable {
    public String id;
    public String name;
    public String artists;
    public String album;
    public String sourceType;
    public String mediaUri;
    public String mimeType;
    public long durationMs;
    public int sequence;

    public FavouriteSong(String id, String name, String artists, String album,
                         String sourceType, String mediaUri, String mimeType,
                         long durationMs, int sequence) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.artists = artists == null ? "" : artists;
        this.album = album == null ? "" : album;
        this.sourceType = sourceType == null ? Song.SOURCE_REMOTE : sourceType;
        this.mediaUri = mediaUri == null ? "" : mediaUri;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.durationMs = Math.max(0L, durationMs);
        this.sequence = sequence;
    }

    public static FavouriteSong fromSong(Song song, int sequence) {
        if (song == null) {
            return null;
        }
        return new FavouriteSong(
                song.id,
                song.name,
                song.artists,
                song.album,
                song.sourceType,
                song.isLocal() ? song.mediaUri : "",
                song.isLocal() ? song.mimeType : "",
                song.isLocal() ? song.durationMs : 0L,
                sequence
        );
    }

    public Song toSong() {
        return new Song(id, name, artists, album, "", sourceType, mediaUri, mimeType, durationMs);
    }

    public boolean matchesSong(Song song) {
        if (song == null) {
            return false;
        }
        return id.equals(song.id);
    }
}
