package com.midairlogn.mlnetease;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LocalFragment extends Fragment {
    private SongAdapter adapter;
    private TextView textStatus;
    private TextView textEmpty;
    private View emptyLayout;
    private View permissionLayout;
    private Button btnScan;
    private Button btnPick;
    private Button btnPermission;
    private final List<Song> localSongs = new ArrayList<>();

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                if (hasLocalLibraryPermission()) {
                    scanLocalSongs();
                } else {
                    renderPermissionRequired();
                }
            }
    );

    private final ActivityResultLauncher<String[]> pickAudioLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            this::handlePickedAudio
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_local, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        textStatus = view.findViewById(R.id.text_local_status);
        textEmpty = view.findViewById(R.id.text_local_empty);
        emptyLayout = view.findViewById(R.id.layout_local_empty);
        permissionLayout = view.findViewById(R.id.layout_local_permission);
        btnScan = view.findViewById(R.id.btn_scan_local_music);
        btnPick = view.findViewById(R.id.btn_pick_local_audio);
        btnPermission = view.findViewById(R.id.btn_grant_local_permission);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_local_songs);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new SongAdapter();
        adapter.setOnItemClickListener(song -> MusicPlayerManager.getInstance(requireContext()).addOrPlaySong(song));
        recyclerView.setAdapter(adapter);

        btnScan.setOnClickListener(v -> scanLocalSongs());
        btnPick.setOnClickListener(v -> pickAudioLauncher.launch(new String[]{"audio/*"}));
        btnPermission.setOnClickListener(v -> requestLocalPermission());
        renderState();
    }

    public void triggerPickAudio() {
        if (isAdded()) {
            pickAudioLauncher.launch(new String[]{"audio/*"});
        }
    }

    public void handleExternalAudio(Uri uri, boolean replacePlaylist) {
        if (uri == null || !isAdded()) {
            return;
        }
        Song song = buildSongFromUri(uri);
        if (song == null) {
            Toast.makeText(requireContext(), R.string.local_audio_open_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        MusicPlayerManager manager = MusicPlayerManager.getInstance(requireContext());
        if (replacePlaylist) {
            List<Song> songs = new ArrayList<>();
            songs.add(song);
            manager.replacePlaylistAndPlay(songs, 0);
        } else {
            manager.addOrPlaySong(song);
        }
    }

    private void scanLocalSongs() {
        if (!hasLocalLibraryPermission()) {
            renderPermissionRequired();
            requestLocalPermission();
            return;
        }
        textStatus.setText(R.string.local_music_scanning);
        new Thread(() -> {
            List<Song> songs = LocalAudioRepository.scan(requireContext());
            if (getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                localSongs.clear();
                localSongs.addAll(songs);
                adapter.setSongs(localSongs);
                textStatus.setText(getString(R.string.local_music_scan_result, localSongs.size()));
                renderState();
            });
        }).start();
    }

    private void handlePickedAudio(Uri uri) {
        if (uri == null || !isAdded()) {
            return;
        }
        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        handleExternalAudio(uri, false);
    }

    private Song buildSongFromUri(Uri uri) {
        LocalAudioMetadata metadata = LocalAudioMetadataReader.read(requireContext(), uri);
        String uriValue = uri.toString();
        Song song = new Song(
                "local-uri:" + uriValue.hashCode(),
                metadata.title.isEmpty() ? getString(R.string.unknown_title) : metadata.title,
                metadata.artist.isEmpty() ? getString(R.string.unknown_artist) : metadata.artist,
                metadata.album,
                "",
                Song.SOURCE_LOCAL_URI,
                uriValue,
                metadata.mimeType,
                metadata.durationMs
        );
        song.lyric = metadata.lyric;
        song.translatedLyric = metadata.translatedLyric;
        song.embeddedPicture = metadata.artworkData;
        return song;
    }

    public Song createSongFromUri(Uri uri) {
        return buildSongFromUri(uri);
    }

    private void renderState() {
        boolean hasPermission = hasLocalLibraryPermission();
        permissionLayout.setVisibility(hasPermission ? View.GONE : View.VISIBLE);
        emptyLayout.setVisibility(localSongs.isEmpty() ? View.VISIBLE : View.GONE);
        textEmpty.setText(hasPermission ? R.string.local_music_empty : R.string.local_music_permission_required);
        if (!hasPermission) {
            textStatus.setText(R.string.local_music_permission_required);
        } else if (localSongs.isEmpty()) {
            textStatus.setText(R.string.local_music_not_scanned);
        }
    }

    private void renderPermissionRequired() {
        permissionLayout.setVisibility(View.VISIBLE);
        emptyLayout.setVisibility(View.VISIBLE);
        textEmpty.setText(R.string.local_music_permission_required);
        textStatus.setText(R.string.local_music_permission_required);
    }

    private void requestLocalPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(new String[]{android.Manifest.permission.READ_MEDIA_AUDIO});
        } else {
            permissionLauncher.launch(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE});
        }
    }

    private boolean hasLocalLibraryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
}
