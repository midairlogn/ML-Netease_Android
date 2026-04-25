package com.midairlogn.mlnetease.home.shortcut;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.midairlogn.mlnetease.home.model.FavouriteSong;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.home.model.HomeShortcut;
import com.midairlogn.mlnetease.network.NeteaseApi;
import com.midairlogn.mlnetease.playback.core.MusicPlayerManager;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.Song;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ShortcutPlaybackLauncher {
    public interface PlaybackCallback {
        void onStarted();

        void onError();
    }

    private ShortcutPlaybackLauncher() {
    }

    public static boolean playFavourites(Context context) {
        return playFavourites(context, null);
    }

    public static boolean playFavourites(Context context, PlaybackCallback callback) {
        if (context == null) {
            notifyError(callback);
            return false;
        }

        SettingsManager settingsManager = new SettingsManager(context);
        List<FavouriteSong> favourites = settingsManager.getFavouriteSongs();
        if (favourites.isEmpty()) {
            showToast(context, R.string.favourites_empty_hint);
            AppShortcutController.refresh(context);
            notifyError(callback);
            return false;
        }

        List<Song> songs = new ArrayList<>();
        for (FavouriteSong favourite : favourites) {
            if (favourite != null) {
                songs.add(favourite.toSong());
            }
        }

        if (songs.isEmpty()) {
            showToast(context, R.string.favourites_cleanup_removed);
            AppShortcutController.refresh(context);
            notifyError(callback);
            return false;
        }

        MusicPlayerManager.getInstance(context).addPlaylistAndPlayFirstNew(songs);
        notifyStarted(callback);
        return true;
    }

    public static boolean playHomeShortcut(Context context, HomeShortcut shortcut) {
        return playHomeShortcut(context, shortcut, null);
    }

    public static boolean playHomeShortcut(Context context, HomeShortcut shortcut, PlaybackCallback callback) {
        if (context == null || shortcut == null) {
            notifyError(callback);
            return false;
        }

        NeteaseApi neteaseApi = new NeteaseApi(context, new SettingsManager(context));
        NeteaseApi.ApiCallback apiCallback = new NeteaseApi.ApiCallback() {
            @Override
            public void onSuccess(String result) {
                List<Song> songs = parseShortcutSongs(result, shortcut);
                if (songs.isEmpty()) {
                    showToast(context, R.string.hint_no_songs_in_list);
                    notifyError(callback);
                    return;
                }
                MusicPlayerManager.getInstance(context).addPlaylistAndPlayFirstNew(songs);
                notifyStarted(callback);
            }

            @Override
            public void onError(String error) {
                showToast(context, context.getString(R.string.hint_error_title) + error);
                notifyError(callback);
            }
        };

        if (shortcut.isPlaylist()) {
            neteaseApi.playlistDetail(shortcut.id, apiCallback);
            return true;
        }
        if (shortcut.isAlbum()) {
            neteaseApi.albumDetail(shortcut.id, apiCallback);
            return true;
        }

        showToast(context, R.string.hint_shortcut_not_found);
        notifyError(callback);
        return false;
    }

    private static List<Song> parseShortcutSongs(String json, HomeShortcut shortcut) {
        List<Song> songs = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray songsArray;
            if (shortcut.isPlaylist()) {
                songsArray = root.optJSONArray("songs");
            } else {
                JSONObject album = root.optJSONObject("album");
                songsArray = album == null ? null : album.optJSONArray("songs");
            }
            if (songsArray == null) {
                return songs;
            }
            for (int i = 0; i < songsArray.length(); i++) {
                JSONObject obj = songsArray.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                String id = String.valueOf(obj.opt("id"));
                String name = obj.optString("name");
                String artists = obj.optString("artists");
                String album = obj.optString("album");
                String picUrl = obj.optString("picUrl");
                if (id == null || id.trim().isEmpty() || name == null || name.trim().isEmpty()) {
                    continue;
                }
                songs.add(new Song(id, name, artists, album, picUrl));
            }
        } catch (Exception ignored) {
        }
        return songs;
    }

    private static void notifyStarted(PlaybackCallback callback) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(callback::onStarted);
    }

    private static void notifyError(PlaybackCallback callback) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(callback::onError);
    }

    private static void showToast(Context context, int stringRes) {
        showToast(context, context.getString(stringRes));
    }

    private static void showToast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
