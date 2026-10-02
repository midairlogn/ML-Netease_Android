package com.midairlogn.mlnetease.shared.ui;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public final class ResponsiveGrid {

    public interface FullSpanChecker {
        boolean isFullSpan(int position);
    }

    @NonNull
    public static GridLayoutManager apply(@NonNull RecyclerView recyclerView, int minColumnWidthDp) {
        return apply(recyclerView, minColumnWidthDp, null);
    }

    @NonNull
    public static GridLayoutManager apply(@NonNull RecyclerView recyclerView, int minColumnWidthDp, @Nullable FullSpanChecker fullSpanChecker) {
        AutoSpanGridLayoutManager layoutManager = new AutoSpanGridLayoutManager(recyclerView.getContext(), minColumnWidthDp, fullSpanChecker);
        recyclerView.setLayoutManager(layoutManager);
        return layoutManager;
    }

    private static final class AutoSpanGridLayoutManager extends GridLayoutManager {
        private final int minColumnWidthPx;

        AutoSpanGridLayoutManager(Context context, int minColumnWidthDp, @Nullable FullSpanChecker fullSpanChecker) {
            super(context, 1);
            this.minColumnWidthPx = Math.round(minColumnWidthDp * context.getResources().getDisplayMetrics().density);
            if (fullSpanChecker != null) {
                setSpanSizeLookup(new SpanSizeLookup() {
                    @Override
                    public int getSpanSize(int position) {
                        if (fullSpanChecker.isFullSpan(position)) {
                            return AutoSpanGridLayoutManager.this.getSpanCount();
                        }
                        return 1;
                    }
                });
            }
        }

        @Override
        public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
            updateSpanCount();
            super.onLayoutChildren(recycler, state);
        }

        private void updateSpanCount() {
            int availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
            if (availableWidth <= 0 || minColumnWidthPx <= 0) {
                return;
            }
            int spanCount = Math.max(1, availableWidth / minColumnWidthPx);
            if (spanCount != getSpanCount()) {
                setSpanCount(spanCount);
            }
        }
    }

    private ResponsiveGrid() {
    }
}
