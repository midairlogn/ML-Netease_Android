package com.midairlogn.mlnetease.playback.ui;

import android.net.Uri;
import android.os.Bundle;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.midairlogn.mlnetease.playback.ui.pager.CoverFragment;
import com.midairlogn.mlnetease.download.core.PreparedAudioFile;
import com.midairlogn.mlnetease.download.core.RemoteAudioPreparationHelper;
import com.midairlogn.mlnetease.download.model.DownloadTaskSnapshot;
import com.midairlogn.mlnetease.hearing.HearingProtectionTransportController;
import com.midairlogn.mlnetease.playback.ui.pager.LyricsFragment;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.download.core.SongDownloadStarter;
import com.midairlogn.mlnetease.playback.core.MusicPlayerManager;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.Song;
import com.midairlogn.mlnetease.shared.ui.ShareUtils;
import com.midairlogn.mlnetease.shared.ui.SongShareHelper;
import com.midairlogn.mlnetease.shared.ui.UiLaunchGuards;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Locale;

public class PlayerActivity extends AppCompatActivity implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnPlaybackStateChangedListener, MusicPlayerManager.OnFullInfoAvailableListener, MusicPlayerManager.OnPlaybackModeChangedListener, MusicPlayerManager.OnSeekListener, MusicPlayerManager.OnProgressUpdateListener {

    private TextView songTitle, songArtist;
    private TextView currentTime, totalTime;
    private SeekBar seekBar;
    private ImageButton btnPlayPause, btnPrev, btnNext;
    private ImageButton btnMode, btnPlaylist, btnDownloadSong, btnFavouriteSong, btnShareSong;
    private ImageButton btnBack;
    private ViewPager2 viewPager;
    private MusicPlayerManager musicPlayerManager;
    private SettingsManager settingsManager;
    private SongShareHelper songShareHelper;
    private Toast currentToast;
    private boolean isTracking = false;
    private AlertDialog activeDialog;
    private AlertDialog shareProgressDialog;
    private final ExecutorService shareExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean shareInProgress = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        musicPlayerManager = MusicPlayerManager.getInstance(this);
        settingsManager = new SettingsManager(this);
        songShareHelper = new SongShareHelper(this);

        initViews();
        setupViewPager();
        setupControls();

        updateSongInfo(musicPlayerManager.getCurrentSong());
        updatePlaybackState(musicPlayerManager.isPlaying());
        updateModeIcon();

