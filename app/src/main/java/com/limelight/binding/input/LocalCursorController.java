package com.limelight.binding.input;

import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.ui.LocalCursorView;

/**
 * Emulated local cursor, similar to Parsec's.
 *
 * The client owns the cursor position. Mouse motion moves a locally drawn arrow right away and
 * sends the new absolute position to the host, so the arrow never waits for the video round trip.
 *
 * While enabled, this class intercepts every pointer-moving packet NvConnection sends (physical
 * mouse, touch trackpad, controller mouse emulation, evdev). Relative moves are integrated into
 * the local position, and absolute positions replace it, so the arrow and the host cursor never
 * disagree because some input path bypassed the local cursor.
 *
 * Positions are kept in host-stream pixels as floats, so slow movement accumulates fractions
 * instead of being lost to rounding.
 */
public class LocalCursorController implements NvConnection.MouseMoveInterceptor {
    private final NvConnection conn;
    private final View videoView;
    private final LocalCursorView cursorView;
    // Size of the video the host sends, in the orientation the decoder receives it
    private final int hostWidth;
    private final int hostHeight;
    private final float sizeMultiplier;
    private final float sensitivity;
    // Stream pixels per host desktop pixel. Apollo can render its virtual desktop larger than the
    // stream (resolution scale factor), which shrinks the host cursor and relative motion in it.
    private final float streamPixelsPerDesktopPixel;
    private final Runnable refreshRunnable = () -> {
        synchronized (this) {
            refreshPosted = false;
        }
        refreshOnUiThread();
    };

    // Guarded by this. Host-stream pixel coordinates, in [0, hostWidth - 1] x [0, hostHeight - 1].
    private float cursorX = -1;
    private float cursorY = -1;
    // Last position sent to the host, or Integer.MIN_VALUE if the host may be somewhere else
    private int sentX = Integer.MIN_VALUE;
    private int sentY = Integer.MIN_VALUE;
    private boolean refreshPosted;

    private volatile boolean enabled;
    private volatile boolean suspended;

