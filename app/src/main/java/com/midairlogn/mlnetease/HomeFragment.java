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
    private Button btnResetSearch;
    private RadioGroup searchTypeGroup;
    private RecyclerView recyclerView;
    private SongAdapter adapter;
    private HomeShortcutAdapter shortcutAdapter;
    private Button btnPlayAll;
    private Button btnDownloadAll;
    private Button btnAddToShortcut;
    private Button btnManageShortcuts;
    private LinearLayout emptyShortcutLayout;
    private List<HomeShortcut> currentShortcuts = new ArrayList<>();
    private boolean isShortcutMode = true;
    private String lastSearchedId = "";
    private String lastSearchedType = "";
    private String lastSearchedTitle = "";

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
        neteaseApi = new NeteaseApi(requireContext(), new SettingsManager(requireContext()));

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
        btnResetSearch = view.findViewById(R.id.btn_reset_search);
        searchTypeGroup = view.findViewById(R.id.search_type_group);
        recyclerView = view.findViewById(R.id.recycler_view);
        btnManageShortcuts = view.findViewById(R.id.btn_manage_shortcuts);
        emptyShortcutLayout = view.findViewById(R.id.home_shortcut_empty_state);

        recyclerView.setOnTouchListener(hideKeyboardTouchListener);
        searchTypeGroup.setOnTouchListener(hideKeyboardTouchListener);

        btnPlayAll = view.findViewById(R.id.btn_play_all);
        btnDownloadAll = view.findViewById(R.id.btn_download_all);
        btnAddToShortcut = view.findViewById(R.id.btn_add_to_shortcut);
        view.findViewById(R.id.btn_manage_shortcuts_empty).setOnClickListener(v -> showManageShortcutsDialog());
        btnManageShortcuts.setOnClickListener(v -> showManageShortcutsDialog());

        btnAddToShortcut.setOnClickListener(v -> {
            if (lastSearchedId.isEmpty()) return;

            SettingsManager sm = new SettingsManager(requireContext());
            List<HomeShortcut> shortcuts = new ArrayList<>(sm.getHomeShortcuts());
            String type = lastSearchedType.equals("playlist") ? HomeShortcut.TYPE_PLAYLIST : HomeShortcut.TYPE_ALBUM;

            // Check if already exists
            HomeShortcut existing = null;
            for (HomeShortcut s : shortcuts) {
                if (s.type.equals(type) && s.id.equals(lastSearchedId)) {
                    existing = s;
                    break;
                }
            }

            if (existing != null) {
                // Open edit dialog for existing
                ManageShortcutsDialog dialog = new ManageShortcutsDialog();
                dialog.setInitialShortcut(existing);
                dialog.setOnDismissListener(() -> loadShortcuts());
                dialog.show(getParentFragmentManager(), "ManageShortcuts");
            } else {
                // Add new and open edit dialog
                HomeShortcut newShortcut = new HomeShortcut(lastSearchedId, lastSearchedId, type, shortcuts.size());
                shortcuts.add(newShortcut);
                sm.setHomeShortcuts(shortcuts);

                // Important: reload currentShortcuts from settings to get the instance that was just saved
                loadShortcuts();

                // Find the just-added shortcut in the updated list to ensure we pass the correct object reference
                HomeShortcut savedShortcut = null;
                for (HomeShortcut s : currentShortcuts) {
                    if (s.type.equals(type) && s.id.equals(lastSearchedId)) {
                        savedShortcut = s;
                        break;
                    }
                }

                ManageShortcutsDialog dialog = new ManageShortcutsDialog();
                dialog.setInitialShortcut(savedShortcut != null ? savedShortcut : newShortcut);
                dialog.setOnDismissListener(() -> loadShortcuts());
                dialog.show(getParentFragmentManager(), "ManageShortcuts");

                // Disable button after adding
                btnAddToShortcut.setEnabled(false);
                btnAddToShortcut.setAlpha(0.3f);
            }
        });

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
            lastSearchedId = savedInstanceState.getString("lastSearchedId", "");
            lastSearchedType = savedInstanceState.getString("lastSearchedType", "");
            lastSearchedTitle = savedInstanceState.getString("lastSearchedTitle", "");
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
                    btnDownloadAll.setVisibility(View.GONE);
                } else {
                    btnPlayAll.setVisibility(savedSongs.isEmpty() ? View.GONE : View.VISIBLE);
                    boolean showDownload = !savedSongs.isEmpty() && ("playlist".equals(lastSearchedType) || "album".equals(lastSearchedType));
                    btnDownloadAll.setVisibility(showDownload ? View.VISIBLE : View.GONE);
                }
            }
        }

        btnResetSearch.setOnClickListener(v -> resetToShortcutMode());

        searchButton.setOnClickListener(v -> {
            String input = searchInput.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(getContext(), R.string.hint_enter_keywords, Toast.LENGTH_SHORT).show();
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
                String input = searchInput.getText().toString().trim();
                String extractedId = extractId(input);
                if (extractedId != null && !extractedId.isEmpty() && !extractedId.equals(input)) {
                    searchInput.setText(extractedId);
                }
            }
        });

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String input = searchInput.getText().toString().trim();
                if (input.isEmpty()) {
                    Toast.makeText(getContext(), R.string.hint_enter_keywords, Toast.LENGTH_SHORT).show();
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

        btnDownloadAll.setOnClickListener(v -> {
            List<Song> songs = adapter.getSongs();
            if (songs == null || songs.isEmpty()) {
                Toast.makeText(getContext(), R.string.hint_no_songs_in_list, Toast.LENGTH_SHORT).show();
                return;
            }
            String requestType = "playlist".equals(lastSearchedType) ? DownloadRequest.TYPE_PLAYLIST : DownloadRequest.TYPE_ALBUM;
            String requestTitle = lastSearchedTitle;
            if ((requestTitle == null || requestTitle.trim().isEmpty()) && !songs.isEmpty() && songs.get(0).album != null && !songs.get(0).album.trim().isEmpty() && DownloadRequest.TYPE_ALBUM.equals(requestType)) {
                requestTitle = songs.get(0).album;
            }
            if (requestTitle == null || requestTitle.trim().isEmpty()) {
                requestTitle = lastSearchedId;
            }
            DownloadTaskSnapshot task = SongDownloadStarter.downloadList(requireContext(), requestType, requestTitle, songs);
            if (task != null) {
                Toast.makeText(getContext(), getString(R.string.download_added_named, task.title), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (adapter != null && adapter.getSongs() != null) {
            outState.putSerializable("songs", new ArrayList<>(adapter.getSongs()));
        }
        outState.putString("lastSearchedId", lastSearchedId);
        outState.putString("lastSearchedType", lastSearchedType);
        outState.putString("lastSearchedTitle", lastSearchedTitle);
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
                        try {
                            JSONObject root = new JSONObject(result);
                            if (root.optInt("status") == 200) {
                                parseSongIdResult(result);
                            } else {
                                // Fallback to keyword search
                                neteaseApi.search(input, new NeteaseApi.ApiCallback() {
                                    @Override
                                    public void onSuccess(String result) {
                                        parseSearchResult(result);
                                    }

                                    @Override
                                    public void onError(String error) {
                                        Toast.makeText(getContext(), getString(R.string.hint_error_title) + error, Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            // Fallback to keyword search
                            neteaseApi.search(input, new NeteaseApi.ApiCallback() {
                                @Override
                                public void onSuccess(String result) {
                                    parseSearchResult(result);
                                }

                                @Override
                                public void onError(String error) {
                                    Toast.makeText(getContext(), getString(R.string.hint_error_title) + error, Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(String error) {
                        // Fallback to keyword search
                        neteaseApi.search(input, new NeteaseApi.ApiCallback() {
                            @Override
                            public void onSuccess(String result) {
                                parseSearchResult(result);
                            }

                            @Override
                            public void onError(String error) {
                                Toast.makeText(getContext(), getString(R.string.hint_error_title) + error, Toast.LENGTH_SHORT).show();
                            }
                        });
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
                        Toast.makeText(getContext(), getString(R.string.hint_error_title) + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } else if (checkedId == R.id.radio_playlist) {
            String id = extractId(input);
            lastSearchedId = id;
            lastSearchedType = "playlist";
            neteaseApi.playlistDetail(id, new NeteaseApi.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    parsePlaylistResult(result, false);
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(getContext(), getString(R.string.hint_error_title) + error, Toast.LENGTH_SHORT).show();
                }
            });
        } else if (checkedId == R.id.radio_album) {
            String id = extractId(input);
            lastSearchedId = id;
            lastSearchedType = "album";
            neteaseApi.albumDetail(id, new NeteaseApi.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    parseAlbumResult(result, false);
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(getContext(), getString(R.string.hint_error_title) + error, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private String extractId(String input) {
        return HomeShortcutIdParser.normalizeId(input);
    }

    private void resetToShortcutMode() {
        isShortcutMode = true;
        searchInput.setText("");
        adapter.setSongs(new ArrayList<>());
        btnAddToShortcut.setVisibility(View.GONE);
        btnDownloadAll.setVisibility(View.GONE);
        lastSearchedTitle = "";
        updateViewMode();
    }

    private void updateViewMode() {
        if (isShortcutMode) {
            recyclerView.setAdapter(shortcutAdapter);
            btnPlayAll.setVisibility(View.GONE);
            btnDownloadAll.setVisibility(View.GONE);
            btnResetSearch.setVisibility(View.GONE);
            btnManageShortcuts.setVisibility(currentShortcuts.isEmpty() ? View.GONE : View.VISIBLE);
            emptyShortcutLayout.setVisibility(currentShortcuts.isEmpty() ? View.VISIBLE : View.GONE);
        } else {
            recyclerView.setAdapter(adapter);
            btnResetSearch.setVisibility(View.VISIBLE);
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
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), getString(R.string.hint_error_title) + error, Toast.LENGTH_SHORT).show());
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
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), getString(R.string.hint_error_title) + error, Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private void parsePlaylistResult(String json, boolean isShortcut) {
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has("songs")) {
                Toast.makeText(getContext(), R.string.no_song_found, Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray songsArray = root.getJSONArray("songs");
            if (songsArray.length() == 0) {
                Toast.makeText(getContext(), R.string.hint_no_songs_in_list, Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject playlist = root.optJSONObject("playlist");
            lastSearchedTitle = playlist == null ? lastSearchedId : playlist.optString("name", lastSearchedId);
            updateList(songsArray, isShortcut);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), R.string.hint_parse_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void parseAlbumResult(String json, boolean isShortcut) {
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has("album")) return;
            JSONObject album = root.getJSONObject("album");
            if (!album.has("songs")) return;
            JSONArray songsArray = album.getJSONArray("songs");
            if (songsArray.length() == 0) {
                Toast.makeText(getContext(), R.string.hint_no_songs_in_list, Toast.LENGTH_SHORT).show();
                return;
            }
            lastSearchedTitle = album.optString("name", lastSearchedId);
            updateList(songsArray, isShortcut);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), R.string.hint_parse_error, Toast.LENGTH_SHORT).show();
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
                boolean showDownload = !songs.isEmpty() && !isShortcut && ("playlist".equals(lastSearchedType) || "album".equals(lastSearchedType));
                btnDownloadAll.setVisibility(showDownload ? View.VISIBLE : View.GONE);
                btnAddToShortcut.setVisibility(songs.isEmpty() ? View.GONE : View.VISIBLE);

                boolean alreadyExists = false;
                String currentType = lastSearchedType.equals("playlist") ? HomeShortcut.TYPE_PLAYLIST : HomeShortcut.TYPE_ALBUM;
                for (HomeShortcut s : currentShortcuts) {
                    if (s.type.equals(currentType) && s.id.equals(lastSearchedId)) {
                        alreadyExists = true;
                        break;
                    }
                }
                btnAddToShortcut.setEnabled(!alreadyExists);
                btnAddToShortcut.setAlpha(alreadyExists ? 0.3f : 1.0f);
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

            getActivity().runOnUiThread(() -> {
                isShortcutMode = false;
                updateViewMode();
                adapter.setSongs(songs);
                btnPlayAll.setVisibility(View.GONE);
                btnDownloadAll.setVisibility(View.GONE);
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void parseSongIdResult(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("status") != 200) {
                getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), R.string.song_not_found, Toast.LENGTH_SHORT).show()
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

            getActivity().runOnUiThread(() -> {
                isShortcutMode = false;
                updateViewMode();
                adapter.setSongs(songs);
                btnPlayAll.setVisibility(View.GONE);
                btnDownloadAll.setVisibility(View.GONE);
            });

        } catch (Exception e) {
            e.printStackTrace();
            getActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(), R.string.hint_parse_error, Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void showManageShortcutsDialog() {
        ManageShortcutsDialog dialog = new ManageShortcutsDialog();
        dialog.setOnDismissListener(() -> loadShortcuts());
        dialog.show(getParentFragmentManager(), "ManageShortcuts");
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
