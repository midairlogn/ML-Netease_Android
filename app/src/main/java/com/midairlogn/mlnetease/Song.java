package com.midairlogn.mlnetease;

import java.io.Serializable;

public class Song implements Serializable {
    private static final long serialVersionUID = 1L;
    public String id;
    public String name;
    public String artists;
    public String album;
    public String picUrl;

    public Song(String id, String name, String artists, String album, String picUrl) {
        this.id = id;
        this.name = name;
        this.artists = artists;
        this.album = album;
        this.picUrl = picUrl;
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