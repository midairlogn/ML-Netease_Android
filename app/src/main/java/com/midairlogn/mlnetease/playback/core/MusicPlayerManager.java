package com.midairlogn.mlnetease.playback.core;

import android.content.Context;
import android.net.Uri;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Handler;
import android.os.Looper;

import com.midairlogn.mlnetease.image.ImageUtils;
import com.midairlogn.mlnetease.local.media.LocalAudioMetadata;
import com.midairlogn.mlnetease.local.media.LocalAudioMetadataReader;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.network.NeteaseApi;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

public class MusicPlayerManager {
    public static final int MODE_ORDER = 0;
    public static final int MODE_LOOP_ONE = 1;
    public static final int MODE_LOOP_ALL = 2;
    public static final int MODE_SHUFFLE = 3;

    private static MusicPlayerManager instance;
    private MediaPlayer mediaPlayer;
    private List<Song> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private boolean isPaused = false;
    private volatile boolean isSwitchingSong = false;
    private volatile boolean playbackActive = false;
    private boolean lastNotifiedState = false;
    private volatile boolean forceNextPlaybackStateDispatch = false;
    private Context context;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private NeteaseApi neteaseApi;
    private SettingsManager settingsManager;
    private int currentMode;
    private Random random = new Random();
    private int retryCount = 0;
    private static final int MAX_RETRY = 3;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 500L;
    private static final int MILLIBELS_PER_DECIBEL = 100;
    private static final float MAX_NORMALIZED_OUTPUT_PEAK = 0.98f;
    private static final float MAX_FALLBACK_POSITIVE_GAIN_DB = 3.0f;
    private static final float MIN_ALLOWED_GAIN_DB = -20.0f;
    private static final float MAX_ALLOWED_GAIN_DB = 6.0f;
    private static final float MIN_EFFECTIVE_GAIN_DB = 0.1f;
    private static final long APP_VOLUME_RAMP_DURATION_MS = 180L;
    private static final long APP_VOLUME_RAMP_STEP_MS = 16L;
    private int resumePosition = 0;
    private boolean isCompletionListenerEnabled = false;
    private final AtomicLong playRequestIdGenerator = new AtomicLong(0);
    private volatile long activePlayRequestId = 0;
    private volatile NeteaseApi.CancelableRequest activeFullInfoRequest = NeteaseApi.CancelableRequest.NONE;
    private LoudnessEnhancer loudnessEnhancer;
    private int loudnessEnhancerAudioSessionId = -1;
    private float pendingLoudnessGainDb = 0f;
    private boolean hasPendingLoudnessNormalization = false;
    private float currentAppVolumeScalar = 1f;
    private Runnable appVolumeRampRunnable;
    private static final class NormalizationMetadata {
        final boolean hasGain;
        final float gainDb;
        final boolean hasPeak;
        final float peak;
        final String source;

        NormalizationMetadata(boolean hasGain, float gainDb, boolean hasPeak, float peak, String source) {
            this.hasGain = hasGain;
            this.gainDb = gainDb;
            this.hasPeak = hasPeak;
            this.peak = peak;
            this.source = source;
        }
    }

    private volatile boolean isAutoSkipping = false;
    private int continuousSkipCount = 0;
    private Runnable pendingSongNotifyRunnable;
    private Runnable pendingFullInfoNotifyRunnable;
    private static final int NOTIFY_DEBOUNCE_MS = 500;

