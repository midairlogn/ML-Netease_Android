package com.midairlogn.mlnetease.playback.lyrics;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.shared.model.LyricLine;

import java.util.ArrayList;
import java.util.List;

public class LyricsAdapter extends RecyclerView.Adapter<LyricsAdapter.LyricViewHolder> {

    private static final Object PAYLOAD_ACTIVE_STATE = new Object();
    private static final long STYLE_ANIMATION_DURATION_MS = 400L;
    private static final float ACTIVE_TEXT_SIZE_SP = 20f;
    private static final float INACTIVE_TEXT_SIZE_SP = 17f;
    private static final float ACTIVE_TRANSLATION_SIZE_SP = 14f;
    private static final float INACTIVE_TRANSLATION_SIZE_SP = 13f;
    private static final float ACTIVE_TEXT_ALPHA = 1.0f;
    private static final float INACTIVE_TEXT_ALPHA = 0.55f;
    private static final float ACTIVE_TRANSLATION_ALPHA = 0.8f;
    private static final float INACTIVE_TRANSLATION_ALPHA = 0.4f;
    private static final PathInterpolator STYLE_INTERPOLATOR = new PathInterpolator(0.33f, 0f, 0.1f, 1f);
    private static final ArgbEvaluator ARGB_EVALUATOR = new ArgbEvaluator();

    public interface OnLyricClickListener {
        void onLyricClick(LyricLine line, int position);
    }

    private List<LyricLine> lyrics = new ArrayList<>();
    private int activeIndex = -1;
    private boolean showTranslation = false;
    private OnLyricClickListener onLyricClickListener;

    public void setLyrics(List<LyricLine> lyrics) {
        this.lyrics = lyrics;
        activeIndex = -1;
        notifyDataSetChanged();
    }

    public void setShowTranslation(boolean showTranslation) {
        if (this.showTranslation == showTranslation) return;
        this.showTranslation = showTranslation;
        notifyDataSetChanged();
    }

    public void setOnLyricClickListener(OnLyricClickListener onLyricClickListener) {
        this.onLyricClickListener = onLyricClickListener;
    }

    public void setActiveIndex(int index) {
        if (index == activeIndex) return;
        int oldIndex = activeIndex;
        activeIndex = index;

        if (oldIndex >= 0 && oldIndex < lyrics.size()) notifyItemChanged(oldIndex, PAYLOAD_ACTIVE_STATE);
        if (activeIndex >= 0 && activeIndex < lyrics.size()) notifyItemChanged(activeIndex, PAYLOAD_ACTIVE_STATE);
    }

