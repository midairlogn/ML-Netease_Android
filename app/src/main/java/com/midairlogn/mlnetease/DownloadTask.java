package com.midairlogn.mlnetease;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DownloadTask {
    public final String id;
    public final DownloadRequest request;
    public final List<Song> songs;
    public final String title;
    public final String subtitle;
    public final String coverUrl;

    public DownloadTaskStatus status = DownloadTaskStatus.WAITING;
    public int totalCount;
    public int completedCount;
    public int successCount;
    public int failedCount;
    public int currentSongIndex = -1;
    public int progressPercent;
    public long currentSongBytesDownloaded;
    public long currentSongBytesTotal = -1L;
    public long createdAt;
    public long startedAt;
    public long updatedAt;
    public long pausedAt;
    public long totalPausedDuration;
    public long etaMillis = -1L;
    public String currentSongTitle = "";
    public String statusMessage = "";
    public String lastError = "";
    public boolean cancelRequested;
    public boolean pauseRequested;
    public final List<String> failedSongTitles = new ArrayList<>();

    public DownloadTask(String id, DownloadRequest request) {
        this.id = id;
        this.request = request;
        this.songs = request == null || request.songs == null
                ? new ArrayList<>()
                : new ArrayList<>(request.songs);
        this.totalCount = this.songs.size();
        this.title = buildTitle(request, this.songs);
        this.subtitle = buildSubtitle(request, this.songs);
        this.coverUrl = this.songs.isEmpty() ? "" : safe(this.songs.get(0).picUrl);
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        this.statusMessage = "Queued";
    }

    public DownloadTaskSnapshot snapshot() {
        return new DownloadTaskSnapshot(this);
    }

    public boolean isRunnable() {
        return status == DownloadTaskStatus.WAITING || status == DownloadTaskStatus.ACTIVE;
    }

    public String getProgressLabel() {
        if (totalCount <= 1) {
            return String.format(Locale.US, "%d%%", progressPercent);
        }
        return String.format(Locale.US, "%d/%d", completedCount, totalCount);
    }

    public List<String> getFailedSongTitles() {
        return Collections.unmodifiableList(failedSongTitles);
    }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("title", title);
            object.put("subtitle", subtitle);
            object.put("coverUrl", coverUrl);
            object.put("status", status.name());
            object.put("totalCount", totalCount);
            object.put("completedCount", completedCount);
            object.put("successCount", successCount);
            object.put("failedCount", failedCount);
            object.put("currentSongIndex", currentSongIndex);
            object.put("progressPercent", progressPercent);
            object.put("currentSongBytesDownloaded", currentSongBytesDownloaded);
            object.put("currentSongBytesTotal", currentSongBytesTotal);
            object.put("createdAt", createdAt);
            object.put("startedAt", startedAt);
            object.put("updatedAt", updatedAt);
            object.put("pausedAt", pausedAt);
            object.put("totalPausedDuration", totalPausedDuration);
            object.put("etaMillis", etaMillis);
            object.put("currentSongTitle", currentSongTitle);
            object.put("statusMessage", statusMessage);
            object.put("lastError", lastError);

            JSONObject requestObject = new JSONObject();
            requestObject.put("type", request.type);
            requestObject.put("title", request.title);
            JSONArray songsArray = new JSONArray();
            for (Song song : songs) {
                if (song == null) {
                    continue;
                }
                JSONObject songObject = new JSONObject();
                songObject.put("id", song.id);
                songObject.put("name", song.name);
                songObject.put("artists", song.artists);
                songObject.put("album", song.album);
                songObject.put("picUrl", song.picUrl);
                songObject.put("sourceType", song.sourceType);
                songObject.put("mediaUri", song.mediaUri);
                songObject.put("mimeType", song.mimeType);
                songObject.put("durationMs", song.durationMs);
                songsArray.put(songObject);
            }
            requestObject.put("songs", songsArray);
            object.put("request", requestObject);

            JSONArray failedTitles = new JSONArray();
            for (String failedSongTitle : failedSongTitles) {
                failedTitles.put(failedSongTitle);
            }
            object.put("failedSongTitles", failedTitles);
        } catch (Exception ignored) {
        }
        return object;
    }

    public static DownloadTask fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        try {
            JSONObject requestObject = object.optJSONObject("request");
            if (requestObject == null) {
                return null;
            }
            JSONArray songsArray = requestObject.optJSONArray("songs");
            List<Song> songs = new ArrayList<>();
            if (songsArray != null) {
                for (int i = 0; i < songsArray.length(); i++) {
                    JSONObject songObject = songsArray.optJSONObject(i);
                    if (songObject == null) {
                        continue;
                    }
                    songs.add(new Song(
                            songObject.optString("id", ""),
                            songObject.optString("name", ""),
                            songObject.optString("artists", ""),
                            songObject.optString("album", ""),
                            songObject.optString("picUrl", "")
                    ));
                    Song restoredSong = songs.get(songs.size() - 1);
                    restoredSong.sourceType = songObject.optString("sourceType", Song.SOURCE_REMOTE);
                    restoredSong.mediaUri = songObject.optString("mediaUri", "");
                    restoredSong.mimeType = songObject.optString("mimeType", "");
                    restoredSong.durationMs = Math.max(0L, songObject.optLong("durationMs", 0L));
                }
            }
            DownloadRequest request = new DownloadRequest(
                    requestObject.optString("type", DownloadRequest.TYPE_SINGLE),
                    requestObject.optString("title", ""),
                    songs
            );
            DownloadTask task = new DownloadTask(object.optString("id", "download-task-restored-" + System.currentTimeMillis()), request);
            task.status = parseStatus(object.optString("status", DownloadTaskStatus.WAITING.name()));
            task.totalCount = object.optInt("totalCount", task.totalCount);
            task.completedCount = object.optInt("completedCount", 0);
            task.successCount = object.optInt("successCount", 0);
            task.failedCount = object.optInt("failedCount", 0);
            task.currentSongIndex = object.optInt("currentSongIndex", -1);
            task.progressPercent = object.optInt("progressPercent", 0);
            task.currentSongBytesDownloaded = 0L;
            task.currentSongBytesTotal = -1L;
            task.createdAt = object.optLong("createdAt", System.currentTimeMillis());
            task.startedAt = object.optLong("startedAt", 0L);
            task.updatedAt = object.optLong("updatedAt", task.createdAt);
            task.pausedAt = task.status == DownloadTaskStatus.PAUSED ? object.optLong("pausedAt", 0L) : 0L;
            task.totalPausedDuration = object.optLong("totalPausedDuration", 0L);
            task.etaMillis = task.status == DownloadTaskStatus.COMPLETED ? 0L : -1L;
            task.currentSongTitle = object.optString("currentSongTitle", "");
            task.statusMessage = object.optString("statusMessage", defaultStatusMessage(task.status));
            task.lastError = object.optString("lastError", "");
            task.cancelRequested = false;
            task.pauseRequested = false;

            JSONArray failedTitles = object.optJSONArray("failedSongTitles");
            if (failedTitles != null) {
                for (int i = 0; i < failedTitles.length(); i++) {
                    String title = failedTitles.optString(i, "");
                    if (!title.trim().isEmpty()) {
                        task.failedSongTitles.add(title);
                    }
                }
            }
            return task;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String buildTitle(DownloadRequest request, List<Song> songs) {
        if (request != null && request.title != null && !request.title.trim().isEmpty()) {
            return request.title.trim();
        }
        if (!songs.isEmpty() && songs.get(0) != null && songs.get(0).name != null) {
            return songs.get(0).name;
        }
        return "Download";
    }

    private static String buildSubtitle(DownloadRequest request, List<Song> songs) {
        if (songs.size() <= 1) {
            Song first = songs.isEmpty() ? null : songs.get(0);
            String artist = first == null ? "" : safe(first.artists);
            return artist.isEmpty() ? "Single song" : artist;
        }
        if (request == null) {
            return songs.size() + " songs";
        }
        if (DownloadRequest.TYPE_PLAYLIST.equals(request.type)) {
            return songs.size() + " songs in playlist";
        }
        if (DownloadRequest.TYPE_ALBUM.equals(request.type)) {
            return songs.size() + " songs in album";
        }
        return songs.size() + " songs";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static DownloadTaskStatus parseStatus(String value) {
        try {
            return DownloadTaskStatus.valueOf(value);
        } catch (Exception ignored) {
            return DownloadTaskStatus.WAITING;
        }
    }

    private static String defaultStatusMessage(DownloadTaskStatus status) {
        if (status == DownloadTaskStatus.COMPLETED) {
            return "Completed";
        }
        if (status == DownloadTaskStatus.FAILED) {
            return "Failed";
        }
        if (status == DownloadTaskStatus.CANCELLED) {
            return "Cancelled";
        }
        if (status == DownloadTaskStatus.PAUSED) {
            return "Paused";
        }
        return "Queued";
    }
}
