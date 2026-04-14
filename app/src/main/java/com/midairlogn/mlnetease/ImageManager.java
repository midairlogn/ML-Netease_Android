package com.midairlogn.mlnetease;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.ByteArrayOutputStream;

public class ImageManager {

    private static final int MAX_MINI_ART_SIZE_PX = 192;
    private static final int MAX_COVER_ART_SIZE_PX = 1024;
    private static final int MAX_NOTIFICATION_ART_SIZE_PX = 512;

    private static ImageManager instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    private static String remoteCacheKey(String url, int maxDimensionPx) {
        return ImageUtils.normalizeUrl(url) + ":remote:" + Math.max(1, maxDimensionPx);
    }

    private ImageManager() {
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        executorService = Executors.newFixedThreadPool(4);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized ImageManager getInstance() {
        if (instance == null) {
            instance = new ImageManager();
        }
        return instance;
    }

    public void load(final String url, final ImageView imageView, int placeholderResId) {
        if (url == null || url.isEmpty()) {
            imageView.setImageResource(placeholderResId);
            imageView.setTag(null);
            return;
        }

        String normalizedUrl = ImageUtils.normalizeUrl(url);
        String cacheKey = remoteCacheKey(url, MAX_COVER_ART_SIZE_PX);

        if (normalizedUrl.equals(imageView.getTag())) {
            Bitmap cachedBitmap = memoryCache.get(cacheKey);
            if (cachedBitmap != null) {
                imageView.setImageBitmap(cachedBitmap);
            }
            return;
        }

        imageView.setTag(normalizedUrl);

        // Set placeholder immediately
        imageView.setImageResource(placeholderResId);

        // Check cache
        Bitmap cachedBitmap = memoryCache.get(cacheKey);
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap);
            return;
        }

        WeakReference<ImageView> imageViewRef = new WeakReference<>(imageView);
        executorService.submit(() -> {
            Bitmap bitmap = fetchBitmap(url, MAX_COVER_ART_SIZE_PX);
            if (bitmap != null) {
                mainHandler.post(() -> {
                    ImageView targetView = imageViewRef.get();
                    if (targetView == null) {
                        return;
                    }
                    // This is the critical part: ensure the UI is still expecting this image
                    if (normalizedUrl.equals(targetView.getTag())) {
                        targetView.setImageBitmap(bitmap);
                    }
                });
            } else {
                // If it failed, clear the tag so the next call is forced to reload
                mainHandler.post(() -> {
                    ImageView targetView = imageViewRef.get();
                    if (targetView == null) {
                        return;
                    }
                    if (normalizedUrl.equals(targetView.getTag())) {
                        targetView.setTag(null);
                    }
                });
            }
        });
    }

    public Bitmap getEmbeddedBitmap(String cacheKey, byte[] imageData, boolean large) {
        if (cacheKey == null || cacheKey.isEmpty() || imageData == null || imageData.length == 0) {
            return null;
        }
        String sizedKey = cacheKey + (large ? ":large" : ":small");
        Bitmap cachedBitmap = memoryCache.get(sizedKey);
        if (cachedBitmap != null) {
            return cachedBitmap;
        }

        Bitmap bitmap = decodeSampledBitmap(imageData, large ? MAX_COVER_ART_SIZE_PX : MAX_MINI_ART_SIZE_PX);
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
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
        } else {
            imageView.setImageResource(placeholderResId);
        }
    }

    public Bitmap fetchBitmap(final String url) {
        return fetchBitmap(url, MAX_COVER_ART_SIZE_PX);
    }

    public Bitmap fetchBitmap(final String url, int maxDimensionPx) {
        if (url == null || url.isEmpty()) return null;
        String cacheKey = remoteCacheKey(url, maxDimensionPx);
        Bitmap cachedBitmap = memoryCache.get(cacheKey);
        if (cachedBitmap != null) {
            return cachedBitmap;
        }
        return fetchBitmapInternal(url, cacheKey, maxDimensionPx);
    }

    public Bitmap fetchNotificationBitmap(final String url) {
        return fetchBitmap(url, MAX_NOTIFICATION_ART_SIZE_PX);
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
            final Bitmap bitmap = decodeSampledBitmap(readAllBytes(is), maxDimensionPx);
            if (bitmap != null) {
                memoryCache.put(cacheKey, bitmap);
            }
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Bitmap decodeSampledBitmap(byte[] imageData, int maxDimensionPx) {
        try {
            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(imageData, 0, imageData.length, boundsOptions);

            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, maxDimensionPx, maxDimensionPx);
            decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(imageData, 0, imageData.length, decodeOptions);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private byte[] readAllBytes(InputStream inputStream) throws java.io.IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, count);
        }
        return outputStream.toByteArray();
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
