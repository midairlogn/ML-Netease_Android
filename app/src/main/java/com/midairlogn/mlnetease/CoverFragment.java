package com.midairlogn.mlnetease;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class CoverFragment extends Fragment implements MusicPlayerManager.OnSongChangedListener, MusicPlayerManager.OnFullInfoAvailableListener {

    private ImageView albumCover;
    private String currentUrl;
    private byte[] currentEmbeddedPicture;
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
            if (!isPlaceholder && ((currentUrl != null && !currentUrl.isEmpty()) || hasEmbeddedPicture())) {
                Intent intent = new Intent(getContext(), ImageDetailActivity.class);
                intent.putExtra("url", currentUrl);
                if (hasEmbeddedPicture()) {
                    intent.putExtra("image_bytes", currentEmbeddedPicture);
                }
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
            currentEmbeddedPicture = song.embeddedPicture;
            ImageManager.getInstance().loadEmbedded("embedded:" + song.id, song.embeddedPicture, albumCover, R.drawable.ic_ml_app_logo_foreground, true);
            currentUrl = null;
            isPlaceholder = false;
            return;
        }

        currentEmbeddedPicture = null;
        String urlString = song.picUrl;
        if (urlString == null || urlString.isEmpty()) {
            albumCover.setImageResource(R.drawable.ic_ml_app_logo_foreground);
            currentUrl = null;
            isPlaceholder = true;
            return;
        }

        // Allow update if the URL is different, OR if we were previously in a placeholder state (failed load)
        if (ImageUtils.isSameImage(urlString, currentUrl) && !isPlaceholder && albumCover.getTag() != null) {
            return;
        }

        currentUrl = urlString;
        isPlaceholder = false;

        ImageManager.getInstance().load(urlString, albumCover, R.drawable.ic_ml_app_logo_foreground);
    }

    private boolean hasEmbeddedPicture() {
        return currentEmbeddedPicture != null && currentEmbeddedPicture.length > 0;
    }
}
