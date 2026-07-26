package com.midairlogn.mlnetease.playback.lyrics;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.RelativeLayout;

import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.hearing.HearingProtectionTransportController;
import com.midairlogn.mlnetease.playback.core.MusicPlayerManager;
import com.midairlogn.mlnetease.playback.core.MusicService;
import com.midairlogn.mlnetease.playback.ui.PlayerActivity;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.LyricLine;
import com.midairlogn.mlnetease.shared.model.Song;

import java.util.List;

public class FloatingLyricsManager {
    private Context context;
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private SettingsManager settingsManager;
    private MusicPlayerManager musicPlayerManager;

    // Views
    private TextView tvLyricsCurrent;
    private TextView tvLyricsCurrentTranslation;
    private TextView tvLyricsNext;
    private TextView tvLyricsNextTranslation;
    private TextView tvSongTitle;
    private TextView tvSongArtist;
    private RelativeLayout layoutHeader;
    private LinearLayout layoutControls;
    private LinearLayout layoutSettings;
    private LinearLayout rootLayout;

    // Settings Views
    private View colorRed, colorBlue, colorGreen, colorYellow, colorPurple;
    private Button btnFontPlus, btnFontMinus;
    private ImageButton btnTranslation;

    // State
    private boolean isExpanded = false;
    private boolean isSettingsExpanded = false;
    private boolean isAppVisible = false;
    private List<LyricLine> currentLyrics;
    private int currentLyricIndex = -1;
    private float currentTextWidth = -1f;
    private float currentTranslationWidth = -1f;
    private int screenWidth, screenHeight;
    private int portraitWidth; // Fixed width based on portrait mode

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable lyricUpdateTask;
    private Runnable autoCollapseTask = this::collapse;
    private static final long AUTO_COLLAPSE_DELAY = 5000;

    private final MusicPlayerManager.OnPlaybackStateChangedListener playbackStateListener = new MusicPlayerManager.OnPlaybackStateChangedListener() {
        @Override
        public void onPlaybackStateChanged(boolean isPlaying) {
            updatePlayButtonState(isPlaying);
            // Power optimization: only run the 50ms lyric polling loop while playing.
            // When paused, stop the loop and freeze the overlay on the current line.
            if (floatingView != null) {
                if (isPlaying) {
                    startLyricUpdates();
                } else {
                    stopLyricUpdates();
                    // One-shot refresh so the displayed line matches the paused position,
                    // and reset any in-progress text scroll so the frozen text is stable.
                    updateCurrentLyricLine();
                    if (tvLyricsCurrent != null) tvLyricsCurrent.setScrollX(0);
                    if (tvLyricsCurrentTranslation != null) tvLyricsCurrentTranslation.setScrollX(0);
                }
            }
        }
    };

    private final MusicPlayerManager.OnSeekListener seekListener = new MusicPlayerManager.OnSeekListener() {
        @Override
        public void onSeek(int msec) {
            if (floatingView != null) {
                updateCurrentLyricLine();
            }
        }
    };

    public FloatingLyricsManager(Context context) {
        this.context = context;
        this.settingsManager = new SettingsManager(context);
        this.musicPlayerManager = MusicPlayerManager.getInstance(context);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        updateScreenSize();

        // Register listener
        musicPlayerManager.addOnPlaybackStateChangedListener(playbackStateListener);
        musicPlayerManager.addOnSeekListener(seekListener);
    }

    public void setAppVisible(boolean visible) {
        this.isAppVisible = visible;
        if (isAppVisible) {
            hide();
        } else {
            if (settingsManager.isFloatingLyricsEnabled()) {
                show();
            }
        }
    }

    private void updateScreenSize() {
        DisplayMetrics metrics = new DisplayMetrics();
        // Use getRealMetrics to get full screen size including navigation bar area
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // Calculate portrait width (use the smaller dimension to ensure consistent width)
        int minDimension = Math.min(metrics.widthPixels, metrics.heightPixels);
        portraitWidth = (int) (minDimension * 0.9f);
    }

