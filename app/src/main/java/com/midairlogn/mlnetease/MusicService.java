package com.midairlogn.mlnetease;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MusicService extends Service {
    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "music_channel";
    private static final int NOTIFICATION_ID = 1;

    private MediaSessionCompat mediaSession;
    private MusicPlayerManager musicPlayerManager;
    private NotificationManager notificationManager;
    private FloatingLyricsManager floatingLyricsManager;
    private android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final MainApplication.AppVisibilityListener appVisibilityListener = isForeground -> {
        if (floatingLyricsManager != null) {
            floatingLyricsManager.setAppVisible(isForeground);
        }
    };

    private final MusicPlayerManager.OnSongChangedListener songChangedListener = new MusicPlayerManager.OnSongChangedListener() {
        @Override
        public void onSongChanged(Song song) {
            updateMetadata(song);
            if (floatingLyricsManager != null) {
                floatingLyricsManager.updateLyrics(musicPlayerManager.getCurrentLyric());
                floatingLyricsManager.updateSongInfo(song);
            }
        }
    };

    private final MusicPlayerManager.OnPlaybackStateChangedListener playbackStateChangedListener = this::updatePlaybackState;

    private final MusicPlayerManager.OnPlaybackModeChangedListener playbackModeChangedListener = mode -> {
        // Ensure PlaybackState is updated with new Custom Action icon for mode
        updatePlaybackState(musicPlayerManager.isPlaying());
    };

    @Override
    public void onCreate() {
        super.onCreate();
        musicPlayerManager = MusicPlayerManager.getInstance(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        floatingLyricsManager = new FloatingLyricsManager(this);

        if (getApplication() instanceof MainApplication) {
            MainApplication app = (MainApplication) getApplication();
            app.addAppVisibilityListener(appVisibilityListener);
            // Restore floating window if enabled AND app is background
            floatingLyricsManager.setAppVisible(app.isAppForeground());
        }

        createNotificationChannel();
        initMediaSession();

        // Listen to player changes
        musicPlayerManager.addOnSongChangedListener(songChangedListener);
        musicPlayerManager.addOnPlaybackStateChangedListener(playbackStateChangedListener);
        musicPlayerManager.addOnPlaybackModeChangedListener(playbackModeChangedListener);

        // Initial notification to satisfy startForegroundService requirements
        Song currentSong = musicPlayerManager.getCurrentSong();
        if (currentSong != null) {
            updateMetadata(currentSong);
        } else {
            Song placeholder = new Song("", "Music Player", "Ready to play", "", "");
            showNotification(placeholder, false, BitmapFactory.decodeResource(getResources(), R.drawable.ic_app_logo));
        }

        // Ensure PlaybackState is initialized with CustomActions
        updatePlaybackState(musicPlayerManager.isPlaying());
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onCustomAction(String action, Bundle extras) {
                if ("ACTION_TOGGLE_MODE".equals(action)) {
                    musicPlayerManager.togglePlaybackMode();
                } else if ("ACTION_TOGGLE_FLOATING".equals(action)) {
                    SettingsManager sm = new SettingsManager(MusicService.this);
                    boolean newState = !sm.isFloatingLyricsEnabled();
                    sm.setFloatingLyricsEnabled(newState);
                    if (floatingLyricsManager != null) {
                        floatingLyricsManager.onSettingChanged();
                    }
                    showNotification(musicPlayerManager.getCurrentSong(), musicPlayerManager.isPlaying(), null);
                    // Force update playback state to refresh icons in media control
                    updatePlaybackState(musicPlayerManager.isPlaying());
                }
            }

            @Override
            public void onPlay() {
                musicPlayerManager.resume();
            }

            @Override
            public void onPause() {
                musicPlayerManager.pause();
            }

            @Override
            public void onSkipToNext() {
                musicPlayerManager.playNext();
            }

            @Override
            public void onSkipToPrevious() {
                musicPlayerManager.playPrevious();
            }

            @Override
            public void onStop() {
                musicPlayerManager.pause();
                stopSelf();
            }

            @Override
            public void onSeekTo(long pos) {
                musicPlayerManager.seekTo((int) pos);
                updatePlaybackState(musicPlayerManager.isPlaying());
            }
        });

        mediaSession.setActive(true);
    }

    private void updateMetadata(Song song) {
        if (song == null) return;

        // Show immediate notification with default/current art to ensure responsiveness
        Bitmap currentArt = BitmapFactory.decodeResource(getResources(), R.drawable.ic_app_logo);
        MediaMetadataCompat metadata = mediaSession.getController().getMetadata();
        if (metadata != null) {
            Bitmap existingArt = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
            if (existingArt != null) {
                currentArt = existingArt;
            }
        }
        showNotification(song, musicPlayerManager.isPlaying(), currentArt);

        // Fetch album art async
        new Thread(() -> {
            Bitmap albumArt = null;
            if (song.picUrl != null && !song.picUrl.isEmpty()) {
                try {
                    URL url = new URL(song.picUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setDoInput(true);
                    connection.connect();
                    InputStream input = connection.getInputStream();
                    albumArt = BitmapFactory.decodeStream(input);
                } catch (Exception e) {
                    Log.e(TAG, "Error fetching album art", e);
                    // Fallback to app icon or null
                    albumArt = BitmapFactory.decodeResource(getResources(), R.drawable.ic_app_logo);
                }
            }

            if (albumArt == null) {
                 albumArt = BitmapFactory.decodeResource(getResources(), R.drawable.ic_app_logo);
            }

            Bitmap finalAlbumArt = albumArt;

            // Get duration if available, else 0
            long duration = musicPlayerManager.getDuration();

            MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.name)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artists)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration > 0 ? duration : 0)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, finalAlbumArt)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, finalAlbumArt);

            mediaSession.setMetadata(builder.build());
            showNotification(song, musicPlayerManager.isPlaying(), finalAlbumArt);
        }).start();
    }

    private void updatePlaybackState(boolean isPlaying) {
        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO;

        // Ensure duration is updated in metadata if available
        long duration = musicPlayerManager.getDuration();
        if (duration > 0) {
            MediaMetadataCompat currentMeta = mediaSession.getController().getMetadata();
            if (currentMeta != null) {
                long metaDuration = currentMeta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
                if (metaDuration != duration) {
                     MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder(currentMeta);
                     builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
                     mediaSession.setMetadata(builder.build());
                }
            }
        }

        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, musicPlayerManager.getCurrentPosition(), 1.0f);

        // Add Custom Actions for Android 13+ Media Controls
        // Mode Action
        int modeIcon = R.drawable.ic_mode_order;
        switch(musicPlayerManager.getPlaybackMode()) {
            case MusicPlayerManager.MODE_LOOP_ONE: modeIcon = R.drawable.ic_mode_loop_one; break;
            case MusicPlayerManager.MODE_LOOP_ALL: modeIcon = R.drawable.ic_mode_loop_all; break;
            case MusicPlayerManager.MODE_SHUFFLE: modeIcon = R.drawable.ic_mode_shuffle; break;
            default: modeIcon = R.drawable.ic_mode_order; break;
        }
        builder.addCustomAction("ACTION_TOGGLE_MODE", "Mode", modeIcon);

        // Lyrics Action
        SettingsManager sm = new SettingsManager(this);
        boolean isFloatingEnabled = sm.isFloatingLyricsEnabled();
        int floatIcon = isFloatingEnabled ? R.drawable.ic_floating_active : R.drawable.ic_floating;
        builder.addCustomAction("ACTION_TOGGLE_FLOATING", "Lyrics", floatIcon);

        mediaSession.setPlaybackState(builder.build());

        showNotification(musicPlayerManager.getCurrentSong(), isPlaying, null);
    }

    private void showNotification(Song song, boolean isPlaying, Bitmap albumArt) {
        if (song == null) return;

        // If albumArt is passed as null, try to retrieve from current metadata
        if (albumArt == null) {
            MediaMetadataCompat metadata = mediaSession.getController().getMetadata();
            if (metadata != null) {
                albumArt = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
            }
            if (albumArt == null) {
                 albumArt = BitmapFactory.decodeResource(getResources(), R.drawable.ic_app_logo);
            }
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Action playPauseAction = isPlaying ?
                new NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)) :
                new NotificationCompat.Action(android.R.drawable.ic_media_play, "Play",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY));

        // Mode Action
        int modeIcon = R.drawable.ic_mode_order;
        switch(musicPlayerManager.getPlaybackMode()) {
            case MusicPlayerManager.MODE_LOOP_ONE: modeIcon = R.drawable.ic_mode_loop_one; break;
            case MusicPlayerManager.MODE_LOOP_ALL: modeIcon = R.drawable.ic_mode_loop_all; break;
            case MusicPlayerManager.MODE_SHUFFLE: modeIcon = R.drawable.ic_mode_shuffle; break;
            default: modeIcon = R.drawable.ic_mode_order; break;
        }
        PendingIntent modePendingIntent = PendingIntent.getService(this, 1,
            new Intent(this, MusicService.class).setAction("ACTION_TOGGLE_MODE"), PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action modeAction = new NotificationCompat.Action(modeIcon, "Mode", modePendingIntent);

        // Floating Action
        SettingsManager sm = new SettingsManager(this);
        boolean isFloatingEnabled = sm.isFloatingLyricsEnabled();
        int floatIcon = isFloatingEnabled ? R.drawable.ic_floating_active : R.drawable.ic_floating;

        PendingIntent floatPendingIntent = PendingIntent.getService(this, 2,
            new Intent(this, MusicService.class).setAction("ACTION_TOGGLE_FLOATING"), PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action floatAction = new NotificationCompat.Action(floatIcon, "Lyrics", floatPendingIntent);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(song.name)
                .setContentText(song.artists)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setLargeIcon(albumArt)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(modeAction)
                .addAction(android.R.drawable.ic_media_previous, "Previous",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
                .addAction(playPauseAction)
                .addAction(android.R.drawable.ic_media_next, "Next",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
                .addAction(floatAction)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2, 3, 4)) // Show all actions in compact view if space permits, or prioritize play/pause
                .setOngoing(isPlaying)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Music playback controls");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if ("ACTION_TOGGLE_MODE".equals(action)) {
                musicPlayerManager.togglePlaybackMode();
            } else if ("ACTION_TOGGLE_FLOATING".equals(action)) {
                SettingsManager sm = new SettingsManager(this);
                boolean newState = !sm.isFloatingLyricsEnabled();
                sm.setFloatingLyricsEnabled(newState);
                if (floatingLyricsManager != null) {
                    floatingLyricsManager.onSettingChanged();
                }
                showNotification(musicPlayerManager.getCurrentSong(), musicPlayerManager.isPlaying(), null);
                updatePlaybackState(musicPlayerManager.isPlaying());
            } else if ("ACTION_UPDATE_SETTINGS".equals(action)) {
                if (floatingLyricsManager != null) {
                    floatingLyricsManager.onSettingChanged();
                }
                showNotification(musicPlayerManager.getCurrentSong(), musicPlayerManager.isPlaying(), null);
                updatePlaybackState(musicPlayerManager.isPlaying());
            }
        }
        MediaButtonReceiver.handleIntent(mediaSession, intent);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (getApplication() instanceof MainApplication) {
            MainApplication app = (MainApplication) getApplication();
            app.removeAppVisibilityListener(appVisibilityListener);
        }
        musicPlayerManager.removeOnSongChangedListener(songChangedListener);
        musicPlayerManager.removeOnPlaybackStateChangedListener(playbackStateChangedListener);
        musicPlayerManager.removeOnPlaybackModeChangedListener(playbackModeChangedListener);
        if (floatingLyricsManager != null) {
            floatingLyricsManager.hide();
        }
        mediaSession.release();
        super.onDestroy();
    }
}
