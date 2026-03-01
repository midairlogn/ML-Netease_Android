package com.midairlogn.mlnetease;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private int resumePosition = 0;
    private boolean isCompletionListenerEnabled = false;
    private final AtomicLong playRequestIdGenerator = new AtomicLong(0);
    private volatile long activePlayRequestId = 0;

    // Callbacks
    private List<OnSongChangedListener> songChangedListeners = new ArrayList<>();
    private List<OnPlaybackStateChangedListener> playbackStateChangedListeners = new ArrayList<>();
    private List<OnPlaylistChangedListener> playlistChangedListeners = new ArrayList<>();
    private List<OnPlaybackModeChangedListener> playbackModeChangedListeners = new ArrayList<>();
    private List<OnFullInfoAvailableListener> fullInfoAvailableListeners = new ArrayList<>();
    private List<OnSeekListener> seekListeners = new ArrayList<>();
    private List<OnProgressUpdateListener> progressUpdateListeners = new ArrayList<>();
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

    private MusicPlayerManager(Context context) {
        this.context = context.getApplicationContext();
        this.settingsManager = new SettingsManager(this.context);
        this.neteaseApi = new NeteaseApi(settingsManager);
        this.currentMode = settingsManager.getPlayMode();
        mediaPlayer = new MediaPlayer();

        android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .build();
        mediaPlayer.setAudioAttributes(audioAttributes);

        mediaPlayer.setOnCompletionListener(mp -> {
            if (isCompletionListenerEnabled) {
                playNext();
            }
        });
    }

    public static MusicPlayerManager getInstance(Context context) {
        if (instance == null) {
            instance = new MusicPlayerManager(context);
        }
        return instance;
    }

    public void setPlaylist(List<Song> songs) {
        this.playlist = new ArrayList<>(songs);
        this.currentIndex = -1; // Reset current index since playlist changed
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
        notifyPlaylistChanged();
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
        notifyPlaylistChanged();
    }


    public void play(int index) {
        play(index, false);
    }

    private void play(int index, boolean isRetry) {
        if (index < 0 || index >= playlist.size()) return;

        final long requestId = playRequestIdGenerator.incrementAndGet();
        activePlayRequestId = requestId;
        forceNextPlaybackStateDispatch = true;

        // If it is not a retry, reset retry count and resume position
        if (!isRetry) {
            retryCount = 0;
            resumePosition = 0;
        }

        boolean wasPlaying = isPlaying();
        boolean isNewSong = (index != currentIndex);
        currentIndex = index;
        isSwitchingSong = true;
        updateProgressDispatcherState();
        // Temporarily disable completion listener to prevent race conditions during song loading/switching
        isCompletionListenerEnabled = false;

        if (isNewSong) {
            // Stop previous playback to prevent onCompletion events from firing for the old song
            // while we are loading the new one. This prevents race conditions where the old song
            // finishes and triggers playNext() -> play(index+1).
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        Song song = playlist.get(index);

        // Notify change immediately so UI updates (cover, title)
        notifySongChanged(song);
        notifyProgressUpdate(0, 0);
        if (wasPlaying) {
            // Emit one stable pause/loading transition at switch start.
            notifyPlaybackStateChanged(false);
        }
        currentLyric = "Loading...";
        currentTLyric = "";

        // Fetch full info
        neteaseApi.getSongFullInfo(song.id, new NeteaseApi.ApiCallback() {
            @Override
            public void onSuccess(String result) {
                // Ignore stale callback from previous play request.
                if (requestId != activePlayRequestId || currentIndex != index) return;

                try {
                    JSONObject root = new JSONObject(result);
                    if (root.getInt("status") == 200) {
                        String url = root.optString("url", "");
                        currentLyric = root.optString("lyric", "");
                        currentTLyric = root.optString("tlyric", "");

                        // Update Song object with better info if available
                        song.picUrl = root.optString("pic", song.picUrl);
                        song.name = root.optString("name", song.name);
                        song.artists = root.optString("ar_name", song.artists);
                        song.album = root.optString("al_name", song.album);

                        // Notify that full info (lyrics, picUrl, etc.) is now available.
                        // UI components like LyricsFragment and FloatingLyricsManager use this
                        // to refresh lyrics. MusicService uses this to update notification with album art.
                        notifyFullInfoAvailable(song);

                        if (!url.isEmpty()) {
                            android.util.Log.d("MusicPlayerManager", "Playing URL: " + url);
                            playUrl(url, index, requestId);
                        } else {
                            android.util.Log.e("MusicPlayerManager", "Song URL is empty. Check VIP/Copyright status.");
                            isSwitchingSong = false;
                            notifyPlaybackStateChanged(false);
                        }
                    } else {
                        isSwitchingSong = false;
                        notifyPlaybackStateChanged(false);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    isSwitchingSong = false;
                    notifyPlaybackStateChanged(false);
                }
            }

            @Override
            public void onError(String error) {
                if (requestId != activePlayRequestId || currentIndex != index) return;
                android.util.Log.e("MusicPlayerManager", "getSongFullInfo error: " + error);
                isSwitchingSong = false;
                notifyPlaybackStateChanged(false);
            }
        });
    }

    private void playUrl(String url, int expectedIndex, long requestId) {
        if (url == null || url.trim().isEmpty() || "null".equals(url)) {
            android.util.Log.e("MusicPlayerManager", "playUrl called with invalid url: " + url);
            if (requestId == activePlayRequestId) {
                isSwitchingSong = false;
            }
            return;
        }
        try {
            mediaPlayer.reset();
            // Use headers to mimic browser/desktop client to avoid 403 Forbidden from CDN
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154");
            headers.put("Referer", "https://music.163.com/");

            android.net.Uri uri = android.net.Uri.parse(url);
            mediaPlayer.setDataSource(context, uri, headers);

            mediaPlayer.setOnPreparedListener(mp -> {
                if (requestId != activePlayRequestId || currentIndex != expectedIndex) {
                    return;
                }

                isSwitchingSong = false;
                if (resumePosition > 0) {
                    mp.seekTo(resumePosition);
                    resumePosition = 0;
                }
                mp.start();
                isPaused = false;
                // Enable completion listener only after successful preparation and start
                isCompletionListenerEnabled = true;
                notifyPlaybackStateChanged(true);
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                if (requestId != activePlayRequestId || currentIndex != expectedIndex) {
                    return true;
                }

                isSwitchingSong = false;
                android.util.Log.e("MusicPlayerManager", "MediaPlayer Error: what=" + what + ", extra=" + extra);

                if (retryCount < MAX_RETRY) {
                    retryCount++;
                    android.util.Log.d("MusicPlayerManager", "Retrying playback... Attempt " + retryCount);
                    // Save position
                    try {
                        resumePosition = mp.getCurrentPosition();
                    } catch (Exception e) {
                        resumePosition = 0;
                    }
                    // Reload current song with a new request token
                    play(currentIndex, true);
                    return true;
                }

                // Return true if we handled the error, false otherwise
                notifyPlaybackStateChanged(false);
                return true;
            });

            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            if (requestId == activePlayRequestId) {
                isSwitchingSong = false;
            }
            e.printStackTrace();
            android.util.Log.e("MusicPlayerManager", "playUrl exception", e);
        }
    }

    public void pause() {
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPaused = true;
            notifyPlaybackStateChanged(false);
        }
        updateProgressDispatcherState();
    }

    public void resume() {
        if (isPaused && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            isPaused = false;
            notifyPlaybackStateChanged(true);
        }
        updateProgressDispatcherState();
    }

    public void togglePlayPause() {
        if (mediaPlayer.isPlaying()) {
            pause();
        } else {
            resume();
        }
    }

    public void playNext() {
        if (playlist.isEmpty()) return;

        int nextIndex = currentIndex;
        switch (currentMode) {
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
                    if (mediaPlayer.isPlaying()) {
                        pause();
                    } else {
                        isPaused = true;
                        notifyPlaybackStateChanged(false);
                    }
                    return;
                }
                break;
        }
        play(nextIndex);
    }

    public void playPrevious() {
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
        try {
            return mediaPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
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

        mainHandler.post(() -> {
            for (OnSongChangedListener listener : songChangedListeners) {
                listener.onSongChanged(song);
            }
        });
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

    public void addOnProgressUpdateListener(OnProgressUpdateListener listener) {
        if (!progressUpdateListeners.contains(listener)) {
            progressUpdateListeners.add(listener);
        }
        notifyProgressUpdate(getCurrentPosition(), getDuration());
        updateProgressDispatcherState();
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
        return !isSwitchingSong && isPlaying() && !progressUpdateListeners.isEmpty();
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
}
