package com.midairlogn.mlnetease;

public class Song {
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