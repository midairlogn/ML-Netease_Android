package com.midairlogn.mlnetease;

import android.content.Context;
import java.util.Collections;
import java.util.List;

public final class SongDownloadStarter {
    private SongDownloadStarter() {}

    public static DownloadTaskSnapshot downloadCurrentSong(Context context, Song song) {
        if (song == null) {
            return null;
        }
        return start(context, new DownloadRequest(DownloadRequest.TYPE_SINGLE, song.name, Collections.singletonList(song)));
    }

    public static DownloadTaskSnapshot downloadList(Context context, String type, String title, List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return null;
        }
        return start(context, new DownloadRequest(type, title, songs));
    }

    private static DownloadTaskSnapshot start(Context context, DownloadRequest request) {
        Context appContext = context.getApplicationContext();
        DownloadTaskManager taskManager = DownloadTaskManager.getInstance(appContext);
        DownloadTaskSnapshot snapshot = taskManager.enqueue(request);
        SongDownloadService.enqueueProcessing(appContext);
        return snapshot;
    }
}
