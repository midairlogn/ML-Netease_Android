package com.midairlogn.mlnetease;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class HomeShortcutAdapter extends RecyclerView.Adapter<HomeShortcutAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(HomeEntry entry);
    }

    public interface OnManageClickListener {
        void onManageClick(HomeEntry entry);
    }

    private List<HomeEntry> entries = new ArrayList<>();
    private OnItemClickListener listener;
    private OnManageClickListener manageClickListener;

    public void setEntries(List<HomeEntry> entries) {
        this.entries = entries;
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setOnManageClickListener(OnManageClickListener listener) {
        this.manageClickListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_home_shortcut, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HomeEntry entry = entries.get(position);
        holder.title.setText(entry.title);
        holder.subtitle.setText(entry.subtitle);
        holder.type.setText(entry.badge);
        holder.sequence.setText(String.valueOf(position + 1));
        holder.btnManage.setVisibility(entry.showManageIcon ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(entry);
            }
        });
        holder.btnManage.setOnClickListener(v -> {
            if (manageClickListener != null) {
                manageClickListener.onManageClick(entry);
            }
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView sequence;
        TextView title;
        TextView subtitle;
        TextView type;
        ImageButton btnManage;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            sequence = itemView.findViewById(R.id.shortcut_sequence);
            title = itemView.findViewById(R.id.shortcut_title);
            subtitle = itemView.findViewById(R.id.shortcut_subtitle);
            type = itemView.findViewById(R.id.shortcut_type);
            btnManage = itemView.findViewById(R.id.btn_shortcut_manage);
        }
    }
}
