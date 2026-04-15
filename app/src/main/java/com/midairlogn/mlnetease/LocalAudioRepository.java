package com.midairlogn.mlnetease;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

public final class LocalAudioRepository {
    private LocalAudioRepository() {}

    public static List<Song> scan(Context context) {
        List<Song> songs = new ArrayList<>();
        if (context == null) {
            return songs;
        }

        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.IS_MUSIC
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + MediaStore.Audio.Media.DURATION + " >= ?";
        String[] selectionArgs = new String[]{String.valueOf(15_000)};
        String orderBy = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, selectionArgs, orderBy)) {
            if (cursor == null) {
                return songs;
            }

            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int mimeIndex = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE);

            while (cursor.moveToNext()) {
                long mediaId = cursor.getLong(idIndex);
                Uri mediaUri = ContentUris.withAppendedId(collection, mediaId);
                Song song = new Song(
                        "local-media:" + mediaId,
                        safe(cursor.getString(titleIndex)),
                        normalizeArtist(context, cursor.getString(artistIndex)),
                        safe(cursor.getString(albumIndex)),
                        "",
                        Song.SOURCE_LOCAL_MEDIASTORE,
                        mediaUri.toString(),
                        mimeIndex >= 0 ? safe(cursor.getString(mimeIndex)) : "",
                        Math.max(0L, cursor.getLong(durationIndex))
                );
                if (song.name.isEmpty()) {
                    song.name = context.getString(R.string.unknown_title);
                }
                songs.add(song);
            }
        }
        return songs;
    }

    private static String normalizeArtist(Context context, String artist) {
        String safeArtist = safe(artist);
        if (safeArtist.isEmpty() || MediaStore.UNKNOWN_STRING.equals(safeArtist)) {
            return context.getString(R.string.unknown_artist);
        }
        return safeArtist;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
