package com.midairlogn.mlnetease.download.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.midairlogn.mlnetease.download.settings.DownloadCustomizationSettings;
import com.midairlogn.mlnetease.download.file.DownloadFileUtils;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.MainActivity;
import com.midairlogn.mlnetease.download.model.DownloadTask;
import com.midairlogn.mlnetease.download.model.DownloadTaskSnapshot;
import com.midairlogn.mlnetease.download.model.DownloadTaskStatus;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.Song;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

public class SongDownloadService extends Service {
    public static final String ACTION_ENSURE_RUNNING = "com.midairlogn.mlnetease.action.ENSURE_DOWNLOAD_SERVICE_RUNNING";
    public static final String ACTION_PAUSE_TASK = "com.midairlogn.mlnetease.action.PAUSE_DOWNLOAD_TASK";
    public static final String ACTION_RESUME_TASK = "com.midairlogn.mlnetease.action.RESUME_DOWNLOAD_TASK";
    public static final String ACTION_CANCEL_TASK = "com.midairlogn.mlnetease.action.CANCEL_DOWNLOAD_TASK";
    public static final String ACTION_CLEAR_FINISHED = "com.midairlogn.mlnetease.action.CLEAR_FINISHED_DOWNLOAD_TASKS";
    public static final String EXTRA_TASK_ID = "extra_download_task_id";

    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFICATION_ID = 2001;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient httpClient = new OkHttpClient();

    private NotificationManager notificationManager;
    private SettingsManager settingsManager;
    private DownloadTaskManager taskManager;
    private RemoteAudioPreparationHelper remoteAudioPreparationHelper;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean workerRunning;
    private boolean hasRecoveredInterruptedTasks;
    private volatile boolean serviceShuttingDown;

