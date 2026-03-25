package com.midairlogn.mlnetease;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ShortcutAdapter extends RecyclerView.Adapter<ShortcutAdapter.ViewHolder> {

    private final List<HomeShortcut> shortcuts;
    private final OnItemInteractionListener listener;

    public interface OnItemInteractionListener {
        void onEdit(HomeShortcut shortcut);
        void onDelete(int position);
    }

    public ShortcutAdapter(List<HomeShortcut> shortcuts, OnItemInteractionListener listener) {
        this.shortcuts = shortcuts;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_manage_home_shortcut, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HomeShortcut shortcut = shortcuts.get(position);
        holder.tvSequence.setText(String.valueOf(position + 1));
        holder.tvTitle.setText(shortcut.title);
        String display_shortcut_type = "";
        if(shortcut.type.equals(HomeShortcut.TYPE_PLAYLIST)) {
            display_shortcut_type = holder.itemView.getContext().getString(R.string.playlist);
        }
        else if(shortcut.type.equals(HomeShortcut.TYPE_ALBUM)) {
            display_shortcut_type = holder.itemView.getContext().getString(R.string.album);
        }
        else{
            display_shortcut_type = shortcut.type;
        }
        holder.tvMeta.setText(display_shortcut_type + " (ID: " + shortcut.id + ")");
        holder.itemView.setOnClickListener(v -> listener.onEdit(shortcut));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(holder.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return shortcuts.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvSequence, tvTitle, tvMeta;
        ImageButton btnDelete;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSequence = itemView.findViewById(R.id.tv_manage_shortcut_sequence);
            tvTitle = itemView.findViewById(R.id.tv_manage_shortcut_title);
            tvMeta = itemView.findViewById(R.id.tv_manage_shortcut_meta);
            btnDelete = itemView.findViewById(R.id.btn_shortcut_delete);
        }
    }
}
