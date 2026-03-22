package com.midairlogn.mlnetease;

import java.io.Serializable;

public class HomeShortcut implements Serializable {
    public static final String TYPE_PLAYLIST = "playlist";
    public static final String TYPE_ALBUM = "album";

    public String title;
    public String id;
    public String type;
    public int sequence;

    public HomeShortcut(String title, String id, String type, int sequence) {
        this.title = title;
        this.id = id;
        this.type = type;
        this.sequence = sequence;
    }

    public boolean isPlaylist() {
        return TYPE_PLAYLIST.equals(type);
    }

    public boolean isAlbum() {
        return TYPE_ALBUM.equals(type);
    }
}
