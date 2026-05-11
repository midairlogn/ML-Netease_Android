package com.midairlogn.mlnetease.home.shortcut;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.home.model.FavouriteSong;
import com.midairlogn.mlnetease.playback.core.PlaybackActionDispatcher;
import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FavouriteSongsBottomSheetFragment extends BottomSheetDialogFragment {

    private RecyclerView recyclerView;
    private FavouriteAdapter adapter;
    private TextView titleView;
    private SettingsManager settingsManager;
    private final List<FavouriteSong> favourites = new ArrayList<>();
    private Runnable onDismissListener;

    public void setOnDismissListener(Runnable onDismissListener) {
        this.onDismissListener = onDismissListener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_favourite_songs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settingsManager = new SettingsManager(requireContext());
        favourites.clear();
        favourites.addAll(settingsManager.getFavouriteSongs());

        titleView = view.findViewById(R.id.tv_favourite_title);
        recyclerView = view.findViewById(R.id.recycler_view_favourites);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new FavouriteAdapter();
        recyclerView.setAdapter(adapter);
        updateTitle();
        updateEmptyState(view);

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from < 0 || to < 0) {
                    return false;
                }
                Collections.swap(favourites, from, to);
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                persistFavourites();
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        });
        itemTouchHelper.attachToRecyclerView(recyclerView);

        view.findViewById(R.id.btn_close_favourites).setOnClickListener(v -> dismiss());
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (onDismissListener != null) {
            onDismissListener.run();
        }
    }

    private void persistFavourites() {
        settingsManager.normalizeFavouriteSequences(favourites);
        settingsManager.setFavouriteSongs(favourites);
        favourites.clear();
        favourites.addAll(settingsManager.getFavouriteSongs());
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateTitle();
        if (getView() != null) {
            updateEmptyState(getView());
        }
    }

    private void updateTitle() {
        if (titleView != null) {
            titleView.setText(getString(R.string.favourites_sheet_title, favourites.size()));
        }
    }

    private void updateEmptyState(View view) {
        View emptyView = view.findViewById(R.id.tv_favourites_empty);
        if (emptyView != null) {
            emptyView.setVisibility(favourites.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (recyclerView != null) {
            recyclerView.setVisibility(favourites.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private final class FavouriteAdapter extends RecyclerView.Adapter<FavouriteAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist_song, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FavouriteSong song = favourites.get(position);
            holder.sequence.setText(String.valueOf(position + 1));
            holder.title.setText(song.name);
            holder.artist.setText(song.artists);
            holder.itemView.setOnClickListener(v -> {
                Song playableSong = song.toSong();
                PlaybackActionDispatcher.addOrPlaySong(requireContext(), playableSong);
                dismiss();
            });
            holder.btnRemove.setOnClickListener(v -> {
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition < 0 || adapterPosition >= favourites.size()) {
                    return;
                }
                favourites.remove(adapterPosition);
                notifyItemRemoved(adapterPosition);
                persistFavourites();
            });
        }

        @Override
        public int getItemCount() {
            return favourites.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final TextView sequence;
            final TextView title;
            final TextView artist;
            final ImageButton btnRemove;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                sequence = itemView.findViewById(R.id.song_sequence);
                title = itemView.findViewById(R.id.song_title);
                artist = itemView.findViewById(R.id.song_artist);
                btnRemove = itemView.findViewById(R.id.btn_remove);
            }
        }
    }
}
