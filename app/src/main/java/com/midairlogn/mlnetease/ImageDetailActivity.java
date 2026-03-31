package com.midairlogn.mlnetease;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.midairlogn.mlnetease.image.ImageManager;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ImageDetailActivity extends AppCompatActivity {

    private ZoomImageView imageView;
    private String imageUrl;
    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_detail);

        imageView = findViewById(R.id.fullscreen_image);
        imageView.setImageResource(R.drawable.ic_ml_app_logo_foreground);
        Button btnDownload = findViewById(R.id.btn_download);
        ImageButton btnClose = findViewById(R.id.btn_close);

        imageUrl = getIntent().getStringExtra("url");

        if (imageUrl != null) {
            loadImage(imageUrl);
        }

        btnClose.setOnClickListener(v -> finish());

        btnDownload.setOnClickListener(v -> downloadImage());
    }

    private void loadImage(String urlString) {
        new Thread(() -> {
            currentBitmap = ImageManager.getInstance().fetchBitmap(urlString);
            if (currentBitmap != null) {
                runOnUiThread(() -> imageView.setImageBitmap(currentBitmap));
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
}
