package com.midairlogn.mlnetease;

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
}
