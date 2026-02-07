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
}