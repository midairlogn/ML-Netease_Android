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

    private static final int MAX_MINI_ART_SIZE_PX = 192;
    private static final int MAX_COVER_ART_SIZE_PX = 512;
    private static final int ORIGINAL_ART_SIZE_PX = 2048;
    private static final int THUMBNAIL_SIZE_PX = 96;
    private static final int FIXED_CACHE_SIZE_KB = 16 * 1024;

    private static volatile byte[] pendingEmbeddedBytes;
    private static ImageManager instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private final OkHttpClient httpClient;

    private final ConcurrentHashMap<String, Boolean> activeFetches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<FetchCallback>> pendingCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();

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
        memoryCache = new LruCache<String, Bitmap>(FIXED_CACHE_SIZE_KB) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        executorService = Executors.newFixedThreadPool(2);
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

    public static void setPendingEmbeddedBytes(byte[] bytes) {
        pendingEmbeddedBytes = bytes;
    }

    public static byte[] consumePendingEmbeddedBytes() {
        byte[] bytes = pendingEmbeddedBytes;
        pendingEmbeddedBytes = null;
        return bytes;
    }

    public void fetchWithCallback(String url, FetchCallback callback) {
        fetchBitmap(url, MAX_COVER_ART_SIZE_PX, callback);
    }

    public void fetchOriginalBitmap(String url, FetchCallback callback) {
        fetchBitmap(url, ORIGINAL_ART_SIZE_PX, callback);
    }

    private void fetchBitmap(String url, int targetSizePx, FetchCallback callback) {
        if (url == null || url.isEmpty()) {
            if (callback != null) callback.onResult(null);
            return;
        }

        String cacheKey = targetSizePx == ORIGINAL_ART_SIZE_PX
                ? originalRemoteCacheKey(url)
                : remoteCacheKey(url, targetSizePx);

        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            if (callback != null) callback.onResult(cached);
            return;
        }

        if (callback != null) {
            pendingCallbacks.computeIfAbsent(cacheKey, k -> new ArrayList<>()).add(callback);
        }

        if (activeFetches.putIfAbsent(cacheKey, Boolean.TRUE) == null) {
            cancelStaleFetches(cacheKey);

            final String fetchUrl = url;
            executorService.submit(() -> {
                Call call = null;
                try {
                    Request request = new Request.Builder()
                            .url(fetchUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                            .header("Referer", "https://music.163.com/")
                            .build();
                    call = httpClient.newCall(request);
                    activeCalls.put(cacheKey, call);

                    Response response = call.execute();
                    try {
                        if (!response.isSuccessful() || response.body() == null) {
                            notifyCallbacks(cacheKey, null);
                            return;
                        }

                        InputStream is = response.body().byteStream();
                        final Bitmap bitmap = targetSizePx == ORIGINAL_ART_SIZE_PX
                                ? decodeOriginalFromStream(is)
                                : decodeSampledBitmapFromStream(is, targetSizePx);
                        is.close();

                        if (bitmap != null) {
                            memoryCache.put(cacheKey, bitmap);
                        }
                        notifyCallbacks(cacheKey, bitmap);
                    } finally {
                        response.close();
                    }
                } catch (Exception e) {
                    notifyCallbacks(cacheKey, null);
                } finally {
                    activeCalls.remove(cacheKey);
                }
            });
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

    private Bitmap decodeOriginalFromStream(InputStream is) {
        try {
            byte[] data = readAllBytes(is);
            if (data == null || data.length == 0) return null;

            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, boundsOptions);
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                return null;
            }

            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, ORIGINAL_ART_SIZE_PX, ORIGINAL_ART_SIZE_PX);
            decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void notifyCallbacks(String cacheKey, Bitmap bitmap) {
        mainHandler.post(() -> {
            activeFetches.remove(cacheKey);
            List<FetchCallback> callbacks = pendingCallbacks.remove(cacheKey);
            if (callbacks != null) {
                for (FetchCallback cb : callbacks) {
                    cb.onResult(bitmap);
                }
            }
        });
    }

    /**
     * Cancel HTTP fetches for stale cache keys (old URLs no longer being requested).
     * This immediately terminates the OkHttp call, freeing the thread, connection,
     * and response body bytes — instead of waiting for a 5-second timeout.
     */
    private void cancelStaleFetches(String currentCacheKey) {
        for (String key : activeCalls.keySet()) {
            if (!key.equals(currentCacheKey)) {
                Call staleCall = activeCalls.remove(key);
                if (staleCall != null) {
                    staleCall.cancel();
                }
                // Also remove pending callbacks for the stale key
                List<FetchCallback> staleCallbacks = pendingCallbacks.remove(key);
                if (staleCallbacks != null) {
                    staleCallbacks.clear();
                }
            }
        }
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

    public Bitmap getCachedBitmap(String url) {
        if (url == null || url.isEmpty()) return null;
        String cacheKey = remoteCacheKey(url, MAX_COVER_ART_SIZE_PX);
        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        return null;
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

    private Bitmap decodeSampledBitmapFromStream(InputStream is, int maxDimensionPx) {
        try {
            byte[] data = readAllBytes(is);
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
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
            data = null;
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, count);
        }
        byte[] result = outputStream.toByteArray();
        outputStream.reset();
        return result;
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return Math.max(1, inSampleSize);
    }
}
