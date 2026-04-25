package com.midairlogn.mlnetease.home.shortcut;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.midairlogn.mlnetease.MainActivity;
import com.midairlogn.mlnetease.download.file.DownloadFolderOpener;

public class DownloadShortcutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!DownloadFolderOpener.open(this)) {
            Intent fallbackIntent = new Intent(this, MainActivity.class);
            fallbackIntent.setAction(AppShortcutController.ACTION_OPEN_DOWNLOADS);
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(fallbackIntent);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }
}
