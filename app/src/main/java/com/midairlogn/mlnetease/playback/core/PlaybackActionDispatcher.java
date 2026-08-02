package com.midairlogn.mlnetease.playback.core;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.midairlogn.mlnetease.shared.model.Song;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class PlaybackActionDispatcher {
    private static final AtomicLong NEXT_PAYLOAD_ID = new AtomicLong(1L);
    private static final Map<Long, Song> PENDING_SONGS = new HashMap<>();
    private static final Map<Long, List<Song>> PENDING_SONG_LISTS = new HashMap<>();

    private PlaybackActionDispatcher() {
    }

    public static void playIndex(Context context, int index) {
        Intent intent = serviceIntent(context, MusicService.ACTION_PLAY_INDEX);
        intent.putExtra(MusicService.EXTRA_PLAY_INDEX, index);
        startService(context, intent);
    }

    public static void addOrPlaySong(Context context, Song song) {
        if (song == null) {
            return;
        }
        Intent intent = serviceIntent(context, MusicService.ACTION_ADD_OR_PLAY_SONG);
        long payloadId = NEXT_PAYLOAD_ID.getAndIncrement();
        synchronized (PlaybackActionDispatcher.class) {
            PENDING_SONGS.put(payloadId, song);
        }
        intent.putExtra(MusicService.EXTRA_PLAYBACK_PAYLOAD_ID, payloadId);
        startService(context, intent);
    }

    public static void addPlaylistAndPlayFirstNew(Context context, List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        Intent intent = serviceIntent(context, MusicService.ACTION_ADD_PLAYLIST_AND_PLAY_FIRST_NEW);
        putSongsPayload(intent, songs);
        startService(context, intent);
    }

    public static void replacePlaylistAndPlay(Context context, List<Song> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        Intent intent = serviceIntent(context, MusicService.ACTION_REPLACE_PLAYLIST_AND_PLAY);
        putSongsPayload(intent, songs);
        intent.putExtra(MusicService.EXTRA_PLAY_INDEX, startIndex);
        startService(context, intent);
    }

    static Song takeSong(Intent intent) {
        long payloadId = getPayloadId(intent);
        if (payloadId == 0L) {
            return null;
        }
        synchronized (PlaybackActionDispatcher.class) {
            return PENDING_SONGS.remove(payloadId);
        }
    }

    static List<Song> takeSongs(Intent intent) {
        long payloadId = getPayloadId(intent);
        if (payloadId == 0L) {
            return new ArrayList<>();
        }
        synchronized (PlaybackActionDispatcher.class) {
            List<Song> songs = PENDING_SONG_LISTS.remove(payloadId);
            return songs == null ? new ArrayList<>() : songs;
        }
    }

    static void dropPayload(Intent intent) {
        long payloadId = getPayloadId(intent);
        if (payloadId == 0L) {
            return;
        }
        synchronized (PlaybackActionDispatcher.class) {
            PENDING_SONGS.remove(payloadId);
            PENDING_SONG_LISTS.remove(payloadId);
        }
    }

    public static void resume(Context context) {
        startService(context, serviceIntent(context, MusicService.ACTION_RESUME_CURRENT));
    }

    public static void pause(Context context) {
        startService(context, serviceIntent(context, MusicService.ACTION_PAUSE_CURRENT));
    }

    public static void togglePlayPause(Context context) {
        startService(context, serviceIntent(context, MusicService.ACTION_TOGGLE_PLAY_PAUSE));
    }

    public static void playNext(Context context) {
        startService(context, serviceIntent(context, MusicService.ACTION_PLAY_NEXT));
    }

    public static void playPrevious(Context context) {
        startService(context, serviceIntent(context, MusicService.ACTION_PLAY_PREVIOUS));
    }

    public static void dispatchMediaButtonIntent(Context context, Intent mediaButtonIntent) {
        if (mediaButtonIntent == null) {
            return;
        }
        Intent intent = new Intent(mediaButtonIntent);
        intent.setClass(context.getApplicationContext(), MusicService.class);
        startService(context, intent);
    }

    private static Intent serviceIntent(Context context, String action) {
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, MusicService.class);
        intent.setAction(action);
        return intent;
    }

    private static void putSongsPayload(Intent intent, List<Song> songs) {
        long payloadId = NEXT_PAYLOAD_ID.getAndIncrement();
        synchronized (PlaybackActionDispatcher.class) {
            PENDING_SONG_LISTS.put(payloadId, new ArrayList<>(songs));
        }
        intent.putExtra(MusicService.EXTRA_PLAYBACK_PAYLOAD_ID, payloadId);
    }

    private static long getPayloadId(Intent intent) {
        return intent == null ? 0L : intent.getLongExtra(MusicService.EXTRA_PLAYBACK_PAYLOAD_ID, 0L);
    }

    private static void startService(Context context, Intent intent) {
        Context appContext = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }
}
