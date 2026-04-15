package com.midairlogn.mlnetease;

public class DownloadTaskListItem {
    public static final int TYPE_SECTION = 0;
    public static final int TYPE_TASK = 1;

    public final int type;
    public final String title;
    public final DownloadTaskSnapshot task;

    private DownloadTaskListItem(int type, String title, DownloadTaskSnapshot task) {
        this.type = type;
        this.title = title;
        this.task = task;
    }

    public static DownloadTaskListItem section(String title) {
        return new DownloadTaskListItem(TYPE_SECTION, title, null);
    }

    public static DownloadTaskListItem task(DownloadTaskSnapshot task) {
        return new DownloadTaskListItem(TYPE_TASK, null, task);
    }
}
