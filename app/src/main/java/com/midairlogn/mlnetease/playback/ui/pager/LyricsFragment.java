package com.midairlogn.mlnetease.playback.ui.pager;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
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
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.midairlogn.mlnetease.playback.lyrics.LyricsAdapter;
import com.midairlogn.mlnetease.playback.lyrics.LyricsUtils;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.hearing.HearingProtectionTransportController;
import com.midairlogn.mlnetease.playback.core.MusicPlayerManager;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.LyricLine;
import com.midairlogn.mlnetease.shared.model.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LyricsFragment extends Fragment implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnPlaybackStateChangedListener, MusicPlayerManager.OnFullInfoAvailableListener {

    private static final long LYRIC_SYNC_INTERVAL_MS = 300L;
    private static final float LYRIC_SCROLL_MILLISECONDS_PER_INCH = 110f;
    private static final int LYRIC_SCROLL_MIN_DURATION_MS = 280;
    private static final int LYRIC_SCROLL_MAX_DURATION_MS = 700;

    private RecyclerView recyclerView;
    private LyricsAdapter adapter;
    private List<LyricLine> lyricLines = new ArrayList<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateTask;
    private boolean isTracking = false;
    private int currentLineIndex = -1;

    // Timeline Overlay Views
    private View lyricsHighlightBg;
    private View lyricsTimelineLine;
    private TextView lyricsTimelineTime;
    private ImageButton lyricsTimelinePlay;
    private boolean isUserScrolling = false;
    private Runnable hideOverlayRunnable;
    private long selectedTime = -1;
    private SettingsManager settingsManager;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;
    private boolean hasTimestampedLyrics = false;
    private int pendingAutoScrollPosition = RecyclerView.NO_POSITION;
    private final View.OnLayoutChangeListener paddingLayoutListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateRecyclerPadding();

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
        lyricsTimelineLine = view.findViewById(R.id.lyrics_timeline_line);
        lyricsTimelineTime = view.findViewById(R.id.lyrics_timeline_time);
        lyricsTimelinePlay = view.findViewById(R.id.lyrics_timeline_play);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        if (recyclerView.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        adapter = new LyricsAdapter();
        adapter.setOnLyricClickListener((line, position) -> {
            if (!hasTimestampedLyrics) {
                return;
            }
            seekToLyricTime(line.time, true);
        });
        recyclerView.setAdapter(adapter);
        recyclerView.addOnLayoutChangeListener(paddingLayoutListener);
        updateRecyclerPadding();

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
        hideOverlayRunnable = this::clearTimelineOverlay;

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    pendingAutoScrollPosition = RecyclerView.NO_POSITION;
                    if (!hasTimestampedLyrics) {
                        clearTimelineOverlay();
                        return;
                    }
                    isUserScrolling = true;
                    handler.removeCallbacks(hideOverlayRunnable);
                    showTimelineOverlay();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (!hasTimestampedLyrics) {
                        clearTimelineOverlay();
                        return;
                    }
                    // Start timer to hide overlay
                    handler.postDelayed(hideOverlayRunnable, 3000);
                    pendingAutoScrollPosition = RecyclerView.NO_POSITION;
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (lyricsTimelinePlay.getVisibility() == View.VISIBLE) {
                    updateTimelineTime();
                }
            }
        });

        lyricsTimelinePlay.setOnClickListener(v -> {
            if (selectedTime == -1 || !hasTimestampedLyrics) {
                return;
            }
            seekToLyricTime(selectedTime, true);
        });
    }

    private void showTimelineOverlay() {
        lyricsTimelineTime.setVisibility(View.VISIBLE);
        lyricsTimelinePlay.setVisibility(View.VISIBLE);
    }

    private void seekToLyricTime(long time, boolean clearTimelineOverlay) {
        MusicPlayerManager manager = MusicPlayerManager.getInstance(getContext());
        if (manager == null) {
            return;
        }
        manager.seekTo((int) time);
        HearingProtectionTransportController.handleSeekResumeCurrent(requireContext());
        if (clearTimelineOverlay) {
            clearTimelineOverlay();
        }
    }

    private void clearTimelineOverlay() {
        isUserScrolling = false;
        lyricsHighlightBg.setVisibility(View.GONE);
        lyricsTimelineLine.setVisibility(View.GONE);
        lyricsTimelineTime.setVisibility(View.GONE);
        lyricsTimelinePlay.setVisibility(View.GONE);
        handler.removeCallbacks(hideOverlayRunnable);
    }

    private void updateRecyclerPadding() {
        if (recyclerView == null) {
            return;
        }
        int height = recyclerView.getHeight();
        if (height <= 0) {
            return;
        }
        int padding = height / 2;
        recyclerView.setPadding(
                recyclerView.getPaddingLeft(),
                padding,
                recyclerView.getPaddingRight(),
                padding
        );
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
        if (recyclerView != null) {
            recyclerView.removeOnLayoutChangeListener(paddingLayoutListener);
        }
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
        updateLyrics(getString(R.string.hint_loading), "");
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
            hasTimestampedLyrics = LyricsUtils.hasTimestampedLyrics(lyrics);
            selectedTime = -1;
            pendingAutoScrollPosition = RecyclerView.NO_POSITION;
            clearTimelineOverlay();
            if (showTranslation && hasTimestampedLyrics) {
                lyricLines = LyricsUtils.mergeLyricsWithTranslation(lyrics, tlyrics);
            } else if (hasTimestampedLyrics) {
                lyricLines = LyricsUtils.parseLyrics(lyrics);
            } else {
                lyricLines = buildPlainLyricLines(lyrics, showTranslation ? tlyrics : "");
            }
            adapter.setShowTranslation(showTranslation && hasTimestampedLyrics && !tlyrics.trim().isEmpty());
            adapter.setLyrics(lyricLines);
            currentLineIndex = -1;
            if (!lyricLines.isEmpty()) {
                syncLyrics();
            }
        });
    }

    private List<LyricLine> buildPlainLyricLines(String lyrics, String translatedLyrics) {
        List<LyricLine> lines = new ArrayList<>();
        String normalizedLyrics = LyricsUtils.normalizePlainLyrics(lyrics);
        if (normalizedLyrics.isEmpty()) {
            return lines;
        }
        String[] originalLines = normalizedLyrics.split("\\n");
        String normalizedTranslatedLyrics = LyricsUtils.normalizePlainLyrics(translatedLyrics);
        String[] translatedLines = normalizedTranslatedLyrics.isEmpty()
                ? new String[0]
                : normalizedTranslatedLyrics.split("\\n");
        for (int i = 0; i < originalLines.length; i++) {
            String original = originalLines[i].trim();
            if (original.isEmpty()) {
                continue;
            }
            String translation = i < translatedLines.length ? translatedLines[i].trim() : "";
            lines.add(new LyricLine(i, original, translation));
        }
        return lines;
    }

    private void startUpdateTask() {
        stopUpdateTask();
        updateTask = new Runnable() {
            @Override
            public void run() {
                syncLyrics();
                handler.postDelayed(this, LYRIC_SYNC_INTERVAL_MS);
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

        if (!LyricsUtils.hasTimestampedLyrics(manager.getCurrentLyric())) {
            if (currentLineIndex != 0) {
                currentLineIndex = 0;
                adapter.setActiveIndex(currentLineIndex);
                if (!isUserScrolling) {
                    scrollToPosition(currentLineIndex);
                }
            }
            return;
        }

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
        if (position < 0 || position >= lyricLines.size()) return;

        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager == null) return;

        if (pendingAutoScrollPosition == position && recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            return;
        }
        if (pendingAutoScrollPosition != RecyclerView.NO_POSITION && recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            recyclerView.stopScroll();
        }
        pendingAutoScrollPosition = position;

        LinearSmoothScroller smoothScroller = new LinearSmoothScroller(recyclerView.getContext()) {
            @Override
            protected int getVerticalSnapPreference() {
                return LinearSmoothScroller.SNAP_TO_START;
            }

            @Override
            public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2);
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return LYRIC_SCROLL_MILLISECONDS_PER_INCH / displayMetrics.densityDpi;
            }

            @Override
            protected int calculateTimeForScrolling(int dx) {
                int distance = Math.abs(dx);
                if (distance == 0) {
                    return 0;
                }
                return Math.min(super.calculateTimeForScrolling(distance), LYRIC_SCROLL_MAX_DURATION_MS);
            }

            @Override
            protected int calculateTimeForDeceleration(int dx) {
                int distance = Math.abs(dx);
                if (distance == 0) {
                    return 0;
                }
                int duration = super.calculateTimeForDeceleration(distance);
                return Math.max(LYRIC_SCROLL_MIN_DURATION_MS, Math.min(duration, LYRIC_SCROLL_MAX_DURATION_MS));
            }

            @Override
            protected void onStop() {
                super.onStop();
                pendingAutoScrollPosition = RecyclerView.NO_POSITION;
            }
        };
        smoothScroller.setTargetPosition(position);
        layoutManager.startSmoothScroll(smoothScroller);
    }
}
