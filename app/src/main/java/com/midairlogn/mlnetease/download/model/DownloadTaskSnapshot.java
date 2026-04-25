package com.midairlogn.mlnetease.download.model;

import java.util.ArrayList;
import java.util.List;

public class DownloadTaskSnapshot {
    public final String id;
    public final String title;
    public final String subtitle;
    public final String coverUrl;
    public final DownloadTaskStatus status;
    public final int totalCount;
    public final int completedCount;
    public final int successCount;
    public final int failedCount;
    public final int currentSongIndex;
    public final int progressPercent;
    public final long currentSongBytesDownloaded;
    public final long currentSongBytesTotal;
    public final long createdAt;
    public final long startedAt;
    public final long updatedAt;
    public final long etaMillis;
    public final String currentSongTitle;
    public final String statusMessage;
    public final String lastError;
    public final boolean canPause;
    public final boolean canResume;
    public final boolean canCancel;
    public final boolean canRetry;
    public final List<String> failedSongTitles;

    public DownloadTaskSnapshot(DownloadTask task) {
        id = task.id;
        title = task.title;
        subtitle = task.subtitle;
        coverUrl = task.coverUrl;
        status = task.status;
        totalCount = task.totalCount;
        completedCount = task.completedCount;
        successCount = task.successCount;
        failedCount = task.failedCount;
        currentSongIndex = task.currentSongIndex;
        progressPercent = task.progressPercent;
        currentSongBytesDownloaded = task.currentSongBytesDownloaded;
        currentSongBytesTotal = task.currentSongBytesTotal;
        createdAt = task.createdAt;
        startedAt = task.startedAt;
        updatedAt = task.updatedAt;
        etaMillis = task.etaMillis;
        currentSongTitle = task.currentSongTitle;
        statusMessage = task.statusMessage;
        lastError = task.lastError;
        canPause = task.status == DownloadTaskStatus.ACTIVE;
        canResume = task.status == DownloadTaskStatus.PAUSED;
        canCancel = !task.status.isTerminal();
        canRetry = task.status == DownloadTaskStatus.FAILED || task.status == DownloadTaskStatus.CANCELLED;
        failedSongTitles = new ArrayList<>(task.failedSongTitles);
    }
}
