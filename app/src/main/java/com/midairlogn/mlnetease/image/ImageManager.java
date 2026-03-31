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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.midairlogn.mlnetease.ImageUtils;

public class ImageManager {

    private static ImageManager instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executorService;
    private final Handler mainHandler;

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
            return;
        }

        String normalizedUrl = ImageUtils.normalizeUrl(url);
        imageView.setTag(normalizedUrl);

        // Set placeholder immediately
        imageView.setImageResource(placeholderResId);

        // Check cache
        Bitmap cachedBitmap = memoryCache.get(normalizedUrl);
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap);
            return;
        }
        
        executorService.submit(() -> {
            Bitmap bitmap = fetchBitmapInternal(url);
            if (bitmap != null) {
                mainHandler.post(() -> {
                    // This is the critical part: ensure the UI is still expecting this image
                    if (normalizedUrl.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            } else {
                // If it failed, clear the tag so the next call is forced to reload
                mainHandler.post(() -> {
                    if (normalizedUrl.equals(imageView.getTag())) {
                        imageView.setTag(null);
                    }
                });
            }
        });
    }

    public Bitmap fetchBitmap(final String url) {
        if (url == null || url.isEmpty()) return null;
        String normalizedUrl = ImageUtils.normalizeUrl(url);
        Bitmap cachedBitmap = memoryCache.get(normalizedUrl);
        if (cachedBitmap != null) {
            return cachedBitmap;
        }
        return fetchBitmapInternal(url);
    }

    private Bitmap fetchBitmapInternal(String url) {
        String normalizedUrl = ImageUtils.normalizeUrl(url);
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            connection.setRequestProperty("Referer", "https://music.163.com/");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            InputStream is = connection.getInputStream();
            final Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            if (bitmap != null) {
                memoryCache.put(normalizedUrl, bitmap);
            }
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
