package com.midairlogn.mlnetease;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import androidx.appcompat.widget.AppCompatImageView;

public class ZoomImageView extends AppCompatImageView {

    private Matrix matrix;
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    private PointF last = new PointF();
    private PointF start = new PointF();
    private float minScale = 1f;
    private float maxScale = 5f;
    private float[] m;

    private int viewWidth, viewHeight;
    private static final int CLICK = 3;
    private float saveScale = 1f;
    protected float origWidth, origHeight;
    private int oldMeasuredWidth, oldMeasuredHeight;

    private float paddingTop = 0;
    private float paddingBottom = 0;

    private ScaleGestureDetector mScaleDetector;

    public ZoomImageView(Context context) {
        super(context);
        sharedConstructing(context);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        sharedConstructing(context);
    }

    private void sharedConstructing(Context context) {
        super.setClickable(true);
        mScaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        matrix = new Matrix();
        m = new float[9];
        setImageMatrix(matrix);
        setScaleType(ScaleType.MATRIX);

        // Calculate padding to avoid UI elements (X button at top, Download button at bottom)
        float density = context.getResources().getDisplayMetrics().density;
        paddingTop = 64 * density; // 16dp margin + 48dp button
        paddingBottom = 96 * density; // 32dp margin + 48dp button + extra room

        setOnTouchListener((v, event) -> {
            mScaleDetector.onTouchEvent(event);
            PointF curr = new PointF(event.getX(), event.getY());

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    last.set(curr);
                    start.set(last);
                    mode = DRAG;
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG) {
                        float deltaX = curr.x - last.x;
                        float deltaY = curr.y - last.y;
                        float fixTransX = getFixDragTrans(deltaX, viewWidth, origWidth * saveScale, 0, 0);
                        float fixTransY = getFixDragTrans(deltaY, viewHeight, origHeight * saveScale, paddingTop, paddingBottom);
                        matrix.postTranslate(fixTransX, fixTransY);
                        fixTrans();
                        last.set(curr.x, curr.y);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    mode = NONE;
                    int xDiff = (int) Math.abs(curr.x - start.x);
                    int yDiff = (int) Math.abs(curr.y - start.y);
                    if (xDiff < CLICK && yDiff < CLICK)
                        performClick();
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    mode = NONE;
                    break;
            }

            setImageMatrix(matrix);
            invalidate();
            return true;
        });
    }

    public void resetZoom() {
        saveScale = 1f;
        mode = NONE;
        oldMeasuredWidth = 0;
        oldMeasuredHeight = 0;
        matrix.reset();
        setImageMatrix(matrix);
        requestLayout();
        invalidate();
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            mode = ZOOM;
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float mScaleFactor = detector.getScaleFactor();
            float origScale = saveScale;
            saveScale *= mScaleFactor;
            if (saveScale > maxScale) {
                saveScale = maxScale;
                mScaleFactor = maxScale / origScale;
            } else if (saveScale < minScale) {
                saveScale = minScale;
                mScaleFactor = minScale / origScale;
            }

            if (origWidth * saveScale <= viewWidth || origHeight * saveScale <= viewHeight)
                matrix.postScale(mScaleFactor, mScaleFactor, viewWidth / 2, viewHeight / 2);
            else
                matrix.postScale(mScaleFactor, mScaleFactor, detector.getFocusX(), detector.getFocusY());

            fixTrans();
            return true;
        }
    }

    void fixTrans() {
        matrix.getValues(m);
        float transX = m[Matrix.MTRANS_X];
        float transY = m[Matrix.MTRANS_Y];

        float fixTransX = getFixTrans(transX, viewWidth, origWidth * saveScale, 0, 0);
        float fixTransY = getFixTrans(transY, viewHeight, origHeight * saveScale, paddingTop, paddingBottom);

        if (fixTransX != 0 || fixTransY != 0)
            matrix.postTranslate(fixTransX, fixTransY);
    }

    float getFixTrans(float trans, float viewSize, float contentSize, float paddingStart, float paddingEnd) {
        float minTrans, maxTrans;

        if (contentSize <= (viewSize - paddingStart - paddingEnd)) {
            // If content fits within padded area, center it
            minTrans = (viewSize - contentSize - paddingEnd + paddingStart) / 2;
            maxTrans = minTrans;
        } else {
            // If content is larger, allow movement but respect padding
            minTrans = viewSize - contentSize - paddingEnd;
            maxTrans = paddingStart;
        }

        if (trans < minTrans)
            return -trans + minTrans;
        if (trans > maxTrans)
            return -trans + maxTrans;
        return 0;
    }

    float getFixDragTrans(float delta, float viewSize, float contentSize, float paddingStart, float paddingEnd) {
        if (contentSize <= (viewSize - paddingStart - paddingEnd)) {
            return 0;
        }
        return delta;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        viewWidth = View.MeasureSpec.getSize(widthMeasureSpec);
        viewHeight = View.MeasureSpec.getSize(heightMeasureSpec);

        if (oldMeasuredHeight == viewWidth && oldMeasuredHeight == viewHeight
                || viewWidth == 0 || viewHeight == 0)
            return;
        oldMeasuredHeight = viewHeight;
        oldMeasuredWidth = viewWidth;

        if (saveScale == 1) {
            //Fit to screen.
            float scale;

            Drawable drawable = getDrawable();
            if (drawable == null || drawable.getIntrinsicWidth() == 0 || drawable.getIntrinsicHeight() == 0)
                return;
            int bmWidth = drawable.getIntrinsicWidth();
            int bmHeight = drawable.getIntrinsicHeight();

            float scaleX = (float) viewWidth / (float) bmWidth;
            float scaleY = (float) (viewHeight - paddingTop - paddingBottom) / (float) bmHeight;
            scale = Math.min(scaleX, scaleY);
            matrix.setScale(scale, scale);

            // Center the image in padded area
            float redundantYSpace = (float) (viewHeight - paddingTop - paddingBottom) - (scale * (float) bmHeight);
            float redundantXSpace = (float) viewWidth - (scale * (float) bmWidth);
            redundantYSpace /= (float) 2;
            redundantXSpace /= (float) 2;

            matrix.postTranslate(redundantXSpace, paddingTop + redundantYSpace);

            origWidth = viewWidth - 2 * redundantXSpace;
            origHeight = (viewHeight - paddingTop - paddingBottom) - 2 * redundantYSpace;
            setImageMatrix(matrix);
        }
        fixTrans();
    }
}
