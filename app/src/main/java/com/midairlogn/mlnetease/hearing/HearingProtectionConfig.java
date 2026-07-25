package com.midairlogn.mlnetease.hearing;

public final class HearingProtectionConfig {
    public static final int DEFAULT_LISTEN_MINUTES = 90;
    public static final int MIN_LISTEN_MINUTES = 15;
    public static final int MAX_LISTEN_MINUTES = 240;

    public static final int DEFAULT_REST_MINUTES = 15;
    public static final int MIN_REST_MINUTES = 3;
    public static final int MAX_REST_MINUTES = 90;

    private static final int[] LISTEN_DURATION_OPTIONS_MINUTES = {30, 45, 60, 90, 120, 150, 180};
    private static final int[] REST_DURATION_OPTIONS_MINUTES = {5, 10, 15, 20, 30, 45, 60};

    private HearingProtectionConfig() {
    }

    public static int clampListenMinutes(int minutes) {
        return Math.max(MIN_LISTEN_MINUTES, Math.min(minutes, MAX_LISTEN_MINUTES));
    }

    public static int clampRestMinutes(int minutes) {
        return Math.max(MIN_REST_MINUTES, Math.min(minutes, MAX_REST_MINUTES));
    }

    public static int[] getListenDurationOptionsMinutes() {
        return LISTEN_DURATION_OPTIONS_MINUTES.clone();
    }

    public static int[] getRestDurationOptionsMinutes() {
        return REST_DURATION_OPTIONS_MINUTES.clone();
    }

    public static double getPlaybackIntensityMultiplier(int appVolume) {
        if (appVolume >= 95) {
            return 1.65d;
        } else if (appVolume >= 90) {
            return 1.45d;
        } else if (appVolume >= 80) {
            return 1.2d;
        } else if (appVolume >= 65) {
            return 1.0d;
        } else if (appVolume >= 45) {
            return 0.82d;
        } else {
            return 0.68d;
        }
    }

    public static double getRecoveryIntensityPenalty(double intensityMultiplier) {
        if (intensityMultiplier >= 1.6d) {
            return 1.6d;
        } else if (intensityMultiplier >= 1.4d) {
            return 1.35d;
        } else if (intensityMultiplier >= 1.15d) {
            return 1.15d;
        } else {
            return 1.0d;
        }
    }
}
