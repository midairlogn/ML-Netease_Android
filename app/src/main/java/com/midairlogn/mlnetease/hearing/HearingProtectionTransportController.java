package com.midairlogn.mlnetease.hearing;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.midairlogn.mlnetease.playback.core.MusicPlayerManager;
import com.midairlogn.mlnetease.playback.core.MusicService;
import com.midairlogn.mlnetease.playback.core.PlaybackActionDispatcher;

public final class HearingProtectionTransportController {

    private HearingProtectionTransportController() {
    }

    public static void handlePlayPause(Context context) {
        Context appContext = context.getApplicationContext();
        MusicPlayerManager musicPlayerManager = MusicPlayerManager.getInstance(appContext);
        if (HearingProtectionController.getSnapshot(appContext).restActive && !musicPlayerManager.isPlaying()) {
            dispatchServiceAction(appContext, MusicService.ACTION_CANCEL_REST_AND_CONTINUE);
            return;
        }
        PlaybackActionDispatcher.togglePlayPause(appContext);
    }

    public static void handleResume(Context context) {
        Context appContext = context.getApplicationContext();
        MusicPlayerManager musicPlayerManager = MusicPlayerManager.getInstance(appContext);
        HearingProtectionController.HearingProtectionSnapshot snapshot = HearingProtectionController.getSnapshot(appContext);
        if (snapshot.restActive && !musicPlayerManager.isPlaying()) {
            dispatchServiceAction(appContext, MusicService.ACTION_CANCEL_REST_AND_RESUME_CURRENT);
            return;
        }
        PlaybackActionDispatcher.resume(appContext);
    }

    public static void handleSeekResumeCurrent(Context context) {
        Context appContext = context.getApplicationContext();
        MusicPlayerManager musicPlayerManager = MusicPlayerManager.getInstance(appContext);
        if (HearingProtectionController.getSnapshot(appContext).restActive) {
            dispatchServiceAction(appContext, MusicService.ACTION_CANCEL_REST_AND_RESUME_CURRENT);
            return;
        }
        PlaybackActionDispatcher.resume(appContext);
    }

    public static void handleNext(Context context) {
        Context appContext = context.getApplicationContext();
        if (HearingProtectionController.getSnapshot(appContext).restActive) {
            dispatchServiceAction(appContext, MusicService.ACTION_CANCEL_REST_AND_NEXT);
            return;
        }
        PlaybackActionDispatcher.playNext(appContext);
    }

    public static void handlePrevious(Context context) {
        Context appContext = context.getApplicationContext();
        if (HearingProtectionController.getSnapshot(appContext).restActive) {
            dispatchServiceAction(appContext, MusicService.ACTION_CANCEL_REST_AND_PREVIOUS);
            return;
        }
        PlaybackActionDispatcher.playPrevious(appContext);
    }

    private static void dispatchServiceAction(Context context, String action) {
        Intent intent = new Intent(context, MusicService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
