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
    private static final ExecutorService CLEANUP_EXECUTOR = Executors.newSingleThreadExecutor();

    private ShareCacheCleaner() {
    }

    public static void cleanupExpiredAsync(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        CLEANUP_EXECUTOR.execute(() -> cleanupExpired(appContext));
    }

    public static void cleanupExpired(Context context) {
        if (context == null) {
            return;
        }
        File cacheDir = context.getApplicationContext().getCacheDir();
        long cutoff = System.currentTimeMillis() - MAX_SHARE_FILE_AGE_MS;
        cleanupExpiredInDirectory(new File(cacheDir, SHARED_AUDIO_DIRECTORY), cutoff);
        cleanupExpiredInDirectory(new File(cacheDir, SHARED_IMAGE_DIRECTORY), cutoff);
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
            if (file != null && file.lastModified() < cutoff) {
                deleteRecursively(file);
            }
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
