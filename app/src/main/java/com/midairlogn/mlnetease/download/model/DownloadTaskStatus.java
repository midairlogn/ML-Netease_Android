package com.midairlogn.mlnetease.download.model;

public enum DownloadTaskStatus {
    WAITING,
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
