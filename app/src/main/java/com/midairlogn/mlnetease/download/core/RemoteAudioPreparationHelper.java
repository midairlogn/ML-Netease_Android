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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
        return prepare(song, outputDirectory, cancellationSignal, progressListener);
    }

    private PreparedAudioFile prepare(Song song, File outputDirectory, CancellationSignal cancellationSignal,
                                      ProgressListener progressListener) throws Exception {
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

        File downloadedAudio = createOutputFile(outputDirectory, "raw_", extension);
        File taggedAudio = null;
        try {
            fetchFileWithProgress(title, audioUrl, downloadedAudio, progressListener, cancellationSignal);
            throwIfCanceled(cancellationSignal);

            if (progressListener != null) {
                progressListener.onFetchingCover(title);
            }
            byte[] coverBytes = pic == null || pic.isEmpty() ? null : fetchBytesSimple(pic, cancellationSignal);
            throwIfCanceled(cancellationSignal);
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

            File outputAudio = downloadedAudio;
            if (customizationSettings.metadataEnabled) {
                if (progressListener != null) {
                    progressListener.onWritingMetadata(title);
                }
                taggedAudio = new File(outputDirectory, displayName);
                if (taggedAudio.exists() && !taggedAudio.delete()) {
                    throw new IOException("Failed to replace temp tagged file");
                }
                if ("mp3".equals(extension)) {
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
            String mimeType = "mp3".equals(extension) ? "audio/mpeg" : "audio/flac";
            return new PreparedAudioFile(outputAudio, displayName, mimeType, extension);
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
}
