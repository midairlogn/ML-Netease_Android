package com.midairlogn.mlnetease.playback.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class PlaybackMediaButtonReceiver extends BroadcastReceiver {
    private static final String TAG = "PlaybackMediaButtonRcvr";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        Log.d(TAG, "received media button restart intent");
        PlaybackActionDispatcher.dispatchMediaButtonIntent(context, intent);
    }
}
