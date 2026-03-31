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
import com.midairlogn.mlnetease.image.ImageManager;
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
        if (urlString == null || urlString.isEmpty()) {
            return;
        }

        if (ImageUtils.isSameImage(urlString, currentUrl) && !isPlaceholder) {
            return;
        }

        currentUrl = urlString;
        isPlaceholder = false;

        albumCover.setImageResource(R.drawable.ic_ml_app_logo_foreground);
        albumCover.setTag(urlString);

        ImageManager.getInstance().load(urlString, albumCover);
    }
}
