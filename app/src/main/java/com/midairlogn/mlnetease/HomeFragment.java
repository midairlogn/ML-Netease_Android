package com.midairlogn.mlnetease;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.LinearLayout;
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
    private HomeShortcutAdapter shortcutAdapter;
    private Button btnPlayAll;
    private Button btnManageShortcuts;
    private LinearLayout emptyShortcutLayout;
    private List<HomeShortcut> currentShortcuts = new ArrayList<>();
    private boolean isShortcutMode = true;

    private long lastSearchTime = 0;
    private long lastPlayAllTime = 0;
    private static final long CLICK_DEBOUNCE_DELAY = 1000;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        neteaseApi = new NeteaseApi(new SettingsManager(requireContext()));

        // Make root layout focusable to intercept clicks for keyboard hiding
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);

        View.OnTouchListener hideKeyboardTouchListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (getActivity() != null && getActivity().getCurrentFocus() != null) {
                    hideKeyboard(getActivity().getCurrentFocus());
                    getActivity().getCurrentFocus().clearFocus();
                }
            }
            return false;
        };

        view.setOnTouchListener(hideKeyboardTouchListener);

        searchInput = view.findViewById(R.id.search_input);
        searchButton = view.findViewById(R.id.search_button);
        searchTypeGroup = view.findViewById(R.id.search_type_group);
        recyclerView = view.findViewById(R.id.recycler_view);
        btnManageShortcuts = view.findViewById(R.id.btn_manage_shortcuts);
        emptyShortcutLayout = view.findViewById(R.id.home_shortcut_empty_state);

        recyclerView.setOnTouchListener(hideKeyboardTouchListener);
        searchTypeGroup.setOnTouchListener(hideKeyboardTouchListener);

        btnPlayAll = view.findViewById(R.id.btn_play_all);
        view.findViewById(R.id.btn_manage_shortcuts_empty).setOnClickListener(v -> showManageShortcutsDialog());
        btnManageShortcuts.setOnClickListener(v -> showManageShortcutsDialog());

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new SongAdapter();
        shortcutAdapter = new HomeShortcutAdapter();

        shortcutAdapter.setOnItemClickListener(shortcut -> {
            executeShortcut(shortcut);
        });

        recyclerView.setAdapter(shortcutAdapter);
        updateViewMode();
        loadShortcuts();

        if (savedInstanceState != null) {
            List<?> savedSongsRaw = (List<?>) savedInstanceState.getSerializable("songs");
            if (savedSongsRaw != null) {
                List<Song> savedSongs = new ArrayList<>();
                for (Object item : savedSongsRaw) {
                    if (item instanceof Song) {
                        savedSongs.add((Song) item);
                    }
                }
                adapter.setSongs(savedSongs);
                int checkedId = searchTypeGroup.getCheckedRadioButtonId();
                if (checkedId == R.id.radio_song) {
                    btnPlayAll.setVisibility(View.GONE);
                } else {
                    btnPlayAll.setVisibility(savedSongs.isEmpty() ? View.GONE : View.VISIBLE);
                }
            }
        }

        searchButton.setOnClickListener(v -> {
            String input = searchInput.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(getContext(), "Please enter keywords", Toast.LENGTH_SHORT).show();
                return;
            }

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastSearchTime < CLICK_DEBOUNCE_DELAY) {
                return;
            }
            lastSearchTime = currentTime;

            hideKeyboard(v);
            searchInput.clearFocus();
            performSearch();
        });

        searchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                hideKeyboard(v);
            }
        });

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String input = searchInput.getText().toString().trim();
                if (input.isEmpty()) {
                    Toast.makeText(getContext(), "Please enter keywords", Toast.LENGTH_SHORT).show();
                    return true;
                }

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastSearchTime < CLICK_DEBOUNCE_DELAY) {
                    return true;
                }
                lastSearchTime = currentTime;

                hideKeyboard(v);
                searchInput.clearFocus();
                performSearch();
                return true;
            }
            return false;
        });

        btnPlayAll.setOnClickListener(v -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastPlayAllTime < CLICK_DEBOUNCE_DELAY) {
                return;
            }
            lastPlayAllTime = currentTime;

            List<Song> songs = adapter.getSongs();
            if (songs != null && !songs.isEmpty()) {
                MusicPlayerManager.getInstance(getContext()).addPlaylistAndPlayFirstNew(songs);
            }
        });

        adapter.setOnItemClickListener(song -> {
            MusicPlayerManager.getInstance(getContext()).addOrPlaySong(song);
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (adapter != null && adapter.getSongs() != null) {
            outState.putSerializable("songs", new ArrayList<>(adapter.getSongs()));
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    private void performSearch() {
        String input = searchInput.getText().toString().trim();
        if (input.isEmpty()) return;

        int checkedId = searchTypeGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.radio_song) {
            String extractedId = extractId(input);
            boolean isIdOrUrl = input.contains("music.163.com") || input.matches("\\d+");

            if (isIdOrUrl) {
                neteaseApi.getSongFullInfo(extractedId, new NeteaseApi.ApiCallback() {
                    @Override
                    public void onSuccess(String result) {
                        parseSongIdResult(result);
                    }

                    @Override
                    public void onError(String error) {
                        Toast.makeText(getContext(), "Error: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
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
            }
        } else if (checkedId == R.id.radio_playlist) {
            String id = extractId(input);
            neteaseApi.playlistDetail(id, new NeteaseApi.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    parsePlaylistResult(result, false);
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
                    parseAlbumResult(result, false);
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(getContext(), "Error: " + error, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private String extractId(String input) {
        return HomeShortcutIdParser.normalizeId(input);
    }

    private void updateViewMode() {
        if (isShortcutMode) {
            recyclerView.setAdapter(shortcutAdapter);
            btnPlayAll.setVisibility(View.GONE);
            btnManageShortcuts.setVisibility(currentShortcuts.isEmpty() ? View.GONE : View.VISIBLE);
            emptyShortcutLayout.setVisibility(currentShortcuts.isEmpty() ? View.VISIBLE : View.GONE);
        } else {
            recyclerView.setAdapter(adapter);
            btnManageShortcuts.setVisibility(View.GONE);
            emptyShortcutLayout.setVisibility(View.GONE);
            // btnPlayAll visibility managed by updateList() or search result callbacks
        }
    }

    private void loadShortcuts() {
        currentShortcuts = new SettingsManager(requireContext()).getHomeShortcuts();
        shortcutAdapter.setShortcuts(currentShortcuts);
        updateViewMode();
    }

    private void executeShortcut(HomeShortcut shortcut) {
        hideKeyboard(getView());
        searchInput.clearFocus();

        if (shortcut.isPlaylist()) {
            neteaseApi.playlistDetail(shortcut.id, new NeteaseApi.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    parsePlaylistResult(result, true);
                }

                @Override
                public void onError(String error) {
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Error: " + error, Toast.LENGTH_SHORT).show());
                }
            });
        } else if (shortcut.isAlbum()) {
            neteaseApi.albumDetail(shortcut.id, new NeteaseApi.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    parseAlbumResult(result, true);
                }

                @Override
                public void onError(String error) {
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Error: " + error, Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private void parsePlaylistResult(String json, boolean isShortcut) {
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has("songs")) {
                Toast.makeText(getContext(), "No songs found", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray songsArray = root.getJSONArray("songs");
            updateList(songsArray, isShortcut);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Parse error", Toast.LENGTH_SHORT).show();
        }
    }

    private void parseAlbumResult(String json, boolean isShortcut) {
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has("album")) return;
            JSONObject album = root.getJSONObject("album");
            if (!album.has("songs")) return;
            JSONArray songsArray = album.getJSONArray("songs");
            updateList(songsArray, isShortcut);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Parse error", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateList(JSONArray songsArray, boolean isShortcut) throws Exception {
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

        if (isShortcut) {
            getActivity().runOnUiThread(() -> {
                MusicPlayerManager.getInstance(getContext()).addPlaylistAndPlayFirstNew(songs);
            });
        } else {
            getActivity().runOnUiThread(() -> {
                isShortcutMode = false;
                updateViewMode();
                adapter.setSongs(songs);
                btnPlayAll.setVisibility(songs.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }
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
            btnPlayAll.setVisibility(View.GONE);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void parseSongIdResult(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("status") != 200) {
                getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "Song not found", Toast.LENGTH_SHORT).show()
                );
                return;
            }

            String id = root.optString("id");
            String name = root.optString("name");
            String artists = root.optString("ar_name");
            String album = root.optString("al_name");
            String picUrl = root.optString("pic");

            Song song = new Song(id, name, artists, album, picUrl);
            List<Song> songs = new ArrayList<>();
            songs.add(song);

            adapter.setSongs(songs);
            btnPlayAll.setVisibility(View.GONE);

        } catch (Exception e) {
            e.printStackTrace();
            getActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(), "Parse error", Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void showManageShortcutsDialog() {
        // Simple implementation for demonstration - ideally a DialogFragment or BottomSheet
        // For brevity, just Toast placeholder - in a full implementation, you'd inflate dialog_manage_home_shortcuts.xml
        Toast.makeText(getContext(), "Shortcut Management UI (Not fully implemented)", Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard(View view) {
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }
}
