package com.midairlogn.mlnetease.shared.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * Draws fitCenter content with anti-aliased rounded corners around the actual
 * image bounds, so non-square artwork is rounded as well. Vector placeholders
 * fall back to default rendering.
 */
public class RoundedArtworkView extends AppCompatImageView {

    private static final float CORNER_RADIUS_DP = 24f;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF contentBounds = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private final float cornerRadiusPx;
    private Bitmap shaderBitmap;
    private BitmapShader shader;

    public RoundedArtworkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cornerRadiusPx = CORNER_RADIUS_DP * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getScaleType() != ScaleType.FIT_CENTER) {
            super.onDraw(canvas);
            return;
        }
        Drawable drawable = getDrawable();
        Bitmap bitmap = drawable instanceof BitmapDrawable ? ((BitmapDrawable) drawable).getBitmap() : null;
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0
                || !computeContentBounds(bitmap, contentBounds)) {
            super.onDraw(canvas);
            return;
        }

        if (shader == null || shaderBitmap != bitmap) {
            shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaderBitmap = bitmap;
        }

        float scale = Math.min(
                contentBounds.width() / bitmap.getWidth(),
                contentBounds.height() / bitmap.getHeight());
        shaderMatrix.setScale(scale, scale);
        shaderMatrix.postTranslate(contentBounds.left, contentBounds.top);
        shader.setLocalMatrix(shaderMatrix);
        bitmapPaint.setShader(shader);

        canvas.drawRoundRect(contentBounds, cornerRadiusPx, cornerRadiusPx, bitmapPaint);
    }

    private boolean computeContentBounds(Bitmap bitmap, RectF out) {
        float availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (availableWidth <= 0 || availableHeight <= 0) {
            return false;
        }
        float scale = Math.min(availableWidth / bitmap.getWidth(), availableHeight / bitmap.getHeight());
        float drawnWidth = bitmap.getWidth() * scale;
        float drawnHeight = bitmap.getHeight() * scale;
        float left = getPaddingLeft() + (availableWidth - drawnWidth) / 2f;
        float top = getPaddingTop() + (availableHeight - drawnHeight) / 2f;
        out.set(left, top, left + drawnWidth, top + drawnHeight);
        return true;
    }
}
