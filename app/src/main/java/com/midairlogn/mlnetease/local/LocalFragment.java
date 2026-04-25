package com.midairlogn.mlnetease.local;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.view.inputmethod.EditorInfo;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.midairlogn.mlnetease.local.media.LocalAudioRepository;
import com.midairlogn.mlnetease.local.media.LocalAudioSongFactory;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.playback.core.MusicPlayerManager;
import com.midairlogn.mlnetease.shared.adapter.SongAdapter;
import com.midairlogn.mlnetease.shared.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class LocalFragment extends Fragment {
    private SongAdapter adapter;
    private TextView textStatus;
    private TextView textEmpty;
    private EditText inputSearch;
    private View searchContainer;
    private View emptyLayout;
    private View permissionLayout;
    private Button btnScan;
    private Button btnPick;
    private Button btnPermission;
    private ImageButton btnSearch;
    private final List<Song> localSongs = new ArrayList<>();
    private final List<Song> filteredSongs = new ArrayList<>();
    private boolean hasScannedLibrary = false;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearchRunnable;
    private static final long SEARCH_DEBOUNCE_MS = 220L;

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
        inputSearch = view.findViewById(R.id.input_local_search);
        searchContainer = view.findViewById(R.id.layout_local_search);
        emptyLayout = view.findViewById(R.id.layout_local_empty);
        permissionLayout = view.findViewById(R.id.layout_local_permission);
        btnScan = view.findViewById(R.id.btn_scan_local_music);
        btnPick = view.findViewById(R.id.btn_pick_local_audio);
        btnPermission = view.findViewById(R.id.btn_grant_local_permission);
        btnSearch = view.findViewById(R.id.btn_local_search);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_local_songs);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new SongAdapter();
        adapter.setOnItemClickListener(song -> MusicPlayerManager.getInstance(requireContext()).addOrPlaySong(song));
        recyclerView.setAdapter(adapter);

        btnScan.setOnClickListener(v -> scanLocalSongs());
        btnPick.setOnClickListener(v -> pickAudioLauncher.launch(new String[]{"audio/*"}));
        btnPermission.setOnClickListener(v -> requestLocalPermission());
        btnSearch.setOnClickListener(v -> inputSearch.setText(""));
        inputSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearchNow();
                return true;
            }
            return false;
        });
        inputSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateClearButtonVisibility(s == null ? "" : s.toString());
                scheduleSearch(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        updateClearButtonVisibility(inputSearch.getText() == null ? "" : inputSearch.getText().toString());
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
        Song song = LocalAudioSongFactory.create(requireContext(), uri);
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
                hasScannedLibrary = true;
                runSearchNow();
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

    public Song createSongFromUri(Uri uri) {
        return isAdded() ? LocalAudioSongFactory.create(requireContext(), uri) : null;
    }

    private void renderState() {
        boolean hasPermission = hasLocalLibraryPermission();
        permissionLayout.setVisibility(hasPermission ? View.GONE : View.VISIBLE);
        boolean hasSongs = !localSongs.isEmpty();
        boolean hasVisibleSongs = !filteredSongs.isEmpty();
        boolean showSearch = hasPermission && hasScannedLibrary && hasSongs;
        searchContainer.setVisibility(showSearch ? View.VISIBLE : View.GONE);
        emptyLayout.setVisibility((!hasVisibleSongs || !hasSongs) ? View.VISIBLE : View.GONE);

        if (!hasPermission) {
            textEmpty.setText(R.string.local_music_permission_required);
            textStatus.setText(R.string.local_music_permission_required);
        } else if (!hasScannedLibrary) {
            textEmpty.setText(R.string.local_music_empty);
            textStatus.setText(R.string.local_music_not_scanned);
        } else if (!hasSongs) {
            textEmpty.setText(R.string.local_music_empty);
            textStatus.setText(getString(R.string.local_music_scan_result, 0));
        } else if (!hasVisibleSongs) {
            textEmpty.setText(R.string.local_music_search_empty);
            textStatus.setText(getString(R.string.local_music_search_result, 0, localSongs.size()));
        } else if (isSearchActive()) {
            textEmpty.setText(R.string.local_music_search_empty);
            textStatus.setText(getString(R.string.local_music_search_result, filteredSongs.size(), localSongs.size()));
        } else {
            textEmpty.setText(R.string.local_music_empty);
            textStatus.setText(getString(R.string.local_music_scan_result, localSongs.size()));
        }
    }

    private void renderPermissionRequired() {
        permissionLayout.setVisibility(View.VISIBLE);
        searchContainer.setVisibility(View.GONE);
        emptyLayout.setVisibility(View.VISIBLE);
        textEmpty.setText(R.string.local_music_permission_required);
        textStatus.setText(R.string.local_music_permission_required);
    }

    private void scheduleSearch(String query) {
        if (pendingSearchRunnable != null) {
            searchHandler.removeCallbacks(pendingSearchRunnable);
        }
        pendingSearchRunnable = () -> applySearch(query);
        searchHandler.postDelayed(pendingSearchRunnable, SEARCH_DEBOUNCE_MS);
    }

    private void runSearchNow() {
        if (pendingSearchRunnable != null) {
            searchHandler.removeCallbacks(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
        applySearch(inputSearch.getText() == null ? "" : inputSearch.getText().toString());
    }

    private void updateClearButtonVisibility(String query) {
        if (btnSearch == null) {
            return;
        }
        btnSearch.setVisibility(query == null || query.trim().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void applySearch(String query) {
        filteredSongs.clear();
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) {
            filteredSongs.addAll(localSongs);
        } else {
            List<SearchMatch> matches = new ArrayList<>();
            for (Song song : localSongs) {
                int score = scoreSong(song, normalizedQuery);
                if (score >= 0) {
                    matches.add(new SearchMatch(song, score));
                }
            }
            Collections.sort(matches, Comparator.comparingInt((SearchMatch match) -> match.score)
                    .thenComparing(match -> normalize(match.song.name)));
            for (SearchMatch match : matches) {
                filteredSongs.add(match.song);
            }
        }
        adapter.setSongs(filteredSongs);
        renderState();
    }

    private boolean isSearchActive() {
        return inputSearch != null && inputSearch.getText() != null && !inputSearch.getText().toString().trim().isEmpty();
    }

    private int scoreSong(Song song, String query) {
        String title = normalize(song == null ? "" : song.name);
        String artist = normalize(song == null ? "" : song.artists);
        String album = normalize(song == null ? "" : song.album);
        String combined = title + " " + artist + " " + album;

        int bestScore = Integer.MAX_VALUE;
        bestScore = Math.min(bestScore, scoreField(title, query, 0));
        bestScore = Math.min(bestScore, scoreField(artist, query, 120));
        bestScore = Math.min(bestScore, scoreField(album, query, 180));
        bestScore = Math.min(bestScore, scoreField(combined, query, 260));
        return bestScore == Integer.MAX_VALUE ? -1 : bestScore;
    }

    private int scoreField(String field, String query, int basePenalty) {
        if (field.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int containsIndex = field.indexOf(query);
        if (containsIndex >= 0) {
            return basePenalty + containsIndex;
        }

        int fuzzyScore = scoreSubsequence(field, query);
        if (fuzzyScore < 0) {
            return Integer.MAX_VALUE;
        }
        return basePenalty + 400 + fuzzyScore;
    }

    private int scoreSubsequence(String text, String query) {
        int queryIndex = 0;
        int firstMatch = -1;
        int lastMatch = -1;
        int gapPenalty = 0;
        for (int i = 0; i < text.length() && queryIndex < query.length(); i++) {
            if (text.charAt(i) != query.charAt(queryIndex)) {
                continue;
            }
            if (firstMatch < 0) {
                firstMatch = i;
            }
            if (lastMatch >= 0) {
                gapPenalty += Math.max(0, i - lastMatch - 1);
            }
            lastMatch = i;
            queryIndex++;
        }
        if (queryIndex != query.length() || firstMatch < 0 || lastMatch < 0) {
            return -1;
        }
        int spreadPenalty = lastMatch - firstMatch - query.length() + 1;
        return Math.max(0, firstMatch) + Math.max(0, gapPenalty * 3) + Math.max(0, spreadPenalty * 2);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class SearchMatch {
        final Song song;
        final int score;

        SearchMatch(Song song, int score) {
            this.song = song;
            this.score = score;
        }
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

    @Override
    public void onDestroyView() {
        if (pendingSearchRunnable != null) {
            searchHandler.removeCallbacks(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
        super.onDestroyView();
    }
}
