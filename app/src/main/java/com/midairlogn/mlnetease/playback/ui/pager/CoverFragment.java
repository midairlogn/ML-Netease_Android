package com.midairlogn.mlnetease.playback.ui.pager;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.midairlogn.mlnetease.image.ImageDetailActivity;
import com.midairlogn.mlnetease.image.ImageManager;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.playback.core.MusicPlayerManager;
import com.midairlogn.mlnetease.shared.model.Song;

public class CoverFragment extends Fragment implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnFullInfoAvailableListener {

    private ImageView albumCover;
    private String currentUrl;
    private String currentEmbeddedCacheKey;
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
            if (!isPlaceholder && ((currentUrl != null && !currentUrl.isEmpty()) || currentEmbeddedCacheKey != null)) {
                Intent intent = new Intent(getContext(), ImageDetailActivity.class);
                intent.putExtra("url", currentUrl);
                if (currentEmbeddedCacheKey != null) {
                    intent.putExtra("embedded_cache_key", currentEmbeddedCacheKey);
                    Song current = MusicPlayerManager.getInstance(getContext()).getCurrentSong();
                    if (current != null && current.embeddedPicture != null) {
                        String key = ImageManager.storePendingEmbeddedBytes(current.embeddedPicture);
                        intent.putExtra("embedded_bytes_key", key);
                    }
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        });

        MusicPlayerManager manager = MusicPlayerManager.getInstance(getContext());
        manager.addOnSongChangedListener(this);
        manager.addOnFullInfoAvailableListener(this);
        Song current = manager.getCurrentSong();
        if (current != null) {
            updateCover(current);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        MusicPlayerManager.getInstance(getContext()).removeOnSongChangedListener(this);
        MusicPlayerManager.getInstance(getContext()).removeOnFullInfoAvailableListener(this);
        currentEmbeddedCacheKey = null;
    }

    @Override
    public void onSongChanged(Song song) {
        if (song != null) {
            updateCover(song);
        }
    }

    @Override
    public void onFullInfoAvailable(Song song) {
        if (song != null) {
            updateCover(song);
        }
    }

    private void updateCover(Song song) {
        if (song.embeddedPicture != null && song.embeddedPicture.length > 0) {
            currentEmbeddedCacheKey = "embedded:" + song.id;
            ImageManager.getInstance().loadEmbedded(currentEmbeddedCacheKey, song.embeddedPicture, albumCover, R.drawable.ic_ml_app_logo_foreground, true);
            currentUrl = null;
            isPlaceholder = false;
            return;
        }

        currentEmbeddedCacheKey = null;
        String urlString = song.picUrl;
        if (urlString == null || urlString.isEmpty()) {
            albumCover.setImageResource(R.drawable.ic_ml_app_logo_foreground);
            currentUrl = null;
            isPlaceholder = true;
            return;
        }

        currentUrl = urlString;
        isPlaceholder = false;

        ImageManager.getInstance().load(urlString, albumCover, R.drawable.ic_ml_app_logo_foreground);
    }
}
