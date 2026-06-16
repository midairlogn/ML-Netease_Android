package com.midairlogn.mlnetease.shared.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.HorizontalScrollView;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A HorizontalScrollView subclass that includes a dummy mScroller field to satisfy 
 * Huawei's AwareAnimationSmooth reflection-based optimizations and silence
 * the "No field in reflect mScroller" error log.
 */
public class HuaweiSafeHorizontalScrollView extends HorizontalScrollView {

    @Keep
    private Object mScroller; 

    public HuaweiSafeHorizontalScrollView(@NonNull Context context) {
        super(context);
    }

    public HuaweiSafeHorizontalScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public HuaweiSafeHorizontalScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