    public static void enqueueProcessing(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, SongDownloadService.class);
        intent.setAction(ACTION_ENSURE_RUNNING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    public static void pauseTask(Context context, String taskId) {
        dispatchTaskAction(context, ACTION_PAUSE_TASK, taskId);
    }

    public static void resumeTask(Context context, String taskId) {
        dispatchTaskAction(context, ACTION_RESUME_TASK, taskId);
    }

    public static void cancelTask(Context context, String taskId) {
        dispatchTaskAction(context, ACTION_CANCEL_TASK, taskId);
    }

    public static void clearFinishedTasks(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, SongDownloadService.class);
        intent.setAction(ACTION_CLEAR_FINISHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    private static void dispatchTaskAction(Context context, String action, String taskId) {
        if (context == null || taskId == null || taskId.trim().isEmpty()) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, SongDownloadService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        settingsManager = new SettingsManager(this);
        taskManager = DownloadTaskManager.getInstance(this);
        remoteAudioPreparationHelper = new RemoteAudioPreparationHelper(this, settingsManager, httpClient);
        recoverInterruptedTasksIfNeeded();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildIdleNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            maybeScheduleWorker(startId);
            return START_STICKY;
        }

        String action = intent.getAction();
        String taskId = intent.getStringExtra(EXTRA_TASK_ID);
        if (ACTION_PAUSE_TASK.equals(action)) {
            taskManager.requestPause(taskId);
        } else if (ACTION_RESUME_TASK.equals(action)) {
            taskManager.resumeTask(taskId);
        } else if (ACTION_CANCEL_TASK.equals(action)) {
            taskManager.cancelTask(taskId);
        } else if (ACTION_CLEAR_FINISHED.equals(action)) {
            taskManager.clearFinishedTasks();
        }

        updateNotificationForCurrentState();
        maybeScheduleWorker(startId);
        return START_STICKY;
    }

    private void recoverInterruptedTasksIfNeeded() {
        if (hasRecoveredInterruptedTasks) {
            return;
        }
        hasRecoveredInterruptedTasks = true;
        taskManager.recoverInterruptedTasks();
    }

    private void maybeScheduleWorker(int startId) {
        if (workerRunning) {
            updateNotificationForCurrentState();
            return;
        }
        workerRunning = true;
        executor.execute(() -> runQueue(startId));
    }

    private void runQueue(int startId) {
        try {
            while (true) {
                if (serviceShuttingDown) {
                    break;
                }
                DownloadTask activeTask = taskManager.getActiveTask();
                if (activeTask == null) {
                    activeTask = taskManager.getNextWaitingTask();
                    if (activeTask == null) {
                        break;
                    }
                    taskManager.activateTask(activeTask.id);
                    updateNotificationForCurrentState();
                }

                if (taskManager.shouldCancel(activeTask.id)) {
                    taskManager.markCancelled(activeTask.id);
                    updateNotificationForCurrentState();
                    continue;
                }

                try {
                    processTask(activeTask);
                } catch (ServiceStoppingException ignored) {
                    break;
                } catch (PausedTaskException ignored) {
                    if (serviceShuttingDown) {
                        break;
                    }
                    taskManager.markPaused(activeTask.id, getString(R.string.download_paused));
                } catch (CancelledTaskException ignored) {
                    if (serviceShuttingDown) {
                        break;
                    }
                    taskManager.markCancelled(activeTask.id);
                } catch (Exception e) {
                    if (serviceShuttingDown) {
                        break;
                    }
                    e.printStackTrace();
                    taskManager.failTask(activeTask.id, messageOrFallback(e, getString(R.string.download_failed)));
                }

                updateNotificationForCurrentState();
            }
        } finally {
            workerRunning = false;
            if (serviceShuttingDown) {
                taskManager.recoverInterruptedTasks();
            }
            updateNotificationForCurrentState();
            if (!taskManager.hasWaitingOrActiveWork()) {
                stopSelf(startId);
            }
        }
    }

    private void processTask(DownloadTask task) throws Exception {
        if (task == null) {
            return;
        }
        throwIfServiceStopping();
        int total = task.songs.size();
        if (total == 0) {
            taskManager.failTask(task.id, "No songs in task");
            return;
        }

        for (int i = task.completedCount; i < total; i++) {
            throwIfCancelled(task.id);
            throwIfPauseRequested(task.id);

            Song song = task.songs.get(i);
            String displayTitle = song == null || song.name == null || song.name.trim().isEmpty()
                    ? getString(R.string.download)
                    : song.name.trim();
            taskManager.updateTaskProgress(
                    task.id,
                    i,
                    displayTitle,
                    getString(R.string.download_fetching_metadata),
                    computeOverallProgress(task, i, 0),
                    0L,
                    -1L
            );

            try {
                downloadSong(task, song, i, total);
                taskManager.markSongCompleted(task.id, song, true, null);
            } catch (PausedTaskException | CancelledTaskException e) {
                throw e;
            } catch (Exception e) {
                e.printStackTrace();
                taskManager.markSongCompleted(task.id, song, false, messageOrFallback(e, getString(R.string.download_failed)));
            }
        }

        if (taskManager.shouldCancel(task.id)) {
            taskManager.markCancelled(task.id);
            throw new CancelledTaskException();
        }
        if (task.failedCount > 0 && task.successCount == 0) {
            taskManager.failTask(task.id, getString(R.string.download_failed));
            return;
        }
        taskManager.completeTask(task.id);
        showTaskFinishedToast(task.successCount, task.totalCount);
    }

    private void downloadSong(DownloadTask task, Song song, int index, int total) throws Exception {
        String title = song == null || song.name == null || song.name.trim().isEmpty()
                ? getString(R.string.download)
                : song.name.trim();
        String quality = settingsManager.getQuality();
        String extension = DownloadFileUtils.getAudioExtensionForQuality(quality);
        String mimeType = "mp3".equals(extension) ? "audio/mpeg" : "audio/flac";
        String relativePath = DownloadFileUtils.buildRelativePath(task.request.type, task.request.title);

        PreparedAudioFile preparedAudioFile = remoteAudioPreparationHelper.prepareDownloadAudio(
                song,
                getCacheDir(),
                () -> {
                    throwIfServiceStopping();
                    throwIfCancelled(task.id);
                    throwIfPauseRequested(task.id);
                },
                new RemoteAudioPreparationHelper.ProgressListener() {
                    @Override
                    public void onFetchingMetadata(String title) {
                        taskManager.updateTaskProgress(
                                task.id,
                                index,
                                title,
                                getString(R.string.download_fetching_metadata),
                                computeOverallProgress(task, index, 0),
                                0L,
                                -1L
                        );
                    }

                    @Override
                    public void onDownloadingAudio(String title, long downloadedBytes, long totalBytes) {
                        int currentSongProgress = totalBytes > 0L
                                ? (int) Math.round((downloadedBytes * 75.0d) / totalBytes)
                                : 45;
                        taskManager.updateTaskProgress(
                                task.id,
                                index,
                                title,
                                getString(R.string.download_audio_progress),
                                computeOverallProgress(task, index, 5 + currentSongProgress),
                                downloadedBytes,
                                totalBytes
                        );
                    }

                    @Override
                    public void onFetchingCover(String title) {
                        taskManager.updateTaskProgress(
                                task.id,
                                index,
                                title,
                                getString(R.string.download_cover_progress),
                                computeOverallProgress(task, index, 82),
                                0L,
                                -1L
                        );
                    }

                    @Override
                    public void onWritingMetadata(String title) {
                        taskManager.updateTaskProgress(
                                task.id,
                                index,
                                title,
                                getString(R.string.download_writing_tags),
                                computeOverallProgress(task, index, 90),
                                0L,
                                -1L
                        );
                    }
                }
        );

        try {
            if (DownloadFileUtils.audioExists(this, preparedAudioFile.displayName, relativePath)) {
                taskManager.updateTaskProgress(
                        task.id,
                        index,
                        title,
                        getString(R.string.download_song_skipped_exists),
                        computeOverallProgress(task, index, 100),
                        0L,
                        -1L
                );
                return;
            }

            taskManager.updateTaskProgress(
                    task.id,
                    index,
                    title,
                    getString(R.string.download_saving_file),
                    computeOverallProgress(task, index, 96),
                    0L,
                    -1L
            );

            Uri savedUri = DownloadFileUtils.createPendingAudio(this, preparedAudioFile.displayName, mimeType, relativePath);
            boolean publishSuccess = false;
            try {
                DownloadFileUtils.writeAudio(this, savedUri, preparedAudioFile.file);
                throwIfServiceStopping();
                throwIfCancelled(task.id);
                throwIfPauseRequested(task.id);
                DownloadFileUtils.publishAudio(this, savedUri);
                publishSuccess = true;
            } finally {
                if (!publishSuccess) {
                    DownloadFileUtils.deleteAudio(this, savedUri);
                }
            }
        } finally {
            if (preparedAudioFile.file.exists()) {
                preparedAudioFile.file.delete();
            }
        }

        taskManager.updateTaskProgress(
                task.id,
                index,
                title,
                getString(R.string.download_song_completed),
                computeOverallProgress(task, index, 100),
                0L,
                -1L
        );
    }

    private void throwIfPauseRequested(String taskId) throws PausedTaskException {
        if (taskManager.shouldPause(taskId)) {
            throw new PausedTaskException();
        }
    }

    private void throwIfServiceStopping() throws ServiceStoppingException {
        if (serviceShuttingDown || Thread.currentThread().isInterrupted()) {
            throw new ServiceStoppingException();
        }
    }

    private void throwIfCancelled(String taskId) throws CancelledTaskException {
        if (taskManager.shouldCancel(taskId)) {
            throw new CancelledTaskException();
        }
    }

    private int computeOverallProgress(DownloadTask task, int songIndex, int currentSongProgress) {
        int total = Math.max(1, task.totalCount);
        double completedSongs = Math.max(0, songIndex);
        double current = Math.max(0, Math.min(100, currentSongProgress)) / 100.0d;
        return Math.max(0, Math.min(100, (int) Math.round(((completedSongs + current) * 100.0d) / total)));
    }

    private void updateNotificationForCurrentState() {
        List<DownloadTaskSnapshot> tasks = taskManager.getTaskSnapshots();
        Notification notification = hasRunnableTasks(tasks) ? buildQueueNotification(tasks) : buildIdleNotification();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildIdleNotification() {
        int queuedCount = 0;
        for (DownloadTaskSnapshot snapshot : taskManager.getTaskSnapshots()) {
            if (isRunnableNotificationTask(snapshot)) {
                queuedCount++;
            }
        }
        String text = queuedCount == 0
                ? getString(R.string.download_manager_idle)
                : getString(R.string.download_manager_queue_count, queuedCount);
        return baseNotificationBuilder()
                .setContentTitle(getString(R.string.download_manager_title))
                .setContentText(text)
                .setProgress(0, 0, false)
                .setOngoing(queuedCount > 0)
                .build();
    }

    private Notification buildQueueNotification(List<DownloadTaskSnapshot> tasks) {
        int total = 0;
        int current = 1;
        for (DownloadTaskSnapshot task : tasks) {
            if (!isRunnableNotificationTask(task)) {
                continue;
            }
            total++;
            if (task.status == DownloadTaskStatus.ACTIVE) {
                current = total;
            }
        }

        if (total == 0) {
            return buildIdleNotification();
        }

        DownloadTaskSnapshot active = findActiveTask(tasks);
        NotificationCompat.Builder builder = baseNotificationBuilder()
                .setContentTitle(getString(R.string.download_manager_title))
                .setContentText(getString(R.string.download_notification_tasks_progress, current, total))
                .setOngoing(true)
                .setProgress(0, 0, false);

        if (active != null && active.canPause) {
            builder.addAction(0, getString(R.string.download_pause), buildServicePendingIntent(ACTION_PAUSE_TASK, active.id, 11));
        }
        if (active != null && active.canCancel) {
            builder.addAction(0, getString(R.string.cancel), buildServicePendingIntent(ACTION_CANCEL_TASK, active.id, 13));
        }
        return builder.build();
    }

    @Nullable
    private DownloadTaskSnapshot findActiveTask(List<DownloadTaskSnapshot> tasks) {
        for (DownloadTaskSnapshot task : tasks) {
            if (task != null && task.status == DownloadTaskStatus.ACTIVE) {
                return task;
            }
        }
        return null;
    }

    private boolean hasRunnableTasks(List<DownloadTaskSnapshot> tasks) {
        for (DownloadTaskSnapshot task : tasks) {
            if (isRunnableNotificationTask(task)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRunnableNotificationTask(DownloadTaskSnapshot task) {
        return task != null && (task.status == DownloadTaskStatus.WAITING
                || task.status == DownloadTaskStatus.ACTIVE
                || task.status == DownloadTaskStatus.PAUSED);
    }

    private NotificationCompat.Builder baseNotificationBuilder() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(buildMainPendingIntent());
    }

    private PendingIntent buildMainPendingIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_DOWNLOADS);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent buildServicePendingIntent(String action, String taskId, int requestCode) {
        Intent intent = new Intent(this, SongDownloadService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Song Downloads", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void showTaskFinishedToast(int successCount, int total) {
        mainHandler.post(() -> Toast.makeText(this,
                getString(R.string.download_completed_summary, successCount, total),
                Toast.LENGTH_LONG).show());
    }

    private String messageOrFallback(Exception e, String fallback) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) {
            return fallback;
        }
        return e.getMessage().trim();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        serviceShuttingDown = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class PausedTaskException extends Exception {
    }

    private static final class CancelledTaskException extends Exception {
    }

    private static final class ServiceStoppingException extends Exception {
    }
}
