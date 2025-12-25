package com.midairlogn.mlnetease;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
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
    private Context context;
    private NeteaseApi neteaseApi;
    private int currentMode = MODE_ORDER;
    private Random random = new Random();
    private int retryCount = 0;
    private static final int MAX_RETRY = 1;
    private int resumePosition = 0;
    private boolean isCompletionListenerEnabled = false;

    // Callbacks
    private List<OnSongChangedListener> songChangedListeners = new ArrayList<>();
    private List<OnPlaybackStateChangedListener> playbackStateChangedListeners = new ArrayList<>();
    private List<OnPlaylistChangedListener> playlistChangedListeners = new ArrayList<>();
    private List<OnPlaybackModeChangedListener> playbackModeChangedListeners = new ArrayList<>();

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

    private MusicPlayerManager(Context context) {
        this.context = context.getApplicationContext();
        this.neteaseApi = new NeteaseApi(new SettingsManager(this.context));
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

        // If it is not a retry, reset retry count and resume position
        if (!isRetry) {
            retryCount = 0;
            resumePosition = 0;
        }

        boolean isNewSong = (index != currentIndex);
        currentIndex = index;
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
        currentLyric = "Loading...";

        // Fetch full info
        neteaseApi.getSongFullInfo(song.id, new NeteaseApi.ApiCallback() {
            @Override
            public void onSuccess(String result) {
                // Check if the current index is still what we expect
                if (currentIndex != index) return;

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

                        notifySongChanged(song); // Notify again with full info

                        if (!url.isEmpty()) {
                            android.util.Log.d("MusicPlayerManager", "Playing URL: " + url);
                            playUrl(url);
                        } else {
                            android.util.Log.e("MusicPlayerManager", "Song URL is empty. Check VIP/Copyright status.");
                            // Handle error (e.g. notify listeners of error)
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onError(String error) {
                // Handle error
            }
        });
    }

    private void playUrl(String url) {
        if (url == null || url.trim().isEmpty() || "null".equals(url)) {
            android.util.Log.e("MusicPlayerManager", "playUrl called with invalid url: " + url);
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

            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
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
                    // Reload current song
                    play(currentIndex, true);
                    return true;
                }

                // Return true if we handled the error, false otherwise
                notifyPlaybackStateChanged(false);
                return true;
            });

        } catch (Exception e) {
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
    }

    public void resume() {
        if (isPaused && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            isPaused = false;
            notifyPlaybackStateChanged(true);
        }
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
                    // Stop or stay at end? Typically stop or pause.
                    // But here we might just not play.
                    // For now, let's wrap or stop. "Order" usually implies stop at end.
                    // If we just return, it stops.
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getCurrentPosition() {
        try {
            return mediaPlayer.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDuration() {
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
        new Handler(Looper.getMainLooper()).post(() -> {
            for (OnPlaylistChangedListener listener : playlistChangedListeners) {
                listener.onPlaylistChanged(playlist);
            }
        });
    }

    private void notifySongChanged(Song song) {
        new Handler(Looper.getMainLooper()).post(() -> {
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

    private void notifyPlaybackModeChanged(int mode) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (OnPlaybackModeChangedListener listener : playbackModeChangedListeners) {
                listener.onPlaybackModeChanged(mode);
            }
        });
    }

    private void notifyPlaybackStateChanged(boolean isPlaying) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (OnPlaybackStateChangedListener listener : playbackStateChangedListeners) {
                listener.onPlaybackStateChanged(isPlaying);
            }
        });
    }
}
