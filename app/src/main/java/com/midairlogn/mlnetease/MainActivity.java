package com.midairlogn.mlnetease;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.provider.Settings;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.Manifest;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.midairlogn.mlnetease.image.ImageManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnPlaybackStateChangedListener, MusicPlayerManager.OnProgressUpdateListener, MusicPlayerManager.OnFullInfoAvailableListener {

    private static final int REQUEST_CODE_PERMISSIONS = 1001;
    private static final int REQUEST_CODE_OVERLAY = 1002;

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
    private String currentCoverUrl;

    private SettingsManager settingsManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsManager = new SettingsManager(this);
        setAppLocale(settingsManager.getAppLanguage());
        setContentView(R.layout.activity_main);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        musicPlayerManager = MusicPlayerManager.getInstance(this);

        if (savedInstanceState == null) {
            homeFragment = new HomeFragment();
            settingsFragment = new SettingsFragment();
            activeFragment = homeFragment;

            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, settingsFragment, "settings")
                    .hide(settingsFragment)
                    .add(R.id.fragment_container, homeFragment, "home")
                    .commit();
        } else {
            homeFragment = (HomeFragment) getSupportFragmentManager().findFragmentByTag("home");
            settingsFragment = (SettingsFragment) getSupportFragmentManager().findFragmentByTag("settings");

            // Restore active fragment state
            int selectedItemId = ((BottomNavigationView) findViewById(R.id.nav_view)).getSelectedItemId();
            if (selectedItemId == R.id.navigation_home) {
                activeFragment = homeFragment;
            } else {
                activeFragment = settingsFragment;
            }
        }

        checkAndRequestPermissions();

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

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // Notification permission for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // Storage permissions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
        } else {
            checkOverlayPermission();
        }
    }

    private void checkOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.permission_required)
                        .setMessage(getString(R.string.hint_overlay_permission))
                        .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_CODE_OVERLAY);
                        })
                        .setNegativeButton(getString(R.string.cancel), (dialog, which) -> startMusicService())
                        .setCancelable(false)
                        .show();
            } else {
                startMusicService();
            }
        } else {
            startMusicService();
        }
    }

    private void startMusicService() {
        Intent serviceIntent = new Intent(this, MusicService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.permissions_required)
                        .setMessage(R.string.hint_all_permission)
                        .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                            finish(); // Exit app
                        })
                        .setNegativeButton(R.string.exit, (dialog, which) -> finish())
                        .setCancelable(false)
                        .show();
            } else {
                checkOverlayPermission();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OVERLAY) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startMusicService();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.permission_required)
                            .setMessage(R.string.hint_overlay_permission)
                            .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
                                startActivityForResult(intent, REQUEST_CODE_OVERLAY);
                            })
                            .setNegativeButton(R.string.exit, (dialog, which) -> finish())
                            .setCancelable(false)
                            .show();
                }
            }
        }
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
        musicPlayerManager.addOnFullInfoAvailableListener(this);
    }

    private void updateMiniPlayer(Song song) {
        if (song == null) {
            miniPlayerRoot.setVisibility(View.GONE);
            return;
        }

        miniPlayerRoot.setVisibility(View.VISIBLE);
        miniPlayerTitle.setText(song.name);
        miniPlayerArtist.setText(song.artists);

        // Load Cover
        if (song.picUrl != null) {
            if (!ImageUtils.isSameImage(song.picUrl, (String) miniPlayerThumb.getTag())) {
                miniPlayerThumb.setImageResource(R.drawable.ic_ml_app_logo_foreground);
                miniPlayerThumb.setTag(song.picUrl);
                ImageManager.getInstance().load(song.picUrl, miniPlayerThumb);
            }
        } else {
            miniPlayerThumb.setImageResource(R.drawable.ic_ml_app_logo_foreground);
            miniPlayerThumb.setTag(null);
        }
    }

    private boolean lastIsPlaying = false;
    private void updatePlaybackState(boolean isPlaying) {
        if (isPlaying == lastIsPlaying && miniPlayerPlayPause.getDrawable() != null) return;
        lastIsPlaying = isPlaying;
        miniPlayerPlayPause.setImageResource(isPlaying ?
            android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    @Override
    protected void onStart() {
        super.onStart();
        musicPlayerManager.addOnProgressUpdateListener(this);
        onProgressUpdate(musicPlayerManager.getCurrentPosition(), musicPlayerManager.getDuration());
    }

    @Override
    protected void onStop() {
        musicPlayerManager.removeOnProgressUpdateListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        musicPlayerManager.removeOnSongChangedListener(this);
        musicPlayerManager.removeOnPlaybackStateChangedListener(this);
        musicPlayerManager.removeOnFullInfoAvailableListener(this);
        musicPlayerManager.removeOnProgressUpdateListener(this);
    }

    @Override
    public void onSongChanged(Song song) {
        runOnUiThread(() -> updateMiniPlayer(song));
    }

    @Override
    public void onFullInfoAvailable(Song song) {
        runOnUiThread(() -> updateMiniPlayer(song));
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> updatePlaybackState(isPlaying));
    }

    @Override
    public void onProgressUpdate(int current, int total) {
        runOnUiThread(() -> {
            int safeTotal = Math.max(0, total);
            int safeCurrent = Math.max(0, Math.min(current, safeTotal));
            miniPlayerProgress.setMax(safeTotal);
            miniPlayerProgress.setProgress(safeCurrent);
        });
    }

    public void setAppLocale(String languageCode) {
        LocaleListCompat locales;
        if (languageCode.equals("system")) {
            locales = LocaleListCompat.getEmptyLocaleList();
        } else {
            locales = LocaleListCompat.forLanguageTags(languageCode);
        }
        AppCompatDelegate.setApplicationLocales(locales);
    }
}
