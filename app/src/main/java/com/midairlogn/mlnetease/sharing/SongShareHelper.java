package com.midairlogn.mlnetease.sharing;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.midairlogn.mlnetease.download.core.PreparedAudioFile;
import com.midairlogn.mlnetease.download.core.RemoteAudioPreparationHelper;
import com.midairlogn.mlnetease.shared.model.Song;

import java.io.File;

public class SongShareHelper {
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
        ShareCacheCleaner.cleanupExpiredAsync(appContext);
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
        File directory = new File(appContext.getCacheDir(), ShareCacheCleaner.SHARED_AUDIO_DIRECTORY);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }
}
