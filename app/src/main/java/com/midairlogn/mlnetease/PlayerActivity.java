package com.midairlogn.mlnetease;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import java.util.Locale;

public class PlayerActivity extends AppCompatActivity implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnPlaybackStateChangedListener {

    private TextView songTitle, songArtist;
    private TextView currentTime, totalTime;
    private SeekBar seekBar;
    private ImageButton btnPlayPause, btnPrev, btnNext;
    private ImageButton btnMode, btnPlaylist;
    private ViewPager2 viewPager;
    private MusicPlayerManager musicPlayerManager;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isTracking = false;
    private Toast currentToast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        musicPlayerManager = MusicPlayerManager.getInstance(this);

        initViews();
        setupViewPager();
        setupControls();

        updateSongInfo(musicPlayerManager.getCurrentSong());
        updatePlaybackState(musicPlayerManager.isPlaying());
        updateModeIcon();

        musicPlayerManager.addOnSongChangedListener(this);
        musicPlayerManager.addOnPlaybackStateChangedListener(this);

        startProgressUpdater();
    }

    private void initViews() {
        songTitle = findViewById(R.id.player_song_title);
        songArtist = findViewById(R.id.player_song_artist);
        currentTime = findViewById(R.id.text_current_time);
        totalTime = findViewById(R.id.text_total_time);
        seekBar = findViewById(R.id.seek_bar);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        btnMode = findViewById(R.id.btn_mode);
        btnPlaylist = findViewById(R.id.btn_playlist);
        viewPager = findViewById(R.id.view_pager);
    }

    private void setupViewPager() {
        viewPager.setAdapter(new PlayerPagerAdapter(this));
    }

    private void setupControls() {
        btnPlayPause.setOnClickListener(v -> musicPlayerManager.togglePlayPause());
        btnPrev.setOnClickListener(v -> musicPlayerManager.playPrevious());
        btnNext.setOnClickListener(v -> musicPlayerManager.playNext());

        btnMode.setOnClickListener(v -> {
            int mode = musicPlayerManager.getPlaybackMode();
            int newMode = (mode + 1) % 4; // 4 modes
            musicPlayerManager.setPlaybackMode(newMode);
            updateModeIcon();
            showModeToast(newMode);
        });

        btnPlaylist.setOnClickListener(v -> {
            PlaylistBottomSheetFragment bottomSheet = new PlaylistBottomSheetFragment();
            bottomSheet.show(getSupportFragmentManager(), "PlaylistBottomSheet");
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTime.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isTracking = false;
                musicPlayerManager.seekTo(seekBar.getProgress());
            }
        });
    }

    private void updateSongInfo(Song song) {
        if (song != null) {
            songTitle.setText(song.name);
            songArtist.setText(song.artists);
        }
    }

    private void updatePlaybackState(boolean isPlaying) {
        btnPlayPause.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    private void updateModeIcon() {
        int mode = musicPlayerManager.getPlaybackMode();
        int iconRes;

        switch (mode) {
            case MusicPlayerManager.MODE_LOOP_ONE:
                iconRes = R.drawable.ic_mode_loop_one;
                break;
            case MusicPlayerManager.MODE_LOOP_ALL:
                iconRes = R.drawable.ic_mode_loop_all;
                break;
            case MusicPlayerManager.MODE_SHUFFLE:
                iconRes = R.drawable.ic_mode_shuffle;
                break;
            case MusicPlayerManager.MODE_ORDER:
            default:
                iconRes = R.drawable.ic_mode_order;
                break;
        }

        btnMode.setImageResource(iconRes);
    }

    private void showModeToast(int mode) {
        if (currentToast != null) {
            currentToast.cancel();
        }

        String message;
        switch (mode) {
            case MusicPlayerManager.MODE_LOOP_ONE:
                message = "Single Loop";
                break;
            case MusicPlayerManager.MODE_LOOP_ALL:
                message = "Loop All";
                break;
            case MusicPlayerManager.MODE_SHUFFLE:
                message = "Shuffle";
                break;
            case MusicPlayerManager.MODE_ORDER:
            default:
                message = "Order";
                break;
        }
        currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT);
        currentToast.show();
    }

    private String formatTime(int msec) {
        int seconds = msec / 1000;
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, secs);
    }

    private void startProgressUpdater() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isTracking && musicPlayerManager.isPlaying()) {
                    int current = musicPlayerManager.getCurrentPosition();
                    int total = musicPlayerManager.getDuration();

                    seekBar.setMax(total);
                    seekBar.setProgress(current);
                    currentTime.setText(formatTime(current));
                    totalTime.setText(formatTime(total));
                }
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        musicPlayerManager.removeOnSongChangedListener(this);
        musicPlayerManager.removeOnPlaybackStateChangedListener(this);
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onSongChanged(Song song) {
        runOnUiThread(() -> updateSongInfo(song));
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> updatePlaybackState(isPlaying));
    }

    private static class PlayerPagerAdapter extends FragmentStateAdapter {
        public PlayerPagerAdapter(FragmentActivity fa) {
            super(fa);
        }

        @Override
        public Fragment createFragment(int position) {
            if (position == 0) return new CoverFragment();
            return new LyricsFragment();
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }
}