    // Callbacks
    private List<OnSongChangedListener> songChangedListeners = new ArrayList<>();
    private List<OnPlaybackStateChangedListener> playbackStateChangedListeners = new ArrayList<>();
    private List<OnPlaylistChangedListener> playlistChangedListeners = new ArrayList<>();
    private List<OnPlaybackModeChangedListener> playbackModeChangedListeners = new ArrayList<>();
    private List<OnFullInfoAvailableListener> fullInfoAvailableListeners = new ArrayList<>();
    private List<OnSeekListener> seekListeners = new ArrayList<>();
    private List<OnProgressUpdateListener> progressUpdateListeners = new ArrayList<>();
    private List<OnSongCompletionListener> songCompletionListeners = new ArrayList<>();
    private List<OnPlaybackActionListener> playbackActionListeners = new ArrayList<>();
    private boolean isProgressDispatcherRunning = false;
    private final Runnable progressUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isProgressDispatcherRunning) {
                return;
            }

            notifyProgressUpdate(getCurrentPosition(), getDuration());

            if (shouldRunProgressDispatcher()) {
                mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS);
            } else {
                stopProgressDispatcher();
            }
        }
    };

    // Current extended info
    private String currentLyric = "";
    private String currentTLyric = "";

    public interface OnSongChangedListener {
        void onSongChanged(Song song);
    }

    public interface OnPlaybackStateChangedListener {
        void onPlaybackStateChanged(boolean isPlaying);
    }

    public interface OnPlaylistChangedListener {
        void onPlaylistChanged(List<Song> playlist);
    }

    public interface OnPlaybackModeChangedListener {
        void onPlaybackModeChanged(int mode);
    }

    public interface OnFullInfoAvailableListener {
        void onFullInfoAvailable(Song song);
    }

    public interface OnSeekListener {
        void onSeek(int msec);
    }

    public interface OnProgressUpdateListener {
        void onProgressUpdate(int current, int total);
    }

    public interface OnSongCompletionListener {
        boolean onSongCompleted(Song song, int completedIndex);
    }

    public interface OnPlaybackActionListener {
        void onPlaybackAction(boolean userInitiated, String action);
    }

    public static final String PLAYBACK_ACTION_PLAY = "play";
    public static final String PLAYBACK_ACTION_PAUSE = "pause";
    public static final String PLAYBACK_ACTION_RESUME = "resume";
    public static final String PLAYBACK_ACTION_NEXT = "next";
    public static final String PLAYBACK_ACTION_PREVIOUS = "previous";

    private MusicPlayerManager(Context context) {
        this.context = context.getApplicationContext();
        this.settingsManager = new SettingsManager(this.context);
        this.neteaseApi = new NeteaseApi(this.context, settingsManager);
        this.currentMode = settingsManager.getPlayMode();
        mediaPlayer = new MediaPlayer();

        android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .build();
        mediaPlayer.setAudioAttributes(audioAttributes);
        setAppVolume(settingsManager.getAppVolume());

        mediaPlayer.setOnCompletionListener(mp -> {
            playbackActive = false;
            if (isCompletionListenerEnabled) {
                Song completedSong = getCurrentSong();
                int completedIndex = currentIndex;
                if (!notifySongCompleted(completedSong, completedIndex)) {
                    playNext(false, false);
                }
            }
        });
    }

    public static MusicPlayerManager getInstance(Context context) {
        if (instance == null) {
            instance = new MusicPlayerManager(context);
        }
        return instance;
    }

    public void restorePlaybackSnapshotIfNeeded() {
        if (!playlist.isEmpty() || !settingsManager.isHearingProtectionRestActive()) {
            return;
        }
        SettingsManager.PlaybackSnapshot snapshot = settingsManager.getPlaybackSnapshot();
        if (snapshot.songs.isEmpty()) {
            return;
        }
        playlist = new ArrayList<>(snapshot.songs);
        currentIndex = snapshot.currentIndex;
        notifyPlaylistChanged();
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            notifySongChanged(playlist.get(currentIndex));
        }
    }

    private void persistPlaybackSnapshot() {
        if (!settingsManager.isHearingProtectionEnabled()) {
            settingsManager.clearPlaybackSnapshot();
            return;
        }
        settingsManager.setPlaybackSnapshot(playlist, currentIndex);
    }

    public void setPlaylist(List<Song> songs) {
        this.playlist = new ArrayList<>(songs);
        this.currentIndex = -1; // Reset current index since playlist changed
        persistPlaybackSnapshot();
        notifyPlaylistChanged();
    }

    public List<Song> getPlaylist() {
        return playlist;
    }

    public void setPlaybackMode(int mode) {
        this.currentMode = mode;
        settingsManager.setPlayMode(mode);
        notifyPlaybackModeChanged(mode);
    }

    public void reloadPlaybackModeFromSettings() {
        currentMode = settingsManager.getPlayMode();
        notifyPlaybackModeChanged(currentMode);
    }

    public void togglePlaybackMode() {
        int nextMode;
        switch (currentMode) {
            case MODE_ORDER: nextMode = MODE_LOOP_ALL; break;
            case MODE_LOOP_ALL: nextMode = MODE_LOOP_ONE; break;
            case MODE_LOOP_ONE: nextMode = MODE_SHUFFLE; break;
            case MODE_SHUFFLE: nextMode = MODE_ORDER; break;
            default: nextMode = MODE_ORDER; break;
        }
        setPlaybackMode(nextMode);
    }

    public int getPlaybackMode() {
        return currentMode;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void addToPlaylist(Song song) {
        playlist.add(song);
        persistPlaybackSnapshot();
        notifyPlaylistChanged();
    }

    public void addPlaylistAndPlayFirstNew(List<Song> songs) {
        if (songs == null || songs.isEmpty()) return;

        Map<String, Integer> idMap = new HashMap<>();
        for (int i = 0; i < playlist.size(); i++) {
            idMap.put(playlist.get(i).id, i);
        }

        int firstSongIndex = -1;
        boolean playlistChanged = false;

        for (int i = 0; i < songs.size(); i++) {
            Song song = songs.get(i);
            Integer existingIndex = idMap.get(song.id);

            if (existingIndex == null) {
                playlist.add(song);
                int newIndex = playlist.size() - 1;
                idMap.put(song.id, newIndex);
                playlistChanged = true;

                if (i == 0) {
                    firstSongIndex = newIndex;
                }
            } else if (i == 0) {
                firstSongIndex = existingIndex;
            }
        }

        if (playlistChanged) {
            persistPlaybackSnapshot();
            notifyPlaylistChanged();
        }

        if (firstSongIndex != -1) {
            play(firstSongIndex);
        }
    }

    public void addOrPlaySong(Song song) {
        // Check if song already exists in playlist
        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i).id.equals(song.id)) {
                play(i);
                return;
            }
        }
        // If not found, add to end and play
        addToPlaylist(song);
        play(playlist.size() - 1);
    }

    public void removeFromPlaylist(int index) {
        if (index < 0 || index >= playlist.size()) return;

        boolean wasPlaying = (index == currentIndex);
        playlist.remove(index);

        if (index < currentIndex) {
            currentIndex--;
        } else if (index == currentIndex) {
            // Removed currently playing song
            if (playlist.isEmpty()) {
                currentIndex = -1;
                pause(); // Stop playback
                notifySongChanged(null);
            } else {
                // Play next or previous depending on availability
                if (currentIndex >= playlist.size()) {
                    currentIndex = 0; // Wrap to start if was last
                }
                play(currentIndex);
            }
        }
        persistPlaybackSnapshot();
        notifyPlaylistChanged();
    }

    public void moveInPlaylist(int from, int to) {
        if (from < 0 || from >= playlist.size() || to < 0 || to >= playlist.size()) return;

        // Track current song
        Song currentSong = null;
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            currentSong = playlist.get(currentIndex);
        }

        if (from < to) {
            for (int i = from; i < to; i++) {
                Collections.swap(playlist, i, i + 1);
            }
        } else {
            for (int i = from; i > to; i--) {
                Collections.swap(playlist, i, i - 1);
            }
        }

        // Update currentIndex
        if (currentSong != null) {
            currentIndex = playlist.indexOf(currentSong);
        }
        persistPlaybackSnapshot();
        notifyPlaylistChanged();
    }


    public void play(int index) {
        notifyPlaybackAction(true, PLAYBACK_ACTION_PLAY);
        isAutoSkipping = false;
        continuousSkipCount = 0;
        if (pendingSongNotifyRunnable != null) {
            mainHandler.removeCallbacks(pendingSongNotifyRunnable);
            pendingSongNotifyRunnable = null;
        }
        if (pendingFullInfoNotifyRunnable != null) {
            mainHandler.removeCallbacks(pendingFullInfoNotifyRunnable);
            pendingFullInfoNotifyRunnable = null;
        }
        play(index, false);
    }

    private static final int RETRY_DELAY_MS = 2000;
    private static final int AUTO_SKIP_DELAY_MS = 1000;

    public void replacePlaylistAndPlay(List<Song> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) {
            setPlaylist(new ArrayList<>());
            return;
        }
        this.playlist = new ArrayList<>(songs);
        this.currentIndex = -1;
        persistPlaybackSnapshot();
        notifyPlaylistChanged();
        play(Math.max(0, Math.min(startIndex, this.playlist.size() - 1)));
    }

    private void handlePlaybackFailure(int index, long requestId, String reason) {
        if (requestId != activePlayRequestId || currentIndex != index) return;

        android.util.Log.e("MusicPlayerManager", "Playback failure: " + reason + " for index " + index);

        if (retryCount < MAX_RETRY) {
            retryCount++;
            android.util.Log.d("MusicPlayerManager", "Retrying playback in " + RETRY_DELAY_MS + "ms... Attempt " + retryCount);
            // Delay retry to prevent rapid loops (especially when internet is down)
            mainHandler.postDelayed(() -> {
                if (requestId == activePlayRequestId && currentIndex == index) {
                    play(currentIndex, true);
                }
            }, RETRY_DELAY_MS);
        } else {
            android.util.Log.e("MusicPlayerManager", "MAX_RETRY reached. Auto skipping in " + AUTO_SKIP_DELAY_MS + "ms...");
            isAutoSkipping = true;
            continuousSkipCount++;

            if (continuousSkipCount < playlist.size()) {
                mainHandler.postDelayed(() -> {
                    if (requestId == activePlayRequestId && currentIndex == index) {
                        playNext(true);
                    }
                }, AUTO_SKIP_DELAY_MS);
            } else {
                android.util.Log.e("MusicPlayerManager", "No playable songs found in the entire playlist.");
                isAutoSkipping = false;
                continuousSkipCount = 0;
                isSwitchingSong = false;
                notifyPlaybackStateChanged(false);
            }
        }
    }

    private void play(int index, boolean isRetry) {
        if (index < 0 || index >= playlist.size()) return;

        final long requestId = playRequestIdGenerator.incrementAndGet();
        activePlayRequestId = requestId;
        cancelActiveFullInfoRequest();
        forceNextPlaybackStateDispatch = true;

        if (!isRetry) {
            retryCount = 0;
            resumePosition = 0;
        }

        boolean wasPlaying = playbackActive;
        boolean isNewSong = (index != currentIndex);
        currentIndex = index;
        persistPlaybackSnapshot();
        isSwitchingSong = true;
        updateProgressDispatcherState();
        // Temporarily disable completion listener to prevent race conditions during song loading/switching
        isCompletionListenerEnabled = false;

        if (isNewSong) {
            // Stop previous playback to prevent onCompletion events from firing for the old song
            // while we are loading the new one. This prevents race conditions where the old song
            // finishes and triggers playNext() -> play(index+1).
            try {
                if (playbackActive) {
                    mediaPlayer.stop();
                }
                playbackActive = false;
                mediaPlayer.reset();
            } catch (Exception e) {
                playbackActive = false;
                e.printStackTrace();
            }
        }

        Song song = playlist.get(index);

        // Notify change immediately ONLY if it's a new song and not a retry.
        // During retries or auto-skips, we've already notified the change (or it's debounced).
        if (!isRetry && !isAutoSkipping) {
            notifySongChanged(song);
            notifyProgressUpdate(0, 0);
        }

        if (wasPlaying) {
            // Emit one stable pause/loading transition at switch start.
            notifyPlaybackStateChanged(false);
        }
        currentLyric = context.getString(R.string.hint_loading);
        currentTLyric = "";

        if (song.isLocal()) {
            playLocalSong(song, index, requestId);
            return;
        }

        // Fetch full info
        activeFullInfoRequest = neteaseApi.getSongFullInfo(song.id, new NeteaseApi.ApiCallback() {
            @Override
            public void onSuccess(String result) {
                // Ignore stale callback from previous play request.
                if (requestId != activePlayRequestId || currentIndex != index) return;
                if (activeFullInfoRequest != null && activeFullInfoRequest.isCanceled()) {
                    return;
                }
                activeFullInfoRequest = NeteaseApi.CancelableRequest.NONE;

                try {
                    JSONObject root = new JSONObject(result);
                    if (root.getInt("status") == 200) {
                        String url = root.optString("url", "");
                        currentLyric = root.optString("lyric", "");
                        currentTLyric = root.optString("tlyric", "");
                        boolean hasGain = root.has("gain") && !root.isNull("gain");
                        boolean hasPeak = root.has("peak") && !root.isNull("peak");
                        boolean hasClosedGain = root.has("closedGain") && !root.isNull("closedGain");
                        boolean hasClosedPeak = root.has("closedPeak") && !root.isNull("closedPeak");

                        // Update Song object with better info if available
                        song.picUrl = root.optString("pic", song.picUrl);
                        song.name = root.optString("name", song.name);
                        song.artists = root.optString("ar_name", song.artists);
                        song.album = root.optString("al_name", song.album);
                        song.hasLoudnessNormalization = hasClosedGain || hasGain;
                        song.gainDb = hasGain ? (float) root.optDouble("gain", 0d) : 0f;
                        song.peak = hasPeak ? (float) root.optDouble("peak", 0d) : 0f;
                        song.closedGainDb = hasClosedGain ? (float) root.optDouble("closedGain", 0d) : 0f;
                        song.closedPeak = hasClosedPeak ? (float) root.optDouble("closedPeak", 0d) : 0f;
                        clearLoudnessNormalization();
                        if (settingsManager.isDynamicVolumeEnabled()) {
                            NormalizationMetadata normalizationMetadata = resolveNormalizationMetadata(
                                    song,
                                    hasGain,
                                    hasPeak,
                                    hasClosedGain,
                                    hasClosedPeak
                            );
                            song.hasLoudnessNormalization = normalizationMetadata.hasGain;
                            if (normalizationMetadata.hasGain) {
                                float effectiveGainDb = resolveEffectiveNormalizationGainDb(
                                        normalizationMetadata.gainDb,
                                        normalizationMetadata.peak,
                                        normalizationMetadata.hasPeak
                                );
                                if (Math.abs(effectiveGainDb) >= MIN_EFFECTIVE_GAIN_DB) {
                                    pendingLoudnessGainDb = effectiveGainDb;
                                    hasPendingLoudnessNormalization = true;
                                }
                            }
                            if (normalizationMetadata.hasGain) {
                                android.util.Log.d(
                                        "MusicPlayerManager",
                                        "Loudness normalization rawGainDb=" + song.gainDb
                                                + " peak=" + song.peak
                                                + " closedGainDb=" + song.closedGainDb
                                                + " closedPeak=" + song.closedPeak
                                                + " source=" + normalizationMetadata.source
                                                + " selectedGainDb=" + normalizationMetadata.gainDb
                                                + " selectedPeak=" + normalizationMetadata.peak
                                                + " hasPeak=" + normalizationMetadata.hasPeak
                                                + " appliedGainDb=" + (hasPendingLoudnessNormalization ? pendingLoudnessGainDb : 0f)
                                );
                            }
                        } else {
                            song.hasLoudnessNormalization = false;
                        }

                        // Notify that full info (lyrics, picUrl, etc.) is now available.
                        // UI components like LyricsFragment and FloatingLyricsManager use this
                        // to refresh lyrics. MusicService uses this to update notification with album art.
                        notifyFullInfoAvailable(song);

                        if (!url.isEmpty() && !"null".equals(url)) {
                            android.util.Log.d("MusicPlayerManager", "Playing URL: " + url);
                            playUrl(url, index, requestId);
                        } else {
                            handlePlaybackFailure(index, requestId, "Empty URL (Copyright/VIP/Deleted)");
                        }
                    } else {
                        handlePlaybackFailure(index, requestId, "API Status: " + root.getInt("status"));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    handlePlaybackFailure(index, requestId, "JSON Parsing Exception: " + e.getMessage());
                }
            }

            @Override
            public void onError(String error) {
                activeFullInfoRequest = NeteaseApi.CancelableRequest.NONE;
                if (requestId != activePlayRequestId || currentIndex != index) return;
                handlePlaybackFailure(index, requestId, "API Network Error: " + error);
            }
        });
    }

    private void cancelActiveFullInfoRequest() {
        NeteaseApi.CancelableRequest request = activeFullInfoRequest;
        activeFullInfoRequest = NeteaseApi.CancelableRequest.NONE;
        if (request != null) {
            request.cancel();
        }
    }

    private void playLocalSong(Song song, int index, long requestId) {
        Uri mediaUri = song.getMediaUri();
        if (mediaUri == null) {
            handlePlaybackFailure(index, requestId, "Missing local media uri");
            return;
        }

        try {
            LocalAudioMetadata metadata = LocalAudioMetadataReader.read(context, mediaUri);
            if (!metadata.isPlayable) {
                handlePlaybackFailure(index, requestId, "Local file is not a readable audio source");
                return;
            }
            if (!metadata.title.isEmpty()) {
                song.name = metadata.title;
            }
            if (!metadata.artist.isEmpty()) {
                song.artists = metadata.artist;
            }
            song.album = metadata.album;
            song.mimeType = metadata.mimeType;
            song.durationMs = metadata.durationMs;
            song.lyric = metadata.lyric;
            song.translatedLyric = metadata.translatedLyric;
            song.embeddedPicture = metadata.artworkData;
            song.gainDb = 0f;
            song.peak = 0f;
            song.closedGainDb = 0f;
            song.closedPeak = 0f;
            song.hasLoudnessNormalization = false;
            currentLyric = metadata.lyric;
            currentTLyric = metadata.translatedLyric;
            clearLoudnessNormalization();
            notifyFullInfoAvailable(song);
            playUri(mediaUri, index, requestId, false);
        } catch (Exception e) {
            handlePlaybackFailure(index, requestId, "Local metadata/read failure: " + e.getMessage());
        }
    }

    private void playUrl(String url, int expectedIndex, long requestId) {
        if (url == null || url.trim().isEmpty() || "null".equals(url)) {
            android.util.Log.e("MusicPlayerManager", "playUrl called with invalid url: " + url);
            if (requestId == activePlayRequestId) {
                isSwitchingSong = false;
            }
            return;
        }
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154");
        headers.put("Referer", "https://music.163.com/");
        playUri(Uri.parse(url), expectedIndex, requestId, true, headers);
    }

    private void playUri(Uri uri, int expectedIndex, long requestId, boolean remote) {
        playUri(uri, expectedIndex, requestId, remote, null);
    }

    private void playUri(Uri uri, int expectedIndex, long requestId, boolean remote, Map<String, String> headers) {
        if (uri == null) {
            handlePlaybackFailure(expectedIndex, requestId, "Invalid media uri");
            return;
        }
        try {
            playbackActive = false;
            mediaPlayer.reset();
            setAppVolume(settingsManager.getAppVolume());
            if (remote && headers != null && !headers.isEmpty()) {
                mediaPlayer.setDataSource(context, uri, headers);
            } else {
                mediaPlayer.setDataSource(context, uri);
            }

            mediaPlayer.setOnPreparedListener(mp -> {
                if (requestId != activePlayRequestId || currentIndex != expectedIndex) {
                    return;
                }

                isAutoSkipping = false;
                continuousSkipCount = 0;
                isSwitchingSong = false;
                if (resumePosition > 0) {
                    mp.seekTo(resumePosition);
                    resumePosition = 0;
                }
                // Apply per-track gain without touching the user-configured app volume scalar.
                if (hasPendingLoudnessNormalization) {
                    applyLoudnessNormalization(pendingLoudnessGainDb);
                } else {
                    releaseLoudnessEnhancer();
                }
                mp.start();
                playbackActive = true;
                isPaused = false;
                isCompletionListenerEnabled = true;
                notifyPlaybackStateChanged(true);
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                playbackActive = false;
                handlePlaybackFailure(expectedIndex, requestId, "MediaPlayer Error what=" + what + " extra=" + extra);
                return true;
            });

            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            playbackActive = false;
            if (requestId == activePlayRequestId) {
                isSwitchingSong = false;
            }
            e.printStackTrace();
            android.util.Log.e("MusicPlayerManager", remote ? "playUrl exception" : "playUri exception", e);
            handlePlaybackFailure(expectedIndex, requestId, e.getMessage() == null ? "playback exception" : e.getMessage());
        }
    }

    public void pause() {
        notifyPlaybackAction(true, PLAYBACK_ACTION_PAUSE);
        if (!playbackActive) {
            updateProgressDispatcherState();
            return;
        }
        try {
            mediaPlayer.pause();
            playbackActive = false;
            isPaused = true;
            notifyPlaybackStateChanged(false);
        } catch (IllegalStateException ignored) {
            playbackActive = false;
        }
        updateProgressDispatcherState();
    }

    public void markPausedForResume() {
        if (playlist.isEmpty() || currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }
        if (isPaused && !playbackActive) {
            return;
        }
        isPaused = true;
        playbackActive = false;
        notifyPlaybackStateChanged(false);
        updateProgressDispatcherState();
    }

    public void resume() {
        notifyPlaybackAction(true, PLAYBACK_ACTION_RESUME);
        if (isPaused && !playbackActive) {
            try {
                mediaPlayer.start();
                playbackActive = true;
                isPaused = false;
                notifyPlaybackStateChanged(true);
            } catch (IllegalStateException ignored) {
                playbackActive = false;
            }
        }
        updateProgressDispatcherState();
    }

    public void continueAfterHearingProtectionRest() {
        if (playlist.isEmpty()) {
            return;
        }

        int targetIndex = resolveNextIndexForHearingProtection();
        if (targetIndex < 0) {
            return;
        }

        if (targetIndex == currentIndex && currentIndex >= 0) {
            resumePosition = 0;
        }
        play(targetIndex);
    }

    public boolean canContinueAfterHearingProtectionRest() {
        if (playlist.isEmpty()) {
            return false;
        }
        return resolveNextIndexForHearingProtection() >= 0;
    }

    public void togglePlayPause() {
        if (playbackActive) {
            pause();
        } else {
            resume();
        }
    }

    public void playNext() {
        playNext(false, true);
    }

    public void playNext(boolean force) {
        playNext(force, true);
    }

    private void playNext(boolean force, boolean userInitiated) {
        if (userInitiated) {
            notifyPlaybackAction(true, PLAYBACK_ACTION_NEXT);
        }
        if (playlist.isEmpty()) return;

        int nextIndex = currentIndex;
        int effectiveMode = force ? MODE_LOOP_ALL : currentMode;
        switch (effectiveMode) {
            case MODE_LOOP_ONE:
                // Keep current index, just replay
                break;
            case MODE_SHUFFLE:
                nextIndex = random.nextInt(playlist.size());
                break;
            case MODE_LOOP_ALL:
                nextIndex = (currentIndex + 1) % playlist.size();
                break;
            case MODE_ORDER:
            default:
                if (currentIndex < playlist.size() - 1) {
                    nextIndex = currentIndex + 1;
                } else {
                    if (playbackActive) {
                        pause();
                    } else {
                        playbackActive = false;
                        isPaused = true;
                        notifyPlaybackStateChanged(false);
                    }
                    return;
                }
                break;
        }

        // When auto-skipping, we trigger the song changed notification manually
        // and let it be debounced, so that it's the last song we land on that shows up.
        if (isAutoSkipping && nextIndex >= 0 && nextIndex < playlist.size()) {
            notifySongChanged(playlist.get(nextIndex));
        }

        play(nextIndex);
    }

    private int resolveNextIndexForHearingProtection() {
        if (playlist.isEmpty()) {
            return -1;
        }
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            return 0;
        }

        switch (currentMode) {
            case MODE_LOOP_ONE:
                return currentIndex;
            case MODE_SHUFFLE:
                return random.nextInt(playlist.size());
            case MODE_LOOP_ALL:
                return (currentIndex + 1) % playlist.size();
            case MODE_ORDER:
            default:
                if (currentIndex < playlist.size() - 1) {
                    return currentIndex + 1;
                }
                return -1;
        }
    }

    public void playPrevious() {
        notifyPlaybackAction(true, PLAYBACK_ACTION_PREVIOUS);
        if (playlist.isEmpty()) return;

        int prevIndex = currentIndex;
        switch (currentMode) {
            case MODE_LOOP_ONE:
                break;
            case MODE_SHUFFLE:
                prevIndex = random.nextInt(playlist.size());
                break;
            case MODE_LOOP_ALL:
                prevIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
                break;
            case MODE_ORDER:
            default:
                if (currentIndex > 0) {
                    prevIndex = currentIndex - 1;
                } else {
                    return; // Stop at start
                }
                break;
        }
        play(prevIndex);
    }


    public void seekTo(int msec) {
        try {
            mediaPlayer.seekTo(msec);
            // Only notify listeners if we are not in the middle of switching songs.
            // This prevents redundant notification updates during song transitions
            // while still allowing synchronization for user-initiated seeks.
            if (!isSwitchingSong) {
                notifySeek(msec);
            }
            notifyPlaybackStateChanged(isPlaying());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getCurrentPosition() {
        if (isSwitchingSong) {
            return 0;
        }
        try {
            return mediaPlayer.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDuration() {
        if (isSwitchingSong) {
            return 0;
        }
        try {
            return mediaPlayer.getDuration();
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isPlaying() {
        return playbackActive;
    }

    public Song getCurrentSong() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex);
        }
        return null;
    }

    public String getCurrentLyric() {
        return currentLyric;
    }

    public String getCurrentTLyric() {
        return currentTLyric;
    }

    public void addOnSongChangedListener(OnSongChangedListener listener) {
        songChangedListeners.add(listener);
    }

    public void removeOnSongChangedListener(OnSongChangedListener listener) {
        songChangedListeners.remove(listener);
    }

    public void addOnPlaylistChangedListener(OnPlaylistChangedListener listener) {
        playlistChangedListeners.add(listener);
    }

    public void removeOnPlaylistChangedListener(OnPlaylistChangedListener listener) {
        playlistChangedListeners.remove(listener);
    }

    private void notifyPlaylistChanged() {
        mainHandler.post(() -> {
            for (OnPlaylistChangedListener listener : playlistChangedListeners) {
                listener.onPlaylistChanged(playlist);
            }
        });
    }

    private String lastSongChangedId = "";
    private void notifySongChanged(Song song) {
        if (song != null && song.id.equals(lastSongChangedId)) {
            // Check if picUrl is actually known. If it's the same ID but we are missing picUrl
            // we might still want to notify, but usually this is just redundant.
        }
        if (song != null) lastSongChangedId = song.id;

        if (isAutoSkipping) {
            if (pendingSongNotifyRunnable != null) {
                mainHandler.removeCallbacks(pendingSongNotifyRunnable);
            }
            pendingSongNotifyRunnable = () -> {
                // Double check we are still auto skipping. If it ended,
                // we'll send a final notification.
                for (OnSongChangedListener listener : songChangedListeners) {
                    listener.onSongChanged(song);
                }
            };
            mainHandler.postDelayed(pendingSongNotifyRunnable, NOTIFY_DEBOUNCE_MS);
        } else {
            mainHandler.post(() -> {
                for (OnSongChangedListener listener : songChangedListeners) {
                    listener.onSongChanged(song);
                }
            });
        }
    }

    public void addOnPlaybackStateChangedListener(OnPlaybackStateChangedListener listener) {
        playbackStateChangedListeners.add(listener);
    }

    public void removeOnPlaybackStateChangedListener(OnPlaybackStateChangedListener listener) {
        playbackStateChangedListeners.remove(listener);
    }

    public void addOnPlaybackModeChangedListener(OnPlaybackModeChangedListener listener) {
        playbackModeChangedListeners.add(listener);
    }

    public void removeOnPlaybackModeChangedListener(OnPlaybackModeChangedListener listener) {
        playbackModeChangedListeners.remove(listener);
    }

    public void addOnFullInfoAvailableListener(OnFullInfoAvailableListener listener) {
        fullInfoAvailableListeners.add(listener);
    }

    public void removeOnFullInfoAvailableListener(OnFullInfoAvailableListener listener) {
        fullInfoAvailableListeners.remove(listener);
    }

    public void addOnSeekListener(OnSeekListener listener) {
        seekListeners.add(listener);
    }

    public void removeOnSeekListener(OnSeekListener listener) {
        seekListeners.remove(listener);
    }

    public void addOnSongCompletionListener(OnSongCompletionListener listener) {
        if (!songCompletionListeners.contains(listener)) {
            songCompletionListeners.add(listener);
        }
    }

    public void removeOnSongCompletionListener(OnSongCompletionListener listener) {
        songCompletionListeners.remove(listener);
    }

    public void addOnPlaybackActionListener(OnPlaybackActionListener listener) {
        if (!playbackActionListeners.contains(listener)) {
            playbackActionListeners.add(listener);
        }
    }

    public void removeOnPlaybackActionListener(OnPlaybackActionListener listener) {
        playbackActionListeners.remove(listener);
    }

    public void addOnProgressUpdateListener(OnProgressUpdateListener listener) {
        if (!progressUpdateListeners.contains(listener)) {
            progressUpdateListeners.add(listener);
        }
        notifyProgressUpdate(getCurrentPosition(), getDuration());
        updateProgressDispatcherState();
    }

    private float volumePercentToScalar(int percent) {
        int clamped = Math.max(0, Math.min(percent, 100));
        return clamped / 100f;
    }

    public void setAppVolume(int percent) {
        setAppVolume(percent, true);
    }

    private void setAppVolume(int percent, boolean smooth) {
        float volumeScalar = volumePercentToScalar(percent);
        if (!smooth || !playbackActive) {
            cancelAppVolumeRamp();
            applyAppVolumeScalar(volumeScalar);
            return;
        }

        startAppVolumeRamp(volumeScalar);
    }

    private void startAppVolumeRamp(float targetScalar) {
        cancelAppVolumeRamp();
        final float startScalar = currentAppVolumeScalar;
        final long startTimeMs = android.os.SystemClock.uptimeMillis();
        appVolumeRampRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsedMs = android.os.SystemClock.uptimeMillis() - startTimeMs;
                float fraction = Math.min(1f, elapsedMs / (float) APP_VOLUME_RAMP_DURATION_MS);
                float nextScalar = startScalar + ((targetScalar - startScalar) * fraction);
                applyAppVolumeScalar(nextScalar);
                if (fraction < 1f && appVolumeRampRunnable == this) {
                    mainHandler.postDelayed(this, APP_VOLUME_RAMP_STEP_MS);
                } else if (appVolumeRampRunnable == this) {
                    appVolumeRampRunnable = null;
                    applyAppVolumeScalar(targetScalar);
                }
            }
        };
        mainHandler.post(appVolumeRampRunnable);
    }

    private void cancelAppVolumeRamp() {
        if (appVolumeRampRunnable != null) {
            mainHandler.removeCallbacks(appVolumeRampRunnable);
            appVolumeRampRunnable = null;
        }
    }

    private void applyAppVolumeScalar(float volumeScalar) {
        try {
            mediaPlayer.setVolume(volumeScalar, volumeScalar);
            currentAppVolumeScalar = volumeScalar;
        } catch (IllegalStateException ignored) {
        }
    }

    public void applyLoudnessNormalization(float gainDb) {
        if (!Float.isFinite(gainDb)) {
            clearLoudnessNormalization();
            return;
        }
        int audioSessionId;
        try {
            audioSessionId = mediaPlayer.getAudioSessionId();
        } catch (IllegalStateException e) {
            return;
        }
        if (audioSessionId <= 0) {
            return;
        }

        pendingLoudnessGainDb = gainDb;
        hasPendingLoudnessNormalization = true;

        int gainmB = Math.round(gainDb * MILLIBELS_PER_DECIBEL);
        try {
            if (loudnessEnhancer != null && loudnessEnhancerAudioSessionId != audioSessionId) {
                releaseLoudnessEnhancer();
            }
            if (loudnessEnhancer == null) {
                loudnessEnhancer = new LoudnessEnhancer(audioSessionId);
                loudnessEnhancerAudioSessionId = audioSessionId;
            }
            loudnessEnhancer.setTargetGain(gainmB);
            loudnessEnhancer.setEnabled(true);
        } catch (RuntimeException e) {
            releaseLoudnessEnhancer();
        }
    }

    public void onDynamicVolumeSettingChanged() {
        Song currentSong = getCurrentSong();
        if (!settingsManager.isDynamicVolumeEnabled()) {
            clearLoudnessNormalization();
            setAppVolume(settingsManager.getAppVolume());
            return;
        }
        if (currentSong == null || currentSong.isLocal()) {
            clearLoudnessNormalization();
            return;
        }
        NormalizationMetadata normalizationMetadata = resolveStoredNormalizationMetadata(currentSong);
        currentSong.hasLoudnessNormalization = normalizationMetadata.hasGain;
        clearLoudnessNormalization();
        if (!normalizationMetadata.hasGain) {
            return;
        }
        float effectiveGainDb = resolveEffectiveNormalizationGainDb(
                normalizationMetadata.gainDb,
                normalizationMetadata.peak,
                normalizationMetadata.hasPeak
        );
        if (Math.abs(effectiveGainDb) < MIN_EFFECTIVE_GAIN_DB) {
            return;
        }
        pendingLoudnessGainDb = effectiveGainDb;
        hasPendingLoudnessNormalization = true;
        applyLoudnessNormalization(effectiveGainDb);
    }

    private void clearLoudnessNormalization() {
        pendingLoudnessGainDb = 0f;
        hasPendingLoudnessNormalization = false;
        releaseLoudnessEnhancer();
    }

    private NormalizationMetadata resolveNormalizationMetadata(Song song,
                                                               boolean hasGain,
                                                               boolean hasPeak,
                                                               boolean hasClosedGain,
                                                               boolean hasClosedPeak) {
        if (song == null) {
            return new NormalizationMetadata(false, 0f, false, 0f, "none");
        }
        if (hasClosedGain && Float.isFinite(song.closedGainDb)) {
            boolean validClosedPeak = hasClosedPeak && Float.isFinite(song.closedPeak) && song.closedPeak > 0f;
            return new NormalizationMetadata(true, song.closedGainDb, validClosedPeak, song.closedPeak, "closed");
        }
        if (hasGain && Float.isFinite(song.gainDb)) {
            boolean validPeak = hasPeak && Float.isFinite(song.peak) && song.peak > 0f;
            return new NormalizationMetadata(true, song.gainDb, validPeak, song.peak, "raw");
        }
        return new NormalizationMetadata(false, 0f, false, 0f, "none");
    }

    private NormalizationMetadata resolveStoredNormalizationMetadata(Song song) {
        if (song == null) {
            return new NormalizationMetadata(false, 0f, false, 0f, "none");
        }
        boolean hasClosedPeak = Float.isFinite(song.closedPeak) && song.closedPeak > 0f;
        if (Float.isFinite(song.closedGainDb) && (song.closedGainDb != 0f || hasClosedPeak)) {
            return new NormalizationMetadata(true, song.closedGainDb, hasClosedPeak, song.closedPeak, "closed");
        }
        boolean hasPeak = Float.isFinite(song.peak) && song.peak > 0f;
        if (Float.isFinite(song.gainDb) && (song.gainDb != 0f || hasPeak)) {
            return new NormalizationMetadata(true, song.gainDb, hasPeak, song.peak, "raw");
        }
        return new NormalizationMetadata(false, 0f, false, 0f, "none");
    }

    private float resolveEffectiveNormalizationGainDb(float gainDb, float peak, boolean hasPeak) {
        float clampedGainDb = clampGainDb(gainDb);
        if (!Float.isFinite(clampedGainDb)) {
            return 0f;
        }
        if (clampedGainDb <= 0f) {
            return clampedGainDb;
        }
        if (hasPeak && Float.isFinite(peak) && peak > 0f) {
            float safePeak = Math.max(peak, 0.0001f);
            double safeBoostDb = 20d * Math.log10(MAX_NORMALIZED_OUTPUT_PEAK / safePeak);
            return clampGainDb((float) Math.min(clampedGainDb, safeBoostDb));
        }
        return Math.min(clampedGainDb, MAX_FALLBACK_POSITIVE_GAIN_DB);
    }

    private float clampGainDb(float gainDb) {
        return Math.max(MIN_ALLOWED_GAIN_DB, Math.min(gainDb, MAX_ALLOWED_GAIN_DB));
    }

    private void releaseLoudnessEnhancer() {
        if (loudnessEnhancer == null) {
            return;
        }
        try {
            loudnessEnhancer.setEnabled(false);
        } catch (RuntimeException ignored) {
        }
        try {
            loudnessEnhancer.release();
        } catch (RuntimeException ignored) {
        }
        loudnessEnhancer = null;
        loudnessEnhancerAudioSessionId = -1;
    }

    public void release() {
        cancelActiveFullInfoRequest();
        stopProgressDispatcher();
        cancelAppVolumeRamp();
        clearLoudnessNormalization();
        try {
            mediaPlayer.release();
        } catch (Exception ignored) {
        }
        instance = null;
    }

    public void removeOnProgressUpdateListener(OnProgressUpdateListener listener) {
        progressUpdateListeners.remove(listener);
        updateProgressDispatcherState();
    }

    private void notifySeek(int msec) {
        mainHandler.post(() -> {
            for (OnSeekListener listener : seekListeners) {
                listener.onSeek(msec);
            }
        });
    }

    private String lastFullInfoId = "";
    private String lastFullInfoPicUrl = "";
    private void notifyFullInfoAvailable(Song song) {
        if (song != null && song.id.equals(lastFullInfoId)) {
            // Check if picUrl actually changed after normalization.
            if (ImageUtils.isSameImage(song.picUrl, lastFullInfoPicUrl)) {
                // If the only thing that could trigger a cover refresh (picUrl) is effectively the same,
                // and we've already notified full info for this ID, we might skip to reduce jitter.
                // However, we still want to notify for lyrics. Let's check if lyrics changed.
                // For simplicity, we only deduplicate if we are sure it's redundant.
            }
        }
        if (song != null) {
            lastFullInfoId = song.id;
            lastFullInfoPicUrl = song.picUrl;
        }

        // Only notify full info if we are not auto-skipping.
        // During auto-skips, we don't care about lyrics or album art for intermediate songs.
        if (isAutoSkipping) {
            return;
        }

        mainHandler.post(() -> {
            for (OnFullInfoAvailableListener listener : fullInfoAvailableListeners) {
                listener.onFullInfoAvailable(song);
            }
        });
    }

    private void notifyPlaybackModeChanged(int mode) {
        mainHandler.post(() -> {
            for (OnPlaybackModeChangedListener listener : playbackModeChangedListeners) {
                listener.onPlaybackModeChanged(mode);
            }
        });
    }

    private void notifyPlaybackStateChanged(boolean isPlaying) {
        boolean shouldForceDispatch = forceNextPlaybackStateDispatch;
        if (isPlaying == lastNotifiedState && !shouldForceDispatch) {
            updateProgressDispatcherState();
            return;
        }
        forceNextPlaybackStateDispatch = false;
        lastNotifiedState = isPlaying;
        mainHandler.post(() -> {
            for (OnPlaybackStateChangedListener listener : playbackStateChangedListeners) {
                listener.onPlaybackStateChanged(isPlaying);
            }
        });
        updateProgressDispatcherState();
    }

    private boolean shouldRunProgressDispatcher() {
        return !isSwitchingSong && playbackActive && !progressUpdateListeners.isEmpty();
    }

    private void startProgressDispatcher() {
        if (isProgressDispatcherRunning) {
            return;
        }
        isProgressDispatcherRunning = true;
        mainHandler.removeCallbacks(progressUpdateRunnable);
        mainHandler.post(progressUpdateRunnable);
    }

    private void stopProgressDispatcher() {
        if (!isProgressDispatcherRunning) {
            return;
        }
        isProgressDispatcherRunning = false;
        mainHandler.removeCallbacks(progressUpdateRunnable);
    }

    private void updateProgressDispatcherState() {
        if (shouldRunProgressDispatcher()) {
            startProgressDispatcher();
        } else {
            stopProgressDispatcher();
        }
    }

    private void notifyProgressUpdate(int current, int total) {
        final int safeCurrent = Math.max(0, current);
        final int safeTotal = Math.max(0, total);
        mainHandler.post(() -> {
            for (OnProgressUpdateListener listener : progressUpdateListeners) {
                listener.onProgressUpdate(safeCurrent, safeTotal);
            }
        });
    }

    private boolean notifySongCompleted(Song song, int completedIndex) {
        boolean consumed = false;
        for (OnSongCompletionListener listener : songCompletionListeners) {
            if (listener.onSongCompleted(song, completedIndex)) {
                consumed = true;
            }
        }
        return consumed;
    }

    private void notifyPlaybackAction(boolean userInitiated, String action) {
        if (playbackActionListeners.isEmpty()) {
            return;
        }
        mainHandler.post(() -> {
            for (OnPlaybackActionListener listener : playbackActionListeners) {
                listener.onPlaybackAction(userInitiated, action);
            }
        });
    }
}
