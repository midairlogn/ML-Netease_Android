package com.midairlogn.mlnetease.playback.core;

import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;

import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.shared.model.Song;

import java.util.Collections;
import java.util.List;

public class PlaybackBrowserService extends MediaBrowserServiceCompat {
    private static final String ROOT_ID_RECENTS = "recents";
    private static final String RECENT_MEDIA_ID = "recent_resume";

    @Override
    public void onCreate() {
        super.onCreate();
        MusicPlayerManager musicPlayerManager = MusicPlayerManager.getInstance(this);
        musicPlayerManager.restorePlaybackSnapshotIfNeeded();
        PlaybackSessionAnchor anchor = PlaybackSessionAnchor.getInstance(this);
        anchor.ensureResumableSession(musicPlayerManager);
        setSessionToken(anchor.getSessionToken());
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        Bundle extras = new Bundle();
        extras.putBoolean(BrowserRoot.EXTRA_RECENT, true);
        return new BrowserRoot(ROOT_ID_RECENTS, extras);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        if (!ROOT_ID_RECENTS.equals(parentId)) {
            result.sendResult(Collections.emptyList());
            return;
        }

        MusicPlayerManager musicPlayerManager = MusicPlayerManager.getInstance(this);
        musicPlayerManager.restorePlaybackSnapshotIfNeeded();
        Song currentSong = musicPlayerManager.getCurrentSong();
        if (currentSong == null) {
            result.sendResult(Collections.emptyList());
            return;
        }

        PlaybackSessionAnchor.getInstance(this).ensureResumableSession(musicPlayerManager);
        result.sendResult(Collections.singletonList(new MediaBrowserCompat.MediaItem(
                buildRecentDescription(currentSong),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )));
    }

    private MediaDescriptionCompat buildRecentDescription(Song song) {
        MediaDescriptionCompat.Builder builder = new MediaDescriptionCompat.Builder()
                .setMediaId(RECENT_MEDIA_ID)
                .setTitle(song.name)
                .setSubtitle(song.artists)
                .setDescription(song.album);

        if (song.picUrl != null && !song.picUrl.isEmpty()) {
            builder.setIconUri(Uri.parse(song.picUrl));
        } else {
            builder.setIconUri(Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.ic_ml_app_logo_foreground));
        }
        return builder.build();
    }
}
