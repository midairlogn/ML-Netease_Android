package com.midairlogn.mlnetease;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Collections;
import java.util.List;

public final class SongDownloadStarter {
    private SongDownloadStarter() {}

    public static void downloadCurrentSong(Context context, Song song) {
        if (song == null) {
            return;
        }
        start(context, new DownloadRequest(DownloadRequest.TYPE_SINGLE, song.name, Collections.singletonList(song)));
    }

    public static void downloadList(Context context, String type, String title, List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        start(context, new DownloadRequest(type, title, songs));
    }

    private static void start(Context context, DownloadRequest request) {
        Intent intent = new Intent(context, SongDownloadService.class);
        intent.setAction(SongDownloadService.ACTION_START_DOWNLOADS);
        intent.putExtra(SongDownloadService.EXTRA_REQUEST, request);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
