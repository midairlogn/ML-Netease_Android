package com.midairlogn.mlnetease.download.file;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import com.midairlogn.mlnetease.R;

public final class DownloadFolderOpener {
    private static final String ACTION_VIEW_DOWNLOADS = "android.intent.action.VIEW_DOWNLOADS";

    private DownloadFolderOpener() {
    }

    public static Intent createLaunchIntent(Context context) {
        if (context == null) {
            return null;
        }

        Intent primaryIntent = createPrimaryIntent();
        if (canResolve(context, primaryIntent)) {
            return primaryIntent;
        }

        Intent downloadsIntent = createDownloadsIntent();
        if (canResolve(context, downloadsIntent)) {
            return downloadsIntent;
        }

        Intent folderIntent = createFolderFallbackIntent();
        if (canResolve(context, folderIntent)) {
            return folderIntent;
        }

        return null;
    }

    public static boolean open(Context context) {
        if (context == null) {
            return false;
        }

        Intent[] candidateIntents = new Intent[] {
                createPrimaryIntent(),
                createDownloadsIntent(),
                createFolderFallbackIntent()
        };
        for (Intent candidateIntent : candidateIntents) {
            if (!canResolve(context, candidateIntent)) {
                continue;
            }
            try {
                context.startActivity(candidateIntent);
                return true;
            } catch (ActivityNotFoundException ignored) {
            } catch (SecurityException ignored) {
            }
        }

        Toast.makeText(context, R.string.download_open_folder_failed, Toast.LENGTH_SHORT).show();
        return false;
    }

    private static Intent createPrimaryIntent() {
        String folderPath = Environment.DIRECTORY_MUSIC + "/ML Netease";
        Uri folderUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" + Uri.encode(folderPath));
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(folderUri, "vnd.android.document/directory");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static Intent createDownloadsIntent() {
        Intent intent = new Intent(ACTION_VIEW_DOWNLOADS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static Intent createFolderFallbackIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setType("resource/folder");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static boolean canResolve(Context context, Intent intent) {
        if (context == null || intent == null) {
            return false;
        }

        PackageManager packageManager = context.getPackageManager();
        return packageManager != null && intent.resolveActivity(packageManager) != null;
    }
}
