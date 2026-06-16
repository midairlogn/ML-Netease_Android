package com.midairlogn.mlnetease.image;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.sharing.ShareCacheCleaner;
import com.midairlogn.mlnetease.sharing.ShareUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class ImageDetailActivity extends AppCompatActivity {
    private ZoomImageView imageView;
    private String imageUrl;
    private Bitmap currentBitmap;
    private volatile int imageRequestVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_detail);

        imageView = findViewById(R.id.fullscreen_image);
        imageView.setImageResource(R.drawable.ic_ml_app_logo_foreground);
        Button btnDownload = findViewById(R.id.btn_download);
        Button btnShareImage = findViewById(R.id.btn_share_image);
        ImageButton btnClose = findViewById(R.id.btn_close);

        bindIntent(getIntent());

        btnClose.setOnClickListener(v -> finish());

        btnDownload.setOnClickListener(v -> downloadImage());
        btnShareImage.setOnClickListener(v -> shareImage());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bindIntent(intent);
    }

    private void bindIntent(android.content.Intent intent) {
        imageUrl = intent.getStringExtra("url");
        String embeddedCacheKey = intent.getStringExtra("embedded_cache_key");
        byte[] imageBytes = intent.getByteArrayExtra("image_bytes");
        imageRequestVersion++;
        currentBitmap = null;
        imageView.resetZoom();
        imageView.setImageResource(R.drawable.ic_ml_app_logo_foreground);
        if (embeddedCacheKey != null && !embeddedCacheKey.isEmpty()) {
            Bitmap cached = ImageManager.getInstance().getEmbeddedBitmap(
                    embeddedCacheKey.replace(":large", "").replace(":small", ""),
                    null, true);
            if (cached != null && !cached.isRecycled()) {
                currentBitmap = cached;
                imageView.setImageBitmap(cached);
            } else if (imageBytes != null && imageBytes.length > 0) {
                currentBitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                if (currentBitmap != null) {
                    imageView.setImageBitmap(currentBitmap);
                }
            }
        } else if (imageBytes != null && imageBytes.length > 0) {
            currentBitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            if (currentBitmap != null) {
                imageView.setImageBitmap(currentBitmap);
            }
        } else if (imageUrl != null) {
            loadImage(imageUrl);
        }
    }

    private void loadImage(String urlString) {
        final int requestVersion = imageRequestVersion;
        new Thread(() -> {
            Bitmap bitmap = ImageManager.getInstance().fetchBitmap(urlString);
            if (bitmap != null) {
                runOnUiThread(() -> {
                    if (requestVersion != imageRequestVersion || !urlString.equals(imageUrl)) {
                        return;
                    }
                    currentBitmap = bitmap;
                    imageView.setImageBitmap(bitmap);
                });
            }
        }).start();
    }

    private void downloadImage() {
        if (currentBitmap == null) return;

        new Thread(() -> {
            try {
                String filename = "netease_" + System.currentTimeMillis() + ".jpg";

                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }

                ContentResolver resolver = getContentResolver();
                Uri collection;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                } else {
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }

                Uri imageUri = resolver.insert(collection, values);

                if (imageUri != null) {
                    OutputStream out = resolver.openOutputStream(imageUri);
                    currentBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                    if (out != null) {
                        out.close();
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear();
                        values.put(MediaStore.Images.Media.IS_PENDING, 0);
                        resolver.update(imageUri, values, null, null);
                    }

                    runOnUiThread(() -> Toast.makeText(this, R.string.saved_to_gallery, Toast.LENGTH_SHORT).show());
                } else {
                     runOnUiThread(() -> Toast.makeText(this, getString(R.string.hint_save_failed_title) + getString(R.string.hint_null_uri), Toast.LENGTH_SHORT).show());
                }

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.hint_save_failed_title) + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void shareImage() {
        if (currentBitmap == null) {
            Toast.makeText(this, R.string.share_not_available, Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                File shareDirectory = new File(getCacheDir(), ShareCacheCleaner.SHARED_IMAGE_DIRECTORY);
                if (!shareDirectory.exists() && !shareDirectory.mkdirs()) {
                    throw new IllegalStateException("Failed to create share directory");
                }
                ShareCacheCleaner.cleanupExpiredAsync(this);

                File imageFile = new File(shareDirectory, "cover_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream out = new FileOutputStream(imageFile)) {
                    if (!currentBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)) {
                        throw new IllegalStateException("Failed to encode image");
                    }
                    out.flush();
                }

                Uri imageUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", imageFile);
                runOnUiThread(() -> ShareUtils.shareImage(this, getString(R.string.share_cover), imageUri, "image/jpeg"));
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.share_image_failed) + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
