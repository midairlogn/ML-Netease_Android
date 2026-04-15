package com.midairlogn.mlnetease;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class DownloadRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String TYPE_SINGLE = "single";
    public static final String TYPE_PLAYLIST = "playlist";
    public static final String TYPE_ALBUM = "album";

    public final String type;
    public final String title;
    public final List<Song> songs;

    public DownloadRequest(String type, String title, List<Song> songs) {
        this.type = type;
        this.title = title == null ? "" : title;
        this.songs = songs == null ? new ArrayList<>() : new ArrayList<>(songs);
    }
}
