package com.midairlogn.mlnetease.local.media;

public class LocalAudioMetadata {
    public boolean isPlayable = false;
    public String title = "";
    public String artist = "";
    public String album = "";
    public String mimeType = "";
    public long durationMs = 0L;
    public String lyric = "";
    public String translatedLyric = "";
    public byte[] artworkData = null;
    public float gainDb = 0f;
    public float peak = 0f;
    public float closedGainDb = 0f;
    public float closedPeak = 0f;
    public boolean hasLoudnessNormalization = false;
}
