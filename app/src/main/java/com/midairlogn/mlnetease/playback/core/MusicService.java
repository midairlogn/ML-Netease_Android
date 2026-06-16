package com.midairlogn.mlnetease.playback.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
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

import com.midairlogn.mlnetease.hearing.HearingProtectionController;
import com.midairlogn.mlnetease.playback.lyrics.FloatingLyricsManager;
import com.midairlogn.mlnetease.image.ImageManager;
import com.midairlogn.mlnetease.image.ImageUtils;
import com.midairlogn.mlnetease.playback.ui.PlayerActivity;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.MainApplication;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.Song;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class MusicService extends Service {
    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "music_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final long POST_REST_PLAYBACK_START_TIMEOUT_MS = 4_000L;
    private static final int MAX_POST_REST_PLAYBACK_RETRIES = 2;
    private static final long POST_REST_AUDIO_FOCUS_RETRY_DELAY_MS = 1_500L;
    private static final int MAX_POST_REST_AUDIO_FOCUS_RETRIES = 6;
    private static final String FOCUS_ACTION_RESUME_CURRENT = "focus:resume_current";
    private static final String FOCUS_ACTION_NEXT = "focus:next";
    private static final String FOCUS_ACTION_PREVIOUS = "focus:previous";
    public static final String ACTION_UPDATE_SETTINGS = "ACTION_UPDATE_SETTINGS";
    public static final String ACTION_UPDATE_APP_VOLUME = "com.midairlogn.mlnetease.action.UPDATE_APP_VOLUME";
    public static final String EXTRA_SETTINGS_UPDATE_MASK = "com.midairlogn.mlnetease.extra.SETTINGS_UPDATE_MASK";
    public static final int SETTINGS_UPDATE_FLOATING_LYRICS = 1;
    public static final int SETTINGS_UPDATE_LYRIC_APPEARANCE = 1 << 1;
    public static final int SETTINGS_UPDATE_TRANSLATION_INTEGRATION = 1 << 2;
    public static final int SETTINGS_UPDATE_DYNAMIC_VOLUME = 1 << 3;
    public static final int SETTINGS_UPDATE_HEARING_PROTECTION = 1 << 4;
    public static final int SETTINGS_UPDATE_APP_VOLUME = 1 << 5;
    public static final int SETTINGS_UPDATE_ALL_RUNTIME = SETTINGS_UPDATE_FLOATING_LYRICS
            | SETTINGS_UPDATE_LYRIC_APPEARANCE
            | SETTINGS_UPDATE_TRANSLATION_INTEGRATION
            | SETTINGS_UPDATE_DYNAMIC_VOLUME
            | SETTINGS_UPDATE_HEARING_PROTECTION
            | SETTINGS_UPDATE_APP_VOLUME;
    public static final String ACTION_CANCEL_REST_AND_CONTINUE = "com.midairlogn.mlnetease.action.CANCEL_REST_AND_CONTINUE";
    public static final String ACTION_CANCEL_REST_AND_NEXT = "com.midairlogn.mlnetease.action.CANCEL_REST_AND_NEXT";
    public static final String ACTION_CANCEL_REST_AND_PREVIOUS = "com.midairlogn.mlnetease.action.CANCEL_REST_AND_PREVIOUS";
    public static final String ACTION_CANCEL_REST_AND_RESUME_CURRENT = "com.midairlogn.mlnetease.action.CANCEL_REST_AND_RESUME_CURRENT";
    public static final String ACTION_REFRESH_PLAYBACK_STATE = "com.midairlogn.mlnetease.action.REFRESH_PLAYBACK_STATE";
    public static final String ACTION_PLAY_INDEX = "com.midairlogn.mlnetease.action.PLAY_INDEX";
    public static final String ACTION_ADD_OR_PLAY_SONG = "com.midairlogn.mlnetease.action.ADD_OR_PLAY_SONG";
    public static final String ACTION_ADD_PLAYLIST_AND_PLAY_FIRST_NEW = "com.midairlogn.mlnetease.action.ADD_PLAYLIST_AND_PLAY_FIRST_NEW";
    public static final String ACTION_REPLACE_PLAYLIST_AND_PLAY = "com.midairlogn.mlnetease.action.REPLACE_PLAYLIST_AND_PLAY";
    public static final String ACTION_RESUME_CURRENT = "com.midairlogn.mlnetease.action.RESUME_CURRENT";
    public static final String ACTION_TOGGLE_PLAY_PAUSE = "com.midairlogn.mlnetease.action.TOGGLE_PLAY_PAUSE";
    public static final String ACTION_PLAY_NEXT = "com.midairlogn.mlnetease.action.PLAY_NEXT";
    public static final String ACTION_PLAY_PREVIOUS = "com.midairlogn.mlnetease.action.PLAY_PREVIOUS";
    public static final String EXTRA_PLAY_INDEX = "com.midairlogn.mlnetease.extra.PLAY_INDEX";
    public static final String EXTRA_PLAYBACK_PAYLOAD_ID = "com.midairlogn.mlnetease.extra.PLAYBACK_PAYLOAD_ID";
    private static final String ACTION_NOTIFICATION_PLAY = "com.midairlogn.mlnetease.action.NOTIFICATION_PLAY";
    private static final String ACTION_NOTIFICATION_PAUSE = "com.midairlogn.mlnetease.action.NOTIFICATION_PAUSE";
    private static final String ACTION_NOTIFICATION_NEXT = "com.midairlogn.mlnetease.action.NOTIFICATION_NEXT";
    private static final String ACTION_NOTIFICATION_PREVIOUS = "com.midairlogn.mlnetease.action.NOTIFICATION_PREVIOUS";
    private MediaSessionCompat mediaSession;
    private MusicPlayerManager musicPlayerManager;
    private NotificationManager notificationManager;
    private FloatingLyricsManager floatingLyricsManager;
    private HearingProtectionController hearingProtectionController;
    private AudioManager audioManager;
    private SettingsManager settingsManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;
    private boolean pausedByFocusLoss = false;
    private boolean resumeOnFocusGain = false;
    private boolean pendingAutoContinueAfterRest = false;
    private String pendingFocusGainAction = null;
    private Intent pendingFocusGainIntent = null;
    private boolean awaitingPostRestPlaybackStart = false;
    private int postRestPlaybackRetryCount = 0;
    private int postRestAudioFocusRetryCount = 0;
    private String lastSongId = "";
    private String lastPicUrl = "";
    private Bitmap lastBitmap = null;
    private Bitmap logoPlaceholder = null;
    private String fetchingPicUrl = null;
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong artworkRequestIdGenerator = new AtomicLong(0);
    private volatile long activeArtworkRequestId = 0;
    private volatile Future<?> activeArtworkTask;

    // State tracking to prevent redundant notification updates
    private String lastNotifiedSongId = "";
    private boolean lastNotifiedPlayingState = false;
    private int lastNotifiedMode = -1;
    private boolean lastNotifiedFloatingState = false;

    private android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private boolean isPlaybackActive() {
        return musicPlayerManager != null && musicPlayerManager.isPlaying();
    }

    private final Runnable postRestPlaybackStartTimeoutRunnable = () -> {
        if (!awaitingPostRestPlaybackStart || isPlaybackActive()) {
            return;
        }
        awaitingPostRestPlaybackStart = false;
        if (!pendingAutoContinueAfterRest && canAttemptPostRestRetry()) {
            continuePlaybackAfterExpiredRest();
        }
    };

    private final Runnable postRestAudioFocusRetryRunnable = () -> {
        if (!pendingAutoContinueAfterRest || isHearingProtectionRestActive()) {
            return;
        }
        continuePlaybackAfterExpiredRest();
    };

    private enum AudioFocusOutcome {
        GRANTED,
        DELAYED,
        FAILED
    }

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
                floatingLyricsManager.updateLyrics(musicPlayerManager.getCurrentLyric(), musicPlayerManager.getCurrentTLyric());
                floatingLyricsManager.updateSongInfo(song);
            }
        }
    };

    private final MusicPlayerManager.OnPlaybackStateChangedListener playbackStateChangedListener = isPlaying -> {
        if (isPlaying) {
            clearPostRestPlaybackWait(true);
            if (!hasAudioFocus && requestAudioFocus() != AudioFocusOutcome.GRANTED) {
                musicPlayerManager.pause();
                return;
            }
        } else if (!pausedByFocusLoss) {
            abandonAudioFocus();
        }
        updatePlaybackState(isPlaying);
    };

    private final MusicPlayerManager.OnSeekListener seekListener = msec -> {
        // Ensure PlaybackState is updated with new position in MediaSession
        updatePlaybackState(isPlaybackActive());
    };

    private final MusicPlayerManager.OnPlaybackModeChangedListener playbackModeChangedListener = mode -> {
        // Ensure PlaybackState is updated with new Custom Action icon for mode
        updatePlaybackState(isPlaybackActive());
    };

    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (isPlaybackActive()) {
                    pausedByFocusLoss = true;
                    resumeOnFocusGain = true;
                    musicPlayerManager.pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                if (isPlaybackActive()) {
                    pausedByFocusLoss = true;
                    musicPlayerManager.pause();
                }
                resumeOnFocusGain = false;
                hasAudioFocus = false;
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasAudioFocus = true;
                if (pendingAutoContinueAfterRest) {
                    continuePlaybackAfterExpiredRest();
                } else if (pendingFocusGainIntent != null || pendingFocusGainAction != null) {
                    performPendingFocusGainAction();
                } else if (pausedByFocusLoss && resumeOnFocusGain && musicPlayerManager != null && !isPlaybackActive()) {
                    musicPlayerManager.resume();
                }
                pausedByFocusLoss = false;
                resumeOnFocusGain = false;
                break;
            default:
                break;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        musicPlayerManager = MusicPlayerManager.getInstance(this);
        musicPlayerManager.restorePlaybackSnapshotIfNeeded();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        settingsManager = new SettingsManager(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build();
        }
        floatingLyricsManager = new FloatingLyricsManager(this);
        hearingProtectionController = new HearingProtectionController(this, musicPlayerManager);
        hearingProtectionController.start();

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
            Song placeholder = new Song("", getString(R.string.music_player), getString(R.string.ready_to_play), "", "");
            showNotification(placeholder, false, BitmapFactory.decodeResource(getResources(), R.drawable.ic_ml_app_logo_foreground), true, "service:init-placeholder");
        }

        // Ensure PlaybackState is initialized with CustomActions
        updatePlaybackState(isPlaybackActive());
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
                    SettingsManager sm = settingsManager;
                    boolean currentState = sm.isFloatingLyricsEnabled();
                    if (!currentState) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(MusicService.this)) {
                            android.widget.Toast.makeText(MusicService.this, R.string.hint_grant_overlay_settings, android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    sm.setFloatingLyricsEnabled(!currentState);
                    if (floatingLyricsManager != null) {
                        floatingLyricsManager.onSettingChanged();
                    }
                    updatePlaybackState(isPlaybackActive());
                }
            }

            @Override
            public void onPlay() {
                handleExternalPlayRequest();
            }

            @Override
            public void onPause() {
                handleExternalPauseRequest();
            }

            @Override
            public void onSkipToNext() {
                handleExternalNextRequest();
            }

            @Override
            public void onSkipToPrevious() {
                handleExternalPreviousRequest();
            }

            @Override
            public void onStop() {
                pendingFocusGainAction = null;
                dropPendingFocusGainIntent();
                pendingFocusGainIntent = null;
                pausedByFocusLoss = false;
                resumeOnFocusGain = false;
                musicPlayerManager.pause();
                abandonAudioFocus();
                stopSelf();
            }

            @Override
            public void onSeekTo(long pos) {
                musicPlayerManager.seekTo((int) pos);
                if (isHearingProtectionRestActive()) {
                    performRestCancellationAction(ACTION_CANCEL_REST_AND_RESUME_CURRENT);
                }
            }
        });

        mediaSession.setActive(true);
    }

    private AudioFocusOutcome requestAudioFocus() {
        if (audioManager == null) {
            hasAudioFocus = false;
            return AudioFocusOutcome.FAILED;
        }
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                hasAudioFocus = false;
                return AudioFocusOutcome.FAILED;
            }
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasAudioFocus = true;
            return AudioFocusOutcome.GRANTED;
        }
        hasAudioFocus = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            return AudioFocusOutcome.DELAYED;
        }
        return AudioFocusOutcome.FAILED;
    }

    private void abandonAudioFocus() {
        if (!hasAudioFocus || audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
        hasAudioFocus = false;
    }

    private void clearPostRestPlaybackWait(boolean resetRetryCount) {
        awaitingPostRestPlaybackStart = false;
        if (resetRetryCount) {
            postRestPlaybackRetryCount = 0;
            postRestAudioFocusRetryCount = 0;
        }
        handler.removeCallbacks(postRestPlaybackStartTimeoutRunnable);
        handler.removeCallbacks(postRestAudioFocusRetryRunnable);
    }

    private void awaitPostRestPlaybackStart() {
        awaitingPostRestPlaybackStart = true;
        handler.removeCallbacks(postRestPlaybackStartTimeoutRunnable);
        handler.postDelayed(postRestPlaybackStartTimeoutRunnable, POST_REST_PLAYBACK_START_TIMEOUT_MS);
    }

    private boolean canAttemptPostRestRetry() {
        return !isHearingProtectionRestActive()
                && musicPlayerManager.canContinueAfterHearingProtectionRest()
                && postRestPlaybackRetryCount < MAX_POST_REST_PLAYBACK_RETRIES;
    }

    private void schedulePostRestAudioFocusRetry() {
        if (postRestAudioFocusRetryCount >= MAX_POST_REST_AUDIO_FOCUS_RETRIES) {
            pendingAutoContinueAfterRest = false;
            clearPostRestPlaybackWait(true);
            return;
        }
        postRestAudioFocusRetryCount++;
        handler.removeCallbacks(postRestAudioFocusRetryRunnable);
        handler.postDelayed(postRestAudioFocusRetryRunnable, POST_REST_AUDIO_FOCUS_RETRY_DELAY_MS);
    }

    private boolean requestAudioFocusForAction(String action) {
        AudioFocusOutcome focusOutcome = requestAudioFocus();
        if (focusOutcome == AudioFocusOutcome.GRANTED) {
            pendingFocusGainAction = null;
            dropPendingFocusGainIntent();
            pendingFocusGainIntent = null;
            return true;
        }
        pendingFocusGainAction = focusOutcome == AudioFocusOutcome.DELAYED ? action : null;
        dropPendingFocusGainIntent();
        pendingFocusGainIntent = null;
        return false;
    }

    private boolean requestAudioFocusForIntent(Intent intent) {
        AudioFocusOutcome focusOutcome = requestAudioFocus();
        if (focusOutcome == AudioFocusOutcome.GRANTED) {
            pendingFocusGainAction = null;
            dropPendingFocusGainIntent();
            pendingFocusGainIntent = null;
            return true;
        }
        pendingFocusGainAction = null;
        dropPendingFocusGainIntent();
        pendingFocusGainIntent = focusOutcome == AudioFocusOutcome.DELAYED ? new Intent(intent) : null;
        if (pendingFocusGainIntent == null) {
            PlaybackActionDispatcher.dropPayload(intent);
        }
        return false;
    }

    private void dropPendingFocusGainIntent() {
        if (pendingFocusGainIntent != null) {
            PlaybackActionDispatcher.dropPayload(pendingFocusGainIntent);
        }
    }

    private void performPendingFocusGainAction() {
        Intent pendingIntent = pendingFocusGainIntent;
        pendingFocusGainIntent = null;
        if (pendingIntent != null) {
            executeIntentWithAudioFocus(pendingIntent);
            return;
        }
        String pendingAction = pendingFocusGainAction;
        pendingFocusGainAction = null;
        if (pendingAction == null) {
            return;
        }
        executeActionWithAudioFocus(pendingAction);
    }

    private boolean executeActionWithAudioFocus(String action) {
        if (ACTION_CANCEL_REST_AND_CONTINUE.equals(action)
                || ACTION_CANCEL_REST_AND_RESUME_CURRENT.equals(action)
                || ACTION_CANCEL_REST_AND_NEXT.equals(action)
                || ACTION_CANCEL_REST_AND_PREVIOUS.equals(action)) {
            if (hearingProtectionController == null || !hearingProtectionController.cancelRestForUserAction()) {
                return false;
            }
        }
        pausedByFocusLoss = false;
        resumeOnFocusGain = false;
        if (ACTION_CANCEL_REST_AND_CONTINUE.equals(action)) {
            musicPlayerManager.continueAfterHearingProtectionRest();
            return true;
        }
        if (ACTION_CANCEL_REST_AND_RESUME_CURRENT.equals(action)
                || FOCUS_ACTION_RESUME_CURRENT.equals(action)) {
            musicPlayerManager.markPausedForResume();
            musicPlayerManager.resume();
            return true;
        }
        if (ACTION_CANCEL_REST_AND_NEXT.equals(action)
                || FOCUS_ACTION_NEXT.equals(action)) {
            musicPlayerManager.playNext();
            return true;
        }
        if (ACTION_CANCEL_REST_AND_PREVIOUS.equals(action)
                || FOCUS_ACTION_PREVIOUS.equals(action)) {
            musicPlayerManager.playPrevious();
            return true;
        }
        return false;
    }

    private boolean executeIntentWithAudioFocus(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        if (action == null) {
            return false;
        }
        if (ACTION_TOGGLE_PLAY_PAUSE.equals(action) && isPlaybackActive()) {
            handleExternalPauseRequest();
            return true;
        }
        if (!requestAudioFocusForIntent(intent)) {
            return false;
        }
        pausedByFocusLoss = false;
        resumeOnFocusGain = false;
        if (ACTION_PLAY_INDEX.equals(action)) {
            musicPlayerManager.play(intent.getIntExtra(EXTRA_PLAY_INDEX, musicPlayerManager.getCurrentIndex()));
            return true;
        }
        if (ACTION_ADD_OR_PLAY_SONG.equals(action)) {
            Song song = PlaybackActionDispatcher.takeSong(intent);
            if (song == null) {
                return false;
            }
            musicPlayerManager.addOrPlaySong(song);
            return true;
        }
        if (ACTION_ADD_PLAYLIST_AND_PLAY_FIRST_NEW.equals(action)) {
            List<Song> songs = PlaybackActionDispatcher.takeSongs(intent);
            if (songs.isEmpty()) {
                return false;
            }
            musicPlayerManager.addPlaylistAndPlayFirstNew(songs);
            return true;
        }
        if (ACTION_REPLACE_PLAYLIST_AND_PLAY.equals(action)) {
            List<Song> songs = PlaybackActionDispatcher.takeSongs(intent);
            if (songs.isEmpty()) {
                return false;
            }
            musicPlayerManager.replacePlaylistAndPlay(songs, intent.getIntExtra(EXTRA_PLAY_INDEX, 0));
            return true;
        }
        if (ACTION_RESUME_CURRENT.equals(action)) {
            musicPlayerManager.markPausedForResume();
            musicPlayerManager.resume();
            return true;
        }
        if (ACTION_TOGGLE_PLAY_PAUSE.equals(action)) {
            musicPlayerManager.markPausedForResume();
            musicPlayerManager.resume();
            return true;
        }
        if (ACTION_PLAY_NEXT.equals(action)) {
            musicPlayerManager.playNext();
            return true;
        }
        if (ACTION_PLAY_PREVIOUS.equals(action)) {
            musicPlayerManager.playPrevious();
            return true;
        }
        return false;
    }

    private boolean continuePlaybackAfterExpiredRest() {
        pausedByFocusLoss = false;
        resumeOnFocusGain = false;
        if (!musicPlayerManager.canContinueAfterHearingProtectionRest()) {
            pendingAutoContinueAfterRest = false;
            clearPostRestPlaybackWait(true);
            return true;
        }
        AudioFocusOutcome focusOutcome = requestAudioFocus();
        if (focusOutcome == AudioFocusOutcome.DELAYED) {
            clearPostRestPlaybackWait(false);
            pendingAutoContinueAfterRest = true;
            return false;
        }
        if (focusOutcome == AudioFocusOutcome.FAILED) {
            clearPostRestPlaybackWait(false);
            pendingAutoContinueAfterRest = true;
            schedulePostRestAudioFocusRetry();
            return false;
        }
        pendingAutoContinueAfterRest = false;
        postRestAudioFocusRetryCount = 0;
        handler.removeCallbacks(postRestAudioFocusRetryRunnable);
        if (postRestPlaybackRetryCount >= MAX_POST_REST_PLAYBACK_RETRIES) {
            clearPostRestPlaybackWait(true);
            return false;
        }
        postRestPlaybackRetryCount++;
        musicPlayerManager.continueAfterHearingProtectionRest();
        if (isPlaybackActive()) {
            clearPostRestPlaybackWait(true);
            return true;
        }
        awaitPostRestPlaybackStart();
        return false;
    }

    private boolean handleRestCancellationAction(String action) {
        return hearingProtectionController != null
                && isHearingProtectionRestActive()
                && performRestCancellationAction(action);
    }

    private boolean isHearingProtectionRestActive() {
        return hearingProtectionController != null
                && HearingProtectionController.getSnapshot(this).restActive;
    }

    private boolean performRestCancellationAction(String action) {
        clearPostRestPlaybackWait(true);
        pendingAutoContinueAfterRest = false;
        if (!requestAudioFocusForAction(action)) {
            return isHearingProtectionRestActive();
        }
        return executeActionWithAudioFocus(action);
    }

    private boolean handleMediaButtonIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return false;
        }
        KeyEvent keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (keyEvent == null || keyEvent.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        switch (keyEvent.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                handleExternalPlayRequest();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                handleExternalPauseRequest();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (isHearingProtectionRestActive()) {
                    handleExternalPlayRequest();
                } else if (isPlaybackActive()) {
                    handleExternalPauseRequest();
                } else {
                    handleExternalPlayRequest();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                handleExternalNextRequest();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                handleExternalPreviousRequest();
                return true;
            default:
                return false;
        }
    }

    private void handleExternalPlayRequest() {
        if (isHearingProtectionRestActive()) {
            performRestCancellationAction(ACTION_CANCEL_REST_AND_CONTINUE);
            return;
        }
        if (!requestAudioFocusForAction(FOCUS_ACTION_RESUME_CURRENT)) {
            return;
        }
        executeActionWithAudioFocus(FOCUS_ACTION_RESUME_CURRENT);
    }

    private void handleExternalPauseRequest() {
        pendingFocusGainAction = null;
        dropPendingFocusGainIntent();
        pendingFocusGainIntent = null;
        pausedByFocusLoss = false;
        resumeOnFocusGain = false;
        musicPlayerManager.pause();
        abandonAudioFocus();
    }

    private void handleExternalNextRequest() {
        if (isHearingProtectionRestActive()) {
            performRestCancellationAction(ACTION_CANCEL_REST_AND_NEXT);
            return;
        }
        if (!requestAudioFocusForAction(FOCUS_ACTION_NEXT)) {
            return;
        }
        executeActionWithAudioFocus(FOCUS_ACTION_NEXT);
    }

    private void handleExternalPreviousRequest() {
        if (isHearingProtectionRestActive()) {
            performRestCancellationAction(ACTION_CANCEL_REST_AND_PREVIOUS);
            return;
        }
        if (!requestAudioFocusForAction(FOCUS_ACTION_PREVIOUS)) {
            return;
        }
        executeActionWithAudioFocus(FOCUS_ACTION_PREVIOUS);
    }

    private void handleNotificationPlay() {
        handleExternalPlayRequest();
    }

    private void handleNotificationPause() {
        handleExternalPauseRequest();
    }

    private void handleNotificationNext() {
        handleExternalNextRequest();
    }

    private void handleNotificationPrevious() {
        handleExternalPreviousRequest();
    }

    private Bitmap getLogoPlaceholder() {
        if (logoPlaceholder == null || logoPlaceholder.isRecycled()) {
            logoPlaceholder = BitmapFactory.decodeResource(getResources(), R.drawable.ic_ml_app_logo_foreground);
        }
        return logoPlaceholder;
    }

    private void recycleBitmap(Bitmap oldBitmap, Bitmap newBitmap) {
        if (oldBitmap != null && oldBitmap != newBitmap && !oldBitmap.isRecycled()) {
            oldBitmap.recycle();
        }
    }

    private void updateMetadata(Song song) {
        if (song == null) {
            cancelActiveArtworkTask();
            lastSongId = "";
            lastPicUrl = "";
            recycleBitmap(lastBitmap, null);
            lastBitmap = null;
            fetchingPicUrl = null;
            Song placeholder = new Song("", getString(R.string.music_player), getString(R.string.ready_to_play), "", "");
            showNotification(placeholder, false, getLogoPlaceholder(), true, "metadata:null-song");
            return;
        }

        boolean isNewSong = !song.id.equals(lastSongId);
        lastSongId = song.id;

        if (isNewSong) {
            cancelActiveArtworkTask();
        }

        if (song.embeddedPicture != null && song.embeddedPicture.length > 0) {
            Bitmap embeddedBitmap = ImageManager.getInstance().getEmbeddedBitmap("embedded:" + song.id, song.embeddedPicture, true);
            if (embeddedBitmap != null) {
                recycleBitmap(lastBitmap, embeddedBitmap);
                lastBitmap = embeddedBitmap;
                lastPicUrl = "";
                fetchingPicUrl = null;
                updateMediaSessionMetadata(song, embeddedBitmap);
                showNotification(song, isPlaybackActive(), embeddedBitmap, isNewSong, "metadata:embedded-art");
                return;
            }
        }

        // 1. Optimization: If the image URL hasn't changed and a bitmap already exists, update Metadata directly
        if (song.picUrl != null && ImageUtils.isSameImage(song.picUrl, lastPicUrl) && lastBitmap != null) {
            updateMediaSessionMetadata(song, lastBitmap);
            if (isNewSong) {
                showNotification(song, isPlaybackActive(), lastBitmap, false, "metadata:cached-art");
            }
            return;
        }

        // 2. Prevent duplicate downloads
        if (song.picUrl != null && !song.picUrl.isEmpty() && ImageUtils.isSameImage(song.picUrl, fetchingPicUrl)) {
            return;
        }

        // 3. Initial update for new song with logo placeholder
        if (isNewSong) {
            Bitmap logo = getLogoPlaceholder();
            updateMediaSessionMetadata(song, logo);
            showNotification(song, isPlaybackActive(), logo, false, "metadata:new-song");
        }

        if (song.picUrl == null || song.picUrl.isEmpty()) {
            return;
        }

        // Fetch album art async
        final String targetSongId = song.id;
        final long artworkRequestId = artworkRequestIdGenerator.incrementAndGet();
        activeArtworkRequestId = artworkRequestId;
        fetchingPicUrl = song.picUrl;

        activeArtworkTask = artworkExecutor.submit(() -> {
            Bitmap albumArt = ImageManager.getInstance().fetchNotificationBitmap(song.picUrl);
            if (Thread.currentThread().isInterrupted() || artworkRequestId != activeArtworkRequestId) {
                return;
            }
            if (albumArt == null) {
                albumArt = getLogoPlaceholder();
            }

            Bitmap finalAlbumArt = albumArt;

            handler.post(() -> {
                if (artworkRequestId != activeArtworkRequestId) {
                    return;
                }
                if (song.picUrl != null && ImageUtils.isSameImage(song.picUrl, fetchingPicUrl)) {
                    fetchingPicUrl = null;
                }

                if (!targetSongId.equals(lastSongId)) {
                    return;
                }

                recycleBitmap(lastBitmap, finalAlbumArt);
                lastBitmap = finalAlbumArt;
                lastPicUrl = song.picUrl;

                updateMediaSessionMetadata(song, finalAlbumArt);
                showNotification(song, isPlaybackActive(), finalAlbumArt, true, "metadata:art-ready");
            });
        });
    }

    private void cancelActiveArtworkTask() {
        activeArtworkRequestId = artworkRequestIdGenerator.incrementAndGet();
        fetchingPicUrl = null;
        Future<?> task = activeArtworkTask;
        activeArtworkTask = null;
        if (task != null) {
            task.cancel(true);
        }
    }

    private void updateMediaSessionMetadata(Song song, Bitmap albumArt) {
        long duration = musicPlayerManager.getDuration();
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artists)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration > 0 ? duration : 0)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, albumArt);

        mediaSession.setMetadata(builder.build());
    }

    private Bitmap decodeBoundedBitmap(InputStream inputStream, int maxSizePx, Bitmap.Config preferredConfig) throws IOException {
        byte[] imageBytes = readAllBytes(inputStream);

        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, boundsOptions);

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, maxSizePx, maxSizePx);
        decodeOptions.inPreferredConfig = preferredConfig;
        decodeOptions.inDither = true;

        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, decodeOptions);
        if (bitmap == null) {
            return null;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= maxSizePx && height <= maxSizePx) {
            return bitmap;
        }

        float scale = Math.min((float) maxSizePx / width, (float) maxSizePx / height);
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));

        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
        if (scaled != bitmap) {
            bitmap.recycle();
        }
        return scaled;
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, count);
        }
        return outputStream.toByteArray();
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return Math.max(1, inSampleSize);
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
                     showNotification(currentSong, isPlaying, null, true, "playback:duration-updated");
                }
            }
        }

        float playbackSpeed = isPlaying ? 1.0f : 0.0f;
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, musicPlayerManager.getCurrentPosition(), playbackSpeed);

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

        SettingsManager sm = settingsManager;
        boolean isFloatingEnabled = sm.isFloatingLyricsEnabled();
        int floatIcon = isFloatingEnabled ? R.drawable.ic_floating_active : R.drawable.ic_floating;
        builder.addCustomAction("ACTION_TOGGLE_FLOATING", "Lyrics", floatIcon);

        PlaybackStateCompat newState = builder.build();

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
             // Already notified as Paused for this song. Keep MediaSession position/state synced
             // (e.g. paused seeks), but skip redundant notification refreshes.
             mediaSession.setPlaybackState(newState);
             return;
        }

        if (songChanged || playStateChanged || modeChanged || floatingStateChanged || forceNotification) {
            String reason;
            if (forceNotification) {
                reason = "playback:force";
            } else if (songChanged) {
                reason = "playback:song-changed";
            } else if (playStateChanged) {
                reason = "playback:state-changed";
            } else if (modeChanged) {
                reason = "playback:mode-changed";
            } else {
                reason = "playback:floating-changed";
            }
            showNotification(currentSong, isPlaying, null, false, reason);
        }

        // Always update MediaSession
        mediaSession.setPlaybackState(newState);
    }

    private void showNotification(Song song, boolean isPlaying, Bitmap albumArt, boolean forceUpdate, String reason) {
        String songId = (song != null) ? song.id : "";
        int mode = musicPlayerManager.getPlaybackMode();
        boolean floatingState = settingsManager.isFloatingLyricsEnabled();

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
            song = new Song("", getString(R.string.music_player), getString(R.string.ready_to_play), "", "");
            isPlaying = false;
        }

        // If albumArt is passed as null, try to retrieve from current metadata
        if (albumArt == null) {
            MediaMetadataCompat metadata = mediaSession.getController().getMetadata();
            if (metadata != null) {
                albumArt = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
            }
            if (albumArt == null) {
                 albumArt = getLogoPlaceholder();
            }
        }

        Intent intent = new Intent(this, PlayerActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Action playPauseAction = isPlaying ?
                new NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause",
                        PendingIntent.getService(this, 120,
                                new Intent(this, MusicService.class).setAction(ACTION_NOTIFICATION_PAUSE),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)) :
                new NotificationCompat.Action(android.R.drawable.ic_media_play, "Play",
                        PendingIntent.getService(this, 121,
                                new Intent(this, MusicService.class).setAction(ACTION_NOTIFICATION_PLAY),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        // Mode Action
        int modeIcon = R.drawable.ic_mode_order;
        switch(musicPlayerManager.getPlaybackMode()) {
            case MusicPlayerManager.MODE_LOOP_ONE: modeIcon = R.drawable.ic_mode_loop_one; break;
            case MusicPlayerManager.MODE_LOOP_ALL: modeIcon = R.drawable.ic_mode_loop_all; break;
            case MusicPlayerManager.MODE_SHUFFLE: modeIcon = R.drawable.ic_mode_shuffle; break;
            default: modeIcon = R.drawable.ic_mode_order; break;
        }
        PendingIntent modePendingIntent = PendingIntent.getService(this, 122,
            new Intent(this, MusicService.class).setAction("ACTION_TOGGLE_MODE"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Action modeAction = new NotificationCompat.Action(modeIcon, "Mode", modePendingIntent);

        // Floating Action
        SettingsManager sm = settingsManager;
        boolean isFloatingEnabled = sm.isFloatingLyricsEnabled();
        int floatIcon = isFloatingEnabled ? R.drawable.ic_floating_active : R.drawable.ic_floating;

        PendingIntent floatPendingIntent = PendingIntent.getService(this, 123,
            new Intent(this, MusicService.class).setAction("ACTION_TOGGLE_FLOATING"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Action floatAction = new NotificationCompat.Action(floatIcon, "Lyrics", floatPendingIntent);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(song.name)
                .setContentText(song.artists)
                .setSmallIcon(R.drawable.ic_ml_app_logo_foreground)
                .setLargeIcon(albumArt)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(modeAction)
                .addAction(android.R.drawable.ic_media_previous, "Previous",
                        PendingIntent.getService(this, 124,
                                new Intent(this, MusicService.class).setAction(ACTION_NOTIFICATION_PREVIOUS),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(playPauseAction)
                .addAction(android.R.drawable.ic_media_next, "Next",
                        PendingIntent.getService(this, 125,
                                new Intent(this, MusicService.class).setAction(ACTION_NOTIFICATION_NEXT),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
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

    private void applyRuntimeSettingsUpdate(int updateMask) {
        if (updateMask == 0) {
            return;
        }
        boolean appVolumeChanged = (updateMask & SETTINGS_UPDATE_APP_VOLUME) != 0;
        if ((updateMask & SETTINGS_UPDATE_APP_VOLUME) != 0) {
            SettingsManager sm = settingsManager;
            musicPlayerManager.setAppVolume(sm.getAppVolume());
        }
        if ((updateMask & SETTINGS_UPDATE_DYNAMIC_VOLUME) != 0) {
            musicPlayerManager.onDynamicVolumeSettingChanged();
        }
        if (hearingProtectionController != null) {
            if ((updateMask & SETTINGS_UPDATE_HEARING_PROTECTION) != 0) {
                hearingProtectionController.onSettingsChanged();
            } else if (appVolumeChanged) {
                hearingProtectionController.onPlaybackIntensityChanged();
            }
        }

        boolean floatingLyricsSettingsChanged = (updateMask & (SETTINGS_UPDATE_FLOATING_LYRICS
                | SETTINGS_UPDATE_LYRIC_APPEARANCE
                | SETTINGS_UPDATE_TRANSLATION_INTEGRATION)) != 0;
        if (floatingLyricsSettingsChanged && floatingLyricsManager != null) {
            floatingLyricsManager.onSettingChanged();
        }
        if ((updateMask & SETTINGS_UPDATE_FLOATING_LYRICS) != 0) {
            updatePlaybackState(isPlaybackActive());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (handleMediaButtonIntent(intent)) {
            updatePlaybackState(isPlaybackActive(), true);
            return START_STICKY;
        }
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if ("ACTION_TOGGLE_MODE".equals(action)) {
                musicPlayerManager.togglePlaybackMode();
            } else if ("ACTION_TOGGLE_FLOATING".equals(action)) {
                SettingsManager sm = settingsManager;
                boolean currentState = sm.isFloatingLyricsEnabled();
                if (!currentState) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
                        android.widget.Toast.makeText(this, R.string.hint_grant_overlay_app_settings, android.widget.Toast.LENGTH_LONG).show();
                        return START_NOT_STICKY;
                    }
                }
                sm.setFloatingLyricsEnabled(!currentState);
                if (floatingLyricsManager != null) {
                    floatingLyricsManager.onSettingChanged();
                }
                updatePlaybackState(isPlaybackActive());
            } else if (ACTION_UPDATE_SETTINGS.equals(action)) {
                applyRuntimeSettingsUpdate(intent.getIntExtra(EXTRA_SETTINGS_UPDATE_MASK, SETTINGS_UPDATE_ALL_RUNTIME));
            } else if (ACTION_UPDATE_APP_VOLUME.equals(action)) {
                applyRuntimeSettingsUpdate(SETTINGS_UPDATE_APP_VOLUME);
            } else if (ACTION_REFRESH_PLAYBACK_STATE.equals(action)) {
                // A plain playback-state refresh is used by both expired-rest and manual-cancel
                // flows. Only continue playback here when we already know an expired-rest retry
                // is pending, otherwise seek/lyric rest cancellation can incorrectly advance.
                if (pendingAutoContinueAfterRest
                        && hearingProtectionController != null
                        && !isHearingProtectionRestActive()) {
                    continuePlaybackAfterExpiredRest();
                }
                updatePlaybackState(isPlaybackActive(), true);
            } else if (HearingProtectionController.ACTION_HEARING_REST_FINISHED.equals(action)) {
                boolean forceContinueAfterRest = intent.getBooleanExtra(
                        HearingProtectionController.EXTRA_FORCE_CONTINUE_AFTER_REST,
                        false
                );
                boolean restExpired = hearingProtectionController != null
                        && hearingProtectionController.shouldCompleteRestNow();
                if (restExpired) {
                    hearingProtectionController.completeRestFromService();
                }
                boolean shouldContinueAfterRest = forceContinueAfterRest || restExpired;
                if (shouldContinueAfterRest) {
                    continuePlaybackAfterExpiredRest();
                }
                updatePlaybackState(isPlaybackActive(), true);
                return START_STICKY;
            } else if (ACTION_CANCEL_REST_AND_CONTINUE.equals(action)
                    || ACTION_CANCEL_REST_AND_RESUME_CURRENT.equals(action)
                    || ACTION_CANCEL_REST_AND_NEXT.equals(action)
                    || ACTION_CANCEL_REST_AND_PREVIOUS.equals(action)) {
                performRestCancellationAction(action);
                return START_STICKY;
            } else if (ACTION_PLAY_INDEX.equals(action)
                    || ACTION_ADD_OR_PLAY_SONG.equals(action)
                    || ACTION_ADD_PLAYLIST_AND_PLAY_FIRST_NEW.equals(action)
                    || ACTION_REPLACE_PLAYLIST_AND_PLAY.equals(action)
                    || ACTION_RESUME_CURRENT.equals(action)
                    || ACTION_TOGGLE_PLAY_PAUSE.equals(action)
                    || ACTION_PLAY_NEXT.equals(action)
                    || ACTION_PLAY_PREVIOUS.equals(action)) {
                executeIntentWithAudioFocus(intent);
                return START_STICKY;
            } else if (ACTION_NOTIFICATION_PLAY.equals(action)) {
                handleNotificationPlay();
                updatePlaybackState(isPlaybackActive(), true);
                return START_STICKY;
            } else if (ACTION_NOTIFICATION_PAUSE.equals(action)) {
                handleNotificationPause();
                updatePlaybackState(isPlaybackActive(), true);
                return START_STICKY;
            } else if (ACTION_NOTIFICATION_NEXT.equals(action)) {
                handleNotificationNext();
                updatePlaybackState(isPlaybackActive(), true);
                return START_STICKY;
            } else if (ACTION_NOTIFICATION_PREVIOUS.equals(action)) {
                handleNotificationPrevious();
                updatePlaybackState(isPlaybackActive(), true);
                return START_STICKY;
            }
        }
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
        pausedByFocusLoss = false;
        resumeOnFocusGain = false;
        dropPendingFocusGainIntent();
        pendingFocusGainIntent = null;
        clearPostRestPlaybackWait(true);
        abandonAudioFocus();
        musicPlayerManager.removeOnSongChangedListener(songChangedListener);
        musicPlayerManager.removeOnFullInfoAvailableListener(fullInfoAvailableListener);
        musicPlayerManager.removeOnPlaybackStateChangedListener(playbackStateChangedListener);
        musicPlayerManager.removeOnPlaybackModeChangedListener(playbackModeChangedListener);
        musicPlayerManager.removeOnSeekListener(seekListener);
        cancelActiveArtworkTask();
        artworkExecutor.shutdownNow();
        recycleBitmap(lastBitmap, null);
        lastBitmap = null;
        recycleBitmap(logoPlaceholder, null);
        logoPlaceholder = null;
        if (hearingProtectionController != null) {
            hearingProtectionController.stop();
        }
        if (floatingLyricsManager != null) {
            floatingLyricsManager.release();
        }
        mediaSession.release();
        super.onDestroy();
    }
}
