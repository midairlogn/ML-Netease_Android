package com.midairlogn.mlnetease.shared.ui;

import android.content.Context;
import android.util.AttributeSet;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A RecyclerView subclass that includes a dummy mScroller field to satisfy 
 * Huawei's AwareAnimationSmooth reflection-based optimizations and silence
 * the "No field in reflect mScroller" error log.
 */
public class HuaweiSafeRecyclerView extends RecyclerView {

    @Keep
    private Object mScroller; 

    public HuaweiSafeRecyclerView(@NonNull Context context) {
        super(context);
    }

    public HuaweiSafeRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public HuaweiSafeRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
