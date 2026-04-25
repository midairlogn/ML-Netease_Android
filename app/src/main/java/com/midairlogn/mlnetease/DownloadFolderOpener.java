package com.midairlogn.mlnetease;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

public final class DownloadFolderOpener {
    private DownloadFolderOpener() {
    }

    public static boolean open(Context context) {
        if (context == null) {
            return false;
        }

        String folderPath = Environment.DIRECTORY_MUSIC + "/ML Netease";
        Uri folderUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" + Uri.encode(folderPath));

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(folderUri, "vnd.android.document/directory");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException ignored) {
        } catch (SecurityException ignored) {
        }

        try {
            Intent intent = new Intent("android.intent.action.VIEW_DOWNLOADS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException ignored) {
        }

        try {
            Intent fallback = new Intent(Intent.ACTION_VIEW);
            fallback.setType("resource/folder");
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
            return true;
        } catch (ActivityNotFoundException ignored) {
        } catch (Exception ignored) {
        }

        Toast.makeText(context, R.string.download_open_folder_failed, Toast.LENGTH_SHORT).show();
        return false;
    }
}
