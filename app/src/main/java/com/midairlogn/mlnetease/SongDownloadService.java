package com.midairlogn.mlnetease;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SongDownloadService extends Service {
    public static final String ACTION_START_DOWNLOADS = "com.midairlogn.mlnetease.action.START_DOWNLOADS";
    public static final String EXTRA_REQUEST = "extra_download_request";

    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFICATION_ID = 2001;
    private static final Pattern LRC_TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?]");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient httpClient = new OkHttpClient();

    private NotificationManager notificationManager;
    private NeteaseApi neteaseApi;
    private SettingsManager settingsManager;
    private volatile boolean isRunning;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        settingsManager = new SettingsManager(this);
        neteaseApi = new NeteaseApi(this, settingsManager);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.download_preparing), getString(R.string.download_starting), 0, 0, true));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START_DOWNLOADS.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }
        DownloadRequest request = (DownloadRequest) intent.getSerializableExtra(EXTRA_REQUEST);
        if (request == null || request.songs == null || request.songs.isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (isRunning) {
            Toast.makeText(this, R.string.download_already_running, Toast.LENGTH_SHORT).show();
            return START_STICKY;
        }
        isRunning = true;
        executor.execute(() -> processRequest(request, startId));
        return START_STICKY;
    }

    private void processRequest(DownloadRequest request, int startId) {
        int successCount = 0;
        int total = request.songs.size();
        for (int i = 0; i < total; i++) {
            Song song = request.songs.get(i);
            updateNotification(song.name, request.title, i, total, true);
            try {
                downloadSong(request, song, i + 1, total);
                successCount++;
            } catch (Exception e) {
                e.printStackTrace();
                String failedName = song == null || song.name == null || song.name.trim().isEmpty()
                        ? getString(R.string.download_failed)
                        : song.name;
                updateNotification(getString(R.string.download_failed), failedName, i + 1, total, true);
            }
        }

        int finalSuccessCount = successCount;
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.post(() -> Toast.makeText(this,
                getString(R.string.download_completed_summary, finalSuccessCount, total),
                Toast.LENGTH_LONG).show());
        notificationManager.notify(NOTIFICATION_ID, buildNotification(
                getString(R.string.download_completed),
                getString(R.string.download_completed_summary, finalSuccessCount, total),
                total,
                total,
                false));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
        stopSelf(startId);
        isRunning = false;
    }

    private void downloadSong(DownloadRequest request, Song song, int index, int total) throws Exception {
        JSONObject info = fetchSongInfo(song.id);
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

        byte[] audioBytes = fetchBytes(audioUrl);
        byte[] coverBytes = pic == null || pic.isEmpty() ? null : fetchBytes(pic);
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

        byte[] taggedBytes = audioBytes;
        if (customizationSettings.metadataEnabled) {
            taggedBytes = "mp3".equals(extension)
                    ? Mp3TagWriter.writeTaggedBytes(audioBytes, tagData)
                    : FlacTagWriter.writeTaggedBytes(audioBytes, tagData);
        }

        Song finalSong = new Song(song.id, title, artist, album, pic);
        String displayName = DownloadFileUtils.buildDisplayName(finalSong, extension, customizationSettings);
        String mimeType = "mp3".equals(extension) ? "audio/mpeg" : "audio/flac";
        String relativePath = DownloadFileUtils.buildRelativePath(request.type, request.title);
        DownloadFileUtils.saveAudio(this, taggedBytes, displayName, mimeType, relativePath);

        updateNotification(title, request.title, index, total, true);
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
                lock.wait();
            }
        }

        if (state.get() < 0) {
            throw new IOException(errorHolder[0]);
        }
        return new JSONObject(resultHolder[0]);
    }

    private byte[] fetchBytes(String url) throws Exception {
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
        String processedLyric = preprocessLyrics(lyric == null ? "" : lyric);
        if (tlyric == null || tlyric.trim().isEmpty()) {
            return resolveTimestampConflicts(normalizeLrcMilliseconds(processedLyric));
        }
        List<LyricLine> lines = LyricsUtils.mergeLyricsWithTranslation(processedLyric, preprocessLyrics(tlyric));
        StringBuilder builder = new StringBuilder();
        for (LyricLine line : lines) {
            builder.append(formatLrcTimestamp(line.time)).append(line.text);
            if (line.translation != null && !line.translation.trim().isEmpty()) {
                builder.append(" (Translation: ").append(line.translation.trim()).append(")");
            }
            builder.append('\n');
        }
        return resolveTimestampConflicts(normalizeLrcMilliseconds(builder.toString().trim()));
    }

    private String formatLrcTimestamp(long timeMs) {
        long minutes = timeMs / 60000L;
        long seconds = (timeMs % 60000L) / 1000L;
        long millis = timeMs % 1000L;
        return String.format(Locale.US, "[%02d:%02d.%03d]", minutes, seconds, millis);
    }

    private String preprocessLyrics(String lyrics) {
        if (lyrics == null || lyrics.trim().isEmpty()) {
            return "";
        }
        String[] rawLines = lyrics.split("\\n");
        List<ProcessedLyricLine> processedLines = new ArrayList<>();
        Pattern leadingBlockPattern = Pattern.compile("^((?:\\s*\\[\\d{1,2}[:.]\\d{1,2}(?:[:.]\\d{1,3})?.*])+)(.*)$");
        Pattern individualTimestampPattern = Pattern.compile("\\[\\d{1,2}[:.]\\d{1,2}(?:[:.]\\d{1,3})?.*?]");
        for (String rawLine : rawLines) {
            String trimmed = rawLine == null ? "" : rawLine.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher blockMatcher = leadingBlockPattern.matcher(trimmed);
            if (blockMatcher.matches()) {
                String timestamps = blockMatcher.group(1);
                String content = blockMatcher.group(2).trim();
                Matcher timestampMatcher = individualTimestampPattern.matcher(timestamps);
                while (timestampMatcher.find()) {
                    processedLines.add(new ProcessedLyricLine(normalizeTimestampToken(timestampMatcher.group()), content, false));
                }
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.contains(":") && !trimmed.matches("^\\[\\d.*")) {
                processedLines.add(new ProcessedLyricLine(trimmed, "", true));
                continue;
            }
            processedLines.add(new ProcessedLyricLine("", trimmed, false));
        }

        processedLines.sort((left, right) -> {
            if (left.isMetadata != right.isMetadata) {
                return left.isMetadata ? -1 : 1;
            }
            if (left.isMetadata) {
                return 0;
            }
            if (left.timeToken.isEmpty() != right.timeToken.isEmpty()) {
                return left.timeToken.isEmpty() ? 1 : -1;
            }
            return left.timeToken.compareTo(right.timeToken);
        });

        StringBuilder builder = new StringBuilder();
        for (ProcessedLyricLine line : processedLines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line.timeToken).append(line.content);
        }
        return builder.toString();
    }

    private String normalizeTimestampToken(String rawTimestamp) {
        Matcher matcher = Pattern.compile("\\[(\\d{1,2})[:.](\\d{1,2})(?:[:.](\\d{1,3}))?.*?]").matcher(rawTimestamp);
        if (!matcher.matches()) {
            return rawTimestamp;
        }
        String minutes = matcher.group(1) == null ? "00" : matcher.group(1);
        String seconds = matcher.group(2) == null ? "00" : matcher.group(2);
        String millis = matcher.group(3) == null ? "000" : matcher.group(3);
        return String.format(Locale.US, "[%02d:%02d.%03d]",
                Integer.parseInt(minutes),
                Integer.parseInt(seconds),
                Integer.parseInt(padRight(millis, 3).substring(0, 3)));
    }

    private String normalizeLrcMilliseconds(String lyrics) {
        Matcher matcher = LRC_TIMESTAMP_PATTERN.matcher(lyrics == null ? "" : lyrics);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String millis = matcher.group(3);
            if (millis == null) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String replacement = String.format(Locale.US, "[%s:%s.%s]",
                    matcher.group(1),
                    matcher.group(2),
                    padRight(millis, 3).substring(0, 3));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String resolveTimestampConflicts(String lyrics) {
        if (lyrics == null || lyrics.isEmpty()) {
            return "";
        }
        String[] lines = lyrics.split("\\n");
        StringBuilder builder = new StringBuilder();
        long previousTimestampMs = -1L;
        for (String line : lines) {
            String updatedLine = line;
            Matcher matcher = LRC_TIMESTAMP_PATTERN.matcher(line);
            if (matcher.find()) {
                int minutes = Integer.parseInt(matcher.group(1));
                int seconds = Integer.parseInt(matcher.group(2));
                int millis = matcher.group(3) == null ? 0 : Integer.parseInt(padRight(matcher.group(3), 3).substring(0, 3));
                long baseMs = minutes * 60000L + seconds * 1000L;
                long currentMs = baseMs + millis;
                if (currentMs <= previousTimestampMs) {
                    currentMs = Math.min(baseMs + 999L, previousTimestampMs + 5L);
                }
                previousTimestampMs = currentMs;
                long currentOffsetMs = Math.max(0L, Math.min(999L, currentMs - baseMs));
                String normalizedTimestamp = String.format(Locale.US, "[%02d:%02d.%03d]", minutes, seconds, currentOffsetMs);
                updatedLine = matcher.replaceFirst(Matcher.quoteReplacement(normalizedTimestamp));
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(updatedLine);
        }
        return builder.toString().trim();
    }

    private String padRight(String input, int targetLength) {
        String value = input == null ? "" : input;
        StringBuilder builder = new StringBuilder(value);
        while (builder.length() < targetLength) {
            builder.append('0');
        }
        return builder.toString();
    }

    private static final class ProcessedLyricLine {
        final String timeToken;
        final String content;
        final boolean isMetadata;

        ProcessedLyricLine(String timeToken, String content, boolean isMetadata) {
            this.timeToken = timeToken == null ? "" : timeToken;
            this.content = content == null ? "" : content;
            this.isMetadata = isMetadata;
        }
    }

    private void updateNotification(String songName, String requestTitle, int completed, int total, boolean ongoing) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(songName, requestTitle, completed, total, ongoing));
    }

    private Notification buildNotification(String title, String text, int completed, int total, boolean ongoing) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ml_app_logo_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (total > 0) {
            builder.setProgress(total, completed, false);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Song Downloads", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
