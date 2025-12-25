package com.midairlogn.mlnetease;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class LyricsAdapter extends RecyclerView.Adapter<LyricsAdapter.LyricViewHolder> {

    private List<LyricLine> lyrics = new ArrayList<>();
    private int activeIndex = -1;

    public void setLyrics(List<LyricLine> lyrics) {
        this.lyrics = lyrics;
        notifyDataSetChanged();
    }

    public void setActiveIndex(int index) {
        if (index == activeIndex) return;
        int oldIndex = activeIndex;
        activeIndex = index;
        if (oldIndex >= 0 && oldIndex < lyrics.size()) notifyItemChanged(oldIndex);
        if (activeIndex >= 0 && activeIndex < lyrics.size()) notifyItemChanged(activeIndex);
    }

    @NonNull
    @Override
    public LyricViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lyric, parent, false);
        return new LyricViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LyricViewHolder holder, int position) {
        LyricLine line = lyrics.get(position);
        holder.text.setText(line.text);

        if (position == activeIndex) {
            holder.text.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary));
            holder.text.setAlpha(1.0f);
            holder.text.setTextSize(16);
        } else {
            holder.text.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_secondary));
            holder.text.setAlpha(0.6f);
            holder.text.setTextSize(13);
        }
    }

    @Override
    public int getItemCount() {
        return lyrics.size();
    }

    static class LyricViewHolder extends RecyclerView.ViewHolder {
        TextView text;

        public LyricViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.text_lyric_line);
        }
    }
}
