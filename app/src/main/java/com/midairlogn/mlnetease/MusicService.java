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
import androidx.core.app.NotificationManagerCompat;
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
    private String lastSongId = "";
    private String lastPicUrl = "";
    private Bitmap lastBitmap = null;
    private String fetchingPicUrl = null;

    // State tracking to prevent redundant notification updates
    private String lastNotifiedSongId = "";
    private boolean lastNotifiedPlayingState = false;
    private int lastNotifiedMode = -1;
    private boolean lastNotifiedFloatingState = false;

    private android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final MainApplication.AppVisibilityListener appVisibilityListener = isForeground -> {
        if (floatingLyricsManager != null) {
            floatingLyricsManager.setAppVisible(isForeground);
        }
    };

    private final MusicPlayerManager.OnSongChangedListener songChangedListener = new MusicPlayerManager.OnSongChangedListener() {
        @Override
        public void onSongChanged(Song song) {
            // Only update basic metadata (title, artist) immediately when song changes.
            // Album art and lyrics will be handled by fullInfoAvailableListener.
            updateMetadata(song);
            if (floatingLyricsManager != null) {
                floatingLyricsManager.updateSongInfo(song);
            }
        }
    };

    private final MusicPlayerManager.OnFullInfoAvailableListener fullInfoAvailableListener = new MusicPlayerManager.OnFullInfoAvailableListener() {
        @Override
        public void onFullInfoAvailable(Song song) {
            // Update metadata with full info (high-res album art)
            updateMetadata(song);
            // Update lyrics for floating window
            if (floatingLyricsManager != null) {
                floatingLyricsManager.updateLyrics(musicPlayerManager.getCurrentLyric());
                floatingLyricsManager.updateSongInfo(song);
            }
        }
    };

    private final MusicPlayerManager.OnPlaybackStateChangedListener playbackStateChangedListener = this::updatePlaybackState;

    private final MusicPlayerManager.OnSeekListener seekListener = msec -> {
        // Ensure PlaybackState is updated with new position in MediaSession
        updatePlaybackState(musicPlayerManager.isPlaying());
    };

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
        musicPlayerManager.addOnFullInfoAvailableListener(fullInfoAvailableListener);
        musicPlayerManager.addOnPlaybackStateChangedListener(playbackStateChangedListener);
        musicPlayerManager.addOnPlaybackModeChangedListener(playbackModeChangedListener);
        musicPlayerManager.addOnSeekListener(seekListener);

        // Initial notification to satisfy startForegroundService requirements
        Song currentSong = musicPlayerManager.getCurrentSong();
        if (currentSong != null) {
            updateMetadata(currentSong);
        } else {
            Song placeholder = new Song("", "Music Player", "Ready to play", "", "");
            showNotification(placeholder, false, BitmapFactory.decodeResource(getResources(), R.drawable.ic_app_logo), true);
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
                    // updatePlaybackState is called via playbackModeChangedListener
                } else if ("ACTION_TOGGLE_FLOATING".equals(action)) {
                    SettingsManager sm = new SettingsManager(MusicService.this);
                    boolean newState = !sm.isFloatingLyricsEnabled();
                    sm.setFloatingLyricsEnabled(newState);
                    if (floatingLyricsManager != null) {
                        floatingLyricsManager.onSettingChanged();
                    }
                    // This will trigger Update 3 via showNotification since floatingStateChanged will be true
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
                // Removed explicit updatePlaybackState(musicPlayerManager.isPlaying());
                // musicPlayerManager.seekTo already triggers the listener which calls updatePlaybackState
            }
        });

        mediaSession.setActive(true);
    }

    private void updateMetadata(Song song) {
        if (song == null) {
            lastSongId = "";
            lastPicUrl = "";
            lastBitmap = null;
            Song placeholder = new Song("", "Music Player", "Ready to play", "", "");
            showNotification(placeholder, false, BitmapFactory.decodeResource(getResources(), R.drawable.ic_app_logo), true);
            return;
        }

        boolean isNewSong = !song.id.equals(lastSongId);
        lastSongId = song.id;

        // 1. Optimization: If the image URL hasn't changed and a bitmap already exists, update Metadata directly
        if (song.picUrl != null && ImageUtils.isSameImage(song.picUrl, lastPicUrl) && lastBitmap != null) {
            updateMediaSessionMetadata(song, lastBitmap);
            if (isNewSong) {
                // Update 1 for cached song: Full info ready immediately
                showNotification(song, musicPlayerManager.isPlaying(), lastBitmap, false);
            }
            return;
        }

        // 2. Prevent duplicate downloads
        if (song.picUrl != null && !song.picUrl.isEmpty() && ImageUtils.isSameImage(song.picUrl, fetchingPicUrl)) {
            return;
        }

        // 3. Initial update for new song with logo placeholder
        if (isNewSong) {
            // Update 1 for new song: Title/Artist change
            // Optimization: If song.picUrl is null, use logo.
            // If it's not null, we'll fetch it soon, so only show logo briefly IF we don't have it cached (handled above)
            Bitmap logo = BitmapFactory.decodeResource(getResources(), R.drawable.ic_app_logo);
            updateMediaSessionMetadata(song, logo);
            showNotification(song, musicPlayerManager.isPlaying(), logo, false);
        }

        if (song.picUrl == null || song.picUrl.isEmpty()) {
            return;
        }

        // Fetch album art async
        final String targetSongId = song.id;
        fetchingPicUrl = song.picUrl;

        new Thread(() -> {
            Bitmap albumArt;
            try {
                URL url = new URL(song.picUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setDoInput(true);
                connection.connect();
                InputStream input = connection.getInputStream();
                albumArt = BitmapFactory.decodeStream(input);
            } catch (Exception e) {
                Log.e(TAG, "Error fetching album art", e);
                albumArt = BitmapFactory.decodeResource(getResources(), R.drawable.ic_app_logo);
            }

            if (albumArt == null) {
                albumArt = BitmapFactory.decodeResource(getResources(), R.drawable.ic_app_logo);
            }

            Bitmap finalAlbumArt = albumArt;

            handler.post(() -> {
                if (song.picUrl != null && ImageUtils.isSameImage(song.picUrl, fetchingPicUrl)) {
                    fetchingPicUrl = null;
                }

                if (!targetSongId.equals(lastSongId)) {
                    return;
                }

                lastBitmap = finalAlbumArt;
                lastPicUrl = song.picUrl;

                updateMediaSessionMetadata(song, finalAlbumArt);
                // Update 2 for new song: Album art ready. We use forceUpdate=true here
                // because ID and State might not have changed, but the Bitmap did.
                showNotification(song, musicPlayerManager.isPlaying(), finalAlbumArt, true);
            });
        }).start();
    }

    private void updateMediaSessionMetadata(Song song, Bitmap albumArt) {
        long duration = musicPlayerManager.getDuration();
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artists)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration > 0 ? duration : 0)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, albumArt);

        mediaSession.setMetadata(builder.build());
    }

    private void updatePlaybackState(boolean isPlaying) {
        updatePlaybackState(isPlaying, false);
    }

    private void updatePlaybackState(boolean isPlaying, boolean forceNotification) {
        // 1. Determine the actual functional state
        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;

        // 2. Optimization: Check for buffering/transient jitter.
        Song currentSong = musicPlayerManager.getCurrentSong();
        String currentSongId = (currentSong != null) ? currentSong.id : "";

        // If we switched songs, the player might be in a transient state.
        // We want to avoid notification updates for these intermediate states
        // unless they are stable.

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

        // Add Custom Actions
        int modeIcon = R.drawable.ic_mode_order;
        int playbackMode = musicPlayerManager.getPlaybackMode();
        switch(playbackMode) {
            case MusicPlayerManager.MODE_LOOP_ONE: modeIcon = R.drawable.ic_mode_loop_one; break;
            case MusicPlayerManager.MODE_LOOP_ALL: modeIcon = R.drawable.ic_mode_loop_all; break;
            case MusicPlayerManager.MODE_SHUFFLE: modeIcon = R.drawable.ic_mode_shuffle; break;
            default: modeIcon = R.drawable.ic_mode_order; break;
        }
        builder.addCustomAction("ACTION_TOGGLE_MODE", "Mode", modeIcon);

        SettingsManager sm = new SettingsManager(this);
        boolean isFloatingEnabled = sm.isFloatingLyricsEnabled();
        int floatIcon = isFloatingEnabled ? R.drawable.ic_floating_active : R.drawable.ic_floating;
        builder.addCustomAction("ACTION_TOGGLE_FLOATING", "Lyrics", floatIcon);

        PlaybackStateCompat newState = builder.build();
        PlaybackStateCompat oldState = mediaSession.getController().getPlaybackState();

        // 3. Precise Notification Trigger Logic
        boolean playStateChanged = isPlaying != lastNotifiedPlayingState;
        boolean modeChanged = playbackMode != lastNotifiedMode;
        boolean floatingStateChanged = isFloatingEnabled != lastNotifiedFloatingState;
        boolean songChanged = !currentSongId.equals(lastNotifiedSongId);

        // EXTRA FILTER: Precision Buffering/Transient Interception
        // If the song just changed (songChanged is true), we allow Update 3 (initial state sync).
        // But if the song is the same, and we are getting a PlaybackState update:
        // - If it's a "not playing" state (isPlaying=false), we only update if we were previously "playing".
        // - This prevents jitter if multiple "paused/idle/buffering" events fire.
        if (!songChanged && !isPlaying && !lastNotifiedPlayingState && !forceNotification && !modeChanged && !floatingStateChanged) {
             // Already notified as Paused for this song, skip redundant Paused/Buffering updates
             return;
        }

        if (songChanged || playStateChanged || modeChanged || floatingStateChanged || forceNotification) {
            showNotification(currentSong, isPlaying, null, false);
        }

        // Always update MediaSession
        mediaSession.setPlaybackState(newState);
    }

    private void showNotification(Song song, boolean isPlaying, Bitmap albumArt, boolean forceUpdate) {
        String songId = (song != null) ? song.id : "";
        int mode = musicPlayerManager.getPlaybackMode();
        boolean floatingState = new SettingsManager(this).isFloatingLyricsEnabled();

        // Check strict deduplication (ID, State, Mode, Floating)
        boolean isSameState = songId.equals(lastNotifiedSongId) &&
                (isPlaying == lastNotifiedPlayingState) &&
                (mode == lastNotifiedMode) &&
                (floatingState == lastNotifiedFloatingState);

        // If everything is the same, AND we are not forcing an update (like for new Art), RETURN.
        if (isSameState && !forceUpdate) {
            return;
        }

        // Update the cache
        lastNotifiedSongId = songId;
        lastNotifiedPlayingState = isPlaying;
        lastNotifiedMode = mode;
        lastNotifiedFloatingState = floatingState;

        // Note: We MUST call startForeground even if notifications are disabled
        // to avoid ForegroundServiceDidNotStartInTimeException on Android 8.0+
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled, but calling startForeground anyway to satisfy system requirements.");
        }

        if (song == null) {
            song = new Song("", "Music Player", "Ready to play", "", "");
            isPlaying = false;
        }

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
                        .setShowActionsInCompactView(1, 2, 3)) // Show Previous, Play/Pause, and Next in compact view
                .setOngoing(isPlaying)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use IMPORTANCE_LOW to avoid sound/pop-up, but ensure it's visible for the foreground service
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Music playback controls");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (floatingLyricsManager != null) {
            floatingLyricsManager.onConfigurationChanged();
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
                updatePlaybackState(musicPlayerManager.isPlaying());
            } else if ("ACTION_UPDATE_SETTINGS".equals(action)) {
                if (floatingLyricsManager != null) {
                    floatingLyricsManager.onSettingChanged();
                }
                // Only update notification if the floating window toggle changed.
                // updatePlaybackState handles this check automatically via lastNotifiedFloatingState.
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
        musicPlayerManager.removeOnFullInfoAvailableListener(fullInfoAvailableListener);
        musicPlayerManager.removeOnPlaybackStateChangedListener(playbackStateChangedListener);
        musicPlayerManager.removeOnPlaybackModeChangedListener(playbackModeChangedListener);
        musicPlayerManager.removeOnSeekListener(seekListener);
        if (floatingLyricsManager != null) {
            floatingLyricsManager.release();
        }
        mediaSession.release();
        super.onDestroy();
    }
}
