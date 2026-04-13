package com.midairlogn.mlnetease;

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

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
    private NeteaseApi neteaseApi;
    private SettingsManager settingsManager;
    private DownloadTaskManager taskManager;
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
        neteaseApi = new NeteaseApi(this, settingsManager);
        taskManager = DownloadTaskManager.getInstance(this);
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
            updateNotificationForCurrentState();

            try {
                downloadSong(task, song, i, total);
                taskManager.markSongCompleted(task.id, song, true, null);
            } catch (PausedTaskException | CancelledTaskException e) {
                throw e;
            } catch (Exception e) {
                e.printStackTrace();
                taskManager.markSongCompleted(task.id, song, false, messageOrFallback(e, getString(R.string.download_failed)));
            }

            updateNotificationForCurrentState();
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
        JSONObject info = fetchSongInfo(song.id);
        throwIfServiceStopping();
        throwIfCancelled(task.id);
        throwIfPauseRequested(task.id);

        String audioUrl = info.optString("url", "");
        if (audioUrl.isEmpty() || "null".equals(audioUrl)) {
            throw new IllegalStateException("Empty audio url");
        }

        String title = info.optString("name", song.name);
        String artist = info.optString("ar_name", song.artists);
        String album = info.optString("al_name", song.album);
        String pic = info.optString("pic", song.picUrl);
        String lyric = mergeLyrics(info.optString("lyric", ""), info.optString("tlyric", ""));
        String quality = settingsManager.getQuality();
        String extension = DownloadFileUtils.getAudioExtensionForQuality(quality);
        DownloadCustomizationSettings customizationSettings = settingsManager.getDownloadCustomizationSettings();
        Song finalSong = new Song(song.id, title, artist, album, pic);
        String displayName = DownloadFileUtils.buildDisplayName(finalSong, extension, customizationSettings);
        String relativePath = DownloadFileUtils.buildRelativePath(task.request.type, task.request.title);

        if (DownloadFileUtils.audioExists(this, displayName, relativePath)) {
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
                getString(R.string.download_audio_progress),
                computeOverallProgress(task, index, 5),
                0L,
                -1L
        );
        updateNotificationForCurrentState();

        byte[] audioBytes = fetchBytesWithProgress(task, title, audioUrl, index);
        throwIfServiceStopping();
        throwIfCancelled(task.id);
        throwIfPauseRequested(task.id);

        taskManager.updateTaskProgress(
                task.id,
                index,
                title,
                getString(R.string.download_cover_progress),
                computeOverallProgress(task, index, 82),
                0L,
                -1L
        );

        byte[] coverBytes = pic == null || pic.isEmpty() ? null : fetchBytesSimple(pic);
        throwIfServiceStopping();
        if (coverBytes != null && coverBytes.length > 0) {
            coverBytes = CoverUtils.resizeCover(coverBytes);
        }

        DownloadTagData tagData = new DownloadTagData();
        if (customizationSettings.metadataEnabled) {
            tagData.title = customizationSettings.writeTitle ? title : null;
            tagData.artist = customizationSettings.writeArtist ? artist : null;
            tagData.album = customizationSettings.writeAlbum ? album : null;
            tagData.lyrics = customizationSettings.writeLyrics ? lyric : null;
            tagData.coverData = customizationSettings.writeCover ? coverBytes : null;
            tagData.coverMimeType = customizationSettings.writeCover ? CoverUtils.getCoverMimeType() : null;
            if (customizationSettings.writeExtra) {
                tagData.quality = quality;
                tagData.songId = song.id;
                tagData.comment = "Downloaded by ML Netease Android | Netease Song ID: " + song.id;
            }
        }

        taskManager.updateTaskProgress(
                task.id,
                index,
                title,
                getString(R.string.download_writing_tags),
                computeOverallProgress(task, index, 90),
                0L,
                -1L
        );

        byte[] taggedBytes = audioBytes;
        if (customizationSettings.metadataEnabled) {
            taggedBytes = "mp3".equals(extension)
                    ? Mp3TagWriter.writeTaggedBytes(audioBytes, tagData)
                    : FlacTagWriter.writeTaggedBytes(audioBytes, tagData);
        }

        throwIfServiceStopping();
        throwIfCancelled(task.id);
        throwIfPauseRequested(task.id);

        taskManager.updateTaskProgress(
                task.id,
                index,
                title,
                getString(R.string.download_saving_file),
                computeOverallProgress(task, index, 96),
                0L,
                -1L
        );

        String mimeType = "mp3".equals(extension) ? "audio/mpeg" : "audio/flac";
        Uri savedUri = DownloadFileUtils.createPendingAudio(this, displayName, mimeType, relativePath);
        boolean publishSuccess = false;
        try {
            DownloadFileUtils.writeAudio(this, savedUri, taggedBytes);
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

    private JSONObject fetchSongInfo(String songId) throws Exception {
        final Object lock = new Object();
        final AtomicInteger state = new AtomicInteger(0);
        final String[] resultHolder = new String[1];
        final String[] errorHolder = new String[1];

        neteaseApi.getSongFullInfo(songId, new NeteaseApi.ApiCallback() {
            @Override
            public void onSuccess(String result) {
                synchronized (lock) {
                    resultHolder[0] = result;
                    state.set(1);
                    lock.notifyAll();
                }
            }

            @Override
            public void onError(String error) {
                synchronized (lock) {
                    errorHolder[0] = error;
                    state.set(-1);
                    lock.notifyAll();
                }
            }
        });

        synchronized (lock) {
            while (state.get() == 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ServiceStoppingException();
                }
            }
        }

        throwIfServiceStopping();
        if (state.get() < 0) {
            throw new IOException(errorHolder[0]);
        }
        return new JSONObject(resultHolder[0]);
    }

    private byte[] fetchBytesWithProgress(DownloadTask task, String songTitle, String url, int songIndex) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://music.163.com/")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            long totalBytes = body.contentLength();
            try (InputStream inputStream = body.byteStream();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream(totalBytes > 0 && totalBytes < Integer.MAX_VALUE ? (int) totalBytes : 32 * 1024)) {
                byte[] buffer = new byte[16 * 1024];
                long downloaded = 0L;
                int read;
                long lastUpdateAt = 0L;
                throwIfServiceStopping();
                while ((read = inputStream.read(buffer)) != -1) {
                    throwIfServiceStopping();
                    throwIfCancelled(task.id);
                    if (taskManager.shouldPause(task.id)) {
                        throw new PausedTaskException();
                    }
                    outputStream.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (now - lastUpdateAt >= 250L || (totalBytes > 0L && downloaded >= totalBytes)) {
                        int currentSongProgress = totalBytes > 0L
                                ? (int) Math.round((downloaded * 75.0d) / totalBytes)
                                : 45;
                        taskManager.updateTaskProgress(
                                task.id,
                                songIndex,
                                songTitle,
                                getString(R.string.download_audio_progress),
                                computeOverallProgress(task, songIndex, 5 + currentSongProgress),
                                downloaded,
                                totalBytes
                        );
                        updateNotificationForCurrentState();
                        lastUpdateAt = now;
                    }
                }
                return outputStream.toByteArray();
            }
        }
    }

    private byte[] fetchBytesSimple(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://music.163.com/")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            return response.body().bytes();
        }
    }

    private String mergeLyrics(String lyric, String tlyric) {
        return LyricsUtils.buildMergedLrc(this, settingsManager, lyric, tlyric);
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
        DownloadTaskSnapshot active = null;
        List<DownloadTaskSnapshot> tasks = taskManager.getTaskSnapshots();
        for (DownloadTaskSnapshot snapshot : tasks) {
            if (snapshot.status == DownloadTaskStatus.ACTIVE) {
                active = snapshot;
                break;
            }
        }
        Notification notification = active == null ? buildIdleNotification() : buildTaskNotification(active);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildIdleNotification() {
        int queuedCount = 0;
        for (DownloadTaskSnapshot snapshot : taskManager.getTaskSnapshots()) {
            if (snapshot.status == DownloadTaskStatus.WAITING || snapshot.status == DownloadTaskStatus.ACTIVE) {
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

    private Notification buildTaskNotification(DownloadTaskSnapshot task) {
        NotificationCompat.Builder builder = baseNotificationBuilder()
                .setContentTitle(task.title)
                .setContentText(task.statusMessage)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(buildNotificationDetail(task)))
                .setOngoing(true)
                .setProgress(100, task.progressPercent, false);

        if (task.canPause) {
            builder.addAction(0, getString(R.string.download_pause), buildServicePendingIntent(ACTION_PAUSE_TASK, task.id, 11));
        } else if (task.canResume) {
            builder.addAction(0, getString(R.string.download_resume), buildServicePendingIntent(ACTION_RESUME_TASK, task.id, 12));
        }
        if (task.canCancel) {
            builder.addAction(0, getString(R.string.cancel), buildServicePendingIntent(ACTION_CANCEL_TASK, task.id, 13));
        }
        return builder.build();
    }

    private String buildNotificationDetail(DownloadTaskSnapshot task) {
        StringBuilder builder = new StringBuilder();
        if (task.currentSongTitle != null && !task.currentSongTitle.trim().isEmpty()) {
            builder.append(task.currentSongTitle).append("\n");
        }
        builder.append(getString(R.string.download_task_counts, task.completedCount, task.totalCount));
        if (task.etaMillis > 0L) {
            builder.append(" • ").append(getString(R.string.download_eta_format, formatDuration(task.etaMillis)));
        }
        return builder.toString();
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

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes >= 60L) {
            long hours = minutes / 60L;
            long remainingMinutes = minutes % 60L;
            return String.format(Locale.US, "%dh %02dm", hours, remainingMinutes);
        }
        return String.format(Locale.US, "%dm %02ds", minutes, seconds);
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
