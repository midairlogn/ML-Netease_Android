package com.midairlogn.mlnetease.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.ByteArrayOutputStream;

public class ImageManager {

    public interface FetchCallback {
        void onResult(Bitmap bitmap);
    }

    private static final int MAX_MINI_ART_SIZE_PX = 192;
    private static final int MAX_COVER_ART_SIZE_PX = 512;
    private static final int FIXED_CACHE_SIZE_KB = 16 * 1024;

    private static ImageManager instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    private final ConcurrentHashMap<String, Boolean> activeFetches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<FetchCallback>> pendingCallbacks = new ConcurrentHashMap<>();

    private static String remoteCacheKey(String url, int maxDimensionPx) {
        return ImageUtils.normalizeUrl(url) + ":remote:" + Math.max(1, maxDimensionPx);
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
    }

    public static synchronized ImageManager getInstance() {
        if (instance == null) {
            instance = new ImageManager();
        }
        return instance;
    }

    public void fetchWithCallback(String url, FetchCallback callback) {
        if (url == null || url.isEmpty()) {
            if (callback != null) callback.onResult(null);
            return;
        }

        String cacheKey = remoteCacheKey(url, MAX_COVER_ART_SIZE_PX);

        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            if (callback != null) callback.onResult(cached);
            return;
        }

        if (callback != null) {
            pendingCallbacks.computeIfAbsent(cacheKey, k -> new ArrayList<>()).add(callback);
        }

        if (activeFetches.putIfAbsent(cacheKey, Boolean.TRUE) == null) {
            final String fetchUrl = url;
            executorService.submit(() -> {
                try {
                    Bitmap bitmap = fetchBitmap(fetchUrl, MAX_COVER_ART_SIZE_PX);
                    mainHandler.post(() -> {
                        activeFetches.remove(cacheKey);
                        List<FetchCallback> callbacks = pendingCallbacks.remove(cacheKey);
                        if (callbacks != null) {
                            for (FetchCallback cb : callbacks) {
                                cb.onResult(bitmap);
                            }
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        activeFetches.remove(cacheKey);
                        List<FetchCallback> callbacks = pendingCallbacks.remove(cacheKey);
                        if (callbacks != null) {
                            for (FetchCallback cb : callbacks) {
                                cb.onResult(null);
                            }
                        }
                    });
                }
            });
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

    private Bitmap fetchBitmap(final String url, int maxDimensionPx) {
        if (url == null || url.isEmpty()) return null;
        String cacheKey = remoteCacheKey(url, maxDimensionPx);
        Bitmap cachedBitmap = memoryCache.get(cacheKey);
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            return cachedBitmap;
        }
        return fetchBitmapInternal(url, cacheKey, maxDimensionPx);
    }

    private Bitmap fetchBitmapInternal(String url, String cacheKey, int maxDimensionPx) {
        HttpURLConnection connection = null;
        InputStream is = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            connection.setRequestProperty("Referer", "https://music.163.com/");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            is = connection.getInputStream();
            final Bitmap bitmap = decodeSampledBitmapFromStream(is, maxDimensionPx);
            if (bitmap != null) {
                memoryCache.put(cacheKey, bitmap);
            }
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
            if (connection != null) {
                connection.disconnect();
            }
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

    private byte[] readAllBytes(InputStream inputStream) throws java.io.IOException {
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
