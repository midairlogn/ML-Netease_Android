package com.midairlogn.mlnetease;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.concurrent.TimeUnit;

class HearingProtectionController implements
        MusicPlayerManager.OnPlaybackStateChangedListener,
        MusicPlayerManager.OnSongCompletionListener,
        MusicPlayerManager.OnPlaybackActionListener {

    private static final long SHORT_PAUSE_GRACE_MS = 30_000L;
    private static final long SOCIAL_PAUSE_MAX_MS = 5 * 60_000L;
    private static final long RESTORATIVE_PAUSE_MAX_MS = 20 * 60_000L;
    private static final double SOCIAL_RECOVERY_RATIO = 0.5d;
    private static final double MAX_DOSE_RESET_RATIO = 0.05d;
    private static final double BASE_EXPONENTIAL_RECOVERY_PER_MIN = 0.32d;

    private final Context appContext;
    private final MusicPlayerManager musicPlayerManager;
    private final SettingsManager settingsManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private double accumulatedDose;
    private long playbackSessionStartElapsedMs = -1L;
    private long pauseStartedElapsedMs = -1L;
    private double lastPlaybackIntensityMultiplier = 1.0d;
    private boolean restPendingAfterCurrentSong;
    private boolean restActive;
    private long restEndElapsedMs;

    private final Runnable restFinishedRunnable = this::completeRestAndResume;

    HearingProtectionController(Context context, MusicPlayerManager musicPlayerManager) {
        this.appContext = context.getApplicationContext();
        this.musicPlayerManager = musicPlayerManager;
        this.settingsManager = new SettingsManager(appContext);
    }

    void start() {
        musicPlayerManager.addOnPlaybackStateChangedListener(this);
        musicPlayerManager.addOnSongCompletionListener(this);
        musicPlayerManager.addOnPlaybackActionListener(this);
        restoreRestStateIfNeeded();
        if (musicPlayerManager.isPlaying()) {
            playbackSessionStartElapsedMs = SystemClock.elapsedRealtime();
            lastPlaybackIntensityMultiplier = getPlaybackIntensityMultiplier();
        }
    }

    void stop() {
        handler.removeCallbacks(restFinishedRunnable);
        musicPlayerManager.removeOnPlaybackStateChangedListener(this);
        musicPlayerManager.removeOnSongCompletionListener(this);
        musicPlayerManager.removeOnPlaybackActionListener(this);
        foldCurrentPlaybackIntoDose();
        playbackSessionStartElapsedMs = -1L;
        pauseStartedElapsedMs = -1L;
    }

    void onSettingsChanged() {
        if (!settingsManager.isHearingProtectionEnabled()) {
            boolean wasRestActive = restActive;
            cancelRest(false);
            accumulatedDose = 0d;
            restPendingAfterCurrentSong = false;
            if (wasRestActive) {
                musicPlayerManager.playNext();
            }
            return;
        }

        if (restActive) {
            long remainingMs = restEndElapsedMs - SystemClock.elapsedRealtime();
            if (remainingMs <= 0L) {
                completeRestAndResume();
            } else {
                scheduleRestFinished(remainingMs);
            }
        }

        if (!restPendingAfterCurrentSong && accumulatedDose >= getDoseThreshold()) {
            restPendingAfterCurrentSong = true;
        }
    }

    boolean isRestActive() {
        return restActive;
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        long now = SystemClock.elapsedRealtime();
        if (isPlaying) {
            if (restActive) {
                return;
            }
            applyPauseRecoveryIfNeeded(now);
            if (playbackSessionStartElapsedMs < 0L) {
                playbackSessionStartElapsedMs = now;
                lastPlaybackIntensityMultiplier = getPlaybackIntensityMultiplier();
            }
            return;
        }

        foldCurrentPlaybackIntoDose();
        if (!restActive && pauseStartedElapsedMs < 0L) {
            pauseStartedElapsedMs = now;
        }
    }

    @Override
    public boolean onSongCompleted(Song song, int completedIndex) {
        if (!settingsManager.isHearingProtectionEnabled()) {
            return false;
        }
        foldCurrentPlaybackIntoDose();
        if (!restPendingAfterCurrentSong && accumulatedDose >= getDoseThreshold()) {
            restPendingAfterCurrentSong = true;
        }
        if (!restPendingAfterCurrentSong) {
            return false;
        }
        startRest();
        return true;
    }

    @Override
    public void onPlaybackAction(boolean userInitiated, String action) {
        if (!userInitiated) {
            return;
        }
        boolean isManualStartAction = MusicPlayerManager.PLAYBACK_ACTION_RESUME.equals(action)
                || MusicPlayerManager.PLAYBACK_ACTION_PLAY.equals(action)
                || MusicPlayerManager.PLAYBACK_ACTION_NEXT.equals(action)
                || MusicPlayerManager.PLAYBACK_ACTION_PREVIOUS.equals(action);
        if (restActive && isManualStartAction) {
            cancelRest(true);
            accumulatedDose = 0d;
            if (MusicPlayerManager.PLAYBACK_ACTION_RESUME.equals(action)) {
                playbackSessionStartElapsedMs = SystemClock.elapsedRealtime();
                lastPlaybackIntensityMultiplier = getPlaybackIntensityMultiplier();
            } else {
                playbackSessionStartElapsedMs = -1L;
            }
            pauseStartedElapsedMs = -1L;
            return;
        }
        if (MusicPlayerManager.PLAYBACK_ACTION_NEXT.equals(action)
                || MusicPlayerManager.PLAYBACK_ACTION_PREVIOUS.equals(action)
                || MusicPlayerManager.PLAYBACK_ACTION_PLAY.equals(action)) {
            restPendingAfterCurrentSong = false;
        }
        if (MusicPlayerManager.PLAYBACK_ACTION_PLAY.equals(action)
                || MusicPlayerManager.PLAYBACK_ACTION_NEXT.equals(action)
                || MusicPlayerManager.PLAYBACK_ACTION_PREVIOUS.equals(action)) {
            lastPlaybackIntensityMultiplier = getPlaybackIntensityMultiplier();
        }
    }

    private void restoreRestStateIfNeeded() {
        if (!settingsManager.isHearingProtectionEnabled() || !settingsManager.isHearingProtectionRestActive()) {
            settingsManager.clearHearingProtectionRestState();
            return;
        }
        long savedRestEnd = settingsManager.getHearingProtectionRestEndWallClockMs();
        if (savedRestEnd <= 0L) {
            settingsManager.clearHearingProtectionRestState();
            return;
        }
        long remainingMs = savedRestEnd - System.currentTimeMillis();
        if (remainingMs > TimeUnit.HOURS.toMillis(4)) {
            settingsManager.clearHearingProtectionRestState();
            return;
        }
        if (remainingMs <= 0L) {
            restActive = true;
            restEndElapsedMs = savedRestEnd;
            completeRestAndResume();
            return;
        }
        restActive = true;
        restEndElapsedMs = savedRestEnd;
        scheduleRestFinished(remainingMs);
    }

    private void startRest() {
        restPendingAfterCurrentSong = false;
        restActive = true;
        pauseStartedElapsedMs = -1L;
        playbackSessionStartElapsedMs = -1L;
        long restDurationMs = getRestDurationMs();
        restEndElapsedMs = System.currentTimeMillis() + restDurationMs;
        settingsManager.setHearingProtectionRestState(true, restEndElapsedMs);
        musicPlayerManager.pause();
        scheduleRestFinished(restDurationMs);
        Toast.makeText(appContext, appContext.getString(
                R.string.hearing_protection_rest_started,
                formatMinutes(settingsManager.getHearingProtectionRestMinutes())
        ), Toast.LENGTH_LONG).show();
    }

    private void scheduleRestFinished(long delayMs) {
        handler.removeCallbacks(restFinishedRunnable);
        handler.postDelayed(restFinishedRunnable, Math.max(0L, delayMs));
    }

    private void completeRestAndResume() {
        if (!restActive) {
            return;
        }
        handler.removeCallbacks(restFinishedRunnable);
        restActive = false;
        restEndElapsedMs = 0L;
        accumulatedDose = 0d;
        pauseStartedElapsedMs = -1L;
        playbackSessionStartElapsedMs = -1L;
        lastPlaybackIntensityMultiplier = 1.0d;
        settingsManager.clearHearingProtectionRestState();
        Toast.makeText(appContext, R.string.hearing_protection_rest_finished, Toast.LENGTH_SHORT).show();
        musicPlayerManager.playNext();
    }

    private void cancelRest(boolean notifyUser) {
        if (!restActive) {
            settingsManager.clearHearingProtectionRestState();
            return;
        }
        handler.removeCallbacks(restFinishedRunnable);
        restActive = false;
        restEndElapsedMs = 0L;
        settingsManager.clearHearingProtectionRestState();
        if (notifyUser) {
            Toast.makeText(appContext, R.string.hearing_protection_rest_cancelled, Toast.LENGTH_SHORT).show();
        }
    }

    private void foldCurrentPlaybackIntoDose() {
        if (playbackSessionStartElapsedMs < 0L) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long sessionDurationMs = Math.max(0L, now - playbackSessionStartElapsedMs);
        accumulatedDose += sessionDurationMs * lastPlaybackIntensityMultiplier;
        playbackSessionStartElapsedMs = -1L;
        if (settingsManager.isHearingProtectionEnabled() && accumulatedDose >= getDoseThreshold()) {
            restPendingAfterCurrentSong = true;
        }
    }

    private void applyPauseRecoveryIfNeeded(long now) {
        if (pauseStartedElapsedMs < 0L) {
            return;
        }
        long pauseMs = Math.max(0L, now - pauseStartedElapsedMs);
        pauseStartedElapsedMs = -1L;
        if (pauseMs <= SHORT_PAUSE_GRACE_MS) {
            return;
        }
        double ambientRecoveryMultiplier = getAmbientRecoveryMultiplier();
        double intensityPenalty = getRecoveryIntensityPenalty();
        if (ambientRecoveryMultiplier <= 0d) {
            return;
        }

        if (pauseMs <= SOCIAL_PAUSE_MAX_MS) {
            double recoveryDose = pauseMs * SOCIAL_RECOVERY_RATIO * ambientRecoveryMultiplier / intensityPenalty;
            accumulatedDose = Math.max(0d, accumulatedDose - recoveryDose);
            if (accumulatedDose < getDoseThreshold()) {
                restPendingAfterCurrentSong = false;
            }
            return;
        }

        double socialRecoveryDose = SOCIAL_PAUSE_MAX_MS * SOCIAL_RECOVERY_RATIO * ambientRecoveryMultiplier / intensityPenalty;
        accumulatedDose = Math.max(0d, accumulatedDose - socialRecoveryDose);

        long restorativePauseMs = Math.min(pauseMs, RESTORATIVE_PAUSE_MAX_MS) - SOCIAL_PAUSE_MAX_MS;
        if (restorativePauseMs > 0L && accumulatedDose > 0d) {
            double restorativeMinutes = restorativePauseMs / 60_000d;
            double pauseRatio = Math.min(1d, pauseMs / (double) RESTORATIVE_PAUSE_MAX_MS);
            double acceleratedRate = BASE_EXPONENTIAL_RECOVERY_PER_MIN
                    * (1d + pauseRatio)
                    * ambientRecoveryMultiplier
                    / intensityPenalty;
            accumulatedDose = Math.max(0d, accumulatedDose * Math.exp(-acceleratedRate * restorativeMinutes));
        }

        if (pauseMs >= getFullResetPauseThresholdMs() || accumulatedDose <= getDoseThreshold() * MAX_DOSE_RESET_RATIO) {
            accumulatedDose = 0d;
        }

        if (accumulatedDose < getDoseThreshold()) {
            restPendingAfterCurrentSong = false;
        }
    }

    private double getDoseThreshold() {
        return settingsManager.getHearingProtectionListenMinutes() * 60_000d;
    }

    private long getRestDurationMs() {
        return settingsManager.getHearingProtectionRestMinutes() * 60_000L;
    }

    private long getFullResetPauseThresholdMs() {
        return Math.max(RESTORATIVE_PAUSE_MAX_MS, getRestDurationMs());
    }

    private double getPlaybackIntensityMultiplier() {
        int appVolume = settingsManager.getAppVolume();
        if (appVolume >= 95) {
            return 1.65d;
        }
        if (appVolume >= 90) {
            return 1.45d;
        }
        if (appVolume >= 80) {
            return 1.2d;
        }
        if (appVolume >= 65) {
            return 1.0d;
        }
        if (appVolume >= 45) {
            return 0.82d;
        }
        return 0.68d;
    }

    private double getRecoveryIntensityPenalty() {
        if (lastPlaybackIntensityMultiplier >= 1.6d) {
            return 1.6d;
        }
        if (lastPlaybackIntensityMultiplier >= 1.4d) {
            return 1.35d;
        }
        if (lastPlaybackIntensityMultiplier >= 1.15d) {
            return 1.15d;
        }
        return 1.0d;
    }

    private double getAmbientRecoveryMultiplier() {
        // The app does not currently capture microphone-based ambient noise, so recovery
        // assumes a quiet environment. This hook keeps the decay model ready for future
        // ambient-noise integration without changing the core algorithm again.
        // If microphone-based ambient sampling is added later, pauses in >70 dB environments
        // should reduce this multiplier toward 0.5 or 0.0 depending on the measured noise floor.
        return 1.0d;
    }

    private String formatMinutes(int minutes) {
        return appContext.getString(R.string.hearing_protection_option_minutes, minutes);
    }
}