    private void initView() {
        if (floatingView != null) return;

        // Use a ContextThemeWrapper to ensure theme attributes can be resolved
        Context themeContext = new android.view.ContextThemeWrapper(context, R.style.Theme_MLNetease);
        floatingView = LayoutInflater.from(themeContext).inflate(R.layout.layout_floating_lyrics, null);

        rootLayout = floatingView.findViewById(R.id.floating_window_root);
        tvLyricsCurrent = floatingView.findViewById(R.id.tv_lyrics_current);
        tvLyricsCurrentTranslation = floatingView.findViewById(R.id.tv_lyrics_current_translation);
        // Removed setSelected(true) to handle custom scrolling manually
        tvLyricsNext = floatingView.findViewById(R.id.tv_lyrics_next);
        tvLyricsNextTranslation = floatingView.findViewById(R.id.tv_lyrics_next_translation);
        tvSongTitle = floatingView.findViewById(R.id.tv_floating_song_title);
        tvSongArtist = floatingView.findViewById(R.id.tv_floating_song_artist);
        layoutHeader = floatingView.findViewById(R.id.layout_header);
        layoutControls = floatingView.findViewById(R.id.layout_controls);
        layoutSettings = floatingView.findViewById(R.id.layout_settings);

        // Controls
        View iconView = floatingView.findViewById(R.id.iv_icon);
        ImageButton btnClose = floatingView.findViewById(R.id.btn_close);
        btnTranslation = floatingView.findViewById(R.id.btn_translation);
        ImageButton btnPrev = floatingView.findViewById(R.id.btn_prev);
        ImageButton btnPlay = floatingView.findViewById(R.id.btn_play);
        ImageButton btnNext = floatingView.findViewById(R.id.btn_next);
        ImageButton btnSettings = floatingView.findViewById(R.id.btn_settings);

        // Settings
        colorRed = floatingView.findViewById(R.id.color_red);
        colorBlue = floatingView.findViewById(R.id.color_blue);
        colorGreen = floatingView.findViewById(R.id.color_green);
        colorYellow = floatingView.findViewById(R.id.color_yellow);
        colorPurple = floatingView.findViewById(R.id.color_purple);
        btnFontPlus = floatingView.findViewById(R.id.btn_font_plus);
        btnFontMinus = floatingView.findViewById(R.id.btn_font_minus);

        // Apply saved settings
        applySettings();

        // Listeners
        iconView.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            Intent intent = new Intent(context, PlayerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            // Optionally collapse the floating window or keep it as is
            // collapse();
        });

