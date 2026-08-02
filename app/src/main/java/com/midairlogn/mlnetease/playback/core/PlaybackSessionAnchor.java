package com.midairlogn.mlnetease.playback.core;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.midairlogn.mlnetease.shared.model.Song;

public final class PlaybackSessionAnchor {
    private static final String TAG = "PlaybackSessionAnchor";
    private static PlaybackSessionAnchor instance;

    private final Context appContext;
    private final ComponentName mediaButtonReceiverComponent;
    private final PendingIntent mediaButtonReceiverIntent;
    private final MediaSessionCompat.Callback anchorCallback = new MediaSessionCompat.Callback() {
        @Override
        public void onPlay() {
            PlaybackActionDispatcher.resume(appContext);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            if (!PlaybackBrowserService.RECENT_MEDIA_ID.equals(mediaId)) {
                Log.w(TAG, "Ignoring unexpected media id: " + mediaId);
                return;
            }
            PlaybackActionDispatcher.resume(appContext);
        }

        @Override
        public void onPause() {
            PlaybackActionDispatcher.pause(appContext);
        }

        @Override
        public void onStop() {
            PlaybackActionDispatcher.pause(appContext);
        }

        @Override
        public void onSkipToNext() {
            PlaybackActionDispatcher.playNext(appContext);
        }

        @Override
        public void onSkipToPrevious() {
            PlaybackActionDispatcher.playPrevious(appContext);
        }

        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
            Log.d(TAG, "anchor media button action="
                    + (mediaButtonEvent == null ? "null" : mediaButtonEvent.getAction()));
            PlaybackActionDispatcher.dispatchMediaButtonIntent(appContext, mediaButtonEvent);
            return true;
        }
    };

    private MediaSessionCompat mediaSession;
    private boolean serviceAttached;

    private PlaybackSessionAnchor(Context context) {
        appContext = context.getApplicationContext();
        mediaButtonReceiverComponent = new ComponentName(appContext, PlaybackMediaButtonReceiver.class);
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(appContext, PlaybackMediaButtonReceiver.class);
        int mediaButtonFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mediaButtonFlags |= PendingIntent.FLAG_MUTABLE;
        }
        mediaButtonReceiverIntent = PendingIntent.getBroadcast(appContext, 0, mediaButtonIntent, mediaButtonFlags);
    }

    public static synchronized PlaybackSessionAnchor getInstance(Context context) {
        if (instance == null) {
            instance = new PlaybackSessionAnchor(context);
        }
        return instance;
    }

    public synchronized MediaSessionCompat attachServiceCallback(MediaSessionCompat.Callback callback) {
        ensureSession();
        serviceAttached = true;
        mediaSession.setCallback(callback);
        mediaSession.setActive(true);
        return mediaSession;
    }

    public synchronized void detachFromService(MusicPlayerManager musicPlayerManager) {
        ensureSession();
        serviceAttached = false;
        syncFromManagerSnapshot(musicPlayerManager);
        mediaSession.setCallback(anchorCallback);
        mediaSession.setActive(hasResumableTrack(musicPlayerManager));
    }

    public synchronized void ensureResumableSession(MusicPlayerManager musicPlayerManager) {
        ensureSession();
        if (serviceAttached) {
            return;
        }
        syncFromManagerSnapshot(musicPlayerManager);
        mediaSession.setCallback(anchorCallback);
        mediaSession.setActive(hasResumableTrack(musicPlayerManager));
    }

    public synchronized MediaSessionCompat.Token getSessionToken() {
        ensureSession();
        return mediaSession.getSessionToken();
    }

    public synchronized MediaSessionCompat getSession() {
        ensureSession();
        return mediaSession;
    }

    private void ensureSession() {
        if (mediaSession != null) {
            return;
        }
        mediaSession = new MediaSessionCompat(appContext, TAG, mediaButtonReceiverComponent, mediaButtonReceiverIntent);
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setMediaButtonReceiver(mediaButtonReceiverIntent);
        mediaSession.setCallback(anchorCallback);
        mediaSession.setActive(false);
    }

    private void syncFromManagerSnapshot(MusicPlayerManager musicPlayerManager) {
        Song currentSong = musicPlayerManager == null ? null : musicPlayerManager.getCurrentSong();
        if (currentSong == null) {
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY)
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
                    .build());
            mediaSession.setMetadata(new MediaMetadataCompat.Builder().build());
            return;
        }

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO;

        int state = musicPlayerManager != null && musicPlayerManager.isMediaPrepareInFlight()
                ? PlaybackStateCompat.STATE_BUFFERING
                : PlaybackStateCompat.STATE_PAUSED;

        long position = musicPlayerManager == null ? 0L : musicPlayerManager.getCurrentPosition();
        float speed = 0.0f;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, speed)
                .build());

        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentSong.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artists)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,
                        musicPlayerManager == null ? 0L : musicPlayerManager.getDuration());
        if (currentSong.picUrl != null && !currentSong.picUrl.isEmpty()) {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, currentSong.picUrl);
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, currentSong.picUrl);
        }
        mediaSession.setMetadata(metadataBuilder.build());
    }

    private boolean hasResumableTrack(MusicPlayerManager musicPlayerManager) {
        return musicPlayerManager != null && musicPlayerManager.getCurrentSong() != null;
    }
}
