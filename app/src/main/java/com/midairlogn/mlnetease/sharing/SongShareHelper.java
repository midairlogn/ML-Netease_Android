package com.midairlogn.mlnetease.sharing;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.midairlogn.mlnetease.download.core.PreparedAudioFile;
import com.midairlogn.mlnetease.download.core.RemoteAudioPreparationHelper;
import com.midairlogn.mlnetease.shared.model.Song;

import java.io.File;
import java.util.concurrent.TimeUnit;

public class SongShareHelper {
    private static final String SHARE_DIRECTORY = "shared_audio";
    private static final long MAX_SHARE_FILE_AGE_MS = TimeUnit.HOURS.toMillis(6);

    private final Context appContext;
    private final RemoteAudioPreparationHelper remoteAudioPreparationHelper;

    public SongShareHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.remoteAudioPreparationHelper = new RemoteAudioPreparationHelper(appContext);
    }

    public Uri shareLocalSong(Song song) {
        return song == null ? null : song.getMediaUri();
    }

    public PreparedAudioFile prepareRemoteAudioForSharing(Song song,
                                                          RemoteAudioPreparationHelper.CancellationSignal cancellationSignal,
                                                          RemoteAudioPreparationHelper.ProgressListener progressListener) throws Exception {
        File shareDirectory = getShareDirectory();
        cleanupExpiredShareFiles(shareDirectory);
        File sessionDirectory = new File(shareDirectory, String.valueOf(System.currentTimeMillis()));
        if (!sessionDirectory.exists()) {
            sessionDirectory.mkdirs();
        }
        return remoteAudioPreparationHelper.prepareSharedAudio(song, sessionDirectory, cancellationSignal, progressListener);
    }

    public Uri getSharableUri(File file) {
        return FileProvider.getUriForFile(appContext, appContext.getPackageName() + ".fileprovider", file);
    }

    private File getShareDirectory() {
        File directory = new File(appContext.getCacheDir(), SHARE_DIRECTORY);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }

    private void cleanupExpiredShareFiles(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - MAX_SHARE_FILE_AGE_MS;
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file == null || file.lastModified() >= cutoff) {
                continue;
            }
            deleteRecursively(file);
        }
    }

    private void deleteRecursively(File file) {
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
