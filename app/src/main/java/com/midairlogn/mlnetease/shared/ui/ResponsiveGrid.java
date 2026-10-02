package com.midairlogn.mlnetease.shared.ui;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public final class ResponsiveGrid {

    public static void apply(@NonNull RecyclerView recyclerView, int minColumnWidthDp) {
        GridLayoutManager layoutManager = new GridLayoutManager(recyclerView.getContext(), 1);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.post(new Runnable() {
            @Override
            public void run() {
                float density = recyclerView.getResources().getDisplayMetrics().density;
                if (density <= 0f || recyclerView.getWidth() == 0) {
                    return;
                }
                int widthDp = Math.round(recyclerView.getWidth() / density);
                int spanCount = Math.max(1, widthDp / minColumnWidthDp);
                RecyclerView.LayoutManager current = recyclerView.getLayoutManager();
                if (current instanceof GridLayoutManager) {
                    ((GridLayoutManager) current).setSpanCount(spanCount);
                }
            }
        });
    }

    private ResponsiveGrid() {
    }
}
