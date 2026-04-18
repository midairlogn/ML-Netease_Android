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

    static final class HearingProtectionSnapshot {
        final long committedDoseMs;
        final long displayDoseMs;
        final boolean restActive;
        final long restRemainingMs;
        final boolean activelyAccumulating;
        final boolean pauseRecoveryActive;
        final long activeSessionElapsedMs;
        final long pauseElapsedMs;
        final double activeIntensityMultiplier;

        HearingProtectionSnapshot(long committedDoseMs,
                                  long displayDoseMs,
                                  boolean restActive,
                                  long restRemainingMs,
                                  boolean activelyAccumulating,
                                  boolean pauseRecoveryActive,
                                  long activeSessionElapsedMs,
                                  long pauseElapsedMs,
                                  double activeIntensityMultiplier) {
            this.committedDoseMs = Math.max(0L, committedDoseMs);
            this.displayDoseMs = Math.max(0L, displayDoseMs);
            this.restActive = restActive;
            this.restRemainingMs = Math.max(0L, restRemainingMs);
            this.activelyAccumulating = activelyAccumulating;
            this.pauseRecoveryActive = pauseRecoveryActive;
            this.activeSessionElapsedMs = Math.max(0L, activeSessionElapsedMs);
            this.pauseElapsedMs = Math.max(0L, pauseElapsedMs);
            this.activeIntensityMultiplier = Math.max(0d, activeIntensityMultiplier);
        }

        long getDisplayDoseMs() {
            return displayDoseMs;
        }
    }

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
        accumulatedDose = settingsManager.getHearingProtectionAccumulatedDoseMs();
        restoreRestStateIfNeeded();
        restorePauseSessionIfNeeded();
        if (!restActive && musicPlayerManager.isPlaying()) {
            restoreActiveSessionIfNeeded();
            if (playbackSessionStartElapsedMs < 0L) {
                playbackSessionStartElapsedMs = SystemClock.elapsedRealtime();
            }
            lastPlaybackIntensityMultiplier = resolvePersistedOrCurrentIntensity(
                    settingsManager.getHearingProtectionActiveSessionIntensity()
            );
            persistActiveSession();
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
        clearPauseSession();
    }

    void onSettingsChanged() {
        if (!settingsManager.isHearingProtectionEnabled()) {
            boolean wasRestActive = restActive;
            cancelRest(false);
            accumulatedDose = 0d;
            persistAccumulatedDose();
            clearActiveSession();
            clearPauseSession();
            playbackSessionStartElapsedMs = -1L;
            pauseStartedElapsedMs = -1L;
            lastPlaybackIntensityMultiplier = 1.0d;
            restPendingAfterCurrentSong = false;
            if (wasRestActive) {
                musicPlayerManager.playNext();
            }
            return;
        }

        if (restActive) {
            long remainingMs = restEndElapsedMs - System.currentTimeMillis();
            if (remainingMs <= 0L) {
                completeRestAndResume();
            } else {
                scheduleRestFinished(remainingMs);
            }
        }

        if (!restActive && musicPlayerManager.isPlaying() && playbackSessionStartElapsedMs < 0L) {
            playbackSessionStartElapsedMs = SystemClock.elapsedRealtime();
            lastPlaybackIntensityMultiplier = getPlaybackIntensityMultiplier();
            persistActiveSession();
        }

        if (!restPendingAfterCurrentSong && accumulatedDose >= getDoseThreshold()) {
            restPendingAfterCurrentSong = true;
        }
    }

    boolean isRestActive() {
        return restActive;
    }

    static HearingProtectionSnapshot getSnapshot(Context context) {
        Context appContext = context.getApplicationContext();
        SettingsManager settingsManager = new SettingsManager(appContext);
        MusicPlayerManager musicPlayerManager = MusicPlayerManager.getInstance(appContext);

        long persistedDoseMs = settingsManager.getHearingProtectionAccumulatedDoseMs();
        boolean restActive = settingsManager.isHearingProtectionRestActive();
        long restRemainingMs = 0L;
        if (restActive) {
            long restEndWallClockMs = settingsManager.getHearingProtectionRestEndWallClockMs();
            restRemainingMs = Math.max(0L, restEndWallClockMs - System.currentTimeMillis());
        }

        boolean activelyAccumulating = settingsManager.isHearingProtectionEnabled()
                && musicPlayerManager.isPlaying()
                && !restActive;
        boolean pauseRecoveryActive = false;
        long activeSessionElapsedMs = 0L;
        long pauseElapsedMs = 0L;
        double activeIntensityMultiplier = 0d;
        long displayDoseMs = persistedDoseMs;
        if (activelyAccumulating) {
            long activeStartWallClockMs = settingsManager.getHearingProtectionActiveSessionStartWallClockMs();
            if (activeStartWallClockMs > 0L) {
                activeSessionElapsedMs = Math.max(0L, System.currentTimeMillis() - activeStartWallClockMs);
            }
            activeIntensityMultiplier = settingsManager.getHearingProtectionActiveSessionIntensity();
            if (activeIntensityMultiplier <= 0d) {
                activeIntensityMultiplier = getPlaybackIntensityMultiplier(settingsManager.getAppVolume());
            }
            displayDoseMs = Math.max(0L, persistedDoseMs + Math.round(activeSessionElapsedMs * activeIntensityMultiplier));
        } else if (!restActive && settingsManager.isHearingProtectionEnabled()) {
            long pauseStartWallClockMs = settingsManager.getHearingProtectionPauseStartWallClockMs();
            if (pauseStartWallClockMs > 0L) {
                pauseRecoveryActive = true;
                pauseElapsedMs = Math.max(0L, System.currentTimeMillis() - pauseStartWallClockMs);
                long pauseBaseDoseMs = settingsManager.getHearingProtectionPauseBaseDoseMs();
                double pauseIntensityMultiplier = settingsManager.getHearingProtectionPauseIntensity();
                if (pauseIntensityMultiplier <= 0d) {
                    pauseIntensityMultiplier = getPlaybackIntensityMultiplier(settingsManager.getAppVolume());
                }
                displayDoseMs = computeRecoveredDoseMs(
                        settingsManager,
                        pauseBaseDoseMs,
                        pauseElapsedMs,
                        pauseIntensityMultiplier
                );
            }
        }

        return new HearingProtectionSnapshot(
                persistedDoseMs,
                displayDoseMs,
                restActive,
                restRemainingMs,
                activelyAccumulating,
                pauseRecoveryActive,
                activeSessionElapsedMs,
                pauseElapsedMs,
                activeIntensityMultiplier
        );
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        long now = SystemClock.elapsedRealtime();
        if (isPlaying) {
            if (restActive) {
                return;
            }
            applyPauseRecoveryIfNeeded(System.currentTimeMillis(), now);
            if (playbackSessionStartElapsedMs < 0L) {
                playbackSessionStartElapsedMs = now;
                lastPlaybackIntensityMultiplier = getPlaybackIntensityMultiplier();
                persistActiveSession();
            }
            return;
        }

        foldCurrentPlaybackIntoDose();
        if (!restActive && pauseStartedElapsedMs < 0L) {
            pauseStartedElapsedMs = now;
            persistPauseSession();
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
            persistAccumulatedDose();
            if (MusicPlayerManager.PLAYBACK_ACTION_RESUME.equals(action)) {
                playbackSessionStartElapsedMs = SystemClock.elapsedRealtime();
                lastPlaybackIntensityMultiplier = getPlaybackIntensityMultiplier();
                clearPauseSession();
                persistActiveSession();
            } else {
                playbackSessionStartElapsedMs = -1L;
                clearActiveSession();
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
            if (musicPlayerManager.isPlaying()) {
                persistActiveSession();
            }
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

    private void restorePauseSessionIfNeeded() {
        long pauseStartWallClockMs = settingsManager.getHearingProtectionPauseStartWallClockMs();
        if (pauseStartWallClockMs <= 0L) {
            clearPauseSession();
            return;
        }
        if (musicPlayerManager.isPlaying()) {
            clearPauseSession();
            return;
        }
        long pauseElapsedMs = Math.max(0L, System.currentTimeMillis() - pauseStartWallClockMs);
        pauseStartedElapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - pauseElapsedMs);
        lastPlaybackIntensityMultiplier = resolvePersistedOrCurrentIntensity(
                settingsManager.getHearingProtectionPauseIntensity()
        );
    }

    private void restoreActiveSessionIfNeeded() {
        long activeStartWallClockMs = settingsManager.getHearingProtectionActiveSessionStartWallClockMs();
        if (activeStartWallClockMs <= 0L) {
            clearActiveSession();
            return;
        }
        long activeElapsedMs = Math.max(0L, System.currentTimeMillis() - activeStartWallClockMs);
        playbackSessionStartElapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - activeElapsedMs);
    }

    private void startRest() {
        restPendingAfterCurrentSong = false;
        restActive = true;
        pauseStartedElapsedMs = -1L;
        playbackSessionStartElapsedMs = -1L;
        clearPauseSession();
        long restDurationMs = getRestDurationMs();
        restEndElapsedMs = System.currentTimeMillis() + restDurationMs;
        settingsManager.setHearingProtectionRestState(true, restEndElapsedMs);
        musicPlayerManager.pause();
        scheduleRestFinished(restDurationMs);
        Toast.makeText(appContext, appContext.getString(
                R.string.hearing_protection_rest_started,
                settingsManager.getHearingProtectionRestMinutes()
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
        persistAccumulatedDose();
        clearActiveSession();
        clearPauseSession();
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
        clearPauseSession();
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
        persistAccumulatedDose();
        clearActiveSession();
        clearPauseSession();
        if (settingsManager.isHearingProtectionEnabled() && accumulatedDose >= getDoseThreshold()) {
            restPendingAfterCurrentSong = true;
        }
    }

    private void applyPauseRecoveryIfNeeded(long nowWallClockMs, long nowElapsedRealtimeMs) {
        if (pauseStartedElapsedMs < 0L) {
            return;
        }
        long pauseMs = Math.max(0L, nowElapsedRealtimeMs - pauseStartedElapsedMs);
        pauseStartedElapsedMs = -1L;
        accumulatedDose = computeRecoveredDoseMs(
                settingsManager,
                settingsManager.getHearingProtectionPauseBaseDoseMs(),
                pauseMs,
                settingsManager.getHearingProtectionPauseIntensity() > 0f
                        ? settingsManager.getHearingProtectionPauseIntensity()
                        : getPlaybackIntensityMultiplier()
        );
        persistAccumulatedDose();
        clearPauseSession();

        if (accumulatedDose < getDoseThreshold()) {
            restPendingAfterCurrentSong = false;
        }

        if (playbackSessionStartElapsedMs < 0L && musicPlayerManager.isPlaying()) {
            playbackSessionStartElapsedMs = nowElapsedRealtimeMs;
            lastPlaybackIntensityMultiplier = getPlaybackIntensityMultiplier();
            persistActiveSession();
        }
    }

    private void persistAccumulatedDose() {
        settingsManager.setHearingProtectionAccumulatedDoseMs(Math.round(accumulatedDose));
    }

    private void persistActiveSession() {
        if (playbackSessionStartElapsedMs < 0L) {
            clearActiveSession();
            return;
        }
        long activeElapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - playbackSessionStartElapsedMs);
        long startWallClockMs = System.currentTimeMillis() - activeElapsedMs;
        settingsManager.setHearingProtectionActiveSession(startWallClockMs, (float) lastPlaybackIntensityMultiplier);
    }

    private void clearActiveSession() {
        settingsManager.clearHearingProtectionActiveSession();
    }

    private void persistPauseSession() {
        settingsManager.setHearingProtectionPauseSession(
                System.currentTimeMillis(),
                Math.round(accumulatedDose),
                (float) lastPlaybackIntensityMultiplier
        );
    }

    private void clearPauseSession() {
        settingsManager.clearHearingProtectionPauseSession();
    }

    private double resolvePersistedOrCurrentIntensity(float persistedIntensity) {
        if (persistedIntensity > 0f) {
            return persistedIntensity;
        }
        return getPlaybackIntensityMultiplier();
    }

    private static long computeRecoveredDoseMs(SettingsManager settingsManager,
                                               long baseDoseMs,
                                               long pauseDurationMs,
                                               double lastIntensityMultiplier) {
        double currentDose = Math.max(0d, baseDoseMs);
        long safePauseDurationMs = Math.max(0L, pauseDurationMs);
        if (safePauseDurationMs <= SHORT_PAUSE_GRACE_MS) {
            return Math.max(0L, Math.round(currentDose));
        }

        double ambientRecoveryMultiplier = getAmbientRecoveryMultiplier();
        double intensityPenalty = getRecoveryIntensityPenalty(lastIntensityMultiplier);
        if (ambientRecoveryMultiplier <= 0d) {
            return Math.max(0L, Math.round(currentDose));
        }

        if (safePauseDurationMs <= SOCIAL_PAUSE_MAX_MS) {
            double recoveryDose = safePauseDurationMs * SOCIAL_RECOVERY_RATIO * ambientRecoveryMultiplier / intensityPenalty;
            return Math.max(0L, Math.round(Math.max(0d, currentDose - recoveryDose)));
        }

        double socialRecoveryDose = SOCIAL_PAUSE_MAX_MS * SOCIAL_RECOVERY_RATIO * ambientRecoveryMultiplier / intensityPenalty;
        currentDose = Math.max(0d, currentDose - socialRecoveryDose);

        long restorativePauseMs = Math.min(safePauseDurationMs, RESTORATIVE_PAUSE_MAX_MS) - SOCIAL_PAUSE_MAX_MS;
        if (restorativePauseMs > 0L && currentDose > 0d) {
            double restorativeMinutes = restorativePauseMs / 60_000d;
            double pauseRatio = Math.min(1d, safePauseDurationMs / (double) RESTORATIVE_PAUSE_MAX_MS);
            double acceleratedRate = BASE_EXPONENTIAL_RECOVERY_PER_MIN
                    * (1d + pauseRatio)
                    * ambientRecoveryMultiplier
                    / intensityPenalty;
            currentDose = Math.max(0d, currentDose * Math.exp(-acceleratedRate * restorativeMinutes));
        }

        long fullResetPauseThresholdMs = Math.max(RESTORATIVE_PAUSE_MAX_MS, settingsManager.getHearingProtectionRestMinutes() * 60_000L);
        double doseThreshold = settingsManager.getHearingProtectionListenMinutes() * 60_000d;
        if (safePauseDurationMs >= fullResetPauseThresholdMs || currentDose <= doseThreshold * MAX_DOSE_RESET_RATIO) {
            currentDose = 0d;
        }

        return Math.max(0L, Math.round(currentDose));
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
        return getPlaybackIntensityMultiplier(settingsManager.getAppVolume());
    }

    private static double getPlaybackIntensityMultiplier(int appVolume) {
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
        return getRecoveryIntensityPenalty(lastPlaybackIntensityMultiplier);
    }

    private static double getRecoveryIntensityPenalty(double intensityMultiplier) {
        if (intensityMultiplier >= 1.6d) {
            return 1.6d;
        }
        if (intensityMultiplier >= 1.4d) {
            return 1.35d;
        }
        if (intensityMultiplier >= 1.15d) {
            return 1.15d;
        }
        return 1.0d;
    }

    private static double getAmbientRecoveryMultiplier() {
        // The app does not currently capture microphone-based ambient noise, so recovery
        // assumes a quiet environment. This hook keeps the decay model ready for future
        // ambient-noise integration without changing the core algorithm again.
        // If microphone-based ambient sampling is added later, pauses in >70 dB environments
        // should reduce this multiplier toward 0.5 or 0.0 depending on the measured noise floor.
        return 1.0d;
    }

}
