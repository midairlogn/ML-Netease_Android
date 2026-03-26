package com.midairlogn.mlnetease;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class HomeShortcutAdapter extends RecyclerView.Adapter<HomeShortcutAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(HomeShortcut shortcut);
    }

    private List<HomeShortcut> shortcuts = new ArrayList<>();
    private OnItemClickListener listener;

    public void setShortcuts(List<HomeShortcut> shortcuts) {
        this.shortcuts = shortcuts;
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_home_shortcut, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HomeShortcut shortcut = shortcuts.get(position);
        holder.title.setText(shortcut.title);
        holder.subtitle.setText("ID: " + shortcut.id);
        holder.type.setText(shortcut.isPlaylist() ? R.string.playlist : R.string.album);
        holder.sequence.setText(String.valueOf(position + 1));
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(shortcut);
            }
        });
    }

    @Override
    public int getItemCount() {
        return shortcuts.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView sequence;
        TextView title;
        TextView subtitle;
        TextView type;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            sequence = itemView.findViewById(R.id.shortcut_sequence);
            title = itemView.findViewById(R.id.shortcut_title);
            subtitle = itemView.findViewById(R.id.shortcut_subtitle);
            type = itemView.findViewById(R.id.shortcut_type);
        }
    }
}
