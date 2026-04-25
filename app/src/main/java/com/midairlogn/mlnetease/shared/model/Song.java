package com.midairlogn.mlnetease.shared.model;

import android.net.Uri;

import java.io.Serializable;

public class Song implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final String SOURCE_REMOTE = "remote";
    public static final String SOURCE_LOCAL_MEDIASTORE = "local_mediastore";
    public static final String SOURCE_LOCAL_URI = "local_uri";

    public String id;
    public String name;
    public String artists;
    public String album;
    public String picUrl;
    public String sourceType;
    public String mediaUri;
    public String mimeType;
    public long durationMs;
    public String lyric;
    public String translatedLyric;
    public byte[] embeddedPicture;

    public Song(String id, String name, String artists, String album, String picUrl) {
        this.id = id;
        this.name = name;
        this.artists = artists;
        this.album = album;
        this.picUrl = picUrl;
        this.sourceType = SOURCE_REMOTE;
        this.mediaUri = "";
        this.mimeType = "";
        this.durationMs = 0L;
        this.lyric = "";
        this.translatedLyric = "";
        this.embeddedPicture = null;
    }

    public Song(String id, String name, String artists, String album, String picUrl,
                String sourceType, String mediaUri, String mimeType, long durationMs) {
        this(id, name, artists, album, picUrl);
        this.sourceType = isLocalSourceType(sourceType) ? sourceType : SOURCE_REMOTE;
        this.mediaUri = mediaUri == null ? "" : mediaUri;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.durationMs = Math.max(0L, durationMs);
    }

    public boolean isLocal() {
        return isLocalSourceType(sourceType);
    }

    public Uri getMediaUri() {
        if (mediaUri == null || mediaUri.trim().isEmpty()) {
            return null;
        }
        return Uri.parse(mediaUri);
    }

    public static boolean isLocalSourceType(String sourceType) {
        return SOURCE_LOCAL_MEDIASTORE.equals(sourceType) || SOURCE_LOCAL_URI.equals(sourceType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Song song = (Song) o;
        return id != null ? id.equals(song.id) : song.id == null;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