    public LocalCursorController(NvConnection conn, View videoView, LocalCursorView cursorView,
                                 int streamWidth, int streamHeight, float desktopScale,
                                 float sizeMultiplier, float sensitivity) {
        this.conn = conn;
        this.videoView = videoView;
        this.cursorView = cursorView;
        this.hostWidth = Math.max(streamWidth, 2);
        this.hostHeight = Math.max(streamHeight, 2);
        this.streamPixelsPerDesktopPixel = desktopScale > 0 ? 1 / desktopScale : 1;
        this.sizeMultiplier = sizeMultiplier * streamPixelsPerDesktopPixel;
        this.sensitivity = sensitivity;

        cursorView.setVisibility(View.GONE);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** UI thread only. */
    public void setEnabled(boolean enable) {
        if (enable == enabled) {
            return;
        }

        if (enable) {
            synchronized (this) {
                if (cursorX < 0 || cursorY < 0) {
                    // No known position yet: start in the middle of the screen
                    cursorX = (hostWidth - 1) / 2f;
                    cursorY = (hostHeight - 1) / 2f;
                }
                // Without feedback from the host, the only way to sync is to move the host cursor
                // to where we draw ours.
                sentX = sentY = Integer.MIN_VALUE;
                sendPositionLocked();
            }
            enabled = true;
            conn.setMouseMoveInterceptor(this);
        }
        else {
            enabled = false;
            conn.setMouseMoveInterceptor(null);
        }

        refreshOnUiThread();
    }

    /** Hides the cursor while input is not grabbed, keeping its position. UI thread only. */
    public void setSuspended(boolean suspend) {
        suspended = suspend;
        refreshOnUiThread();
    }

    /** Re-runs placement after the video view moves, scales, or changes size. UI thread only. */
    public void onVideoGeometryChanged() {
        if (enabled) {
            refreshOnUiThread();
        }
    }

    /**
     * Applies motion from a captured physical mouse. These are raw device counts, which the host
     * treats as one desktop pixel each for relative motion, so we keep that 1:1 mapping (times
     * the user's sensitivity) to feel the same as relative mode.
     */
    public void onPhysicalMouseMotion(float deltaX, float deltaY) {
        float scale = sensitivity * streamPixelsPerDesktopPixel;
        moveBy(deltaX * scale, deltaY * scale);
    }

    @Override
    public boolean onRelativeMouseMove(short deltaX, short deltaY) {
        if (!enabled) {
            return false;
        }
        // Relative deltas are host desktop pixels
        moveBy(deltaX * streamPixelsPerDesktopPixel, deltaY * streamPixelsPerDesktopPixel);
        return true;
    }

    @Override
    public boolean onRelativeMouseMove(short deltaX, short deltaY, short referenceWidth, short referenceHeight) {
        if (!enabled) {
            return false;
        }
        if (referenceWidth <= 0 || referenceHeight <= 0) {
            return onRelativeMouseMove(deltaX, deltaY);
        }
        else {
            moveBy(deltaX * (hostWidth / (float) referenceWidth), deltaY * (hostHeight / (float) referenceHeight));
        }
        return true;
    }

    @Override
    public boolean onAbsoluteMousePosition(short x, short y, short referenceWidth, short referenceHeight) {
        if (!enabled || referenceWidth <= 1 || referenceHeight <= 1) {
            return false;
        }

        synchronized (this) {
            // Same scaling the host applies to absolute packets, whose reference size is sent
            // minus one (see InputStream.c), so the far edge maps to the last host pixel
            cursorX = clamp(x / (float) (referenceWidth - 1) * (hostWidth - 1), hostWidth);
            cursorY = clamp(y / (float) (referenceHeight - 1) * (hostHeight - 1), hostHeight);
            sendPositionLocked();
        }
        requestRefresh();
        return true;
    }

    /**
     * Moves the cursor to where some other input (a native pen) already put the host cursor,
     * without sending a mouse packet. UI thread only.
     */
    public void followExternalPointer(float x, float y, float referenceWidth, float referenceHeight) {
        if (!enabled || referenceWidth <= 0 || referenceHeight <= 0) {
            return;
        }

        synchronized (this) {
            cursorX = clamp(x / referenceWidth * (hostWidth - 1), hostWidth);
            cursorY = clamp(y / referenceHeight * (hostHeight - 1), hostHeight);
            // The host cursor is wherever that input put it, so our next move must be sent
            sentX = sentY = Integer.MIN_VALUE;
        }
        requestRefresh();
    }

    private void moveBy(float deltaX, float deltaY) {
        if (deltaX == 0 && deltaY == 0) {
            return;
        }

        synchronized (this) {
            float newX = clamp(cursorX + deltaX, hostWidth);
            float newY = clamp(cursorY + deltaY, hostHeight);
            if (newX == cursorX && newY == cursorY) {
                // Pushing against an edge
                return;
            }
            cursorX = newX;
            cursorY = newY;
            sendPositionLocked();
        }
        requestRefresh();
    }

    private static float clamp(float value, int size) {
        return Math.max(0, Math.min(value, size - 1));
    }

    // Sends the position if it moved by at least a whole host pixel since the last send
    private void sendPositionLocked() {
        int x = Math.round(cursorX);
        int y = Math.round(cursorY);
        if (x == sentX && y == sentY) {
            return;
        }
        sentX = x;
        sentY = y;
        conn.sendMousePositionDirect((short) x, (short) y, (short) hostWidth, (short) hostHeight);
    }

    // Places the arrow once per display frame, however many input events arrive in between.
    // Choreographer runs animation callbacks right before drawing, so this lands in the same
    // frame a direct update would.
    private void requestRefresh() {
        synchronized (this) {
            if (refreshPosted) {
                return;
            }
            refreshPosted = true;
        }
        cursorView.postOnAnimation(refreshRunnable);
    }

    private void refreshOnUiThread() {
        float x, y;
        synchronized (this) {
            x = cursorX;
            y = cursorY;
        }

        int viewWidth = videoView.getWidth();
        int viewHeight = videoView.getHeight();
        if (!enabled || suspended || viewWidth <= 0 || viewHeight <= 0 || x < 0 || y < 0) {
            if (cursorView.getVisibility() != View.GONE) {
                cursorView.setVisibility(View.GONE);
            }
            return;
        }

        // Map the host position into the video view, then through the view's pan/zoom transform
        // into the shared parent's coordinates. The cursor view is a sibling of the video view.
        // Axes scale separately because stretch mode can distort the video, and the host cursor
        // in it, non-uniformly.
        float pixelsPerHostPixelX = viewWidth / (float) hostWidth;
        float pixelsPerHostPixelY = viewHeight / (float) hostHeight;
        float localX = x * pixelsPerHostPixelX;
        float localY = y * pixelsPerHostPixelY;
        float scaleX = videoView.getScaleX();
        float scaleY = videoView.getScaleY();
        float pivotX = videoView.getPivotX();
        float pivotY = videoView.getPivotY();
        float parentX = videoView.getLeft() + videoView.getTranslationX() + pivotX + (localX - pivotX) * scaleX;
        float parentY = videoView.getTop() + videoView.getTranslationY() + pivotY + (localY - pivotY) * scaleY;

        cursorView.setCursorScale(pixelsPerHostPixelX * scaleX * sizeMultiplier,
                pixelsPerHostPixelY * scaleY * sizeMultiplier);
        cursorView.setHotspotPosition(parentX, parentY);
        if (cursorView.getVisibility() != View.VISIBLE) {
            cursorView.setVisibility(View.VISIBLE);
        }
    }
}