        musicPlayerManager.addOnSongChangedListener(this);
        musicPlayerManager.addOnFullInfoAvailableListener(this);
        musicPlayerManager.addOnPlaybackStateChangedListener(this);
        musicPlayerManager.addOnPlaybackModeChangedListener(this);
        musicPlayerManager.addOnSeekListener(this);


    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
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
        btnFavouriteSong = findViewById(R.id.btn_favourite_song);
        btnShareSong = findViewById(R.id.btn_share_song);
        btnDownloadSong = findViewById(R.id.btn_download_song);
        btnBack = findViewById(R.id.btn_back);
        viewPager = findViewById(R.id.view_pager);
    }

    private void setupViewPager() {
        viewPager.setAdapter(new PlayerPagerAdapter(this));
    }

    private void setupControls() {
        btnPlayPause.setOnClickListener(v -> HearingProtectionTransportController.handlePlayPause(this));
        btnPrev.setOnClickListener(v -> HearingProtectionTransportController.handlePrevious(this));
        btnNext.setOnClickListener(v -> HearingProtectionTransportController.handleNext(this));
        btnBack.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        btnMode.setOnClickListener(v -> {
            musicPlayerManager.togglePlaybackMode();
            int newMode = musicPlayerManager.getPlaybackMode();
            updateModeIcon();
            showModeToast(newMode);
        });

        btnPlaylist.setOnClickListener(v -> {
            PlaylistBottomSheetFragment bottomSheet = new PlaylistBottomSheetFragment();
            UiLaunchGuards.showDialogFragmentOnce(getSupportFragmentManager(), bottomSheet, "PlaylistBottomSheet");
        });

        btnDownloadSong.setOnClickListener(v -> {
            Song currentSong = musicPlayerManager.getCurrentSong();
            if (currentSong == null) {
                Toast.makeText(this, R.string.no_music, Toast.LENGTH_SHORT).show();
                return;
            }
            DownloadTaskSnapshot task = SongDownloadStarter.downloadCurrentSong(this, currentSong);
            if (task != null) {
                Toast.makeText(this, getString(R.string.download_added_named, task.title), Toast.LENGTH_SHORT).show();
            }
        });

        btnShareSong.setOnClickListener(v -> shareCurrentSong());

        btnFavouriteSong.setOnClickListener(v -> toggleFavourite());

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
                HearingProtectionTransportController.handleResume(PlayerActivity.this);
            }
        });
    }

    private void updateSongInfo(Song song) {
        if (song == null) {
            songTitle.setText(R.string.song_title);
            songArtist.setText(R.string.artist_name);
            btnDownloadSong.setVisibility(android.view.View.VISIBLE);
            btnShareSong.setVisibility(android.view.View.VISIBLE);
            renderFavouriteState(null);
            return;
        }

        songTitle.setText(song.name);
        songArtist.setText(song.artists);
        btnDownloadSong.setVisibility(song.isLocal() ? android.view.View.GONE : android.view.View.VISIBLE);
        btnShareSong.setVisibility(android.view.View.VISIBLE);
        renderFavouriteState(song);
    }

    private void shareCurrentSong() {
        Song currentSong = musicPlayerManager.getCurrentSong();
        if (currentSong == null) {
            Toast.makeText(this, R.string.no_music, Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentSong.isLocal()) {
            shareLocalSong(currentSong);
            return;
        }
        showRemoteShareDialog(currentSong);
    }

    private void shareLocalSong(Song song) {
        Uri uri = songShareHelper.shareLocalSong(song);
        if (uri == null) {
            Toast.makeText(this, R.string.share_not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        String mimeType = song.mimeType == null || song.mimeType.trim().isEmpty() ? "audio/*" : song.mimeType.trim();
        ShareUtils.shareAudio(this, getString(R.string.share_song), uri, mimeType);
    }

    private void showRemoteShareDialog(Song song) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.share_song_as)
                .setItems(new CharSequence[]{getString(R.string.share_link), getString(R.string.share_audio)}, (d, which) -> {
                    if (which == 0) {
                        ShareUtils.shareText(this, getString(R.string.share_song), ShareUtils.buildSongUrl(song.id));
                    } else {
                        shareRemoteSongAudio(song);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        if (UiLaunchGuards.showAlertDialogOnce(activeDialog, dialog)) {
            activeDialog = dialog;
            dialog.setOnDismissListener(ignored -> activeDialog = null);
        }
    }

    private void shareRemoteSongAudio(Song song) {
        if (!shareInProgress.compareAndSet(false, true)) {
            return;
        }
        showShareProgressDialog();
        shareExecutor.execute(() -> {
            try {
                PreparedAudioFile preparedAudioFile = songShareHelper.prepareRemoteAudioForSharing(
                        song,
                        () -> {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new InterruptedException();
                            }
                        },
                        new RemoteAudioPreparationHelper.ProgressListener() {
                            @Override
                            public void onFetchingMetadata(String title) {
                                updateShareProgressMessage(R.string.download_fetching_metadata);
                            }

                            @Override
                            public void onDownloadingAudio(String title, long downloadedBytes, long totalBytes) {
                                updateShareProgressMessage(R.string.download_audio_progress);
                            }

                            @Override
                            public void onFetchingCover(String title) {
                                updateShareProgressMessage(R.string.download_cover_progress);
                            }

                            @Override
                            public void onWritingMetadata(String title) {
                                updateShareProgressMessage(R.string.download_writing_tags);
                            }
                        }
                );
                Uri uri = songShareHelper.getSharableUri(preparedAudioFile.file);
                mainHandler.post(() -> {
                    dismissShareProgressDialog();
                    ShareUtils.shareAudio(this, getString(R.string.share_song), uri, preparedAudioFile.mimeType);
                    shareInProgress.set(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    dismissShareProgressDialog();
                    Toast.makeText(this, getString(R.string.share_audio_failed) + e.getMessage(), Toast.LENGTH_SHORT).show();
                    shareInProgress.set(false);
                });
            }
        });
    }

    private void showShareProgressDialog() {
        dismissShareProgressDialog();
        shareProgressDialog = new AlertDialog.Builder(this)
                .setMessage(R.string.share_audio_preparing)
                .setCancelable(false)
                .create();
        shareProgressDialog.show();
    }

    private void updateShareProgressMessage(int messageRes) {
        mainHandler.post(() -> {
            if (shareProgressDialog != null && shareProgressDialog.isShowing()) {
                shareProgressDialog.setMessage(getString(messageRes));
            }
        });
    }

    private void dismissShareProgressDialog() {
        if (shareProgressDialog != null) {
            shareProgressDialog.dismiss();
            shareProgressDialog = null;
        }
    }

    private void toggleFavourite() {
        Song currentSong = musicPlayerManager.getCurrentSong();
        if (currentSong == null) {
            Toast.makeText(this, R.string.no_music, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean changed;
        int messageRes;
        if (settingsManager.isFavouriteSong(currentSong)) {
            changed = settingsManager.removeFavouriteSong(currentSong);
            messageRes = R.string.favourite_removed;
        } else {
            changed = settingsManager.addFavouriteSong(currentSong);
            messageRes = R.string.favourite_added;
        }

        if (changed) {
            renderFavouriteState(currentSong);
            Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
        }
    }

    private void renderFavouriteState(Song song) {
        boolean isFavourite = song != null && settingsManager.isFavouriteSong(song);
        btnFavouriteSong.setSelected(isFavourite);
        btnFavouriteSong.setContentDescription(getString(isFavourite ? R.string.unfavourite : R.string.favourite));
    }

    private boolean lastIsPlaying = false;
    private void updatePlaybackState(boolean isPlaying) {
        if (isPlaying == lastIsPlaying && btnPlayPause.getDrawable() != null) return;
        lastIsPlaying = isPlaying;
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
                message = getString(R.string.playmode_single_loop);
                break;
            case MusicPlayerManager.MODE_LOOP_ALL:
                message = getString(R.string.playmode_loop_all);
                break;
            case MusicPlayerManager.MODE_SHUFFLE:
                message = getString(R.string.playmode_shuffle);
                break;
            case MusicPlayerManager.MODE_ORDER:
            default:
                message = getString(R.string.playmode_order);
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

    private void renderProgress(int current, int total) {
        if (total <= 0) {
            seekBar.setMax(0);
            seekBar.setProgress(0);
            currentTime.setText(R.string.zero_timestamp);
            totalTime.setText(R.string.null_timestamp);
            return;
        }

        int safeCurrent = Math.max(0, Math.min(current, total));
        seekBar.setMax(total);
        if (!isTracking) {
            seekBar.setProgress(safeCurrent);
            currentTime.setText(formatTime(safeCurrent));
        }
        totalTime.setText(formatTime(total));
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
        dismissShareProgressDialog();
        shareExecutor.shutdownNow();
        super.onDestroy();
        musicPlayerManager.removeOnSongChangedListener(this);
        musicPlayerManager.removeOnFullInfoAvailableListener(this);
        musicPlayerManager.removeOnPlaybackStateChangedListener(this);
        musicPlayerManager.removeOnPlaybackModeChangedListener(this);
        musicPlayerManager.removeOnSeekListener(this);
        musicPlayerManager.removeOnProgressUpdateListener(this);
    }

    @Override
    public void onSongChanged(Song song) {
        runOnUiThread(() -> {
            updateSongInfo(song);
            renderProgress(0, 0);
        });
    }

    @Override
    public void onFullInfoAvailable(Song song) {
        runOnUiThread(() -> updateSongInfo(song));
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> updatePlaybackState(isPlaying));
    }

    @Override
    public void onPlaybackModeChanged(int mode) {
        runOnUiThread(this::updateModeIcon);
    }

    @Override
    public void onProgressUpdate(int current, int total) {
        runOnUiThread(() -> renderProgress(current, total));
    }

    @Override
    public void onSeek(int msec) {
        runOnUiThread(() -> {
            if (!isTracking) {
                seekBar.setProgress(msec);
                currentTime.setText(formatTime(msec));
            }
        });
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
