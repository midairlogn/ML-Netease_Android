package com.midairlogn.mlnetease;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class CoverFragment extends Fragment implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnFullInfoAvailableListener {

    private ImageView albumCover;
    private String currentUrl;
    private boolean isPlaceholder = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cover, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        albumCover = view.findViewById(R.id.album_cover);

        albumCover.setOnClickListener(v -> {
            if (!isPlaceholder && currentUrl != null && !currentUrl.isEmpty()) {
                Intent intent = new Intent(getContext(), ImageDetailActivity.class);
                intent.putExtra("url", currentUrl);
                startActivity(intent);
            }
        });

        MusicPlayerManager manager = MusicPlayerManager.getInstance(getContext());
        manager.addOnSongChangedListener(this);
        manager.addOnFullInfoAvailableListener(this);
        Song current = manager.getCurrentSong();
        if (current != null) {
            updateCover(current.picUrl);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MusicPlayerManager.getInstance(getContext()).removeOnSongChangedListener(this);
        MusicPlayerManager.getInstance(getContext()).removeOnFullInfoAvailableListener(this);
    }

    @Override
    public void onSongChanged(Song song) {
        if (song != null) {
            updateCover(song.picUrl);
        }
    }

    @Override
    public void onFullInfoAvailable(Song song) {
        if (song != null) {
            updateCover(song.picUrl);
        }
    }

    private void updateCover(String urlString) {
        if (urlString != null && urlString.equals(currentUrl) && !isPlaceholder) {
            return;
        }

        currentUrl = urlString;

        // Only set placeholder if we don't have a valid cover already
        if (albumCover != null && (albumCover.getDrawable() == null || isPlaceholder || albumCover.getTag() == null || !urlString.equals(albumCover.getTag()))) {
            isPlaceholder = true;
            albumCover.setImageResource(R.drawable.ic_app_logo);
            albumCover.setTag(null);
        }

        if (urlString == null || urlString.isEmpty()) {
            return;
        }

        final String targetUrl = urlString;
        new Thread(() -> {
            try {
                URL url = new URL(targetUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154");
                connection.setRequestProperty("Referer", "https://music.163.com/");
                connection.setDoInput(true);
                connection.connect();
                InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (targetUrl.equals(currentUrl)) {
                            albumCover.setImageBitmap(bitmap);
                            albumCover.setTag(targetUrl);
                            isPlaceholder = false;
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
