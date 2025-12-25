package com.midairlogn.mlnetease;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnPlaybackStateChangedListener {

    private HomeFragment homeFragment;
    private SettingsFragment settingsFragment;
    private Fragment activeFragment;

    // Mini Player Views
    private View miniPlayerRoot;
    private ImageView miniPlayerThumb;
    private TextView miniPlayerTitle;
    private TextView miniPlayerArtist;
    private ProgressBar miniPlayerProgress;
    private ImageView miniPlayerPlayPause;
    private ImageButton miniPlayerPlaylist;
    private View miniPlayerDivider;

    private MusicPlayerManager musicPlayerManager;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private String currentCoverUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        musicPlayerManager = MusicPlayerManager.getInstance(this);

        homeFragment = new HomeFragment();
        settingsFragment = new SettingsFragment();
        activeFragment = homeFragment;

        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, settingsFragment, "settings")
                .hide(settingsFragment)
                .add(R.id.fragment_container, homeFragment, "home")
                .commit();

        // Start MusicService to handle background playback and media controls
        Intent serviceIntent = new Intent(this, MusicService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        BottomNavigationView navView = findViewById(R.id.nav_view);
        navView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_home) {
                getSupportFragmentManager().beginTransaction().hide(activeFragment).show(homeFragment).commit();
                activeFragment = homeFragment;
                return true;
            } else if (itemId == R.id.navigation_settings) {
                getSupportFragmentManager().beginTransaction().hide(activeFragment).show(settingsFragment).commit();
                activeFragment = settingsFragment;
                return true;
            }
            return false;
        });

        initMiniPlayer();
    }

    private void initMiniPlayer() {
        miniPlayerRoot = findViewById(R.id.mini_player_root); // The ConstraintLayout in include
        // Since we used <include id="mini_player">, the root view of the included layout (which has id mini_player_root)
        // might not be directly findable by "mini_player_root" if the include overrides the ID.
        // Actually, checking activity_main.xml: <include android:id="@+id/mini_player" ... />
        // The included layout's root view ID is overridden by the include ID if specified.
        // So the root view is findViewById(R.id.mini_player).
        miniPlayerRoot = findViewById(R.id.mini_player);

        miniPlayerThumb = findViewById(R.id.mini_player_thumb);
        miniPlayerTitle = findViewById(R.id.mini_player_title);
        miniPlayerArtist = findViewById(R.id.mini_player_artist);
        miniPlayerProgress = findViewById(R.id.mini_player_progress);
        miniPlayerPlayPause = findViewById(R.id.mini_player_play_pause);
        miniPlayerPlaylist = findViewById(R.id.mini_player_playlist);
        miniPlayerDivider = findViewById(R.id.mini_player_divider);

        miniPlayerRoot.setOnClickListener(v -> {
            Intent intent = new Intent(this, PlayerActivity.class);
            startActivity(intent);
        });

        // The play/pause button area includes the progress bar
        View playPauseContainer = (View) miniPlayerPlayPause.getParent();
        playPauseContainer.setOnClickListener(v -> musicPlayerManager.togglePlayPause());

        miniPlayerPlaylist.setOnClickListener(v -> {
            PlaylistBottomSheetFragment bottomSheet = new PlaylistBottomSheetFragment();
            bottomSheet.show(getSupportFragmentManager(), "PlaylistBottomSheet");
        });

        // Initial State
        updateMiniPlayer(musicPlayerManager.getCurrentSong());
        updatePlaybackState(musicPlayerManager.isPlaying());

        // Listeners
        musicPlayerManager.addOnSongChangedListener(this);
        musicPlayerManager.addOnPlaybackStateChangedListener(this);
    }

    private void updateMiniPlayer(Song song) {
        if (song == null) {
            miniPlayerRoot.setVisibility(View.GONE);
            stopProgressUpdater();
            return;
        }

        miniPlayerRoot.setVisibility(View.VISIBLE);
        miniPlayerTitle.setText(song.name);
        miniPlayerArtist.setText(song.artists);

        // Load Cover
        if (song.picUrl != null && !song.picUrl.equals(currentCoverUrl)) {
            currentCoverUrl = song.picUrl;
            new Thread(() -> {
                try {
                    URL url = new URL(currentCoverUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setDoInput(true);
                    connection.connect();
                    InputStream input = connection.getInputStream();
                    Bitmap bitmap = BitmapFactory.decodeStream(input);

                    runOnUiThread(() -> {
                        if (miniPlayerThumb != null) {
                            miniPlayerThumb.setImageBitmap(bitmap);
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        } else if (song.picUrl == null) {
            miniPlayerThumb.setImageResource(R.drawable.ic_music_note);
            currentCoverUrl = null;
        }

        startProgressUpdater();
    }

    private void updatePlaybackState(boolean isPlaying) {
        miniPlayerPlayPause.setImageResource(isPlaying ?
            android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

        if (isPlaying) {
            startProgressUpdater();
        } else {
            // We can keep updating if we want to show paused progress, but usually we can stop.
            // However, keeping it running ensures if something changes externally (rare) we catch it.
            // But better to save resources.
            // stopProgressUpdater(); // Actually, let's keep it running or restart it?
            // If paused, progress doesn't change.
        }
    }

    private void startProgressUpdater() {
        if (progressRunnable == null) {
            progressRunnable = new Runnable() {
                @Override
                public void run() {
                    if (musicPlayerManager.getCurrentSong() != null) {
                        int current = musicPlayerManager.getCurrentPosition();
                        int total = musicPlayerManager.getDuration();

                        if (total > 0) {
                            miniPlayerProgress.setMax(total);
                            miniPlayerProgress.setProgress(current);
                        }
                    }
                    if (musicPlayerManager.isPlaying()) {
                        handler.postDelayed(this, 500);
                    }
                }
            };
        }
        handler.removeCallbacks(progressRunnable);
        handler.post(progressRunnable);
    }

    private void stopProgressUpdater() {
        if (progressRunnable != null) {
            handler.removeCallbacks(progressRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        musicPlayerManager.removeOnSongChangedListener(this);
        musicPlayerManager.removeOnPlaybackStateChangedListener(this);
        stopProgressUpdater();
    }

    @Override
    public void onSongChanged(Song song) {
        runOnUiThread(() -> updateMiniPlayer(song));
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> updatePlaybackState(isPlaying));
    }
}
