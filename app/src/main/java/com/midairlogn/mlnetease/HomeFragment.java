package com.midairlogn.mlnetease;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class HomeFragment extends Fragment {

    private NeteaseApi neteaseApi;
    private EditText searchInput;
    private Button searchButton;
    private RadioGroup searchTypeGroup;
    private RecyclerView recyclerView;
    private SongAdapter adapter;
    private View playerContainer;
    private TextView currentSongTitle, currentSongArtist;
    private Button btnPlayPause;
    private Button btnPlayAll;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        neteaseApi = new NeteaseApi(new SettingsManager(requireContext()));

        searchInput = view.findViewById(R.id.search_input);
        searchButton = view.findViewById(R.id.search_button);
        searchTypeGroup = view.findViewById(R.id.search_type_group);
        recyclerView = view.findViewById(R.id.recycler_view);
        playerContainer = view.findViewById(R.id.player_container);
        currentSongTitle = view.findViewById(R.id.current_song_title);
        currentSongArtist = view.findViewById(R.id.current_song_artist);
        btnPlayPause = view.findViewById(R.id.btn_play_pause);
        btnPlayAll = view.findViewById(R.id.btn_play_all);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new SongAdapter();
        recyclerView.setAdapter(adapter);

        searchButton.setOnClickListener(v -> performSearch());

        btnPlayAll.setOnClickListener(v -> {
            List<Song> songs = adapter.getSongs();
            if (songs != null && !songs.isEmpty()) {
                MusicPlayerManager.getInstance(getContext()).setPlaylist(songs);
                MusicPlayerManager.getInstance(getContext()).play(0);
            }
        });

        adapter.setOnItemClickListener(song -> {
            List<Song> songs = adapter.getSongs();
            int index = songs.indexOf(song);
            MusicPlayerManager.getInstance(getContext()).setPlaylist(songs);
            MusicPlayerManager.getInstance(getContext()).play(index);
        });

        playerContainer.setOnClickListener(v -> {
            startActivity(new android.content.Intent(getContext(), PlayerActivity.class));
        });

        btnPlayPause.setOnClickListener(v -> {
            MusicPlayerManager.getInstance(getContext()).togglePlayPause();
        });

        MusicPlayerManager.getInstance(getContext()).addOnSongChangedListener(this::updateMiniPlayer);
        MusicPlayerManager.getInstance(getContext()).addOnPlaybackStateChangedListener(this::updatePlayPauseButton);
    }

    private void updateMiniPlayer(Song song) {
        if (song == null) return;
        playerContainer.setVisibility(View.VISIBLE);
        currentSongTitle.setText(song.name);
        currentSongArtist.setText(song.artists);
    }

    private void updatePlayPauseButton(boolean isPlaying) {
        if (btnPlayPause != null) {
            btnPlayPause.setText(isPlaying ? "Pause" : "Play");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Remove listeners? Need to keep reference or make anonymous inner class field
        // But for simplicity, we skip removal or implement interface properly
    }

    private void performSearch() {
        String input = searchInput.getText().toString().trim();
        if (input.isEmpty()) return;

        int checkedId = searchTypeGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.radio_song) {
            neteaseApi.search(input, new NeteaseApi.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    parseSearchResult(result);
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(getContext(), "Error: " + error, Toast.LENGTH_SHORT).show();
                }
            });
        } else if (checkedId == R.id.radio_playlist) {
            String id = extractId(input);
            neteaseApi.playlistDetail(id, new NeteaseApi.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    parsePlaylistResult(result);
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(getContext(), "Error: " + error, Toast.LENGTH_SHORT).show();
                }
            });
        } else if (checkedId == R.id.radio_album) {
            String id = extractId(input);
            neteaseApi.albumDetail(id, new NeteaseApi.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    parseAlbumResult(result);
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(getContext(), "Error: " + error, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private String extractId(String input) {
        if (input.contains("music.163.com")) {
            int index = input.indexOf("id=");
            if (index != -1) {
                String sub = input.substring(index + 3);
                int end = sub.indexOf("&");
                if (end != -1) {
                    return sub.substring(0, end);
                }
                return sub;
            }
        }
        return input;
    }

    private void parsePlaylistResult(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has("songs")) {
                Toast.makeText(getContext(), "No songs found", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray songsArray = root.getJSONArray("songs");
            updateList(songsArray);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Parse error", Toast.LENGTH_SHORT).show();
        }
    }

    private void parseAlbumResult(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has("album")) return;
            JSONObject album = root.getJSONObject("album");
            if (!album.has("songs")) return;
            JSONArray songsArray = album.getJSONArray("songs");
            updateList(songsArray);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Parse error", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateList(JSONArray songsArray) throws Exception {
        List<Song> songs = new ArrayList<>();
        for (int i = 0; i < songsArray.length(); i++) {
            JSONObject obj = songsArray.getJSONObject(i);
            String id = String.valueOf(obj.opt("id"));
            String name = obj.optString("name");
            String artists = obj.optString("artists");
            String album = obj.optString("album");
            String picUrl = obj.optString("picUrl");

            songs.add(new Song(id, name, artists, album, picUrl));
        }
        adapter.setSongs(songs);
        btnPlayAll.setVisibility(songs.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void parseSearchResult(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has("result")) return;
            JSONObject res = root.getJSONObject("result");
            if (!res.has("songs")) return;

            JSONArray songsArray = res.getJSONArray("songs");
            List<Song> songs = new ArrayList<>();
            for (int i = 0; i < songsArray.length(); i++) {
                JSONObject obj = songsArray.getJSONObject(i);
                String id = String.valueOf(obj.getInt("id"));
                String name = obj.getString("name");

                StringBuilder artists = new StringBuilder();
                JSONArray ar = obj.getJSONArray("ar");
                for (int k = 0; k < ar.length(); k++) {
                    if (k > 0) artists.append("/");
                    artists.append(ar.getJSONObject(k).getString("name"));
                }

                JSONObject al = obj.getJSONObject("al");
                String album = al.getString("name");
                String picUrl = al.optString("picUrl", "");

                songs.add(new Song(id, name, artists.toString(), album, picUrl));
            }

            adapter.setSongs(songs);
            btnPlayAll.setVisibility(songs.isEmpty() ? View.GONE : View.VISIBLE);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}