package com.midairlogn.mlnetease;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

public final class LocalAudioSongFactory {
    private LocalAudioSongFactory() {}

    @Nullable
    public static Song create(Context context, Uri uri) {
        if (context == null || uri == null) {
            return null;
        }

        LocalAudioMetadata metadata = LocalAudioMetadataReader.read(context, uri);
        String uriValue = uri.toString();
        Song song = new Song(
                "local-uri:" + uriValue.hashCode(),
                metadata.title.isEmpty() ? context.getString(R.string.unknown_title) : metadata.title,
                metadata.artist.isEmpty() ? context.getString(R.string.unknown_artist) : metadata.artist,
                metadata.album,
                "",
                Song.SOURCE_LOCAL_URI,
                uriValue,
                metadata.mimeType,
                metadata.durationMs
        );
        song.lyric = metadata.lyric;
        song.translatedLyric = metadata.translatedLyric;
        song.embeddedPicture = metadata.artworkData;
        return song;
    }
}