    @NonNull
    @Override
    public LyricViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lyric, parent, false);
        LyricViewHolder holder = new LyricViewHolder(view);
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION || onLyricClickListener == null) {
                return;
            }
            onLyricClickListener.onLyricClick(lyrics.get(adapterPosition), adapterPosition);
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull LyricViewHolder holder, int position) {
        bindLyric(holder, position);
        applyActiveState(holder, position == activeIndex, false);
    }

    @Override
    public void onBindViewHolder(@NonNull LyricViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        applyActiveState(holder, position == activeIndex, true);
    }

    private void bindLyric(@NonNull LyricViewHolder holder, int position) {
        LyricLine line = lyrics.get(position);
        holder.text.setText(line.text);

        boolean hasTranslation = !TextUtils.isEmpty(line.translation);
        if (showTranslation && hasTranslation) {
            holder.translation.setVisibility(View.VISIBLE);
            holder.translation.setText(line.translation);
        } else {
            holder.translation.setVisibility(View.GONE);
            holder.translation.setText("");
        }
    }

    private void applyActiveState(@NonNull LyricViewHolder holder, boolean active, boolean animate) {
        int textColor = ContextCompat.getColor(holder.itemView.getContext(), active ? R.color.text_primary : R.color.text_secondary);
        float textAlpha = active ? ACTIVE_TEXT_ALPHA : INACTIVE_TEXT_ALPHA;
        float translationAlpha = active ? ACTIVE_TRANSLATION_ALPHA : INACTIVE_TRANSLATION_ALPHA;
        float textSizePx = spToPx(holder.itemView.getResources(), active ? ACTIVE_TEXT_SIZE_SP : INACTIVE_TEXT_SIZE_SP);
        float translationSizePx = spToPx(holder.itemView.getResources(), active ? ACTIVE_TRANSLATION_SIZE_SP : INACTIVE_TRANSLATION_SIZE_SP);

        holder.cancelStyleAnimation();

        if (!animate) {
            holder.text.setTextColor(textColor);
            holder.text.setAlpha(textAlpha);
            holder.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
            holder.text.setScaleX(1f);
            holder.text.setScaleY(1f);
            holder.translation.setTextColor(textColor);
            holder.translation.setAlpha(translationAlpha);
            holder.translation.setTextSize(TypedValue.COMPLEX_UNIT_PX, translationSizePx);
            holder.translation.setScaleX(1f);
            holder.translation.setScaleY(1f);
            return;
        }

        holder.startTextColor = holder.text.getCurrentTextColor();
        holder.startTextAlpha = holder.text.getAlpha();
        holder.startTranslationAlpha = holder.translation.getAlpha();
        float startTextSizePx = holder.text.getTextSize();
        float startTranslationSizePx = holder.translation.getTextSize();

        // Target layout properties applied immediately to trigger correct measurement
        holder.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        holder.translation.setTextSize(TypedValue.COMPLEX_UNIT_PX, translationSizePx);

        // Visual scaling to bridge the gap
        holder.startTextScale = startTextSizePx / textSizePx;
        holder.startTranslationScale = startTranslationSizePx / translationSizePx;

        holder.targetTextColor = textColor;
        holder.targetTextAlpha = textAlpha;
        holder.targetTranslationAlpha = translationAlpha;

        holder.text.setScaleX(holder.startTextScale);
        holder.text.setScaleY(holder.startTextScale);
        holder.translation.setScaleX(holder.startTranslationScale);
        holder.translation.setScaleY(holder.startTranslationScale);

        holder.styleAnimator.start();
    }

    private static float spToPx(@NonNull Resources resources, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.getDisplayMetrics());
    }

    private static float lerp(float start, float end, float progress) {
        return start + ((end - start) * progress);
    }

    @Override
    public int getItemCount() {
        return lyrics.size();
    }

    @Override
    public void onViewRecycled(@NonNull LyricViewHolder holder) {
        holder.cancelStyleAnimation();
        super.onViewRecycled(holder);
    }

    static class LyricViewHolder extends RecyclerView.ViewHolder {
        TextView text;
        TextView translation;
        ValueAnimator styleAnimator;

        int targetTextColor;
        float targetTextAlpha;
        float targetTranslationAlpha;
        float startTextScale;
        float startTranslationScale;
        int startTextColor;
        float startTextAlpha;
        float startTranslationAlpha;

        public LyricViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.text_lyric_line);
            translation = itemView.findViewById(R.id.text_lyric_translation);

            styleAnimator = ValueAnimator.ofFloat(0f, 1f);
            styleAnimator.setDuration(STYLE_ANIMATION_DURATION_MS);
            styleAnimator.setInterpolator(STYLE_INTERPOLATOR);
            styleAnimator.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();

                int currentColor = (int) ARGB_EVALUATOR.evaluate(progress, startTextColor, targetTextColor);
                text.setTextColor(currentColor);
                text.setAlpha(lerp(startTextAlpha, targetTextAlpha, progress));
                translation.setTextColor(currentColor);
                translation.setAlpha(lerp(startTranslationAlpha, targetTranslationAlpha, progress));

                float currentTextScale = lerp(startTextScale, 1f, progress);
                text.setScaleX(currentTextScale);
                text.setScaleY(currentTextScale);
                float currentTransScale = lerp(startTranslationScale, 1f, progress);
                translation.setScaleX(currentTransScale);
                translation.setScaleY(currentTransScale);
            });
        }

        void cancelStyleAnimation() {
            if (styleAnimator != null) {
                styleAnimator.cancel();
            }
        }
    }
}
