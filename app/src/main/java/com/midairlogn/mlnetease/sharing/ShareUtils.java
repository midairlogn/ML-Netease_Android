package com.midairlogn.mlnetease.sharing;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.midairlogn.mlnetease.R;

public final class ShareUtils {
    private static final String NETEASE_SONG_URL = "https://music.163.com/#/song?id=%s";
    private static final String NETEASE_PLAYLIST_URL = "https://music.163.com/#/playlist?id=%s";
    private static final String NETEASE_ALBUM_URL = "https://music.163.com/#/album?id=%s";

    private ShareUtils() {
    }

    public static String buildSongUrl(String id) {
        return String.format(NETEASE_SONG_URL, sanitizeId(id));
    }

    public static String buildPlaylistUrl(String id) {
        return String.format(NETEASE_PLAYLIST_URL, sanitizeId(id));
    }

    public static String buildAlbumUrl(String id) {
        return String.format(NETEASE_ALBUM_URL, sanitizeId(id));
    }

    public static void shareText(@NonNull Context context, @NonNull String chooserTitle, @NonNull String text) {
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        context.startActivity(Intent.createChooser(intent, chooserTitle));
    }

    public static void shareAudio(@NonNull Context context, @NonNull String chooserTitle, @NonNull Uri uri, @NonNull String mimeType) {
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .setClipData(ClipData.newRawUri(context.getString(R.string.share_audio), uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, chooserTitle));
    }

    private static String sanitizeId(String id) {
        return id == null ? "" : id.trim();
    }
}
