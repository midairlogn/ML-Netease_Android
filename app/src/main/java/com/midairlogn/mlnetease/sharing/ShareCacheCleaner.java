package com.midairlogn.mlnetease.sharing;

import android.content.Context;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ShareCacheCleaner {
    public static final String SHARED_AUDIO_DIRECTORY = "shared_audio";
    public static final String SHARED_IMAGE_DIRECTORY = "shared_images";
    private static final long MAX_SHARE_FILE_AGE_MS = TimeUnit.HOURS.toMillis(6);
    private static final long CLEANUP_THROTTLE_MS = TimeUnit.MINUTES.toMillis(30);
    private static final ExecutorService CLEANUP_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Object CLEANUP_LOCK = new Object();
    private static long lastCleanupAt;
    private static boolean cleanupScheduled;

    private ShareCacheCleaner() {
    }

    public static void cleanupExpiredAsync(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        if (!shouldScheduleCleanup()) {
            return;
        }
        CLEANUP_EXECUTOR.execute(() -> {
            try {
                cleanupExpiredNow(appContext);
            } finally {
                markCleanupFinished();
            }
        });
    }

    public static void cleanupExpired(Context context) {
        if (context == null) {
            return;
        }
        if (!shouldRunCleanup()) {
            return;
        }
        cleanupExpiredNow(context.getApplicationContext());
    }

    private static void cleanupExpiredNow(Context context) {
        File cacheDir = context.getApplicationContext().getCacheDir();
        long cutoff = System.currentTimeMillis() - MAX_SHARE_FILE_AGE_MS;
        cleanupExpiredInDirectory(new File(cacheDir, SHARED_AUDIO_DIRECTORY), cutoff);
        cleanupExpiredInDirectory(new File(cacheDir, SHARED_IMAGE_DIRECTORY), cutoff);
    }

    private static boolean shouldScheduleCleanup() {
        synchronized (CLEANUP_LOCK) {
            long now = System.currentTimeMillis();
            if (cleanupScheduled || now - lastCleanupAt < CLEANUP_THROTTLE_MS) {
                return false;
            }
            cleanupScheduled = true;
            lastCleanupAt = now;
            return true;
        }
    }

    private static boolean shouldRunCleanup() {
        synchronized (CLEANUP_LOCK) {
            long now = System.currentTimeMillis();
            if (now - lastCleanupAt < CLEANUP_THROTTLE_MS) {
                return false;
            }
            lastCleanupAt = now;
            return true;
        }
    }

    private static void markCleanupFinished() {
        synchronized (CLEANUP_LOCK) {
            cleanupScheduled = false;
        }
    }

    private static void cleanupExpiredInDirectory(File directory, long cutoff) {
        if (directory == null || !directory.exists()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file != null && isExpiredShareEntry(file, cutoff)) {
                deleteRecursively(file);
            }
        }
    }

    private static boolean isExpiredShareEntry(File file, long cutoff) {
        Long timestamp = extractTimestamp(file.getName());
        if (timestamp != null) {
            return timestamp < cutoff;
        }
        return file.lastModified() < cutoff;
    }

    private static Long extractTimestamp(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String timestampText = name;
        if (name.startsWith("cover_")) {
            int dotIndex = name.indexOf('.', 6);
            timestampText = dotIndex > 6 ? name.substring(6, dotIndex) : name.substring(6);
        }
        try {
            return Long.parseLong(timestampText);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
