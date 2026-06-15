package com.midairlogn.mlnetease.shared.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A ScrollView subclass that includes a dummy mScroller field to satisfy 
 * Huawei's AwareAnimationSmooth reflection-based optimizations and silence
 * the "No field in reflect mScroller" error log.
 */
public class HuaweiSafeScrollView extends ScrollView {

    @Keep
    private Object mScroller; 

    public HuaweiSafeScrollView(@NonNull Context context) {
        super(context);
    }

    public HuaweiSafeScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public HuaweiSafeScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
