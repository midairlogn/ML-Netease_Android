package com.midairlogn.mlnetease;

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
import androidx.core.content.ContextCompat;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.util.Collections;
import java.util.List;

public class PlaylistBottomSheetFragment extends BottomSheetDialogFragment {

    private RecyclerView recyclerView;
    private PlaylistAdapter adapter;
    private MusicPlayerManager musicPlayerManager;
    private TextView tvPlaylistTitle;

    private boolean isDragging = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        musicPlayerManager = MusicPlayerManager.getInstance(getContext());

        tvPlaylistTitle = view.findViewById(R.id.tv_playlist_title);
        updateTitle();

        recyclerView = view.findViewById(R.id.recycler_view_playlist);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new PlaylistAdapter(musicPlayerManager.getPlaylist());
        recyclerView.setAdapter(adapter);

        // Scroll to current song
        int currentIndex = musicPlayerManager.getCurrentIndex();
        if (currentIndex >= 0 && currentIndex < adapter.getItemCount()) {
            recyclerView.scrollToPosition(currentIndex);
        }

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {

            @Override
            public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isDragging = true;
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                isDragging = false;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int fromPos = viewHolder.getAdapterPosition();
                int toPos = target.getAdapterPosition();

                musicPlayerManager.moveInPlaylist(fromPos, toPos);
                adapter.notifyItemMoved(fromPos, toPos);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Not using swipe to delete here, using button
            }
        });
        itemTouchHelper.attachToRecyclerView(recyclerView);

        musicPlayerManager.addOnPlaylistChangedListener(playlist -> {
            if (adapter != null && !isDragging) {
                adapter.setSongs(playlist);
            }
            updateTitle();
        });

        musicPlayerManager.addOnSongChangedListener(song -> {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void updateTitle() {
        if (tvPlaylistTitle != null && musicPlayerManager != null) {
            int count = musicPlayerManager.getPlaylist().size();
            tvPlaylistTitle.setText("Current Playlist (" + count + ")");
        }
    }

    private class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {
        private List<Song> songs;

        public PlaylistAdapter(List<Song> songs) {
            this.songs = songs;
        }

        public void setSongs(List<Song> songs) {
            this.songs = songs;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist_song, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Song song = songs.get(position);
            holder.title.setText(song.name);
            holder.artist.setText(song.artists);
            holder.sequence.setText(String.valueOf(position + 1));

            int currentIndex = musicPlayerManager.getCurrentIndex();
            if (position == currentIndex) {
                int color = ContextCompat.getColor(holder.itemView.getContext(), R.color.brand_primary);
                holder.title.setTextColor(color);
                holder.sequence.setTextColor(color);
            } else {
                holder.title.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary));
                holder.sequence.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_secondary));
            }

            holder.itemView.setOnClickListener(v -> {
                musicPlayerManager.play(holder.getAdapterPosition());
                dismiss();
            });

            holder.btnRemove.setOnClickListener(v -> {
                musicPlayerManager.removeFromPlaylist(holder.getAdapterPosition());
            });
        }

        @Override
        public int getItemCount() {
            return songs.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView title, artist, sequence;
            ImageButton btnRemove;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.song_title);
                artist = itemView.findViewById(R.id.song_artist);
                sequence = itemView.findViewById(R.id.song_sequence);
                btnRemove = itemView.findViewById(R.id.btn_remove);
            }
        }
    }
}
