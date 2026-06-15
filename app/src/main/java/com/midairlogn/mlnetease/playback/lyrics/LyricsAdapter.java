package com.midairlogn.mlnetease.playback.lyrics;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
    private static final long STYLE_ANIMATION_DURATION_MS = 260L;
    private static final float ACTIVE_TEXT_SIZE_SP = 20f;
    private static final float INACTIVE_TEXT_SIZE_SP = 15f;
    private static final float ACTIVE_TRANSLATION_SIZE_SP = 14f;
    private static final float INACTIVE_TRANSLATION_SIZE_SP = 13f;
    private static final float ACTIVE_TEXT_ALPHA = 1.0f;
    private static final float INACTIVE_TEXT_ALPHA = 0.6f;
    private static final float ACTIVE_TRANSLATION_ALPHA = 0.85f;
    private static final float INACTIVE_TRANSLATION_ALPHA = 0.5f;
    private static final PathInterpolator STYLE_INTERPOLATOR = new PathInterpolator(0.2f, 0f, 0f, 1f);
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
        return new LyricViewHolder(view);
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
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION || onLyricClickListener == null) {
                return;
            }
            onLyricClickListener.onLyricClick(lyrics.get(adapterPosition), adapterPosition);
        });

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
        int translationColor = textColor;
        float textAlpha = active ? ACTIVE_TEXT_ALPHA : INACTIVE_TEXT_ALPHA;
        float translationAlpha = active ? ACTIVE_TRANSLATION_ALPHA : INACTIVE_TRANSLATION_ALPHA;
        float textSizePx = spToPx(holder.itemView.getResources(), active ? ACTIVE_TEXT_SIZE_SP : INACTIVE_TEXT_SIZE_SP);
        float translationSizePx = spToPx(holder.itemView.getResources(), active ? ACTIVE_TRANSLATION_SIZE_SP : INACTIVE_TRANSLATION_SIZE_SP);

        holder.cancelStyleAnimation();

        if (!animate) {
            holder.text.setTextColor(textColor);
            holder.text.setAlpha(textAlpha);
            holder.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
            holder.translation.setTextColor(translationColor);
            holder.translation.setAlpha(translationAlpha);
            holder.translation.setTextSize(TypedValue.COMPLEX_UNIT_PX, translationSizePx);
            return;
        }

        int startTextColor = holder.text.getCurrentTextColor();
        int startTranslationColor = holder.translation.getCurrentTextColor();
        float startTextAlpha = holder.text.getAlpha();
        float startTranslationAlpha = holder.translation.getAlpha();
        float startTextSizePx = holder.text.getTextSize();
        float startTranslationSizePx = holder.translation.getTextSize();

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        holder.styleAnimator = animator;
        animator.setDuration(STYLE_ANIMATION_DURATION_MS);
        animator.setInterpolator(STYLE_INTERPOLATOR);
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            holder.text.setTextColor((int) ARGB_EVALUATOR.evaluate(progress, startTextColor, textColor));
            holder.text.setAlpha(lerp(startTextAlpha, textAlpha, progress));
            holder.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, lerp(startTextSizePx, textSizePx, progress));

            holder.translation.setTextColor((int) ARGB_EVALUATOR.evaluate(progress, startTranslationColor, translationColor));
            holder.translation.setAlpha(lerp(startTranslationAlpha, translationAlpha, progress));
            holder.translation.setTextSize(TypedValue.COMPLEX_UNIT_PX, lerp(startTranslationSizePx, translationSizePx, progress));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (holder.styleAnimator == animation) {
                    holder.styleAnimator = null;
                }
            }
        });
        animator.start();
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

        public LyricViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.text_lyric_line);
            translation = itemView.findViewById(R.id.text_lyric_translation);
        }

        void cancelStyleAnimation() {
            if (styleAnimator != null) {
                styleAnimator.cancel();
                styleAnimator = null;
            }
        }
    }
}