        btnClose.setOnClickListener(v -> {
            settingsManager.setFloatingLyricsEnabled(false);
            hide();
            // Notify service to update notification icon
            Intent intent = new Intent(context, MusicService.class);
            intent.setAction(MusicService.ACTION_UPDATE_SETTINGS);
            intent.putExtra(MusicService.EXTRA_SETTINGS_UPDATE_MASK, MusicService.SETTINGS_UPDATE_FLOATING_LYRICS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        });
        btnTranslation.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            boolean newState = !settingsManager.isTranslationIntegrationEnabled();
            settingsManager.setTranslationIntegrationEnabled(newState);
            updateTranslationButtonState();
            onSettingChanged();
            Intent intent = new Intent(context, MusicService.class);
            intent.setAction(MusicService.ACTION_UPDATE_SETTINGS);
            intent.putExtra(MusicService.EXTRA_SETTINGS_UPDATE_MASK, MusicService.SETTINGS_UPDATE_TRANSLATION_INTEGRATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        });
        btnPrev.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            HearingProtectionTransportController.handlePrevious(context);
        });
        btnPlay.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            HearingProtectionTransportController.handlePlayPause(context);
        });
        btnNext.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            HearingProtectionTransportController.handleNext(context);
        });
        btnSettings.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            toggleSettings();
        });

        // Color Listeners
        colorRed.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            updateColor(context.getResources().getColor(R.color.lyrics_color_red, null));
        });
        colorBlue.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            updateColor(context.getResources().getColor(R.color.lyrics_color_blue, null));
        });
        colorGreen.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            updateColor(context.getResources().getColor(R.color.lyrics_color_green, null));
        });
        colorYellow.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            updateColor(context.getResources().getColor(R.color.lyrics_color_yellow, null));
        });
        colorPurple.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            updateColor(context.getResources().getColor(R.color.lyrics_color_purple, null));
        });

        // Font Listeners
        btnFontPlus.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            updateFontSize(2f);
        });
        btnFontMinus.setOnClickListener(v -> {
            resetAutoCollapseTimer();
            updateFontSize(-2f);
        });

        // Initial play button state
        btnPlay.setImageResource(musicPlayerManager.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        updateTranslationButtonState();

        // Drag & Touch Listener
        rootLayout.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isClick = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_OUTSIDE:
                        if (isExpanded) {
                            collapse();
                        }
                        return true;

                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true; // Consume event

                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                             if (!isExpanded) {
                                 expand();
                             }
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);

                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false;
                            resetAutoCollapseTimer();
                        }

                        if (!isClick) {
                            int newX = initialX + dx;
                            int newY = initialY + dy;

                            // Check boundaries
                            // We need to measure view size to keep it inside
                            int width = floatingView.getWidth();
                            int height = floatingView.getHeight();

                            // Dynamically get current screen size to handle orientation changes
                            updateScreenSize();

                            // Ensure it doesn't go off screen
                            if (newX < 0) newX = 0;
                            if (newY < 0) newY = 0;
                            if (newX + width > screenWidth) newX = screenWidth - width;
                            if (newY + height > screenHeight) newY = screenHeight - height;

                            params.x = newX;
                            params.y = newY;
                            if (floatingView.getWindowToken() != null) {
                                try {
                                    windowManager.updateViewLayout(floatingView, params);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        // Layout Params
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        // Use portraitWidth to ensure consistent width regardless of orientation
        params = new WindowManager.LayoutParams(
                portraitWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = (screenWidth - portraitWidth) / 2;
        // Default position: Bottom area (approx 80% down)
        params.y = (int) (screenHeight * 0.8f);

    }

    private void updateTranslationButtonState() {
        if (btnTranslation == null) return;
        boolean isTranslationEnabled = settingsManager.isTranslationIntegrationEnabled();
        btnTranslation.setAlpha(isTranslationEnabled ? 1.0f : 0.4f);
    }

    private void updateColor(int color) {
        settingsManager.setLyricColor(color);
        applySettings();
    }

    private void updateFontSize(float delta) {
        float currentSize = settingsManager.getLyricSize();
        float newSize = Math.max(10f, Math.min(30f, currentSize + delta));
        settingsManager.setLyricSize(newSize);
        applySettings();
    }

    private void applySettings() {
        int color = settingsManager.getLyricColor();
        if (color == 0) {
            color = context.getResources().getColor(R.color.lyrics_color_blue, null); // Default blue
        }

        // Font Size
        float size = settingsManager.getLyricSize();
        if (tvLyricsCurrent != null) {
            tvLyricsCurrent.setTextSize(size);
            tvLyricsCurrent.setTextColor(color);
        }
        if (tvLyricsNext != null) {
            tvLyricsNext.setTextSize(Math.max(10f, size - 2f));
        }
        if (tvLyricsCurrentTranslation != null) {
            tvLyricsCurrentTranslation.setTextSize(Math.max(9f, size - 4f));
            tvLyricsCurrentTranslation.setTextColor(color);
        }
        if (tvLyricsNextTranslation != null) {
            tvLyricsNextTranslation.setTextSize(Math.max(9f, size - 5f));
            tvLyricsNextTranslation.setTextColor(context.getResources().getColor(R.color.dim_translation_color, null));
        }
    }

    public void show() {
        if (!settingsManager.isFloatingLyricsEnabled()) return;
        if (isAppVisible) return; // Do not show if app is visible
        if (!android.provider.Settings.canDrawOverlays(context)) return;
        if (floatingView != null && floatingView.getWindowToken() != null) {
            // Already showing, just update settings and return
            applySettings();
            updateCurrentLyricLine();
            startLyricUpdates();
            return;
        }

        initView();
        try {
            windowManager.addView(floatingView, params);
        } catch (Exception e) {
            e.printStackTrace();
            hide();
            return;
        }

        // Apply settings again to ensure fresh state
        applySettings();

        // Update lyrics immediately
        updateLyrics(musicPlayerManager.getCurrentLyric(), musicPlayerManager.getCurrentTLyric());
        updateSongInfo(musicPlayerManager.getCurrentSong());
        startLyricUpdates();
    }

    public void hide() {
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        stopLyricUpdates();
        handler.removeCallbacks(autoCollapseTask);
        clearViewReferences();
        currentLyrics = null;
        isExpanded = false;
        isSettingsExpanded = false;
    }

    private void clearViewReferences() {
        floatingView = null;
        rootLayout = null;
        tvLyricsCurrent = null;
        tvLyricsCurrentTranslation = null;
        tvLyricsNext = null;
        tvLyricsNextTranslation = null;
        tvSongTitle = null;
        tvSongArtist = null;
        layoutHeader = null;
        layoutControls = null;
        layoutSettings = null;
        colorRed = null;
        colorBlue = null;
        colorGreen = null;
        colorYellow = null;
        colorPurple = null;
        btnFontPlus = null;
        btnFontMinus = null;
        btnTranslation = null;
    }

    private void expand() {
        isExpanded = true;
        layoutHeader.setVisibility(View.VISIBLE);
        layoutControls.setVisibility(View.VISIBLE);

        // Background semi-transparent with rounded corners
        rootLayout.setBackgroundResource(R.drawable.bg_floating_window);

        // Check boundaries
        rootLayout.post(() -> checkBoundaries());

        // Start auto-collapse timer
        resetAutoCollapseTimer();
    }

    private void collapse() {
        isExpanded = false;
        isSettingsExpanded = false;
        layoutHeader.setVisibility(View.GONE);
        layoutControls.setVisibility(View.GONE);
        layoutSettings.setVisibility(View.GONE);

        // Background transparent
        rootLayout.setBackgroundColor(Color.TRANSPARENT);

        // Cancel auto-collapse timer
        handler.removeCallbacks(autoCollapseTask);
    }

    private void toggleSettings() {
        if (!isExpanded) return;
        isSettingsExpanded = !isSettingsExpanded;
        layoutSettings.setVisibility(isSettingsExpanded ? View.VISIBLE : View.GONE);
        rootLayout.post(() -> checkBoundaries());
    }

    private void checkBoundaries() {
        if (floatingView == null || floatingView.getWindowToken() == null) return;

        // Update screen size in case of orientation change
        updateScreenSize();

        // Update params width to ensure it matches the current portraitWidth calculation
        if (params.width != portraitWidth) {
            params.width = portraitWidth;
        }

        int width = floatingView.getWidth();
        int height = floatingView.getHeight();

        // If width or height is 0, the view hasn't been laid out yet.
        // Post again to check when it's ready.
        if (width == 0 || height == 0) {
            rootLayout.postDelayed(this::checkBoundaries, 50);
            return;
        }

        boolean changed = false;

        if (params.x + width > screenWidth) {
            params.x = Math.max(0, screenWidth - width);
            changed = true;
        }
        if (params.y + height > screenHeight) {
            params.y = Math.max(0, screenHeight - height);
            changed = true;
        }
        if (params.x < 0) { params.x = 0; changed = true; }
        if (params.y < 0) { params.y = 0; changed = true; }

        if (changed || params.width != portraitWidth) {
            try {
                windowManager.updateViewLayout(floatingView, params);
            } catch (Exception e) {
                // Ignore errors if the view is already detached or something similar
                e.printStackTrace();
            }
        }
    }

    public void onConfigurationChanged() {
        if (floatingView != null && floatingView.getWindowToken() != null) {
            updateScreenSize();
            checkBoundaries();
        }
    }

    public void updateLyrics(String lyrics, String tlyrics) {
        if (settingsManager.isTranslationIntegrationEnabled()) {
            currentLyrics = LyricsUtils.mergeLyricsWithTranslation(lyrics, tlyrics);
        } else {
            currentLyrics = LyricsUtils.parseLyrics(lyrics);
        }

        // Add song title and artist at 00:00.000 if not present
        Song song = musicPlayerManager.getCurrentSong();
        if (song != null) {
            boolean hasStartLyric = false;
            if (!currentLyrics.isEmpty()) {
                LyricLine first = currentLyrics.get(0);
                if (first.time == 0) {
                    hasStartLyric = true;
                }
            }

            if (!hasStartLyric) {
                String text = song.name + "  " + song.artists;
                // Add to the beginning of the list
                currentLyrics.add(0, new LyricLine(0, text));
            }
        }

        currentLyricIndex = -1;
        if (floatingView != null) {
            updateCurrentLyricLine();
        }
    }

    public void onSongChanged(Song song) {
        currentLyrics = null;
        currentLyricIndex = -1;
        updateSongInfo(song);
        showLyricMessage(song == null ? R.string.no_music : R.string.hint_loading);
    }

    public void updateSongInfo(Song song) {
        if (tvSongTitle != null) {
            tvSongTitle.setText(song == null ? context.getString(R.string.no_music) : song.name);
        }
        if (tvSongArtist != null) {
            tvSongArtist.setText(song == null ? "" : song.artists);
        }
    }

    private void startLyricUpdates() {
        if (lyricUpdateTask != null) return;
        // Power optimization: do not run the 50ms polling loop while paused.
        // Still paint the current line once so the overlay reflects the latest state.
        if (!musicPlayerManager.isPlaying()) {
            updateCurrentLyricLine();
            return;
        }
        lyricUpdateTask = new Runnable() {
            @Override
            public void run() {
                // Defensive: if playback was paused between scheduled ticks, stop here.
                if (!musicPlayerManager.isPlaying()) {
                    lyricUpdateTask = null;
                    return;
                }
                updateCurrentLyricLine();
                handler.postDelayed(this, 50); // Update frequently for smooth scrolling
            }
        };
        handler.post(lyricUpdateTask);
    }

    private void stopLyricUpdates() {
        if (lyricUpdateTask != null) {
            handler.removeCallbacks(lyricUpdateTask);
            lyricUpdateTask = null;
        }
    }

    private void resetAutoCollapseTimer() {
        handler.removeCallbacks(autoCollapseTask);
        if (isExpanded) {
            handler.postDelayed(autoCollapseTask, AUTO_COLLAPSE_DELAY);
        }
    }

    private void updateCurrentLyricLine() {
        if (musicPlayerManager.getCurrentSong() == null) {
            showLyricMessage(R.string.no_music);
            return;
        }

        if (currentLyrics == null || currentLyrics.isEmpty()) {
            showLyricMessage(R.string.no_lyrics);
            return;
        }

        int pos = musicPlayerManager.getCurrentPosition();
        int newIndex = -1;

        // Optimization: start searching from current index if valid
        int searchStartIndex = (currentLyricIndex != -1 && currentLyricIndex < currentLyrics.size()
                && currentLyrics.get(currentLyricIndex).time <= pos) ? currentLyricIndex : 0;

        for (int i = searchStartIndex; i < currentLyrics.size(); i++) {
            if (currentLyrics.get(i).time > pos) {
                break;
            }
            newIndex = i;
        }

        if (newIndex != -1) {
            // Update text if index changed
            if (newIndex != currentLyricIndex) {
                currentLyricIndex = newIndex;
                LyricLine currentLine = currentLyrics.get(currentLyricIndex);
                String text = currentLine.text;
                boolean showTranslation = settingsManager.isTranslationIntegrationEnabled();
                int highlightColor = settingsManager.getLyricColor();
                if (highlightColor == 0) highlightColor = context.getResources().getColor(R.color.lyrics_color_blue, null); // Default
                int dimTranslationColor = context.getResources().getColor(R.color.dim_translation_color, null);

                if (tvLyricsCurrent != null) {
                    tvLyricsCurrent.setText(text);
                    tvLyricsCurrent.setTextColor(highlightColor);
                    tvLyricsCurrent.setScrollX(0);
                    currentTextWidth = tvLyricsCurrent.getPaint().measureText(text);
                }

                if (tvLyricsCurrentTranslation != null) {
                    if (showTranslation && !TextUtils.isEmpty(currentLine.translation)) {
                        tvLyricsCurrentTranslation.setText(currentLine.translation);
                        tvLyricsCurrentTranslation.setTextColor(highlightColor);
                        tvLyricsCurrentTranslation.setVisibility(View.VISIBLE);
                        currentTranslationWidth = tvLyricsCurrentTranslation.getPaint().measureText(currentLine.translation);
                    } else {
                        tvLyricsCurrentTranslation.setText("");
                        tvLyricsCurrentTranslation.setVisibility(View.GONE);
                        currentTranslationWidth = 0;
                    }
                    tvLyricsCurrentTranslation.setScrollX(0);
                } else {
                    currentTranslationWidth = 0;
                }

                if (tvLyricsNext != null) {
                    if (currentLyricIndex + 1 < currentLyrics.size()) {
                        tvLyricsNext.setText(currentLyrics.get(currentLyricIndex + 1).text);
                    } else {
                        tvLyricsNext.setText("");
                    }
                }

                if (tvLyricsNextTranslation != null) {
                    tvLyricsNextTranslation.setTextColor(dimTranslationColor);
                    if (currentLyricIndex + 1 < currentLyrics.size()) {
                        LyricLine nextLine = currentLyrics.get(currentLyricIndex + 1);
                        if (showTranslation && !TextUtils.isEmpty(nextLine.translation)) {
                            tvLyricsNextTranslation.setText(nextLine.translation);
                            tvLyricsNextTranslation.setVisibility(View.VISIBLE);
                        } else {
                            tvLyricsNextTranslation.setText("");
                            tvLyricsNextTranslation.setVisibility(View.GONE);
                        }
                    } else {
                        tvLyricsNextTranslation.setText("");
                        tvLyricsNextTranslation.setVisibility(View.GONE);
                    }
                }
            }

            // Calculate scrolling
            long posInMs = (long) pos;
            long startTime = currentLyrics.get(currentLyricIndex).time;
            long endTime = (currentLyricIndex + 1 < currentLyrics.size()) ?
                    currentLyrics.get(currentLyricIndex + 1).time :
                    startTime + 5000; // Default 5s for last line
            long duration = endTime - startTime;

            if (tvLyricsCurrent != null) {
                tvLyricsCurrent.setScrollX(calculateScrollX(tvLyricsCurrent, currentTextWidth, posInMs, startTime, duration));
            }
            if (tvLyricsCurrentTranslation != null && tvLyricsCurrentTranslation.getVisibility() == View.VISIBLE) {
                tvLyricsCurrentTranslation.setScrollX(calculateScrollX(tvLyricsCurrentTranslation, currentTranslationWidth, posInMs, startTime, duration));
            }
        }
    }

    private void showLyricMessage(int messageResId) {
        currentTextWidth = 0f;
        currentTranslationWidth = 0f;
        if (tvLyricsCurrent != null) {
            tvLyricsCurrent.setText(messageResId);
            tvLyricsCurrent.setScrollX(0);
        }
        if (tvLyricsCurrentTranslation != null) {
            tvLyricsCurrentTranslation.setText("");
            tvLyricsCurrentTranslation.setVisibility(View.GONE);
            tvLyricsCurrentTranslation.setScrollX(0);
        }
        if (tvLyricsNext != null) {
            tvLyricsNext.setText("");
        }
        if (tvLyricsNextTranslation != null) {
            tvLyricsNextTranslation.setText("");
            tvLyricsNextTranslation.setVisibility(View.GONE);
        }
    }

    private int calculateScrollX(TextView textView, float textWidth, long pos, long startTime, long duration) {
        if (duration <= 0) return 0;

        // Use pre-measured text width to avoid frequent allocations in the loop
        int viewWidth = textView.getWidth();
        int contentWidth = 0;
        float maxScroll = 0;

        if (viewWidth > 0) {
            contentWidth = viewWidth - textView.getPaddingLeft() - textView.getPaddingRight();
            maxScroll = Math.max(0, textWidth - contentWidth);
        }

        if (maxScroll <= 0) return 0;

        long defaultDelay = 1000; // 1 second
        long minDelay = 200;      // 0.2 second

        // Calculate comfortable scrolling speed
        // Assume a fast but readable speed: 0.4 px/ms (400 px/s)
        float speedThreshold = 0.4f;

        // Minimum time required to scroll the full distance at threshold speed
        long minScrollTime = (long) (maxScroll / speedThreshold);

        // Remaining time for delays
        long availableDelay = duration - minScrollTime;

        long startDelay, endDelay;

        if (availableDelay >= defaultDelay * 2) {
            // Plenty of time, use default delays
            startDelay = defaultDelay;
            endDelay = defaultDelay;
        } else if (availableDelay > minDelay * 2) {
            // Moderate time, split remaining time
            startDelay = availableDelay / 2;
            endDelay = availableDelay / 2;
        } else {
            // Tight time, use minimum delays and force faster scrolling
            startDelay = Math.min(minDelay, duration / 4);
            endDelay = startDelay;
        }

        long scrollDuration = duration - startDelay - endDelay;
        if (scrollDuration <= 0) scrollDuration = 1; // Prevent divide by zero

        long currentPosWithinLine = pos - startTime;
        float progress = 0f;

        if (currentPosWithinLine <= startDelay) {
            progress = 0f;
        } else if (currentPosWithinLine >= (duration - endDelay)) {
            progress = 1f;
        } else {
            progress = (float) (currentPosWithinLine - startDelay) / scrollDuration;
        }

        if (progress < 0) progress = 0;
        if (progress > 1) progress = 1;

        return (int) (maxScroll * progress);
    }

    private void updatePlayButtonState(boolean isPlaying) {
        if (floatingView != null) {
            ImageButton btnPlay = floatingView.findViewById(R.id.btn_play);
            if (btnPlay != null) {
                btnPlay.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            }
        }
    }

    // Call this when user toggles the feature in settings
    public void onSettingChanged() {
        applySettings(); // Update colors/sizes if window is already showing
        updateTranslationButtonState();
        updateLyrics(musicPlayerManager.getCurrentLyric(), musicPlayerManager.getCurrentTLyric());
        if (settingsManager.isFloatingLyricsEnabled()) {
            show();
        } else {
            hide();
        }
    }

    public void release() {
        hide();
        musicPlayerManager.removeOnPlaybackStateChangedListener(playbackStateListener);
        musicPlayerManager.removeOnSeekListener(seekListener);
    }
}
