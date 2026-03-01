package com.midairlogn.mlnetease;

public class LyricLine {
    public long time;
    public String text;
    public String translation;

    public LyricLine(long time, String text) {
        this(time, text, "");
    }

    public LyricLine(long time, String text, String translation) {
        this.time = time;
        this.text = text;
        this.translation = translation == null ? "" : translation;
    }
}
