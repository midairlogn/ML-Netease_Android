package com.midairlogn.mlnetease;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LyricsFragment extends Fragment implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnPlaybackStateChangedListener, MusicPlayerManager.OnFullInfoAvailableListener {

    private RecyclerView recyclerView;
    private LyricsAdapter adapter;
    private List<LyricLine> lyricLines = new ArrayList<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateTask;
    private boolean isTracking = false;
    private int currentLineIndex = -1;

    // Timeline Overlay Views
    private View lyricsTimelineOverlay;
    private View lyricsHighlightBg;
    private View lyricsTimelineLine;
    private TextView lyricsTimelineTime;
    private ImageButton lyricsTimelinePlay;
    private boolean isUserScrolling = false;
    private Runnable hideOverlayRunnable;
    private long selectedTime = -1;
    private SettingsManager settingsManager;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_lyrics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        recyclerView = view.findViewById(R.id.recycler_lyrics);

        // Initialize Overlay Views
        lyricsHighlightBg = view.findViewById(R.id.lyrics_highlight_bg);
        lyricsTimelineOverlay = view.findViewById(R.id.lyrics_timeline_overlay);
        lyricsTimelineLine = view.findViewById(R.id.lyrics_timeline_line);
        lyricsTimelineTime = view.findViewById(R.id.lyrics_timeline_time);
        lyricsTimelinePlay = view.findViewById(R.id.lyrics_timeline_play);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new LyricsAdapter();
        recyclerView.setAdapter(adapter);

        // Dynamically set vertical padding to half of the screen height
        // This ensures first and last lyrics can scroll to center
        recyclerView.post(() -> {
            int halfHeight = recyclerView.getHeight() / 2;
            recyclerView.setPadding(
                recyclerView.getPaddingLeft(),
                halfHeight,
                recyclerView.getPaddingRight(),
                halfHeight
            );
        });

        setupTimelineInteraction();

        MusicPlayerManager manager = MusicPlayerManager.getInstance(getContext());
        settingsManager = new SettingsManager(requireContext());
        manager.addOnSongChangedListener(this);
        manager.addOnFullInfoAvailableListener(this);
        manager.addOnPlaybackStateChangedListener(this);

        preferenceChangeListener = (sharedPreferences, key) -> {
            if (settingsManager == null) return;
            if (!"translation_integration_enabled".equals(key)) return;
            MusicPlayerManager refreshedManager = MusicPlayerManager.getInstance(requireContext());
            updateLyrics(refreshedManager.getCurrentLyric(), refreshedManager.getCurrentTLyric());
        };
        settingsManager.getPrefs().registerOnSharedPreferenceChangeListener(preferenceChangeListener);

        updateLyrics(manager.getCurrentLyric(), manager.getCurrentTLyric());

        if (manager.isPlaying()) {
            startUpdateTask();
        }
    }

    private void setupTimelineInteraction() {
        hideOverlayRunnable = () -> {
            lyricsTimelineOverlay.setVisibility(View.GONE);
            lyricsHighlightBg.setVisibility(View.GONE);
            isUserScrolling = false;
        };

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    isUserScrolling = true;
                    handler.removeCallbacks(hideOverlayRunnable);
                    lyricsTimelineOverlay.setVisibility(View.VISIBLE);
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // Start timer to hide overlay
                    handler.postDelayed(hideOverlayRunnable, 3000);
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (lyricsTimelineOverlay.getVisibility() == View.VISIBLE) {
                    updateTimelineTime();
                }
            }
        });

        lyricsTimelinePlay.setOnClickListener(v -> {
            if (selectedTime != -1) {
                MusicPlayerManager manager = MusicPlayerManager.getInstance(getContext());
                manager.seekTo((int) selectedTime);
                manager.resume();
                isUserScrolling = false;
                lyricsTimelineOverlay.setVisibility(View.GONE);
                lyricsHighlightBg.setVisibility(View.GONE);
                handler.removeCallbacks(hideOverlayRunnable);
            }
        });
    }

    private void updateTimelineTime() {
        if (lyricLines.isEmpty()) return;

        int centerY = recyclerView.getHeight() / 2;
        View centerView = recyclerView.findChildViewUnder(recyclerView.getWidth() / 2f, centerY);

        if (centerView != null) {
            int top = centerView.getTop();
            int bottom = centerView.getBottom();
            int height = bottom - top;
            int viewCenterY = (top + bottom) / 2;
            
            int paddingTop = centerView.getPaddingTop();
            int paddingBottom = centerView.getPaddingBottom();

            int relativeY = centerY - top; // Y position relative to view top

            boolean isOverText = relativeY >= paddingTop && relativeY <= (height - paddingBottom);

            if (isOverText) {
                // Show Highlight
                lyricsHighlightBg.setVisibility(View.VISIBLE);
                lyricsTimelineLine.setVisibility(View.INVISIBLE); // Hide dashed line
                
                int textHeight = height - paddingTop - paddingBottom;
                if (lyricsHighlightBg.getLayoutParams().height != textHeight) {
                    lyricsHighlightBg.getLayoutParams().height = textHeight;
                    lyricsHighlightBg.requestLayout();
                }

                // Update Time
                int pos = recyclerView.getChildAdapterPosition(centerView);
                if (pos != RecyclerView.NO_POSITION && pos < lyricLines.size()) {
                    long time = lyricLines.get(pos).time;
                    selectedTime = time;
                    lyricsTimelineTime.setText(formatTime(time));
                }

            } else {
                // In the gap
                lyricsHighlightBg.setVisibility(View.INVISIBLE);
                lyricsTimelineLine.setVisibility(View.VISIBLE);

                int pos = recyclerView.getChildAdapterPosition(centerView);
                if (pos != RecyclerView.NO_POSITION && pos < lyricLines.size()) {
                    long time = lyricLines.get(pos).time;
                    selectedTime = time;
                    lyricsTimelineTime.setText(formatTime(time));
                }
            }
        }
    }

    private String formatTime(long msec) {
        long seconds = msec / 1000;
        long minutes = seconds / 60;
        long secs = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, secs);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopUpdateTask();
        handler.removeCallbacks(hideOverlayRunnable);
        MusicPlayerManager.getInstance(getContext()).removeOnSongChangedListener(this);
        MusicPlayerManager.getInstance(getContext()).removeOnFullInfoAvailableListener(this);
        MusicPlayerManager.getInstance(getContext()).removeOnPlaybackStateChangedListener(this);
        if (settingsManager != null && preferenceChangeListener != null) {
            settingsManager.getPrefs().unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
    }

    @Override
    public void onSongChanged(Song song) {
        // Reset lyrics when song changes
        updateLyrics("Loading...", "");
    }

    @Override
    public void onFullInfoAvailable(Song song) {
        MusicPlayerManager manager = MusicPlayerManager.getInstance(getContext());
        updateLyrics(manager.getCurrentLyric(), manager.getCurrentTLyric());
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        if (isPlaying) {
            startUpdateTask();
        } else {
            stopUpdateTask();
        }
    }

    private void updateLyrics(String lyrics, String tlyrics) {
        if (getActivity() == null || settingsManager == null) return;

        getActivity().runOnUiThread(() -> {
            boolean showTranslation = settingsManager.isTranslationIntegrationEnabled();
            if (showTranslation) {
                lyricLines = LyricsUtils.mergeLyricsWithTranslation(lyrics, tlyrics);
            } else {
                lyricLines = LyricsUtils.parseLyrics(lyrics);
            }
            adapter.setShowTranslation(showTranslation);
            adapter.setLyrics(lyricLines);
            currentLineIndex = -1;
            if (!lyricLines.isEmpty()) {
                syncLyrics();
            }
        });
    }

    private void startUpdateTask() {
        stopUpdateTask();
        updateTask = new Runnable() {
            @Override
            public void run() {
                syncLyrics();
                handler.postDelayed(this, 300);
            }
        };
        handler.post(updateTask);
    }

    private void stopUpdateTask() {
        if (updateTask != null) {
            handler.removeCallbacks(updateTask);
            updateTask = null;
        }
    }

    private void syncLyrics() {
        if (lyricLines.isEmpty()) return;

        MusicPlayerManager manager = MusicPlayerManager.getInstance(getContext());
        if (manager == null) return;

        int position = manager.getCurrentPosition();

        int newIndex = -1;
        for (int i = 0; i < lyricLines.size(); i++) {
            if (lyricLines.get(i).time > position) {
                break;
            }
            newIndex = i;
        }

        if (newIndex != -1 && newIndex != currentLineIndex) {
            currentLineIndex = newIndex;
            // Always update highlight even when user is scrolling
            adapter.setActiveIndex(currentLineIndex);
            // Only auto-scroll if user is not interacting
            if (!isUserScrolling) {
                scrollToPosition(currentLineIndex);
            }
        }
    }

    private void scrollToPosition(int position) {
        if (recyclerView == null) return;

        LinearSmoothScroller smoothScroller = new LinearSmoothScroller(recyclerView.getContext()) {
            @Override
            protected int getVerticalSnapPreference() {
                return LinearSmoothScroller.SNAP_TO_START;
            }

            @Override
            public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2);
            }
        };
        smoothScroller.setTargetPosition(position);
        if (recyclerView.getLayoutManager() != null) {
            recyclerView.getLayoutManager().startSmoothScroll(smoothScroller);
        }
    }
}
