package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Draws a Windows-style arrow cursor over the stream.
 *
 * The arrow is recorded into the display list once per size change. Moving it only changes the
 * view's translation, which the RenderThread applies without re-recording or re-laying out, so a
 * move costs about as much as a property update.
 */
public class LocalCursorView extends View {
    // Arrow geometry in host pixels at 100% scale. The tip (hotspot) is at (0, 0).
    private static final float[] ARROW_POINTS = {
            0f, 0f,
            0f, 16.5f,
            4f, 12.6f,
            6.9f, 19.1f,
            9.4f, 18.0f,
            6.6f, 11.8f,
            12f, 11.8f,
    };
    private static final float ARROW_WIDTH = 12f;
    private static final float ARROW_HEIGHT = 19.1f;
    private static final float OUTLINE_WIDTH = 1f;
    // Room around the arrow for the outline, so the stroke isn't clipped by the view bounds.
    private static final float PADDING = 1.5f;

    private final Path arrowPath = new Path();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // View pixels per host pixel, per axis
    private float cursorScaleX = 1f;
    private float cursorScaleY = 1f;

    public LocalCursorView(Context context) {
        super(context);

        arrowPath.moveTo(ARROW_POINTS[0], ARROW_POINTS[1]);
        for (int i = 2; i < ARROW_POINTS.length; i += 2) {
            arrowPath.lineTo(ARROW_POINTS[i], ARROW_POINTS[i + 1]);
        }
        arrowPath.close();

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0xFFFFFFFF);

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeJoin(Paint.Join.MITER);
        outlinePaint.setStrokeWidth(OUTLINE_WIDTH);
        outlinePaint.setColor(0xFF000000);

        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    /** Sets how many view pixels one host pixel covers on each axis. */
    public void setCursorScale(float newScaleX, float newScaleY) {
        if (newScaleX <= 0 || newScaleY <= 0 ||
                (Math.abs(newScaleX - cursorScaleX) < 0.001f && Math.abs(newScaleY - cursorScaleY) < 0.001f)) {
            return;
        }
        cursorScaleX = newScaleX;
        cursorScaleY = newScaleY;
        requestLayout();
        invalidate();
    }

    /** Places the arrow tip at (x, y) in the parent's coordinate space. */
    public void setHotspotPosition(float x, float y) {
        setTranslationX(x - PADDING * cursorScaleX);
        setTranslationY(y - PADDING * cursorScaleY);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = (int) Math.ceil((ARROW_WIDTH + 2 * PADDING) * cursorScaleX);
        int height = (int) Math.ceil((ARROW_HEIGHT + 2 * PADDING) * cursorScaleY);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(cursorScaleX, cursorScaleY);
        canvas.translate(PADDING, PADDING);
        canvas.drawPath(arrowPath, fillPaint);
        canvas.drawPath(arrowPath, outlinePaint);
        canvas.restore();
    }
}
