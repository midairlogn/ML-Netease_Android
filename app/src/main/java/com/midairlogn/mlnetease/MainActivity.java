package com.midairlogn.mlnetease;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
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
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnPlaybackStateChangedListener, MusicPlayerManager.OnProgressUpdateListener, MusicPlayerManager.OnFullInfoAvailableListener {

    private static final int REQUEST_CODE_PERMISSIONS = 1001;
    private static final int REQUEST_CODE_OVERLAY = 1002;
    public static final String EXTRA_OPEN_TAB = "extra_open_tab";
    public static final String TAB_HOME = "home";
    public static final String TAB_LOCAL = "local";
    public static final String TAB_DOWNLOADS = "downloads";
    public static final String TAB_SETTINGS = "settings";

    private HomeFragment homeFragment;
    private LocalFragment localFragment;
    private DownloadsFragment downloadsFragment;
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
    private boolean pendingExternalAudioIntent = false;
    private AlertDialog activeDialog;

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
            activeFragment = homeFragment;
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, homeFragment, TAB_HOME)
                    .commit();
        } else {
            restoreFragments();
            activeFragment = resolveActiveFragment();
        }

        checkAndRequestPermissions();

        BottomNavigationView navView = findViewById(R.id.nav_view);
        syncNavigationSelection(navView);
        navView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_home) {
                switchToTab(TAB_HOME);
                return true;
            } else if (itemId == R.id.navigation_local) {
                switchToTab(TAB_LOCAL);
                return true;
            } else if (itemId == R.id.navigation_downloads) {
                switchToTab(TAB_DOWNLOADS);
                return true;
            } else if (itemId == R.id.navigation_settings) {
                switchToTab(TAB_SETTINGS);
                return true;
            }
            return false;
        });
        applyRequestedTab(getIntent(), navView, savedInstanceState == null);

        initMiniPlayer();
        if (pendingExternalAudioIntent) {
            handleIncomingAudioIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        BottomNavigationView navView = findViewById(R.id.nav_view);
        if (navView != null) {
            applyRequestedTab(intent, navView, false);
        }
        if (hasIncomingAudioIntent(intent)) {
            handleIncomingAudioIntent(intent);
        }
    }

    private void applyRequestedTab(Intent intent, BottomNavigationView navView, boolean firstCreate) {
        if (intent == null || navView == null) {
            return;
        }
        String targetTab = intent.getStringExtra(EXTRA_OPEN_TAB);
        if (TAB_DOWNLOADS.equals(targetTab)) {
            navView.setSelectedItemId(R.id.navigation_downloads);
        } else if (TAB_LOCAL.equals(targetTab)) {
            navView.setSelectedItemId(R.id.navigation_local);
        } else if (TAB_SETTINGS.equals(targetTab)) {
            navView.setSelectedItemId(R.id.navigation_settings);
        } else if (TAB_HOME.equals(targetTab) && !firstCreate) {
            navView.setSelectedItemId(R.id.navigation_home);
        }
        intent.removeExtra(EXTRA_OPEN_TAB);
        pendingExternalAudioIntent = hasIncomingAudioIntent(intent);
    }

    private void restoreFragments() {
        homeFragment = findFragmentByTag(TAB_HOME, HomeFragment.class);
        localFragment = findFragmentByTag(TAB_LOCAL, LocalFragment.class);
        downloadsFragment = findFragmentByTag(TAB_DOWNLOADS, DownloadsFragment.class);
        settingsFragment = findFragmentByTag(TAB_SETTINGS, SettingsFragment.class);
    }

    private <T extends Fragment> T findFragmentByTag(String tag, Class<T> fragmentClass) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if (fragmentClass.isInstance(fragment)) {
            return fragmentClass.cast(fragment);
        }
        return null;
    }

    private Fragment getOrCreateFragment(String tabTag) {
        switch (tabTag) {
            case TAB_LOCAL:
                if (localFragment == null) {
                    localFragment = findFragmentByTag(TAB_LOCAL, LocalFragment.class);
                }
                if (localFragment == null) {
                    localFragment = new LocalFragment();
                }
                return localFragment;
            case TAB_DOWNLOADS:
                if (downloadsFragment == null) {
                    downloadsFragment = findFragmentByTag(TAB_DOWNLOADS, DownloadsFragment.class);
                }
                if (downloadsFragment == null) {
                    downloadsFragment = new DownloadsFragment();
                }
                return downloadsFragment;
            case TAB_SETTINGS:
                if (settingsFragment == null) {
                    settingsFragment = findFragmentByTag(TAB_SETTINGS, SettingsFragment.class);
                }
                if (settingsFragment == null) {
                    settingsFragment = new SettingsFragment();
                }
                return settingsFragment;
            case TAB_HOME:
            default:
                if (homeFragment == null) {
                    homeFragment = findFragmentByTag(TAB_HOME, HomeFragment.class);
                }
                if (homeFragment == null) {
                    homeFragment = new HomeFragment();
                }
                return homeFragment;
        }
    }

    private void switchToTab(String tabTag) {
        Fragment target = getOrCreateFragment(tabTag);
        switchToFragment(target, tabTag);
    }

    private void switchToFragment(Fragment target, String tabTag) {
        if (target == null || activeFragment == target) {
            return;
        }

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (activeFragment != null) {
            transaction.hide(activeFragment);
        }
        if (target.isAdded()) {
            transaction.show(target);
        } else {
            transaction.add(R.id.fragment_container, target, tabTag);
        }
        transaction.commit();
        activeFragment = target;
    }

    private Fragment resolveActiveFragment() {
        if (homeFragment != null && homeFragment.isAdded() && !homeFragment.isHidden()) {
            return homeFragment;
        }
        if (localFragment != null && localFragment.isAdded() && !localFragment.isHidden()) {
            return localFragment;
        }
        if (downloadsFragment != null && downloadsFragment.isAdded() && !downloadsFragment.isHidden()) {
            return downloadsFragment;
        }
        if (settingsFragment != null && settingsFragment.isAdded() && !settingsFragment.isHidden()) {
            return settingsFragment;
        }
        return homeFragment != null ? homeFragment : settingsFragment;
    }

    private void syncNavigationSelection(BottomNavigationView navView) {
        if (navView == null || activeFragment == null) {
            return;
        }
        int itemId = R.id.navigation_home;
        if (activeFragment == localFragment) {
            itemId = R.id.navigation_local;
        } else if (activeFragment == downloadsFragment) {
            itemId = R.id.navigation_downloads;
        } else if (activeFragment == settingsFragment) {
            itemId = R.id.navigation_settings;
        }
        if (navView.getSelectedItemId() != itemId) {
            navView.setSelectedItemId(itemId);
        }
    }

    public void reloadHomeShortcuts() {
        HomeFragment fragment = homeFragment;
        if (fragment != null) {
            fragment.reloadShortcuts();
        }
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
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.permission_required)
                        .setMessage(getString(R.string.hint_overlay_permission))
                        .setPositiveButton(R.string.go_to_settings, (dialogInterface, which) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_CODE_OVERLAY);
                        })
                        .setNegativeButton(getString(R.string.cancel), (dialogInterface, which) -> startMusicService())
                        .setCancelable(false)
                        .create();
                showManagedDialog(dialog);
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
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.permissions_required)
                        .setMessage(R.string.hint_partial_permission)
                        .setPositiveButton(R.string.go_to_settings, (dialogInterface, which) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                            startMusicService();
                        })
                        .setNegativeButton(R.string.cancel, (dialogInterface, which) ->
                                new android.os.Handler(getMainLooper()).post(this::checkOverlayPermission))
                        .create();
                showManagedDialog(dialog);
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
                    AlertDialog dialog = new AlertDialog.Builder(this)
                            .setTitle(R.string.permission_required)
                            .setMessage(R.string.hint_overlay_permission)
                            .setPositiveButton(R.string.go_to_settings, (dialogInterface, which) -> {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
                                startActivityForResult(intent, REQUEST_CODE_OVERLAY);
                            })
                            .setNegativeButton(R.string.exit, (dialogInterface, which) -> finish())
                            .setCancelable(false)
                            .create();
                    showManagedDialog(dialog);
                }
            }
        }
    }

    private void showManagedDialog(@NonNull AlertDialog dialog) {
        if (!UiLaunchGuards.showAlertDialogOnce(activeDialog, dialog)) {
            return;
        }
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeDialog == dialog) {
                activeDialog = null;
            }
        });
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
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        });

        // The play/pause button area includes the progress bar
        View playPauseContainer = (View) miniPlayerPlayPause.getParent();
        playPauseContainer.setOnClickListener(v -> musicPlayerManager.togglePlayPause());

        miniPlayerPlaylist.setOnClickListener(v -> {
            PlaylistBottomSheetFragment bottomSheet = new PlaylistBottomSheetFragment();
            UiLaunchGuards.showDialogFragmentOnce(getSupportFragmentManager(), bottomSheet, "PlaylistBottomSheet");
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

        if (song.embeddedPicture != null && song.embeddedPicture.length > 0) {
            ImageManager.getInstance().loadEmbedded("embedded:" + song.id, song.embeddedPicture, miniPlayerThumb, R.drawable.ic_ml_app_logo_foreground, false);
        } else if (song.picUrl != null && !song.picUrl.isEmpty()) {
            // Check if current view is showing placeholder (logo)
            boolean isPlaceholder = miniPlayerThumb.getTag() == null || miniPlayerThumb.getTag().equals(R.drawable.ic_ml_app_logo_foreground);
            if (isPlaceholder || !ImageUtils.isSameImage(song.picUrl, (String) miniPlayerThumb.getTag())) {
                miniPlayerThumb.setTag(song.picUrl);
                ImageManager.getInstance().load(song.picUrl, miniPlayerThumb, R.drawable.ic_ml_app_logo_foreground);
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
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        activeDialog = null;
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
            if (miniPlayerProgress == null) {
                return;
            }
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
        if (AppCompatDelegate.getApplicationLocales().equals(locales)) {
            return;
        }
        AppCompatDelegate.setApplicationLocales(locales);
    }

    private boolean hasIncomingAudioIntent(Intent intent) {
        return intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null;
    }

    private void handleIncomingAudioIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        Uri data = intent.getData();
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            String type = intent.getType();
            if (type == null || type.startsWith("audio/")) {
                BottomNavigationView navView = findViewById(R.id.nav_view);
                if (navView != null) {
                    navView.setSelectedItemId(R.id.navigation_local);
                }
                Song song = LocalAudioSongFactory.create(this, data);
                if (song != null) {
                    MusicPlayerManager.getInstance(this).addOrPlaySong(song);
                } else {
                    Toast.makeText(this, R.string.local_audio_open_failed, Toast.LENGTH_SHORT).show();
                }
                pendingExternalAudioIntent = false;
                intent.setAction(null);
                intent.setData(null);
            }
        }
    }
}
