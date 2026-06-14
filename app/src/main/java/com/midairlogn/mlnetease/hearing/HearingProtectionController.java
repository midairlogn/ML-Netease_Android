package com.midairlogn.mlnetease.hearing;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.playback.core.MusicPlayerManager;
import com.midairlogn.mlnetease.playback.core.MusicService;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.Song;

import java.util.concurrent.TimeUnit;

public class HearingProtectionController implements
        MusicPlayerManager.OnPlaybackStateChangedListener,
        MusicPlayerManager.OnSongCompletionListener,
        MusicPlayerManager.OnPlaybackActionListener {

    private static final String TAG = "HearingProtection";

    public static final class HearingProtectionSnapshot {
        public final long committedDoseMs;
        public final long displayDoseMs;
        public final boolean restActive;
        public final long restRemainingMs;
        public final boolean activelyAccumulating;
        public final boolean pauseRecoveryActive;
        public final long activeSessionElapsedMs;
        public final long pauseElapsedMs;
        public final double activeIntensityMultiplier;

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

        public long getDisplayDoseMs() {
            return displayDoseMs;
        }
    }

    private static final long SHORT_PAUSE_GRACE_MS = 30_000L;
    private static final long SOCIAL_PAUSE_MAX_MS = 5 * 60_000L;
    private static final long RESTORATIVE_PAUSE_MAX_MS = 20 * 60_000L;
    private static final long ACTIVE_SESSION_PERSIST_INTERVAL_MS = 30_000L;
    private static final long ELAPSED_REALTIME_BOOT_MATCH_TOLERANCE_MS = TimeUnit.MINUTES.toMillis(2);
    private static final double SOCIAL_RECOVERY_RATIO = 0.5d;
    private static final double MAX_DOSE_RESET_RATIO = 0.05d;
    private static final double BASE_EXPONENTIAL_RECOVERY_PER_MIN = 0.32d;
    public static final String ACTION_HEARING_REST_FINISHED = "com.midairlogn.mlnetease.action.HEARING_REST_FINISHED";
    public static final String EXTRA_FORCE_CONTINUE_AFTER_REST = "extra_force_continue_after_rest";
    private static final int REST_FINISH_REQUEST_CODE = 1003;

    private final Context appContext;
    private final MusicPlayerManager musicPlayerManager;
    private final SettingsManager settingsManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AlarmManager alarmManager;

    private double accumulatedDose;
    private long playbackSessionStartElapsedMs = -1L;
    private long pauseStartedElapsedMs = -1L;
    private double lastPlaybackIntensityMultiplier = 1.0d;
    private boolean restPendingAfterCurrentSong;
    private boolean restActive;
    private long restEndWallClockMs;
    private long restEndElapsedRealtimeMs;

    private final Runnable restFinishedRunnable = this::requestRestCompletion;
    private final Runnable activeSessionPersistenceRunnable = this::persistActiveSessionCheckpointIfNeeded;

    public HearingProtectionController(Context context, MusicPlayerManager musicPlayerManager) {
        this.appContext = context.getApplicationContext();
        this.musicPlayerManager = musicPlayerManager;
        this.settingsManager = new SettingsManager(appContext);
        this.alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
    }

    public void start() {
        musicPlayerManager.addOnPlaybackStateChangedListener(this);
        musicPlayerManager.addOnSongCompletionListener(this);
        musicPlayerManager.addOnPlaybackActionListener(this);
        accumulatedDose = settingsManager.getHearingProtectionAccumulatedDoseMs();
        if (!settingsManager.isHearingProtectionEnabled()) {
            resetTrackingState(true);
            return;
        }
        restoreRestStateIfNeeded();
        if (shouldCompleteRestNow()) {
            requestRestCompletion();
            return;
        }
        restoreStoppedActiveSessionAsPauseIfNeeded();
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

    public void stop() {
        handler.removeCallbacks(restFinishedRunnable);
        handler.removeCallbacks(activeSessionPersistenceRunnable);
        musicPlayerManager.removeOnPlaybackStateChangedListener(this);
        musicPlayerManager.removeOnSongCompletionListener(this);
        musicPlayerManager.removeOnPlaybackActionListener(this);
        if (!restActive) {
            if (musicPlayerManager.isPlaying()) {
                persistActiveSessionForServiceStop();
            } else {
                recordPauseForServiceStopIfNeeded();
            }
        }
        playbackSessionStartElapsedMs = -1L;
        pauseStartedElapsedMs = restActive ? -1L : pauseStartedElapsedMs;
    }

    public void onSettingsChanged() {
        if (!settingsManager.isHearingProtectionEnabled()) {
            boolean wasRestActive = restActive;
            resetTrackingState(true);
            if (wasRestActive) {
                continueAfterRestViaService(true);
            }
            return;
        }

        if (restActive) {
            long remainingMs = getRemainingRestMs();
            if (remainingMs <= 0L) {
                requestRestCompletion();
            } else {
                scheduleRestFinished(remainingMs);
                scheduleRestFinishAlarm(restEndWallClockMs);
            }
        }

        if (!restActive && musicPlayerManager.isPlaying()) {
            if (playbackSessionStartElapsedMs < 0L) {
                playbackSessionStartElapsedMs = SystemClock.elapsedRealtime();
                lastPlaybackIntensityMultiplier = getPlaybackIntensityMultiplier();
                persistActiveSession();
            } else {
                double newMultiplier = getPlaybackIntensityMultiplier();
                if (newMultiplier != lastPlaybackIntensityMultiplier) {
                    foldCurrentPlaybackIntoDose();
                    playbackSessionStartElapsedMs = SystemClock.elapsedRealtime();
                    lastPlaybackIntensityMultiplier = newMultiplier;
                    persistActiveSession();
                }
            }
        }

        if (!restPendingAfterCurrentSong && accumulatedDose >= getDoseThreshold()) {
            restPendingAfterCurrentSong = true;
        }
    }

    public boolean isRestActive() {
        return restActive;
    }

    public boolean shouldCompleteRestNow() {
        return restActive
                && (restEndElapsedRealtimeMs > 0L || restEndWallClockMs > 0L)
                && getRemainingRestMs() <= 0L;
    }

    public void completeRestFromService() {
        if (!shouldCompleteRestNow()) {
            return;
        }
        Log.d(TAG, "completeRestFromService: completing expired rest");
        completeRestAndResume();
    }

    public boolean cancelRestForUserAction() {
        if (!restActive && settingsManager.isHearingProtectionRestActive()) {
            restActive = true;
            restEndWallClockMs = settingsManager.getHearingProtectionRestEndWallClockMs();
            restEndElapsedRealtimeMs = settingsManager.getHearingProtectionRestEndElapsedRealtimeMs();
        }
        if (!restActive) {
            return false;
        }
        Log.d(TAG, "cancelRestForUserAction: cancelling rest and resetting dose");
        cancelRest(true);
        accumulatedDose = 0d;
        persistAccumulatedDose();
        playbackSessionStartElapsedMs = -1L;
        pauseStartedElapsedMs = -1L;
        lastPlaybackIntensityMultiplier = 1.0d;
        clearActiveSession();
        clearPauseSession();
        return true;
    }

    public static HearingProtectionSnapshot getSnapshot(Context context) {
        Context appContext = context.getApplicationContext();
        SettingsManager settingsManager = new SettingsManager(appContext);
        MusicPlayerManager musicPlayerManager = MusicPlayerManager.getInstance(appContext);

        long persistedDoseMs = settingsManager.getHearingProtectionAccumulatedDoseMs();
        boolean restActive = settingsManager.isHearingProtectionRestActive();
        long restRemainingMs = 0L;
        if (restActive) {
            long restEndElapsedRealtimeMs = settingsManager.getHearingProtectionRestEndElapsedRealtimeMs();
            if (restEndElapsedRealtimeMs > 0L) {
                restRemainingMs = Math.max(0L, restEndElapsedRealtimeMs - SystemClock.elapsedRealtime());
            } else {
                long restEndWallClockMs = settingsManager.getHearingProtectionRestEndWallClockMs();
                restRemainingMs = Math.max(0L, restEndWallClockMs - System.currentTimeMillis());
            }
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
            long activeStartElapsedRealtimeMs = settingsManager.getHearingProtectionActiveSessionStartElapsedRealtimeMs();
            long activeStartWallClockMs = settingsManager.getHearingProtectionActiveSessionStartWallClockMs();
            if (activeStartElapsedRealtimeMs > 0L || activeStartWallClockMs > 0L) {
                activeSessionElapsedMs = resolveDurationSinceTimestamp(
                        activeStartWallClockMs,
                        activeStartElapsedRealtimeMs
                );
            }
            activeIntensityMultiplier = settingsManager.getHearingProtectionActiveSessionIntensity();
            if (activeIntensityMultiplier <= 0d) {
                activeIntensityMultiplier = getPlaybackIntensityMultiplier(settingsManager.getAppVolume());
            }
            displayDoseMs = Math.max(0L, persistedDoseMs + Math.round(activeSessionElapsedMs * activeIntensityMultiplier));
        } else if (!restActive && settingsManager.isHearingProtectionEnabled()) {
            long pauseStartElapsedRealtimeMs = settingsManager.getHearingProtectionPauseStartElapsedRealtimeMs();
            long pauseStartWallClockMs = settingsManager.getHearingProtectionPauseStartWallClockMs();
            long pauseElapsedMsCandidate = 0L;
            boolean hasPauseSession = false;
            if (pauseStartElapsedRealtimeMs > 0L || pauseStartWallClockMs > 0L) {
                pauseRecoveryActive = true;
                hasPauseSession = true;
                pauseElapsedMsCandidate = resolveDurationSinceTimestamp(
                        pauseStartWallClockMs,
                        pauseStartElapsedRealtimeMs
                );
            }
            if (hasPauseSession) {
                pauseElapsedMs = pauseElapsedMsCandidate;
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
            } else {
                long activeStartElapsedRealtimeMs = settingsManager.getHearingProtectionActiveSessionStartElapsedRealtimeMs();
                long activeStartWallClockMs = settingsManager.getHearingProtectionActiveSessionStartWallClockMs();
                long activeEndElapsedRealtimeMs = settingsManager.getHearingProtectionActiveSessionLastElapsedRealtimeMs();
                long activeEndWallClockMs = settingsManager.getHearingProtectionActiveSessionLastWallClockMs();
                boolean hasStoppedActiveSession = (activeStartElapsedRealtimeMs > 0L || activeStartWallClockMs > 0L)
                        && (activeEndElapsedRealtimeMs > 0L || activeEndWallClockMs > 0L);
                if (hasStoppedActiveSession) {
                    double activeIntensity = settingsManager.getHearingProtectionActiveSessionIntensity();
                    if (activeIntensity <= 0d) {
                        activeIntensity = getPlaybackIntensityMultiplier(settingsManager.getAppVolume());
                    }
                    long activeDurationMs = resolveDurationBetween(
                            activeStartWallClockMs,
                            activeStartElapsedRealtimeMs,
                            activeEndWallClockMs,
                            activeEndElapsedRealtimeMs
                    );
                    long recoveredBaseDoseMs = Math.max(0L,
                            persistedDoseMs + Math.round(activeDurationMs * activeIntensity));
                    pauseElapsedMs = resolveDurationSinceTimestamp(
                            activeEndWallClockMs,
                            activeEndElapsedRealtimeMs
                    );
                    pauseRecoveryActive = true;
                    displayDoseMs = computeRecoveredDoseMs(
                            settingsManager,
                            recoveredBaseDoseMs,
                            pauseElapsedMs,
                            activeIntensity
                    );
                }
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
        if (!settingsManager.isHearingProtectionEnabled()) {
            resetTrackingState(true);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (isPlaying) {
            if (restActive) {
                return;
            }
            applyPauseRecoveryIfNeeded(now);
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
        Log.d(TAG, "songCompleted: dose threshold reached, starting rest");
        startRest();
        return true;
    }

    @Override
    public void onPlaybackAction(boolean userInitiated, String action) {
        if (!settingsManager.isHearingProtectionEnabled()) {
            resetTrackingState(true);
            return;
        }
        if (!userInitiated) {
            return;
        }
        boolean isManualStartAction = MusicPlayerManager.PLAYBACK_ACTION_RESUME.equals(action)
                || MusicPlayerManager.PLAYBACK_ACTION_PLAY.equals(action)
                || MusicPlayerManager.PLAYBACK_ACTION_NEXT.equals(action)
                || MusicPlayerManager.PLAYBACK_ACTION_PREVIOUS.equals(action);
        if (restActive && isManualStartAction) {
            boolean cancelledRest = cancelRestForUserAction();
            if (MusicPlayerManager.PLAYBACK_ACTION_RESUME.equals(action) && musicPlayerManager.isPlaying()) {
                playbackSessionStartElapsedMs = SystemClock.elapsedRealtime();
                lastPlaybackIntensityMultiplier = getPlaybackIntensityMultiplier();
                clearPauseSession();
                persistActiveSession();
            } else {
                playbackSessionStartElapsedMs = -1L;
                clearActiveSession();
            }
            pauseStartedElapsedMs = -1L;
            if (cancelledRest
                    && MusicPlayerManager.PLAYBACK_ACTION_RESUME.equals(action)
                    && !musicPlayerManager.isPlaying()) {
                handler.post(this::continueAfterRestViaService);
            }
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
        long savedRestEndElapsedRealtime = settingsManager.getHearingProtectionRestEndElapsedRealtimeMs();
        if (savedRestEnd <= 0L && savedRestEndElapsedRealtime <= 0L) {
            settingsManager.clearHearingProtectionRestState();
            return;
        }
        long remainingMs = savedRestEndElapsedRealtime > 0L
                ? savedRestEndElapsedRealtime - SystemClock.elapsedRealtime()
                : savedRestEnd - System.currentTimeMillis();
        if (remainingMs > TimeUnit.HOURS.toMillis(4)) {
            Log.w(TAG, "restoreRestStateIfNeeded: clearing implausible rest duration, remainingMs=" + remainingMs);
            settingsManager.clearHearingProtectionRestState();
            return;
        }
        restActive = true;
        restEndWallClockMs = savedRestEnd > 0L ? savedRestEnd : System.currentTimeMillis() + Math.max(0L, remainingMs);
        restEndElapsedRealtimeMs = savedRestEndElapsedRealtime > 0L
                ? savedRestEndElapsedRealtime
                : SystemClock.elapsedRealtime() + Math.max(0L, remainingMs);
        Log.d(TAG, "restoreRestStateIfNeeded: restored rest, remainingMs=" + Math.max(0L, remainingMs));
        if (remainingMs <= 0L) {
            return;
        }
        scheduleRestFinished(remainingMs);
        scheduleRestFinishAlarm(restEndWallClockMs);
    }

    private void restoreStoppedActiveSessionAsPauseIfNeeded() {
        if (restActive || musicPlayerManager.isPlaying() || hasPersistedPauseSession()) {
            return;
        }

        long activeStartElapsedRealtimeMs = settingsManager.getHearingProtectionActiveSessionStartElapsedRealtimeMs();
        long activeStartWallClockMs = settingsManager.getHearingProtectionActiveSessionStartWallClockMs();
        if (activeStartElapsedRealtimeMs <= 0L && activeStartWallClockMs <= 0L) {
            return;
        }

        long activeEndElapsedRealtimeMs = settingsManager.getHearingProtectionActiveSessionLastElapsedRealtimeMs();
        long activeEndWallClockMs = settingsManager.getHearingProtectionActiveSessionLastWallClockMs();
        if (activeEndElapsedRealtimeMs <= 0L && activeEndWallClockMs <= 0L) {
            clearActiveSession();
            return;
        }

        long activeDurationMs = resolveDurationBetween(
                activeStartWallClockMs,
                activeStartElapsedRealtimeMs,
                activeEndWallClockMs,
                activeEndElapsedRealtimeMs
        );
        double intensityMultiplier = resolvePersistedOrCurrentIntensity(
                settingsManager.getHearingProtectionActiveSessionIntensity()
        );
        accumulatedDose += activeDurationMs * intensityMultiplier;
        persistAccumulatedDose();
        clearActiveSession();

        long pauseStartWallClockMs = resolveWallClockForTimestamp(activeEndWallClockMs, activeEndElapsedRealtimeMs);
        pauseStartedElapsedMs = resolveElapsedRealtimeForWallClock(pauseStartWallClockMs, activeEndElapsedRealtimeMs);
        lastPlaybackIntensityMultiplier = intensityMultiplier;
        if (accumulatedDose > 0d) {
            persistPauseSession(
                    pauseStartWallClockMs,
                    isElapsedRealtimeFromCurrentBoot(
                            pauseStartWallClockMs,
                            activeEndElapsedRealtimeMs,
                            SystemClock.elapsedRealtime()
                    )
                            ? activeEndElapsedRealtimeMs
                            : 0L
            );
        }
    }

    private void restorePauseSessionIfNeeded() {
        long pauseStartElapsedRealtimeMs = settingsManager.getHearingProtectionPauseStartElapsedRealtimeMs();
        long pauseStartWallClockMs = settingsManager.getHearingProtectionPauseStartWallClockMs();
        if (pauseStartElapsedRealtimeMs <= 0L && pauseStartWallClockMs <= 0L) {
            clearPauseSession();
            return;
        }
        pauseStartedElapsedMs = resolveElapsedRealtimeForWallClock(
                pauseStartWallClockMs,
                pauseStartElapsedRealtimeMs
        );
        lastPlaybackIntensityMultiplier = resolvePersistedOrCurrentIntensity(
                settingsManager.getHearingProtectionPauseIntensity()
        );
        if (musicPlayerManager.isPlaying()) {
            applyPauseRecoveryIfNeeded(SystemClock.elapsedRealtime());
        }
    }

    private void restoreActiveSessionIfNeeded() {
        long activeStartElapsedRealtimeMs = settingsManager.getHearingProtectionActiveSessionStartElapsedRealtimeMs();
        long activeStartWallClockMs = settingsManager.getHearingProtectionActiveSessionStartWallClockMs();
        if (activeStartElapsedRealtimeMs <= 0L && activeStartWallClockMs <= 0L) {
            clearActiveSession();
            return;
        }
        long nowElapsedRealtimeMs = SystemClock.elapsedRealtime();
        if (isElapsedRealtimeFromCurrentBoot(
                activeStartWallClockMs,
                activeStartElapsedRealtimeMs,
                nowElapsedRealtimeMs
        )) {
            playbackSessionStartElapsedMs = activeStartElapsedRealtimeMs;
            return;
        }
        long activeElapsedMs = resolveDurationSinceTimestamp(
                activeStartWallClockMs,
                activeStartElapsedRealtimeMs,
                nowElapsedRealtimeMs
        );
        playbackSessionStartElapsedMs = Math.max(0L, nowElapsedRealtimeMs - activeElapsedMs);
    }

    private void startRest() {
        restPendingAfterCurrentSong = false;
        restActive = true;
        pauseStartedElapsedMs = -1L;
        playbackSessionStartElapsedMs = -1L;
        clearPauseSession();
        long restDurationMs = getRestDurationMs();
        restEndWallClockMs = System.currentTimeMillis() + restDurationMs;
        restEndElapsedRealtimeMs = SystemClock.elapsedRealtime() + restDurationMs;
        settingsManager.setHearingProtectionRestState(true, restEndWallClockMs, restEndElapsedRealtimeMs);
        Log.i(TAG, "startRest: rest started, durationMs=" + restDurationMs
                + ", doseMs=" + Math.round(accumulatedDose));
        musicPlayerManager.pause();
        scheduleRestFinished(restDurationMs);
        scheduleRestFinishAlarm(restEndWallClockMs);
        requestPlaybackStateRefresh(appContext);
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
        Log.i(TAG, "completeRestAndResume: rest finished, resetting dose and rest state");
        handler.removeCallbacks(restFinishedRunnable);
        restActive = false;
        restEndWallClockMs = 0L;
        restEndElapsedRealtimeMs = 0L;
        accumulatedDose = 0d;
        persistAccumulatedDose();
        clearActiveSession();
        clearPauseSession();
        pauseStartedElapsedMs = -1L;
        playbackSessionStartElapsedMs = -1L;
        lastPlaybackIntensityMultiplier = 1.0d;
        cancelRestFinishAlarm();
        settingsManager.clearHearingProtectionRestState();
        requestPlaybackStateRefresh(appContext);
        Toast.makeText(appContext, R.string.hearing_protection_rest_finished, Toast.LENGTH_SHORT).show();
    }

    private void cancelRest(boolean notifyUser) {
        if (!restActive) {
            settingsManager.clearHearingProtectionRestState();
            return;
        }
        Log.i(TAG, "cancelRest: rest cancelled, notifyUser=" + notifyUser);
        handler.removeCallbacks(restFinishedRunnable);
        restActive = false;
        restEndWallClockMs = 0L;
        restEndElapsedRealtimeMs = 0L;
        cancelRestFinishAlarm();
        settingsManager.clearHearingProtectionRestState();
        clearPauseSession();
        requestPlaybackStateRefresh(appContext);
        if (notifyUser) {
            Toast.makeText(appContext, R.string.hearing_protection_rest_cancelled, Toast.LENGTH_SHORT).show();
        }
    }

    private void foldCurrentPlaybackIntoDose() {
        if (!settingsManager.isHearingProtectionEnabled()) {
            resetTrackingState(true);
            return;
        }
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
            Log.d(TAG, "foldCurrentPlaybackIntoDose: threshold reached, rest pending after current song");
        }
    }

    private void recordPauseForServiceStopIfNeeded() {
        // A stopped playback service with no active playback means music has stopped from
        // the user's point of view. Persist it as a pause so load can recover offline.
        if (playbackSessionStartElapsedMs >= 0L) {
            foldCurrentPlaybackIntoDose();
        }
        if (pauseStartedElapsedMs >= 0L) {
            if (!hasPersistedPauseSession()) {
                persistPauseSession();
            }
            return;
        }
        if (accumulatedDose > 0d) {
            pauseStartedElapsedMs = SystemClock.elapsedRealtime();
            persistPauseSession();
        }
    }

    private void resetTrackingState(boolean clearDose) {
        handler.removeCallbacks(restFinishedRunnable);
        if (restActive) {
            cancelRest(false);
        } else {
            cancelRestFinishAlarm();
            settingsManager.clearHearingProtectionRestState();
        }
        if (clearDose) {
            accumulatedDose = 0d;
            persistAccumulatedDose();
        }
        clearActiveSession();
        clearPauseSession();
        playbackSessionStartElapsedMs = -1L;
        pauseStartedElapsedMs = -1L;
        lastPlaybackIntensityMultiplier = 1.0d;
        restPendingAfterCurrentSong = false;
    }

    private void applyPauseRecoveryIfNeeded(long nowElapsedRealtimeMs) {
        boolean hasPersistedPauseSession = hasPersistedPauseSession();
        if (pauseStartedElapsedMs < 0L && !hasPersistedPauseSession) {
            return;
        }
        long pauseMs;
        if (hasPersistedPauseSession) {
            pauseMs = resolveDurationSinceTimestamp(
                    settingsManager.getHearingProtectionPauseStartWallClockMs(),
                    settingsManager.getHearingProtectionPauseStartElapsedRealtimeMs(),
                    nowElapsedRealtimeMs
            );
        } else {
            pauseMs = Math.max(0L, nowElapsedRealtimeMs - pauseStartedElapsedMs);
        }
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
        persistActiveSession(true);
    }

    private void persistActiveSessionForServiceStop() {
        if (playbackSessionStartElapsedMs < 0L) {
            restoreActiveSessionIfNeeded();
        }
        if (playbackSessionStartElapsedMs < 0L) {
            playbackSessionStartElapsedMs = SystemClock.elapsedRealtime();
        }
        persistActiveSession(false);
    }

    private void persistActiveSession(boolean scheduleNext) {
        if (playbackSessionStartElapsedMs < 0L) {
            clearActiveSession();
            return;
        }
        long nowElapsedRealtimeMs = SystemClock.elapsedRealtime();
        long nowWallClockMs = System.currentTimeMillis();
        long activeElapsedMs = Math.max(0L, nowElapsedRealtimeMs - playbackSessionStartElapsedMs);
        long startWallClockMs = nowWallClockMs - activeElapsedMs;
        settingsManager.setHearingProtectionActiveSession(
                startWallClockMs,
                playbackSessionStartElapsedMs,
                nowWallClockMs,
                nowElapsedRealtimeMs,
                (float) lastPlaybackIntensityMultiplier
        );
        if (scheduleNext) {
            scheduleActiveSessionPersistence();
        }
    }

    private void persistActiveSessionCheckpointIfNeeded() {
        if (!settingsManager.isHearingProtectionEnabled()
                || restActive
                || playbackSessionStartElapsedMs < 0L
                || !musicPlayerManager.isPlaying()) {
            return;
        }
        persistActiveSession();
    }

    private void scheduleActiveSessionPersistence() {
        handler.removeCallbacks(activeSessionPersistenceRunnable);
        if (settingsManager.isHearingProtectionEnabled()
                && !restActive
                && playbackSessionStartElapsedMs >= 0L
                && musicPlayerManager.isPlaying()) {
            handler.postDelayed(activeSessionPersistenceRunnable, ACTIVE_SESSION_PERSIST_INTERVAL_MS);
        }
    }

    private void clearActiveSession() {
        handler.removeCallbacks(activeSessionPersistenceRunnable);
        settingsManager.clearHearingProtectionActiveSession();
    }

    private void persistPauseSession() {
        if (pauseStartedElapsedMs < 0L) {
            clearPauseSession();
            return;
        }
        long pauseElapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - pauseStartedElapsedMs);
        long pauseStartWallClockMs = System.currentTimeMillis() - pauseElapsedMs;
        persistPauseSession(pauseStartWallClockMs, pauseStartedElapsedMs);
    }

    private void persistPauseSession(long pauseStartWallClockMs, long pauseStartElapsedRealtimeMs) {
        settingsManager.setHearingProtectionPauseSession(
                pauseStartWallClockMs,
                pauseStartElapsedRealtimeMs,
                Math.round(accumulatedDose),
                (float) lastPlaybackIntensityMultiplier
        );
    }

    private void clearPauseSession() {
        settingsManager.clearHearingProtectionPauseSession();
    }

    private boolean hasPersistedPauseSession() {
        return settingsManager.getHearingProtectionPauseStartElapsedRealtimeMs() > 0L
                || settingsManager.getHearingProtectionPauseStartWallClockMs() > 0L;
    }

    private static long resolveDurationBetween(long startWallClockMs,
                                               long startElapsedRealtimeMs,
                                               long endWallClockMs,
                                               long endElapsedRealtimeMs) {
        if (startElapsedRealtimeMs > 0L
                && endElapsedRealtimeMs > 0L
                && endElapsedRealtimeMs >= startElapsedRealtimeMs) {
            return endElapsedRealtimeMs - startElapsedRealtimeMs;
        }
        if (startWallClockMs > 0L && endWallClockMs > 0L && endWallClockMs >= startWallClockMs) {
            return endWallClockMs - startWallClockMs;
        }
        return 0L;
    }

    private static long resolveDurationSinceTimestamp(long wallClockMs, long elapsedRealtimeMs) {
        return resolveDurationSinceTimestamp(wallClockMs, elapsedRealtimeMs, SystemClock.elapsedRealtime());
    }

    private static long resolveDurationSinceTimestamp(long wallClockMs,
                                                      long elapsedRealtimeMs,
                                                      long nowElapsedRealtimeMs) {
        if (isElapsedRealtimeFromCurrentBoot(wallClockMs, elapsedRealtimeMs, nowElapsedRealtimeMs)) {
            return Math.max(0L, nowElapsedRealtimeMs - elapsedRealtimeMs);
        }
        if (wallClockMs > 0L) {
            return Math.max(0L, System.currentTimeMillis() - wallClockMs);
        }
        if (elapsedRealtimeMs > 0L && elapsedRealtimeMs <= nowElapsedRealtimeMs) {
            return Math.max(0L, nowElapsedRealtimeMs - elapsedRealtimeMs);
        }
        return 0L;
    }

    private static long resolveWallClockForTimestamp(long wallClockMs, long elapsedRealtimeMs) {
        if (wallClockMs > 0L) {
            return wallClockMs;
        }
        if (elapsedRealtimeMs > 0L && elapsedRealtimeMs <= SystemClock.elapsedRealtime()) {
            long elapsedSinceTimestampMs = Math.max(0L, SystemClock.elapsedRealtime() - elapsedRealtimeMs);
            return System.currentTimeMillis() - elapsedSinceTimestampMs;
        }
        return System.currentTimeMillis();
    }

    private static long resolveElapsedRealtimeForWallClock(long wallClockMs, long elapsedRealtimeMs) {
        if (isElapsedRealtimeFromCurrentBoot(wallClockMs, elapsedRealtimeMs, SystemClock.elapsedRealtime())) {
            return elapsedRealtimeMs;
        }
        if (wallClockMs > 0L) {
            long elapsedSinceWallClockMs = Math.max(0L, System.currentTimeMillis() - wallClockMs);
            return Math.max(0L, SystemClock.elapsedRealtime() - elapsedSinceWallClockMs);
        }
        if (elapsedRealtimeMs > 0L && elapsedRealtimeMs <= SystemClock.elapsedRealtime()) {
            return elapsedRealtimeMs;
        }
        return SystemClock.elapsedRealtime();
    }

    private static boolean isElapsedRealtimeFromCurrentBoot(long wallClockMs,
                                                            long elapsedRealtimeMs,
                                                            long nowElapsedRealtimeMs) {
        if (wallClockMs <= 0L || elapsedRealtimeMs <= 0L || elapsedRealtimeMs > nowElapsedRealtimeMs) {
            return false;
        }
        long persistedBootWallClockMs = wallClockMs - elapsedRealtimeMs;
        long currentBootWallClockMs = System.currentTimeMillis() - nowElapsedRealtimeMs;
        return Math.abs(persistedBootWallClockMs - currentBootWallClockMs)
                <= ELAPSED_REALTIME_BOOT_MATCH_TOLERANCE_MS;
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

    private void scheduleRestFinishAlarm(long triggerAtWallClockMs) {
        if (alarmManager == null) {
            Log.w(TAG, "scheduleRestFinishAlarm: alarm manager unavailable");
            return;
        }
        PendingIntent pendingIntent = buildRestFinishedPendingIntent();
        long safeTriggerMs = Math.max(System.currentTimeMillis(), triggerAtWallClockMs);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTriggerMs, pendingIntent);
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTriggerMs, pendingIntent);
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, safeTriggerMs, pendingIntent);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, safeTriggerMs, pendingIntent);
        }
    }

    private void cancelRestFinishAlarm() {
        if (alarmManager == null) {
            return;
        }
        alarmManager.cancel(buildRestFinishedPendingIntent());
    }

    private PendingIntent buildRestFinishedPendingIntent() {
        // Deliver the alarm through the manifest receiver so it can restart the
        // playback service via startForegroundService() when the app is backgrounded.
        Intent intent = new Intent(appContext, HearingProtectionRestReceiver.class);
        intent.setAction(ACTION_HEARING_REST_FINISHED);
        return PendingIntent.getBroadcast(
                appContext,
                REST_FINISH_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    public static void startMusicServiceForRestCompletion(Context context) {
        startMusicServiceForRestCompletion(context, false);
    }

    public static void requestPlaybackStateRefresh(Context context) {
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, MusicService.class);
        intent.setAction(MusicService.ACTION_REFRESH_PLAYBACK_STATE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    public static void startMusicServiceForRestCompletion(Context context, boolean forceContinueAfterRest) {
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, MusicService.class);
        intent.setAction(ACTION_HEARING_REST_FINISHED);
        intent.putExtra(EXTRA_FORCE_CONTINUE_AFTER_REST, forceContinueAfterRest);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    private void requestRestCompletion() {
        if (!shouldCompleteRestNow()) {
            return;
        }
        continueAfterRestViaService();
    }

    private long getRemainingRestMs() {
        if (!restActive) {
            return 0L;
        }
        if (restEndElapsedRealtimeMs > 0L) {
            return Math.max(0L, restEndElapsedRealtimeMs - SystemClock.elapsedRealtime());
        }
        if (restEndWallClockMs > 0L) {
            return Math.max(0L, restEndWallClockMs - System.currentTimeMillis());
        }
        return 0L;
    }

    private void continueAfterRestViaService() {
        continueAfterRestViaService(false);
    }

    private void continueAfterRestViaService(boolean forceContinueAfterRest) {
        startMusicServiceForRestCompletion(appContext, forceContinueAfterRest);
    }

}
