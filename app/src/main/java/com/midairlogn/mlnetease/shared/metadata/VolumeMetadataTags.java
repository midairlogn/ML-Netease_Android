package com.midairlogn.mlnetease.shared.metadata;

import java.util.Locale;

public final class VolumeMetadataTags {
    public static final String REPLAYGAIN_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN";
    public static final String REPLAYGAIN_TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK";
    public static final String NETEASE_GAIN = "NETEASE_GAIN";
    public static final String NETEASE_PEAK = "NETEASE_PEAK";
    public static final String NETEASE_CLOSED_GAIN = "NETEASE_CLOSED_GAIN";
    public static final String NETEASE_CLOSED_PEAK = "NETEASE_CLOSED_PEAK";

    private VolumeMetadataTags() {}

    public static String formatGainDb(float gainDb) {
        return formatNumber(gainDb) + " dB";
    }

    public static String formatNumber(float value) {
        return String.format(Locale.US, "%.4f", value);
    }

    public static float parseFloat(String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim().replace("dB", "").replace("DB", "").replace("db", "").trim();
        if (normalized.isEmpty()) {
            return fallback;
        }
        try {
            float parsed = Float.parseFloat(normalized);
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
