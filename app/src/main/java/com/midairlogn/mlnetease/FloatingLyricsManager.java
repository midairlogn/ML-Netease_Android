package com.midairlogn.mlnetease;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
    private TextView tvLyricsNext;
    private TextView tvSongTitle;
    private TextView tvSongArtist;
    private RelativeLayout layoutHeader;
    private LinearLayout layoutControls;
    private LinearLayout layoutSettings;
    private LinearLayout rootLayout;

    // Settings Views
    private View colorRed, colorBlue, colorGreen, colorYellow, colorPurple;
    private Button btnFontPlus, btnFontMinus;

    // State
    private boolean isExpanded = false;
    private boolean isSettingsExpanded = false;
    private boolean isLocked = false;
    private boolean isAppVisible = false;
    private List<LyricLine> currentLyrics;
    private int currentLyricIndex = -1;
    private int screenWidth, screenHeight;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable lyricUpdateTask;

    private final MusicPlayerManager.OnPlaybackStateChangedListener playbackStateListener = new MusicPlayerManager.OnPlaybackStateChangedListener() {
        @Override
        public void onPlaybackStateChanged(boolean isPlaying) {
            updatePlayButtonState(isPlaying);
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
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
    }

    private void initView() {
        if (floatingView != null) return;

        // Use a ContextThemeWrapper to ensure theme attributes can be resolved
        Context themeContext = new android.view.ContextThemeWrapper(context, R.style.Theme_MLNetease);
        floatingView = LayoutInflater.from(themeContext).inflate(R.layout.layout_floating_lyrics, null);

        rootLayout = floatingView.findViewById(R.id.floating_window_root);
        tvLyricsCurrent = floatingView.findViewById(R.id.tv_lyrics_current);
        tvLyricsNext = floatingView.findViewById(R.id.tv_lyrics_next);
        tvSongTitle = floatingView.findViewById(R.id.tv_floating_song_title);
        tvSongArtist = floatingView.findViewById(R.id.tv_floating_song_artist);
        layoutHeader = floatingView.findViewById(R.id.layout_header);
        layoutControls = floatingView.findViewById(R.id.layout_controls);
        layoutSettings = floatingView.findViewById(R.id.layout_settings);

        // Controls
        View iconView = floatingView.findViewById(R.id.iv_icon);
        ImageButton btnClose = floatingView.findViewById(R.id.btn_close);
        ImageButton btnLock = floatingView.findViewById(R.id.btn_lock);
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
            Intent intent = new Intent(context, PlayerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            // Optionally collapse the floating window or keep it as is
            // collapse();
        });

        btnClose.setOnClickListener(v -> {
            settingsManager.setFloatingLyricsEnabled(false);
            hide();
            // Notify service to update notification icon
            Intent intent = new Intent(context, MusicService.class);
            intent.setAction("ACTION_UPDATE_SETTINGS");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        });
        btnLock.setOnClickListener(v -> toggleLock(btnLock));
        btnPrev.setOnClickListener(v -> musicPlayerManager.playPrevious());
        btnPlay.setOnClickListener(v -> {
            if (musicPlayerManager.isPlaying()) {
                musicPlayerManager.pause();
                btnPlay.setImageResource(android.R.drawable.ic_media_play);
            } else {
                musicPlayerManager.resume();
                btnPlay.setImageResource(android.R.drawable.ic_media_pause);
            }
        });
        btnNext.setOnClickListener(v -> musicPlayerManager.playNext());
        btnSettings.setOnClickListener(v -> toggleSettings());

        // Color Listeners
        colorRed.setOnClickListener(v -> updateColor(Color.parseColor("#F44336")));
        colorBlue.setOnClickListener(v -> updateColor(Color.parseColor("#2196F3")));
        colorGreen.setOnClickListener(v -> updateColor(Color.parseColor("#4CAF50")));
        colorYellow.setOnClickListener(v -> updateColor(Color.parseColor("#FFEB3B")));
        colorPurple.setOnClickListener(v -> updateColor(Color.parseColor("#9C27B0")));

        // Font Listeners
        btnFontPlus.setOnClickListener(v -> updateFontSize(2f));
        btnFontMinus.setOnClickListener(v -> updateFontSize(-2f));

        // Initial play button state
        btnPlay.setImageResource(musicPlayerManager.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

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
                        if (isLocked) return true;

                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);

                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false;
                        }

                        if (!isClick) {
                            int newX = initialX + dx;
                            int newY = initialY + dy;

                            // Check boundaries
                            // We need to measure view size to keep it inside
                            int width = floatingView.getWidth();
                            int height = floatingView.getHeight();

                            // Ensure it doesn't go off screen
                            // Since gravity is TOP | LEFT, x/y is top-left corner
                            // But actually default gravity for window manager is often center or something if not specified.
                            // I set Gravity.TOP | Gravity.START in LayoutParams.

                            if (newX < 0) newX = 0;
                            if (newY < 0) newY = 0;
                            if (newX + width > screenWidth) newX = screenWidth - width;
                            if (newY + height > screenHeight) newY = screenHeight - height;

                            params.x = newX;
                            params.y = newY;
                            windowManager.updateViewLayout(floatingView, params);
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

        int width = (int) (screenWidth * 0.9f);
        params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = (screenWidth - width) / 2;
        // Default position: Bottom area (approx 80% down)
        params.y = (int) (screenHeight * 0.8f);

        // Start updates
        startLyricUpdates();
    }

    private void toggleLock(ImageButton btn) {
        isLocked = !isLocked;
        btn.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_lock_open);
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
            color = Color.parseColor("#4CAF50"); // Default green
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
    }

    public void show() {
        if (!settingsManager.isFloatingLyricsEnabled()) return;
        if (isAppVisible) return; // Do not show if app is visible
        if (!android.provider.Settings.canDrawOverlays(context)) return;
        if (floatingView != null && floatingView.getWindowToken() != null) {
            // Already showing, just update settings and return
            applySettings();
            return;
        }

        initView();
        try {
            windowManager.addView(floatingView, params);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Apply settings again to ensure fresh state
        applySettings();

        // Update lyrics immediately
        updateLyrics(musicPlayerManager.getCurrentLyric());
        updateSongInfo(musicPlayerManager.getCurrentSong());
    }

    public void hide() {
        if (floatingView != null && floatingView.getWindowToken() != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        stopLyricUpdates();
        floatingView = null; // Clean up
        isExpanded = false;
        isSettingsExpanded = false;
    }

    private void expand() {
        isExpanded = true;
        layoutHeader.setVisibility(View.VISIBLE);
        layoutControls.setVisibility(View.VISIBLE);

        // Background semi-transparent with rounded corners
        rootLayout.setBackgroundResource(R.drawable.bg_floating_window);

        // Check boundaries
        rootLayout.post(() -> checkBoundaries());
    }

    private void collapse() {
        isExpanded = false;
        isSettingsExpanded = false;
        layoutHeader.setVisibility(View.GONE);
        layoutControls.setVisibility(View.GONE);
        layoutSettings.setVisibility(View.GONE);

        // Background transparent
        rootLayout.setBackgroundColor(Color.TRANSPARENT);
    }

    private void toggleSettings() {
        if (!isExpanded) return;
        isSettingsExpanded = !isSettingsExpanded;
        layoutSettings.setVisibility(isSettingsExpanded ? View.VISIBLE : View.GONE);
        rootLayout.post(() -> checkBoundaries());
    }

    private void checkBoundaries() {
        if (floatingView == null) return;

        int width = floatingView.getWidth();
        int height = floatingView.getHeight();
        boolean changed = false;

        if (params.x + width > screenWidth) {
            params.x = screenWidth - width;
            changed = true;
        }
        if (params.y + height > screenHeight) {
            params.y = screenHeight - height;
            changed = true;
        }
        if (params.x < 0) { params.x = 0; changed = true; }
        if (params.y < 0) { params.y = 0; changed = true; }

        if (changed) {
            windowManager.updateViewLayout(floatingView, params);
        }
    }

    public void updateLyrics(String lyrics) {
        currentLyrics = LyricsUtils.parseLyrics(lyrics);
        currentLyricIndex = -1;
    }

    public void updateSongInfo(Song song) {
        if (song != null) {
            if (tvSongTitle != null) tvSongTitle.setText(song.name);
            if (tvSongArtist != null) tvSongArtist.setText(song.artists);
        }
    }

    private void startLyricUpdates() {
        if (lyricUpdateTask != null) return;
        lyricUpdateTask = new Runnable() {
            @Override
            public void run() {
                updateCurrentLyricLine();
                handler.postDelayed(this, 300);
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

    private void updateCurrentLyricLine() {
        if (currentLyrics == null || currentLyrics.isEmpty()) {
            if (tvLyricsCurrent != null) tvLyricsCurrent.setText("No Lyrics");
            if (tvLyricsNext != null) tvLyricsNext.setText("");
            return;
        }

        if (!musicPlayerManager.isPlaying()) return;

        int pos = musicPlayerManager.getCurrentPosition();
        int newIndex = -1;

        for (int i = 0; i < currentLyrics.size(); i++) {
            if (currentLyrics.get(i).time > pos) {
                break;
            }
            newIndex = i;
        }

        if (newIndex != -1 && newIndex != currentLyricIndex) {
            currentLyricIndex = newIndex;
            String text = currentLyrics.get(currentLyricIndex).text;
            if (tvLyricsCurrent != null) {
                tvLyricsCurrent.setText(text);

                // Apply color highlight logic
                // ...

                int highlightColor = settingsManager.getLyricColor();
                if (highlightColor == 0) highlightColor = Color.parseColor("#4CAF50"); // Default

                tvLyricsCurrent.setTextColor(highlightColor);
            }

            if (tvLyricsNext != null) {
                if (currentLyricIndex + 1 < currentLyrics.size()) {
                    tvLyricsNext.setText(currentLyrics.get(currentLyricIndex + 1).text);
                } else {
                    tvLyricsNext.setText("");
                }
            }
        }
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
        if (settingsManager.isFloatingLyricsEnabled()) {
            show();
        } else {
            hide();
        }
    }
}
