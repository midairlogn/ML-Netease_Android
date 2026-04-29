package com.midairlogn.mlnetease.hearing;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.midairlogn.mlnetease.playback.core.MusicPlayerManager;
import com.midairlogn.mlnetease.playback.core.MusicService;

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
        musicPlayerManager.togglePlayPause();
    }

    public static void handleResume(Context context) {
        Context appContext = context.getApplicationContext();
        MusicPlayerManager musicPlayerManager = MusicPlayerManager.getInstance(appContext);
        HearingProtectionController.HearingProtectionSnapshot snapshot = HearingProtectionController.getSnapshot(appContext);
        if (snapshot.restActive && !musicPlayerManager.isPlaying()) {
            dispatchServiceAction(appContext, MusicService.ACTION_CANCEL_REST_AND_RESUME_CURRENT);
            return;
        }
        musicPlayerManager.resume();
    }

    public static void handleNext(Context context) {
        Context appContext = context.getApplicationContext();
        if (HearingProtectionController.getSnapshot(appContext).restActive) {
            dispatchServiceAction(appContext, MusicService.ACTION_CANCEL_REST_AND_NEXT);
            return;
        }
        MusicPlayerManager.getInstance(appContext).playNext();
    }

    public static void handlePrevious(Context context) {
        Context appContext = context.getApplicationContext();
        if (HearingProtectionController.getSnapshot(appContext).restActive) {
            dispatchServiceAction(appContext, MusicService.ACTION_CANCEL_REST_AND_PREVIOUS);
            return;
        }
        MusicPlayerManager.getInstance(appContext).playPrevious();
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
