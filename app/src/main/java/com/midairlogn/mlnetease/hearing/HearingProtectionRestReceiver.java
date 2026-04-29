package com.midairlogn.mlnetease.hearing;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class HearingProtectionRestReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (!HearingProtectionController.ACTION_HEARING_REST_FINISHED.equals(intent.getAction())) {
            return;
        }
        HearingProtectionController.startMusicServiceForRestCompletion(context);
    }
}
