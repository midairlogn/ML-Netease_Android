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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricsFragment extends Fragment implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnPlaybackStateChangedListener {

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

        setupTimelineInteraction();

        MusicPlayerManager manager = MusicPlayerManager.getInstance(getContext());
        manager.addOnSongChangedListener(this);
        manager.addOnPlaybackStateChangedListener(this);

        updateLyrics(manager.getCurrentLyric());

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
                MusicPlayerManager.getInstance(getContext()).seekTo((int) selectedTime);
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
            // Check if center Y intersects with the content of the view (excluding large gaps if any)
            // Our items touch each other (24dp total padding).
            // Let's define "Between" as close to the top or bottom edge?
            // Actually, usually "Highlight" means we are mostly over the item.
            // Let's check if the center is within the view bounds.
            // Since items touch, it's ALWAYS within SOME view bounds.
            // The user request: "Only in 2 lyric boxes... show thin dashed line".
            // This implies there's a space where we are NOT on a lyric box.
            // But our layout has no margins.
            // Maybe we should simulate this by checking if we are near the boundary?
            // E.g. within 10% of top or bottom?

            int top = centerView.getTop();
            int bottom = centerView.getBottom();
            int height = bottom - top;
            int viewCenterY = (top + bottom) / 2;

            // Simple logic: If we are over a view, show highlight.
            // But to support "dashed line between", we need a "gap" logic.
            // Let's assume if we are within e.g. 8dp of the edge, show dashed line?
            // Or maybe just show highlight always if on view?
            // User said: "only in 2 lyric boxes... show thin dashed line".
            // This strongly suggests they want the dashed line ONLY when strictly between items.
            // With 0 margin, "between" is a single pixel line.
            // So we rarely see it.
            // To make it visible, we can treat the "padding area" as "between".
            // item_lyric has 12dp top and bottom padding.
            // So text is from top+12 to bottom-12.
            // If centerY is within [top, top+12] or [bottom-12, bottom], we are "between".

            // padding is in dp, need to convert or guess. 12dp is approx 30-40px.
            // Let's get padding from view if possible, or assume based on layout.
            // item_lyric.xml paddingVertical="12dp".
            // Let's just use a threshold, say 30px (approx 10-12dp).
            // Actually, we can check centerView.getPaddingTop().

            int paddingTop = centerView.getPaddingTop();
            int paddingBottom = centerView.getPaddingBottom();

            int relativeY = centerY - top; // Y position relative to view top

            boolean isOverText = relativeY >= paddingTop && relativeY <= (height - paddingBottom);

            if (isOverText) {
                // Show Highlight
                lyricsHighlightBg.setVisibility(View.VISIBLE);
                lyricsTimelineLine.setVisibility(View.INVISIBLE); // Hide dashed line

                // Adjust Highlight Height and Position
                // We want highlight to cover the text area?
                // Text area height = height - paddingVertical
                // Top margin of highlight = top + paddingTop?
                // But highlight is a sibling of RecyclerView (behind it).
                // We need to set its Y translation or layout params.
                // Since it's layout_gravity center_vertical, it stays in center.
                // But the ITEM is moving.
                // Wait, if the highlight is STATIONARY (center of screen),
                // then we are selecting the item that is currently PASSING through the center.
                // So the highlight doesn't move. The item moves.
                // So we just need to set the highlight height to match the text height of the item.
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
                // Keep time of the item we are "closest" to?
                // Or maybe just keep selectedTime as is.
                // If we are in top padding, we are closer to THIS item's text.
                // If in bottom padding, still THIS item.
                // Wait, if relativeY < paddingTop, we are above text -> "Between prev and this"
                // If relativeY > height - paddingBottom, we are below text -> "Between this and next"

                // Let's just update time based on the item we are technically "in",
                // even if we show dashed line.
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
        MusicPlayerManager.getInstance(getContext()).removeOnPlaybackStateChangedListener(this);
    }

    @Override
    public void onSongChanged(Song song) {
        updateLyrics(MusicPlayerManager.getInstance(getContext()).getCurrentLyric());
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        if (isPlaying) {
            startUpdateTask();
        } else {
            stopUpdateTask();
        }
    }

    private void updateLyrics(String lyrics) {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            lyricLines = LyricsUtils.parseLyrics(lyrics);
            adapter.setLyrics(lyricLines);
            currentLineIndex = -1;
            if (!lyricLines.isEmpty()) {
                syncLyrics();
            }
        });
    }

    // Removed parseLyrics method as it is now in LyricsUtils

    private void startUpdateTask() {
        stopUpdateTask();
        updateTask = new Runnable() {
            @Override
            public void run() {
                syncLyrics();
                handler.postDelayed(this, 300); // Check every 300ms
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
        if (isUserScrolling) return; // Do not auto-scroll if user is interacting

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
            adapter.setActiveIndex(currentLineIndex);
            scrollToPosition(currentLineIndex);
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
