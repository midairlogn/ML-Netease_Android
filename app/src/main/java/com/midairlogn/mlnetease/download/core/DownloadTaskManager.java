package com.midairlogn.mlnetease.download.core;

import android.content.Context;

import androidx.annotation.Nullable;

import com.midairlogn.mlnetease.download.model.DownloadRequest;
import com.midairlogn.mlnetease.download.model.DownloadTask;
import com.midairlogn.mlnetease.download.model.DownloadTaskSnapshot;
import com.midairlogn.mlnetease.download.model.DownloadTaskStatus;
import com.midairlogn.mlnetease.shared.model.Song;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class DownloadTaskManager {
    private static final String TASK_ID_PREFIX = "download-task-";

    public interface Listener {
        void onDownloadTasksChanged(List<DownloadTaskSnapshot> tasks);
    }

    private static final AtomicLong ID_COUNTER = new AtomicLong(1L);
    private static volatile DownloadTaskManager instance;

    public static DownloadTaskManager getInstance(Context context) {
        if (instance == null) {
            synchronized (DownloadTaskManager.class) {
                if (instance == null) {
                    instance = new DownloadTaskManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context appContext;
    private final Object lock = new Object();
    private final List<DownloadTask> tasks = new ArrayList<>();
    private final Set<Listener> listeners = new LinkedHashSet<>();

    private DownloadTaskManager(Context appContext) {
        this.appContext = appContext;
        tasks.addAll(DownloadTaskStore.load(appContext));
        removeCompletedTasksOnLaunch();
        seedIdCounterFromLoadedTasks();
    }

    public DownloadTaskSnapshot enqueue(DownloadRequest request) {
        DownloadTask task = new DownloadTask(nextTaskId(), request);
        synchronized (lock) {
            tasks.add(task);
        }
        persistTasks();
        notifyListeners();
        return task.snapshot();
    }

    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        synchronized (lock) {
            listeners.add(listener);
        }
        listener.onDownloadTasksChanged(getTaskSnapshots());
    }

    public void removeListener(Listener listener) {
        synchronized (lock) {
            listeners.remove(listener);
        }
    }

    public List<DownloadTaskSnapshot> getTaskSnapshots() {
        synchronized (lock) {
            List<DownloadTaskSnapshot> snapshots = new ArrayList<>(tasks.size());
            for (DownloadTask task : tasks) {
                snapshots.add(task.snapshot());
            }
            snapshots.sort(snapshotComparator());
            return snapshots;
        }
    }

    @Nullable
    public DownloadTask getTask(String taskId) {
        synchronized (lock) {
            for (DownloadTask task : tasks) {
                if (task.id.equals(taskId)) {
                    return task;
                }
            }
            return null;
        }
    }

    @Nullable
    public DownloadTask getActiveTask() {
        synchronized (lock) {
            for (DownloadTask task : tasks) {
                if (task.status == DownloadTaskStatus.ACTIVE) {
                    return task;
                }
            }
            return null;
        }
    }

    @Nullable
    public DownloadTask getNextWaitingTask() {
        synchronized (lock) {
            for (DownloadTask task : tasks) {
                if (task.status == DownloadTaskStatus.WAITING) {
                    return task;
                }
            }
            return null;
        }
    }

    public boolean hasWaitingOrActiveWork() {
        synchronized (lock) {
            for (DownloadTask task : tasks) {
                if (task.status == DownloadTaskStatus.WAITING || task.status == DownloadTaskStatus.ACTIVE) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean activateTask(String taskId) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null || task.status == DownloadTaskStatus.CANCELLED || task.status == DownloadTaskStatus.COMPLETED || task.status == DownloadTaskStatus.FAILED) {
                return false;
            }
            for (DownloadTask candidate : tasks) {
                if (candidate == task || candidate.status != DownloadTaskStatus.ACTIVE) {
                    continue;
                }
                return false;
            }
            task.status = DownloadTaskStatus.ACTIVE;
            task.pauseRequested = false;
            task.cancelRequested = false;
            if (task.startedAt == 0L) {
                task.startedAt = System.currentTimeMillis();
            }
            if (task.pausedAt > 0L) {
                task.totalPausedDuration += Math.max(0L, System.currentTimeMillis() - task.pausedAt);
                task.pausedAt = 0L;
            }
            task.updatedAt = System.currentTimeMillis();
            if (task.statusMessage == null || task.statusMessage.trim().isEmpty()) {
                task.statusMessage = "Preparing download";
            }
        }
        persistTasks();
        notifyListeners();
        return true;
    }

    public void requestPause(String taskId) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null || task.status != DownloadTaskStatus.ACTIVE) {
                return;
            }
            task.pauseRequested = true;
            task.statusMessage = "Pausing after current step";
            task.updatedAt = System.currentTimeMillis();
        }
        persistTasks();
        notifyListeners();
    }

    public void markPaused(String taskId, String message) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null || task.status.isTerminal()) {
                return;
            }
            task.status = DownloadTaskStatus.PAUSED;
            task.pauseRequested = false;
            task.pausedAt = System.currentTimeMillis();
            task.currentSongBytesDownloaded = 0L;
            task.currentSongBytesTotal = -1L;
            task.statusMessage = safeMessage(message, "Paused");
            task.etaMillis = -1L;
            task.updatedAt = System.currentTimeMillis();
        }
        persistTasks();
        notifyListeners();
    }

    public void resumeTask(String taskId) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null || task.status != DownloadTaskStatus.PAUSED) {
                return;
            }
            task.status = DownloadTaskStatus.WAITING;
            task.pauseRequested = false;
            task.cancelRequested = false;
            task.statusMessage = "Queued to resume";
            task.updatedAt = System.currentTimeMillis();
        }
        persistTasks();
        notifyListeners();
    }

    public void cancelTask(String taskId) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null || task.status.isTerminal()) {
                return;
            }
            task.cancelRequested = true;
            task.pauseRequested = false;
            if (task.status == DownloadTaskStatus.WAITING || task.status == DownloadTaskStatus.PAUSED) {
                task.status = DownloadTaskStatus.CANCELLED;
                task.statusMessage = "Cancelled";
                task.currentSongBytesDownloaded = 0L;
                task.currentSongBytesTotal = -1L;
                task.etaMillis = -1L;
            } else {
                task.statusMessage = "Cancelling";
            }
            task.updatedAt = System.currentTimeMillis();
        }
        persistTasks();
        notifyListeners();
    }

    public void retryTask(String taskId) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null || !task.status.isTerminal()) {
                return;
            }
            task.status = DownloadTaskStatus.WAITING;
            task.completedCount = 0;
            task.successCount = 0;
            task.failedCount = 0;
            task.currentSongIndex = -1;
            task.progressPercent = 0;
            task.currentSongBytesDownloaded = 0L;
            task.currentSongBytesTotal = -1L;
            task.startedAt = 0L;
            task.updatedAt = System.currentTimeMillis();
            task.pausedAt = 0L;
            task.totalPausedDuration = 0L;
            task.etaMillis = -1L;
            task.currentSongTitle = "";
            task.statusMessage = "Queued";
            task.lastError = "";
            task.cancelRequested = false;
            task.pauseRequested = false;
            task.failedSongTitles.clear();
        }
        persistTasks();
        notifyListeners();
    }

    public void recoverInterruptedTasks() {
        boolean changed = false;
        synchronized (lock) {
            for (DownloadTask task : tasks) {
                if (task.status != DownloadTaskStatus.ACTIVE) {
                    continue;
                }
                task.status = DownloadTaskStatus.WAITING;
                task.pauseRequested = false;
                task.cancelRequested = false;
                task.currentSongIndex = Math.max(task.completedCount, 0);
                task.currentSongBytesDownloaded = 0L;
                task.currentSongBytesTotal = -1L;
                task.etaMillis = -1L;
                if (task.statusMessage == null || task.statusMessage.trim().isEmpty()
                        || "Cancelling".equals(task.statusMessage)
                        || "Pausing after current step".equals(task.statusMessage)) {
                    task.statusMessage = "Queued";
                }
                task.updatedAt = System.currentTimeMillis();
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        persistTasks();
        notifyListeners();
    }

    public void clearFinishedTasks() {
        synchronized (lock) {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                if (tasks.get(i).status.isTerminal()) {
                    tasks.remove(i);
                }
            }
        }
        persistTasks();
        notifyListeners();
    }

    public void removeTask(String taskId) {
        synchronized (lock) {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                DownloadTask task = tasks.get(i);
                if (task.id.equals(taskId) && task.status.isTerminal()) {
                    tasks.remove(i);
                }
            }
        }
        persistTasks();
        notifyListeners();
    }

    public void updateTaskProgress(String taskId,
                                   int currentSongIndex,
                                   String currentSongTitle,
                                   String statusMessage,
                                   int progressPercent,
                                   long bytesDownloaded,
                                   long bytesTotal) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null || task.status.isTerminal()) {
                return;
            }
            task.currentSongIndex = currentSongIndex;
            task.currentSongTitle = currentSongTitle == null ? "" : currentSongTitle;
            task.statusMessage = safeMessage(statusMessage, task.statusMessage);
            task.progressPercent = clamp(progressPercent);
            task.currentSongBytesDownloaded = Math.max(0L, bytesDownloaded);
            task.currentSongBytesTotal = bytesTotal;
            task.etaMillis = estimateEtaLocked(task);
            task.updatedAt = System.currentTimeMillis();
        }
        persistTasks();
        notifyListeners();
    }

    public void markSongCompleted(String taskId, Song song, boolean success, @Nullable String errorMessage) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null || task.status.isTerminal()) {
                return;
            }
            task.completedCount = Math.min(task.totalCount, task.completedCount + 1);
            if (success) {
                task.successCount++;
            } else {
                task.failedCount++;
                String title = song == null || song.name == null || song.name.trim().isEmpty() ? "Unknown song" : song.name.trim();
                task.failedSongTitles.add(title);
                task.lastError = safeMessage(errorMessage, task.lastError);
            }
            task.progressPercent = task.totalCount <= 0 ? 0 : clamp((int) Math.round((task.completedCount * 100.0d) / task.totalCount));
            task.currentSongBytesDownloaded = 0L;
            task.currentSongBytesTotal = -1L;
            task.etaMillis = estimateEtaLocked(task);
            task.updatedAt = System.currentTimeMillis();
        }
        persistTasks();
        notifyListeners();
    }

    public void completeTask(String taskId) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null) {
                return;
            }
            task.status = DownloadTaskStatus.COMPLETED;
            task.progressPercent = 100;
            task.statusMessage = String.format(Locale.US, "%d/%d downloaded", task.successCount, task.totalCount);
            task.currentSongBytesDownloaded = 0L;
            task.currentSongBytesTotal = -1L;
            task.etaMillis = 0L;
            task.cancelRequested = false;
            task.pauseRequested = false;
            task.updatedAt = System.currentTimeMillis();
        }
        persistTasks();
        notifyListeners();
    }

    public void failTask(String taskId, String errorMessage) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null) {
                return;
            }
            task.status = DownloadTaskStatus.FAILED;
            task.lastError = safeMessage(errorMessage, task.lastError);
            task.statusMessage = safeMessage(errorMessage, "Download failed");
            task.currentSongBytesDownloaded = 0L;
            task.currentSongBytesTotal = -1L;
            task.etaMillis = -1L;
            task.cancelRequested = false;
            task.pauseRequested = false;
            task.updatedAt = System.currentTimeMillis();
        }
        persistTasks();
        notifyListeners();
    }

    public void markCancelled(String taskId) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            if (task == null) {
                return;
            }
            task.status = DownloadTaskStatus.CANCELLED;
            task.statusMessage = "Cancelled";
            task.currentSongBytesDownloaded = 0L;
            task.currentSongBytesTotal = -1L;
            task.etaMillis = -1L;
            task.cancelRequested = false;
            task.pauseRequested = false;
            task.updatedAt = System.currentTimeMillis();
        }
        persistTasks();
        notifyListeners();
    }

    public boolean shouldPause(String taskId) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            return task != null && task.pauseRequested;
        }
    }

    public boolean shouldCancel(String taskId) {
        synchronized (lock) {
            DownloadTask task = findTaskLocked(taskId);
            return task != null && task.cancelRequested;
        }
    }

    public void ensureServiceRunning() {
        SongDownloadService.enqueueProcessing(appContext);
    }

    private void notifyListeners() {
        List<Listener> listenerSnapshot;
        List<DownloadTaskSnapshot> taskSnapshots = getTaskSnapshots();
        synchronized (lock) {
            listenerSnapshot = new ArrayList<>(listeners);
        }
        for (Listener listener : listenerSnapshot) {
            listener.onDownloadTasksChanged(taskSnapshots);
        }
    }

    private void persistTasks() {
        List<DownloadTask> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(tasks);
        }
        DownloadTaskStore.save(appContext, snapshot);
    }

    private DownloadTask findTaskLocked(String taskId) {
        for (DownloadTask task : tasks) {
            if (task.id.equals(taskId)) {
                return task;
            }
        }
        return null;
    }

    private static String nextTaskId() {
        return TASK_ID_PREFIX + ID_COUNTER.getAndIncrement();
    }

    private void seedIdCounterFromLoadedTasks() {
        long nextId = 1L;
        synchronized (lock) {
            for (DownloadTask task : tasks) {
                if (task == null || task.id == null || !task.id.startsWith(TASK_ID_PREFIX)) {
                    continue;
                }
                try {
                    long numericId = Long.parseLong(task.id.substring(TASK_ID_PREFIX.length()));
                    nextId = Math.max(nextId, numericId + 1L);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        while (true) {
            long current = ID_COUNTER.get();
            if (current >= nextId || ID_COUNTER.compareAndSet(current, nextId)) {
                return;
            }
        }
    }

    private void removeCompletedTasksOnLaunch() {
        boolean changed = false;
        synchronized (lock) {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                if (tasks.get(i).status == DownloadTaskStatus.COMPLETED) {
                    tasks.remove(i);
                    changed = true;
                }
            }
        }
        if (changed) {
            persistTasks();
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String safeMessage(String message, String fallback) {
        return message == null || message.trim().isEmpty() ? fallback : message.trim();
    }

    private static Comparator<DownloadTaskSnapshot> snapshotComparator() {
        return (left, right) -> {
            int leftRank = rank(left.status);
            int rightRank = rank(right.status);
            if (leftRank != rightRank) {
                return Integer.compare(leftRank, rightRank);
            }
            return Long.compare(right.updatedAt, left.updatedAt);
        };
    }

    private static int rank(DownloadTaskStatus status) {
        if (status == DownloadTaskStatus.ACTIVE) {
            return 0;
        }
        if (status == DownloadTaskStatus.WAITING || status == DownloadTaskStatus.PAUSED) {
            return 1;
        }
        return 2;
    }

    private static long estimateEtaLocked(DownloadTask task) {
        if (task.status != DownloadTaskStatus.ACTIVE || task.totalCount <= 0 || task.startedAt <= 0L) {
            return task.status == DownloadTaskStatus.COMPLETED ? 0L : -1L;
        }
        long elapsed = System.currentTimeMillis() - task.startedAt - task.totalPausedDuration;
        if (elapsed <= 1500L) {
            return -1L;
        }
        double songWeight = task.totalCount <= 0 ? 0.0d : (double) task.completedCount / task.totalCount;
        double currentWeight = task.progressPercent / 100.0d;
        if (task.totalCount > 0) {
            songWeight = (task.completedCount + Math.max(0.0d, Math.min(1.0d, currentWeight))) / task.totalCount;
        }
        if (songWeight <= 0.01d) {
            return -1L;
        }
        long projectedTotal = (long) (elapsed / songWeight);
        return Math.max(0L, projectedTotal - elapsed);
    }
}
