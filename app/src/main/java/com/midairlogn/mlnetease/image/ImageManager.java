package com.midairlogn.mlnetease.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ImageManager {

    public interface FetchCallback {
        void onResult(Bitmap bitmap);
    }

    private interface EncodedFetchCallback {
        void onResult(byte[] imageData);
    }

    private static final class EncodedRequest {
        final EncodedFetchCallback callback;

        EncodedRequest(EncodedFetchCallback callback) {
            this.callback = callback;
        }
    }

    private static final class ActiveEncodedCall {
        final Object generation;
        final Call call;
        final boolean playbackOwned;

        ActiveEncodedCall(Object generation, Call call, boolean playbackOwned) {
            this.generation = generation;
            this.call = call;
            this.playbackOwned = playbackOwned;
        }
    }

    private static final int MAX_MINI_ART_SIZE_PX = 192;
    private static final int MAX_COVER_ART_SIZE_PX = 512;
    private static final int ORIGINAL_ART_SIZE_PX = 2048;
    private static final int THUMBNAIL_SIZE_PX = 96;
    private static final int MIN_CACHE_SIZE_KB = 4 * 1024;
    private static final int MAX_CACHE_SIZE_KB = 16 * 1024;
    private static final int MAX_IMAGE_DOWNLOAD_BYTES = 16 * 1024 * 1024;

    private static final ConcurrentHashMap<String, byte[]> pendingImageBytes = new ConcurrentHashMap<>();
    private static ImageManager instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final LruCache<String, byte[]> encodedMemoryCache;
    private final ExecutorService executorService;
    private final ExecutorService networkExecutorService;
    private final ExecutorService playbackNetworkExecutorService;
    private final Handler mainHandler;
    private final OkHttpClient httpClient;

    private final ConcurrentHashMap<String, Boolean> activeFetches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<FetchCallback>> pendingCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> activeEncodedGenerations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<EncodedRequest>> pendingEncodedCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveEncodedCall> activeEncodedCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> protectedEncodedFetches = new ConcurrentHashMap<>();
    private String latestPlaybackIdentity;
    private String retainedPlaybackEncodedIdentity;
    private byte[] retainedPlaybackEncodedData;
    private String latestOriginalRequestIdentity;
    private String retainedOriginalIdentity;
    private Bitmap retainedOriginalBitmap;

    private static String remoteCacheKey(String url, int maxDimensionPx) {
        return ImageUtils.normalizeUrl(url) + ":remote:" + Math.max(1, maxDimensionPx);
    }

    private static String thumbnailCacheKey(String url) {
        return ImageUtils.normalizeUrl(url) + ":thumb:" + THUMBNAIL_SIZE_PX;
    }

    private static String originalRemoteCacheKey(String url) {
        return ImageUtils.normalizeUrl(url) + ":original";
    }

    private ImageManager() {
        long maxHeapKb = Runtime.getRuntime().maxMemory() / 1024L;
        int cacheSizeKb = (int) Math.max(MIN_CACHE_SIZE_KB, Math.min(MAX_CACHE_SIZE_KB, maxHeapKb / 16L));
        int bitmapCacheSizeKb = Math.max(1, cacheSizeKb / 2);
        int encodedCacheSizeKb = Math.max(1, cacheSizeKb - bitmapCacheSizeKb);
        memoryCache = new LruCache<String, Bitmap>(bitmapCacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        encodedMemoryCache = new LruCache<String, byte[]>(encodedCacheSizeKb) {
            @Override
            protected int sizeOf(String key, byte[] imageData) {
                return Math.max(1, imageData.length / 1024);
            }
        };
        executorService = Executors.newFixedThreadPool(2);
        networkExecutorService = Executors.newFixedThreadPool(2);
        playbackNetworkExecutorService = Executors.newFixedThreadPool(2);
        mainHandler = new Handler(Looper.getMainLooper());
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    public static synchronized ImageManager getInstance() {
        if (instance == null) {
            instance = new ImageManager();
        }
        return instance;
    }

    public static synchronized void trimMemoryIfInitialized(int level) {
        if (instance != null) {
            instance.trimMemory(level);
        }
    }

    public static synchronized void clearMemoryCacheIfInitialized() {
        if (instance != null) {
            instance.clearMemoryCache();
        }
    }

    public static String storePendingEmbeddedBytes(byte[] bytes) {
        String key = java.util.UUID.randomUUID().toString();
        pendingImageBytes.put(key, bytes);
        return key;
    }

    public static byte[] consumePendingEmbeddedBytes(String key) {
        if (key == null) return null;
        return pendingImageBytes.remove(key);
    }

    public void fetchWithCallback(String url, FetchCallback callback) {
        fetchBitmap(url, MAX_COVER_ART_SIZE_PX, false, false, callback);
    }

    public void fetchPlaybackBitmap(String url, FetchCallback callback) {
        fetchBitmap(url, MAX_COVER_ART_SIZE_PX, true, true, callback);
    }

    public void fetchOriginalBitmap(String url, FetchCallback callback) {
        fetchBitmap(url, ORIGINAL_ART_SIZE_PX, true, false, callback);
    }

    public void fetchPlaybackOriginalBitmap(String url, FetchCallback callback) {
        fetchBitmap(url, ORIGINAL_ART_SIZE_PX, true, true, callback);
    }

    private void fetchBitmap(String url, int targetSizePx, boolean useOriginalSource,
                             boolean playbackRequest, FetchCallback callback) {
        if (url == null || url.isEmpty()) {
            if (callback != null) callback.onResult(null);
            return;
        }

        String sourceUrl = useOriginalSource ? ImageUtils.originalImageUrl(url) : url;
        String sourceIdentity = ImageUtils.normalizeUrl(sourceUrl);
        if (playbackRequest) {
            markPlaybackRequested(sourceIdentity);
            cancelStalePlaybackFetches(sourceIdentity);
        }
        String cacheKey = targetSizePx == ORIGINAL_ART_SIZE_PX
                ? originalRemoteCacheKey(sourceUrl)
                : remoteCacheKey(sourceUrl, targetSizePx);

        if (targetSizePx == ORIGINAL_ART_SIZE_PX) {
            markOriginalRequested(url);
            Bitmap retained = getRetainedOriginalBitmap(url);
            if (retained != null) {
                if (callback != null) callback.onResult(retained);
                return;
            }
        }

        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            if (targetSizePx == ORIGINAL_ART_SIZE_PX) {
                retainOriginalBitmapIfLatest(url, cached);
            }
            if (callback != null) callback.onResult(cached);
            return;
        }

        if (callback != null) {
            pendingCallbacks.computeIfAbsent(cacheKey, k -> new ArrayList<>()).add(callback);
        }
        if (!playbackRequest && activeEncodedGenerations.containsKey(sourceIdentity)) {
            protectedEncodedFetches.put(sourceIdentity, Boolean.TRUE);
        }

        if (activeFetches.putIfAbsent(cacheKey, Boolean.TRUE) == null) {
            fetchEncodedImage(sourceUrl, playbackRequest, imageData -> {
                if (imageData == null || imageData.length == 0) {
                    notifyCallbacks(cacheKey, null);
                    return;
                }
                executorService.submit(() -> {
                Bitmap bitmap = targetSizePx == ORIGINAL_ART_SIZE_PX
                        ? decodeOriginalFromBytes(imageData)
                        : decodeSampledBitmapFromBytes(imageData, targetSizePx);
                if (bitmap != null) {
                    memoryCache.put(cacheKey, bitmap);
                    if (targetSizePx == ORIGINAL_ART_SIZE_PX) {
                        retainOriginalBitmapIfLatest(url, bitmap);
                        releaseRetainedPlaybackEncoded(sourceIdentity);
                    }
                }
                notifyCallbacks(cacheKey, bitmap);
                });
            });
        }
    }

    private void fetchEncodedImage(String url, boolean playbackRequest, EncodedFetchCallback callback) {
        String identity = ImageUtils.normalizeUrl(url);
        byte[] retained = getRetainedPlaybackEncoded(identity);
        if (retained != null) {
            callback.onResult(retained);
            return;
        }
        byte[] cached = encodedMemoryCache.get(identity);
        if (cached != null && cached.length > 0) {
            callback.onResult(cached);
            return;
        }

        pendingEncodedCallbacks.computeIfAbsent(identity, key -> new ArrayList<>())
                .add(new EncodedRequest(callback));
        if (!playbackRequest) {
            protectedEncodedFetches.put(identity, Boolean.TRUE);
        }
        Object generation = new Object();
        if (activeEncodedGenerations.putIfAbsent(identity, generation) == null) {
            final Call call;
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                        .header("Referer", "https://music.163.com/")
                        .build();
                call = httpClient.newCall(request);
            } catch (RuntimeException e) {
                notifyEncodedCallbacks(identity, generation, null);
                return;
            }
            ActiveEncodedCall activeCall = new ActiveEncodedCall(generation, call, playbackRequest);
            activeEncodedCalls.put(identity, activeCall);
            ExecutorService networkExecutor = playbackRequest
                    ? playbackNetworkExecutorService
                    : networkExecutorService;
            networkExecutor.submit(() -> {
                byte[] imageData = null;
                try {
                    Response response = call.execute();
                    try {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().contentLength() <= MAX_IMAGE_DOWNLOAD_BYTES) {
                            imageData = readAllBytes(response.body().byteStream(), MAX_IMAGE_DOWNLOAD_BYTES);
                        }
                    } finally {
                        response.close();
                    }
                } catch (Exception ignored) {
                    imageData = null;
                }
                if (!hasDecodableImageBounds(imageData)) {
                    imageData = null;
                }

                notifyEncodedCallbacks(identity, generation, imageData);
            });
        }
    }

    private boolean hasDecodableImageBounds(byte[] imageData) {
        if (imageData == null || imageData.length == 0) return false;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(imageData, 0, imageData.length, options);
            return options.outWidth > 0 && options.outHeight > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public Bitmap decodeOriginalFromBytes(byte[] imageData) {
        if (imageData == null || imageData.length == 0) return null;

        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageData, 0, imageData.length, boundsOptions);
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, ORIGINAL_ART_SIZE_PX, ORIGINAL_ART_SIZE_PX);
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(imageData, 0, imageData.length, decodeOptions);
    }

    private void notifyCallbacks(String cacheKey, Bitmap bitmap) {
        Runnable notify = () -> {
            activeFetches.remove(cacheKey);
            List<FetchCallback> callbacks = pendingCallbacks.remove(cacheKey);
            if (callbacks != null) {
                for (FetchCallback cb : callbacks) {
                    cb.onResult(bitmap);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notify.run();
        } else {
            mainHandler.post(notify);
        }
    }

    private void notifyEncodedCallbacks(String identity, Object generation, byte[] imageData) {
        mainHandler.post(() -> {
            if (!activeEncodedGenerations.remove(identity, generation)) {
                return;
            }
            ActiveEncodedCall activeCall = activeEncodedCalls.get(identity);
            if (activeCall != null && activeCall.generation == generation) {
                activeEncodedCalls.remove(identity, activeCall);
            }
            if (imageData != null && imageData.length > 0) {
                encodedMemoryCache.put(identity, imageData);
                retainPlaybackEncodedIfLatest(identity, imageData);
            }
            protectedEncodedFetches.remove(identity);
            List<EncodedRequest> requests = pendingEncodedCallbacks.remove(identity);
            dispatchEncodedRequests(requests, imageData);
        });
    }

    private void cancelStalePlaybackFetches(String currentIdentity) {
        for (java.util.Map.Entry<String, ActiveEncodedCall> entry : activeEncodedCalls.entrySet()) {
            String identity = entry.getKey();
            ActiveEncodedCall activeCall = entry.getValue();
            if ((currentIdentity != null && identity.equals(currentIdentity))
                    || !activeCall.playbackOwned
                    || protectedEncodedFetches.containsKey(identity)) {
                continue;
            }
            if (activeEncodedGenerations.remove(identity, activeCall.generation)) {
                activeEncodedCalls.remove(identity, activeCall);
                protectedEncodedFetches.remove(identity);
                List<EncodedRequest> requests = pendingEncodedCallbacks.remove(identity);
                activeCall.call.cancel();
                dispatchEncodedRequests(requests, null);
            }
        }
    }

    private void dispatchEncodedRequests(List<EncodedRequest> requests, byte[] imageData) {
        if (requests != null) {
            for (EncodedRequest request : requests) {
                request.callback.onResult(imageData);
            }
        }
    }

    public void onPlaybackArtworkChanged(String url) {
        String identity = url == null || url.isEmpty()
                ? null
                : ImageUtils.normalizeUrl(ImageUtils.originalImageUrl(url));
        markPlaybackRequested(identity);
        cancelStalePlaybackFetches(identity);
    }

    public void load(final String url, final ImageView imageView, int placeholderResId) {
        if (url == null || url.isEmpty()) {
            imageView.setImageResource(placeholderResId);
            imageView.setTag(null);
            return;
        }

        String normalizedUrl = ImageUtils.normalizeUrl(url);
        imageView.setTag(normalizedUrl);
        imageView.setImageResource(placeholderResId);

        fetchWithCallback(url, bitmap -> {
            if (normalizedUrl.equals(imageView.getTag()) && bitmap != null && !bitmap.isRecycled()) {
                imageView.setImageBitmap(bitmap);
            }
        });
    }

    public void loadPlayback(final String url, final ImageView imageView, int placeholderResId) {
        if (url == null || url.isEmpty()) {
            imageView.setImageResource(placeholderResId);
            imageView.setTag(null);
            return;
        }

        String normalizedUrl = ImageUtils.normalizeUrl(url);
        imageView.setTag(normalizedUrl);
        imageView.setImageResource(placeholderResId);

        fetchPlaybackBitmap(url, bitmap -> {
            if (normalizedUrl.equals(imageView.getTag()) && bitmap != null && !bitmap.isRecycled()) {
                imageView.setImageBitmap(bitmap);
            }
        });
    }

    public void loadOriginal(final String url, final ImageView imageView, int placeholderResId) {
        String originalUrl = ImageUtils.originalImageUrl(url);
        if (originalUrl == null || originalUrl.isEmpty()) {
            imageView.setImageResource(placeholderResId);
            imageView.setTag(null);
            return;
        }

        String normalizedUrl = ImageUtils.normalizeUrl(originalUrl);
        imageView.setTag(normalizedUrl);
        imageView.setImageResource(placeholderResId);

        fetchPlaybackOriginalBitmap(originalUrl, bitmap -> {
            if (normalizedUrl.equals(imageView.getTag()) && bitmap != null && !bitmap.isRecycled()) {
                imageView.setImageBitmap(bitmap);
            }
        });
    }

    public Bitmap getCachedBitmap(String url) {
        if (url == null || url.isEmpty()) return null;
        String cacheKey = remoteCacheKey(url, MAX_COVER_ART_SIZE_PX);
        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        return null;
    }

    public Bitmap getCachedPlaybackBitmap(String url) {
        if (url == null || url.isEmpty()) return null;
        String cacheKey = remoteCacheKey(ImageUtils.originalImageUrl(url), MAX_COVER_ART_SIZE_PX);
        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        return null;
    }

    private synchronized Bitmap getRetainedOriginalBitmap(String url) {
        String identity = ImageUtils.normalizeUrl(ImageUtils.originalImageUrl(url));
        if (identity.equals(retainedOriginalIdentity)
                && retainedOriginalBitmap != null
                && !retainedOriginalBitmap.isRecycled()) {
            return retainedOriginalBitmap;
        }
        return null;
    }

    private synchronized void markPlaybackRequested(String identity) {
        if (!Objects.equals(identity, latestPlaybackIdentity)) {
            latestPlaybackIdentity = identity;
            retainedPlaybackEncodedIdentity = null;
            retainedPlaybackEncodedData = null;
            latestOriginalRequestIdentity = identity;
            retainedOriginalIdentity = null;
            retainedOriginalBitmap = null;
        }
    }

    private synchronized byte[] getRetainedPlaybackEncoded(String identity) {
        if (identity.equals(retainedPlaybackEncodedIdentity)) {
            return retainedPlaybackEncodedData;
        }
        return null;
    }

    private synchronized void retainPlaybackEncodedIfLatest(String identity, byte[] imageData) {
        if (identity.equals(latestPlaybackIdentity)) {
            retainedPlaybackEncodedIdentity = identity;
            retainedPlaybackEncodedData = imageData;
        }
    }

    private synchronized void releaseRetainedPlaybackEncoded(String identity) {
        if (identity.equals(retainedPlaybackEncodedIdentity)) {
            retainedPlaybackEncodedIdentity = null;
            retainedPlaybackEncodedData = null;
        }
    }

    private synchronized void markOriginalRequested(String url) {
        String identity = ImageUtils.normalizeUrl(ImageUtils.originalImageUrl(url));
        if (!Objects.equals(identity, latestOriginalRequestIdentity)) {
            latestOriginalRequestIdentity = identity;
            retainedOriginalIdentity = null;
            retainedOriginalBitmap = null;
        }
    }

    private synchronized void retainOriginalBitmapIfLatest(String url, Bitmap bitmap) {
        String identity = ImageUtils.normalizeUrl(ImageUtils.originalImageUrl(url));
        if (identity.equals(latestOriginalRequestIdentity)) {
            retainedOriginalIdentity = identity;
            retainedOriginalBitmap = bitmap;
        }
    }

    /**
     * Returns a 96×96 thumbnail for the given bitmap, cached per source URL.
     * Eliminates bitmap allocation churn during rapid song switching.
     */
    public Bitmap getThumbnail(Bitmap source, String sourceUrl) {
        if (source == null || source.isRecycled()) return null;
        if (sourceUrl == null || sourceUrl.isEmpty()) return createThumbnailUncached(source);

        String key = thumbnailCacheKey(sourceUrl);
        Bitmap cached = memoryCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        int w = source.getWidth();
        int h = source.getHeight();
        Bitmap thumb;
        if (w <= THUMBNAIL_SIZE_PX && h <= THUMBNAIL_SIZE_PX) {
            thumb = source;
        } else {
            float scale = Math.min((float) THUMBNAIL_SIZE_PX / w, (float) THUMBNAIL_SIZE_PX / h);
            int tw = Math.max(1, Math.round(w * scale));
            int th = Math.max(1, Math.round(h * scale));
            thumb = Bitmap.createScaledBitmap(source, tw, th, true);
        }
        memoryCache.put(key, thumb);
        return thumb;
    }

    /**
     * Creates an uncached thumbnail. Use only when sourceUrl is unavailable.
     */
    public Bitmap createThumbnail(Bitmap source) {
        return createThumbnailUncached(source);
    }

    private Bitmap createThumbnailUncached(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        int w = source.getWidth();
        int h = source.getHeight();
        if (w <= THUMBNAIL_SIZE_PX && h <= THUMBNAIL_SIZE_PX) {
            return source;
        }
        float scale = Math.min((float) THUMBNAIL_SIZE_PX / w, (float) THUMBNAIL_SIZE_PX / h);
        int tw = Math.max(1, Math.round(w * scale));
        int th = Math.max(1, Math.round(h * scale));
        return Bitmap.createScaledBitmap(source, tw, th, true);
    }

    public Bitmap getEmbeddedBitmap(String cacheKey, byte[] imageData, boolean large) {
        if (cacheKey == null || cacheKey.isEmpty() || imageData == null || imageData.length == 0) {
            return null;
        }
        String sizedKey = cacheKey + (large ? ":large" : ":small");
        Bitmap cachedBitmap = memoryCache.get(sizedKey);
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            return cachedBitmap;
        }

        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageData, 0, imageData.length, boundsOptions);
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return null;
        }
        int targetSize = large ? MAX_COVER_ART_SIZE_PX : MAX_MINI_ART_SIZE_PX;
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, targetSize, targetSize);
        decodeOptions.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length, decodeOptions);
        if (bitmap != null) {
            memoryCache.put(sizedKey, bitmap);
        }
        return bitmap;
    }

    public Bitmap getOriginalEmbeddedBitmap(String cacheKey, byte[] imageData) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return null;
        }

        String originalKey = cacheKey + ":original";
        Bitmap cachedBitmap = memoryCache.get(originalKey);
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            return cachedBitmap;
        }
        if (imageData == null || imageData.length == 0) {
            return null;
        }

        Bitmap bitmap = decodeOriginalFromBytes(imageData);
        if (bitmap != null) {
            memoryCache.put(originalKey, bitmap);
        }
        return bitmap;
    }

    public void fetchOriginalEmbeddedBitmap(String cacheKey, byte[] imageData, FetchCallback callback) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            if (callback != null) callback.onResult(null);
            return;
        }

        String originalKey = cacheKey + ":original";
        Bitmap cachedBitmap = memoryCache.get(originalKey);
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            if (callback != null) callback.onResult(cachedBitmap);
            return;
        }

        if (callback != null) {
            pendingCallbacks.computeIfAbsent(originalKey, k -> new ArrayList<>()).add(callback);
        }
        if (activeFetches.putIfAbsent(originalKey, Boolean.TRUE) == null) {
            if (imageData == null || imageData.length == 0) {
                notifyCallbacks(originalKey, null);
                return;
            }
            executorService.submit(() -> {
                Bitmap bitmap = getOriginalEmbeddedBitmap(cacheKey, imageData);
                notifyCallbacks(originalKey, bitmap);
            });
        }
    }

    public void loadEmbedded(String cacheKey, byte[] imageData, ImageView imageView, int placeholderResId, boolean large) {
        if (imageData == null || imageData.length == 0) {
            imageView.setImageResource(placeholderResId);
            imageView.setTag(null);
            return;
        }

        String sizedKey = cacheKey + (large ? ":large" : ":small");
        imageView.setTag(sizedKey);
        Bitmap bitmap = getEmbeddedBitmap(cacheKey, imageData, large);
        if (bitmap != null && !bitmap.isRecycled()) {
            imageView.setImageBitmap(bitmap);
        } else {
            imageView.setImageResource(placeholderResId);
        }
    }

    private Bitmap decodeSampledBitmapFromBytes(byte[] data, int maxDimensionPx) {
        try {
            if (data == null || data.length == 0) return null;

            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, boundsOptions);
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                return null;
            }

            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, maxDimensionPx, maxDimensionPx);
            decodeOptions.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readAllBytes(InputStream inputStream, int maxBytes) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = inputStream.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new IOException("Image response exceeds size limit");
            }
            outputStream.write(buffer, 0, count);
        }
        return outputStream.toByteArray();
    }

    public void trimMemory(int level) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            memoryCache.evictAll();
            encodedMemoryCache.evictAll();
            clearRetainedOriginalBitmap();
            clearRetainedPlaybackEncoded();
        } else if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            memoryCache.trimToSize(memoryCache.maxSize() / 2);
            encodedMemoryCache.trimToSize(encodedMemoryCache.maxSize() / 2);
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            memoryCache.trimToSize(memoryCache.maxSize() / 2);
            encodedMemoryCache.trimToSize(encodedMemoryCache.maxSize() / 2);
            clearRetainedOriginalBitmap();
            clearRetainedPlaybackEncoded();
        }
    }

    public void loadOriginalEmbedded(String cacheKey, byte[] imageData, ImageView imageView, int placeholderResId) {
        if (imageData == null || imageData.length == 0) {
            imageView.setImageResource(placeholderResId);
            imageView.setTag(null);
            return;
        }

        String originalKey = cacheKey + ":original";
        imageView.setTag(originalKey);
        imageView.setImageResource(placeholderResId);
        fetchOriginalEmbeddedBitmap(cacheKey, imageData, bitmap -> {
            if (originalKey.equals(imageView.getTag()) && bitmap != null && !bitmap.isRecycled()) {
                imageView.setImageBitmap(bitmap);
            }
        });
    }

    public void clearMemoryCache() {
        memoryCache.evictAll();
        encodedMemoryCache.evictAll();
        clearRetainedOriginalBitmap();
        clearRetainedPlaybackEncoded();
    }

    private synchronized void clearRetainedOriginalBitmap() {
        retainedOriginalIdentity = null;
        retainedOriginalBitmap = null;
    }

    private synchronized void clearRetainedPlaybackEncoded() {
        retainedPlaybackEncodedIdentity = null;
        retainedPlaybackEncodedData = null;
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        while ((height / inSampleSize) > reqHeight || (width / inSampleSize) > reqWidth) {
            if (inSampleSize > Integer.MAX_VALUE / 2) {
                break;
            }
            inSampleSize *= 2;
        }

        return Math.max(1, inSampleSize);
    }
}
