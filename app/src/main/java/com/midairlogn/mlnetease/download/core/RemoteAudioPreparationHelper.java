package com.midairlogn.mlnetease.download.core;

import android.content.Context;

import com.midairlogn.mlnetease.download.file.DownloadFileUtils;
import com.midairlogn.mlnetease.download.model.DownloadTagData;
import com.midairlogn.mlnetease.download.settings.DownloadCustomizationSettings;
import com.midairlogn.mlnetease.download.tag.FlacTagWriter;
import com.midairlogn.mlnetease.download.tag.Mp3TagWriter;
import com.midairlogn.mlnetease.image.CoverUtils;
import com.midairlogn.mlnetease.network.NeteaseApi;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.Song;
import com.midairlogn.mlnetease.playback.lyrics.LyricsUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RemoteAudioPreparationHelper {
    public interface CancellationSignal {
        void throwIfCanceled() throws Exception;
    }

    public interface ProgressListener {
        void onFetchingMetadata(String title);

        void onDownloadingAudio(String title, long downloadedBytes, long totalBytes);

        void onFetchingCover(String title);

        void onWritingMetadata(String title);
    }

    public interface ExistingAudioChecker {
        boolean exists(String displayName, String relativePath);
    }

    private final Context context;
    private final SettingsManager settingsManager;
    private final NeteaseApi neteaseApi;
    private final OkHttpClient httpClient;

    public RemoteAudioPreparationHelper(Context context) {
        this(context, new SettingsManager(context.getApplicationContext()), new OkHttpClient());
    }

    public RemoteAudioPreparationHelper(Context context, SettingsManager settingsManager, OkHttpClient httpClient) {
        this.context = context.getApplicationContext();
        this.settingsManager = settingsManager;
        this.httpClient = httpClient;
        this.neteaseApi = new NeteaseApi(this.context, settingsManager);
    }

    public PreparedAudioFile prepareSharedAudio(Song song, File outputDirectory, CancellationSignal cancellationSignal,
                                                ProgressListener progressListener) throws Exception {
        return prepare(song, outputDirectory, cancellationSignal, progressListener);
    }

    public PreparedAudioFile prepareDownloadAudio(Song song, File outputDirectory, CancellationSignal cancellationSignal,
                                                  ProgressListener progressListener) throws Exception {
        return prepare(song, outputDirectory, cancellationSignal, progressListener, null, null);
    }

    public PreparedAudioFile prepareDownloadAudio(Song song, File outputDirectory, CancellationSignal cancellationSignal,
                                                  ProgressListener progressListener, String relativePath,
                                                  ExistingAudioChecker existingAudioChecker) throws Exception {
        return prepare(song, outputDirectory, cancellationSignal, progressListener, relativePath, existingAudioChecker);
    }

    private PreparedAudioFile prepare(Song song, File outputDirectory, CancellationSignal cancellationSignal,
                                      ProgressListener progressListener) throws Exception {
        return prepare(song, outputDirectory, cancellationSignal, progressListener, null, null);
    }

    private PreparedAudioFile prepare(Song song, File outputDirectory, CancellationSignal cancellationSignal,
                                      ProgressListener progressListener, String relativePath,
                                      ExistingAudioChecker existingAudioChecker) throws Exception {
        if (song == null) {
            throw new IllegalArgumentException("Song is required");
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("Output directory is required");
        }
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Failed to create temp directory");
        }

        throwIfCanceled(cancellationSignal);
        if (progressListener != null) {
            progressListener.onFetchingMetadata(song.name == null ? "" : song.name);
        }
        JSONObject info = fetchSongInfo(song.id, cancellationSignal);
        throwIfCanceled(cancellationSignal);

        String quality = settingsManager.getQuality();
        String audioUrl = info.optString("url", "");
        if (audioUrl.isEmpty() || "null".equals(audioUrl)) {
            throw new IllegalStateException("Empty audio url");
        }

        String serverExtension = resolveServerAudioExtension(info, audioUrl, quality);

        String title = info.optString("name", song.name);
        String artist = info.optString("ar_name", song.artists);
        String album = info.optString("al_name", song.album);
        String pic = info.optString("pic", song.picUrl);
        String lyric = mergeLyrics(info.optString("lyric", ""), info.optString("tlyric", ""));
        DownloadCustomizationSettings customizationSettings = settingsManager.getDownloadCustomizationSettings();
        Song finalSong = new Song(song.id, title, artist, album, pic);
        String expectedDisplayName = DownloadFileUtils.buildDisplayName(finalSong, serverExtension, customizationSettings);
        if (existingAudioChecker != null && existingAudioChecker.exists(expectedDisplayName, relativePath)) {
            return skippedExistingFile(expectedDisplayName, serverExtension);
        }

        File downloadedAudio = createOutputFile(outputDirectory, "raw_", serverExtension);
        File taggedAudio = null;
        try {
            fetchFileWithProgress(title, audioUrl, downloadedAudio, progressListener, cancellationSignal);
            throwIfCanceled(cancellationSignal);

            String actualExtension = resolveRawFileExtension(downloadedAudio, serverExtension);
            String actualQuality = info.optString("level", "");
            String displayName = DownloadFileUtils.buildDisplayName(finalSong, actualExtension, customizationSettings);
            if (!actualExtension.equals(serverExtension)
                    && existingAudioChecker != null
                    && existingAudioChecker.exists(displayName, relativePath)) {
                return skippedExistingFile(displayName, actualExtension);
            }

            if (progressListener != null) {
                progressListener.onFetchingCover(title);
            }
            byte[] coverBytes = pic == null || pic.isEmpty() ? null : fetchBytesSimple(pic, cancellationSignal);
            throwIfCanceled(cancellationSignal);
            String coverMimeType = CoverUtils.getCoverMimeType(coverBytes);
            if (coverBytes != null && coverBytes.length > 0) {
                boolean needsFlacSafeResize = !"mp3".equals(actualExtension)
                        && !FlacTagWriter.canWritePictureBlock(coverBytes, coverMimeType);
                if (customizationSettings.resizeCover || needsFlacSafeResize) {
                    coverBytes = CoverUtils.resizeCover(coverBytes);
                    coverMimeType = CoverUtils.getCoverMimeType();
                }
                if (!"mp3".equals(actualExtension) && !FlacTagWriter.canWritePictureBlock(coverBytes, coverMimeType)) {
                    coverBytes = null;
                    coverMimeType = null;
                }
            }

            DownloadTagData tagData = new DownloadTagData();
            if (customizationSettings.metadataEnabled) {
                tagData.title = customizationSettings.writeTitle ? title : null;
                tagData.artist = customizationSettings.writeArtist ? artist : null;
                tagData.album = customizationSettings.writeAlbum ? album : null;
                tagData.lyrics = customizationSettings.writeLyrics ? lyric : null;
                tagData.coverData = customizationSettings.writeCover ? coverBytes : null;
                tagData.coverMimeType = customizationSettings.writeCover ? coverMimeType : null;
                if (customizationSettings.writeExtra) {
                    tagData.quality = actualQuality.isEmpty() ? quality : actualQuality;
                    tagData.songId = song.id;
                    tagData.comment = "Downloaded by ML Netease Android | Netease Song ID: " + song.id;
                }
                if (customizationSettings.writeVolumeMetadata) {
                    applyVolumeTagData(tagData, info);
                }
            }

            File outputAudio = downloadedAudio;
            if (customizationSettings.metadataEnabled) {
                if (progressListener != null) {
                    progressListener.onWritingMetadata(title);
                }
                taggedAudio = new File(outputDirectory, displayName);
                if (taggedAudio.exists() && !taggedAudio.delete()) {
                    throw new IOException("Failed to replace temp tagged file");
                }
                if ("mp3".equals(actualExtension)) {
                    Mp3TagWriter.writeTaggedFile(downloadedAudio, taggedAudio, tagData);
                } else {
                    FlacTagWriter.writeTaggedFile(downloadedAudio, taggedAudio, tagData);
                }
                outputAudio = taggedAudio;
            } else {
                File renamedOutput = new File(outputDirectory, displayName);
                if (renamedOutput.exists() && !renamedOutput.delete()) {
                    throw new IOException("Failed to replace temp audio file");
                }
                if (!downloadedAudio.renameTo(renamedOutput)) {
                    throw new IOException("Failed to finalize temp audio file");
                }
                outputAudio = renamedOutput;
                downloadedAudio = null;
            }

            throwIfCanceled(cancellationSignal);
            String mimeType = "mp3".equals(actualExtension) ? "audio/mpeg" : "audio/flac";
            return new PreparedAudioFile(outputAudio, displayName, mimeType, actualExtension);
        } finally {
            if (downloadedAudio != null && downloadedAudio.exists()) {
                downloadedAudio.delete();
            }
        }
    }

    private JSONObject fetchSongInfo(String songId, CancellationSignal cancellationSignal) throws Exception {
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
                throwIfCanceled(cancellationSignal);
                try {
                    lock.wait(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }

        throwIfCanceled(cancellationSignal);
        if (state.get() < 0) {
            throw new IOException(errorHolder[0]);
        }
        return new JSONObject(resultHolder[0]);
    }

    private PreparedAudioFile skippedExistingFile(String displayName, String extension) {
        String mimeType = "mp3".equals(extension) ? "audio/mpeg" : "audio/flac";
        return new PreparedAudioFile(null, displayName, mimeType, extension, true);
    }

    private void applyVolumeTagData(DownloadTagData tagData, JSONObject info) {
        if (tagData == null || info == null) {
            return;
        }
        tagData.hasGain = info.has("gain") && !info.isNull("gain");
        tagData.gainDb = tagData.hasGain ? (float) info.optDouble("gain", 0d) : 0f;
        tagData.hasPeak = info.has("peak") && !info.isNull("peak");
        tagData.peak = tagData.hasPeak ? (float) info.optDouble("peak", 0d) : 0f;
        tagData.hasClosedGain = info.has("closedGain") && !info.isNull("closedGain");
        tagData.closedGainDb = tagData.hasClosedGain ? (float) info.optDouble("closedGain", 0d) : 0f;
        tagData.hasClosedPeak = info.has("closedPeak") && !info.isNull("closedPeak");
        tagData.closedPeak = tagData.hasClosedPeak ? (float) info.optDouble("closedPeak", 0d) : 0f;
    }

    private void fetchFileWithProgress(String title, String url, File destinationFile, ProgressListener progressListener,
                                       CancellationSignal cancellationSignal) throws Exception {
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
                 FileOutputStream outputStream = new FileOutputStream(destinationFile)) {
                byte[] buffer = new byte[16 * 1024];
                long downloaded = 0L;
                int read;
                long lastUpdateAt = 0L;
                while ((read = inputStream.read(buffer)) != -1) {
                    throwIfCanceled(cancellationSignal);
                    outputStream.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (progressListener != null && (now - lastUpdateAt >= 250L || (totalBytes > 0L && downloaded >= totalBytes))) {
                        progressListener.onDownloadingAudio(title, downloaded, totalBytes);
                        lastUpdateAt = now;
                    }
                }
                outputStream.flush();
            }
        }
    }

    private byte[] fetchBytesSimple(String url, CancellationSignal cancellationSignal) throws Exception {
        throwIfCanceled(cancellationSignal);
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
        return LyricsUtils.buildMergedLrc(context, settingsManager, lyric, tlyric);
    }

    private void throwIfCanceled(CancellationSignal cancellationSignal) throws Exception {
        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();
        }
    }

    private File createOutputFile(File directory, String prefix, String extension) throws IOException {
        return File.createTempFile(prefix, "." + extension, directory);
    }

    private String resolveServerAudioExtension(JSONObject info, String audioUrl, String requestedQuality) {
        String serverType = normalizeExtension(info.optString("type", ""));
        if (isSupportedAudioExtension(serverType)) {
            return serverType;
        }

        String encodeType = normalizeExtension(info.optString("encodeType", ""));
        if (isSupportedAudioExtension(encodeType)) {
            return encodeType;
        }

        String urlExtension = extractExtensionFromUrl(audioUrl);
        if (isSupportedAudioExtension(urlExtension)) {
            return urlExtension;
        }

        return DownloadFileUtils.getAudioExtensionForQuality(requestedQuality);
    }

    private String resolveRawFileExtension(File audioFile, String fallbackExtension) throws IOException {
        byte[] header = new byte[12];
        int read;
        try (FileInputStream inputStream = new FileInputStream(audioFile)) {
            read = inputStream.read(header);
        }
        String detectedExtension = detectAudioExtension(header, read);
        return detectedExtension.isEmpty() ? fallbackExtension : detectedExtension;
    }

    private String detectAudioExtension(byte[] header, int length) {
        if (header == null || length <= 0) {
            return "";
        }
        if (length >= 4 && header[0] == 'f' && header[1] == 'L' && header[2] == 'a' && header[3] == 'C') {
            return "flac";
        }
        if (length >= 3 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            return "mp3";
        }
        if (length >= 2 && (header[0] & 0xFF) == 0xFF) {
            int second = header[1] & 0xE0;
            if (second == 0xE0) {
                return "mp3";
            }
        }
        return "";
    }

    private String extractExtensionFromUrl(String audioUrl) {
        if (audioUrl == null || audioUrl.trim().isEmpty()) {
            return "";
        }
        try {
            URI uri = new URI(audioUrl);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                return "";
            }
            int lastSlash = path.lastIndexOf('/');
            String fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
                return "";
            }
            String extension = normalizeExtension(fileName.substring(dotIndex + 1));
            return isSupportedAudioExtension(extension) ? extension : "";
        } catch (URISyntaxException e) {
            int queryIndex = audioUrl.indexOf('?');
            String path = queryIndex >= 0 ? audioUrl.substring(0, queryIndex) : audioUrl;
            int lastSlash = path.lastIndexOf('/');
            String fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
                return "";
            }
            String extension = normalizeExtension(fileName.substring(dotIndex + 1));
            return isSupportedAudioExtension(extension) ? extension : "";
        }
    }

    private String normalizeExtension(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase();
    }

    private boolean isSupportedAudioExtension(String extension) {
        return "mp3".equals(extension) || "flac".equals(extension);
    }
}
