package dev.prototype.lovense;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.util.Arrays;

/** Foreground direct-control pad. Device/feature discovery and native output stay in the Activity. */
public final class TouchPadView extends View {
    public interface Listener {
        void onTargetsChanged(double[] normalized);
        void onStateChanged();
        /** Send the native priority Stop; ACTION_CANCEL applies to the complete gesture stream. */
        void onCancelRequested();
        default void onPlaybackTouched() {}
    }

    private final TouchModel model = new TouchModel();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final DashPathEffect heldDash;
    private final DashPathEffect dividerDash;
    private Listener listener;
    private int background, border, foreground, muted, pointOne, pointTwo, restShelf, wine;
    private long lastInputUptime;
    public long getLastInputUptime() { return lastInputUptime; }
    private int rejectedPoint = -1;
    private long feedbackEpoch;
    private boolean locked;
    private boolean ghost;
    public void setGhost(boolean value) { ghost = value; invalidate(); }
    public void setLocked(boolean value) { locked = value; }

    public TouchPadView(Context context) {
        super(context);
        heldDash = new DashPathEffect(new float[] { dp(4), dp(3) }, 0);
        dividerDash = new DashPathEffect(new float[] { dp(3), dp(4) }, 0);
        setMinimumHeight(dp(140));
        setClickable(true);
        setFocusable(false);
        // Touch exploration uses inline labeled setpoint controls with explicit Start/Stop.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setDarkTheme(false);
    }

    public void setListener(Listener replacement) { listener = replacement; }
    private boolean backgroundPlayback;
    public void setBackgroundPlayback(boolean enabled) { backgroundPlayback = enabled; }
    public void releaseForBackground() {
        double[] before = model.targets(); model.releaseTouches(); allowParentInterception();
        notifyTargetsIfChanged(before); invalidate(); notifyState();
    }

    public void configure(TouchModel.Feature[] features) {
        model.configure(features);
        clearFeedback();
        invalidate();
        notifyState();
    }

    public void setHold(boolean enabled) {
        double[] before = model.targets();
        model.setHold(enabled);
        notifyTargetsIfChanged(before);
        invalidate();
        notifyState();
    }

    /** No output callbacks: the caller separately issues the priority Stop when appropriate. */
    public void clear() {
        model.clear();
        clearFeedback();
        allowParentInterception();
        invalidate();
        notifyState();
    }

    /** For alternate native controls only; this is not a request to transmit output. */
    public void setTargets(double[] normalized) {
        model.setTargets(normalized);
        clearFeedback();
        invalidate();
        notifyState();
    }

    public void setVisualTargets(double[] normalized) {
        model.setVisualTargets(normalized); invalidate(); notifyState();
    }

    public double[] getVisualTargets() { return model.visualTargets(); }
    public double[] getTargets() { return model.targets(); }
    public boolean isHolding() { return model.isHolding(); }
    public int getActiveTouchCount() { return model.activeTouchCount(); }
    public boolean isPointActive(int point) { return point >= 0 && point < model.size() && model.isPointActive(point); }
    public int getFeatureCount() { return model.size(); }
    public TouchModel.Feature getFeature(int point) { return model.feature(point); }

    public void setDarkTheme(boolean dark) {
        restShelf = Color.parseColor(dark ? "#382E31" : "#DED0CA");
        wine = Color.parseColor(dark ? "#A65870" : "#7E2E43");
        background = Color.parseColor(dark ? "#2A2526" : "#E7E0DA");
        border = Color.parseColor(dark ? "#54464A" : "#C4B8B2");
        foreground = Color.parseColor(dark ? "#F3ECE5" : "#2E2828");
        muted = Color.parseColor(dark ? "#C0B1AA" : "#6F6463");
        pointOne = Color.parseColor(dark ? "#7FD9DF" : "#116A77");
        pointTwo = Color.parseColor(dark ? "#C7A7ED" : "#6C4AA1");
        invalidate();
    }

    @Override public void setEnabled(boolean enabled) {
        // Disabled pads may mirror the alternate slider controls. Reapplying the same
        // enabled state during a UI refresh must not erase those synchronized targets.
        if (!enabled && isEnabled()) clear();
        super.setEnabled(enabled);
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (oldWidth > 0 && oldHeight > 0 && (width != oldWidth || height != oldHeight)
                && (model.activeTouchCount() > 0 || model.hasOutput() || model.isHolding())) cancelAndStop();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus && (model.activeTouchCount() > 0 || model.hasOutput())) { if (backgroundPlayback) releaseForBackground(); else cancelAndStop(); }
    }

    @Override protected void onDetachedFromWindow() {
        if (model.activeTouchCount() > 0 || model.hasOutput()) { if (backgroundPlayback) releaseForBackground(); else cancelAndStop(); }
        super.onDetachedFromWindow();
    }

    private float trackTop() { return dp(28); }
    private float trackBottom() { return Math.max(trackTop() + 1, getHeight() - dp(28)); }
    private double position(float y) { return (trackBottom() - y) / (trackBottom() - trackTop()); }
    private float levelY(int point) {
        return trackBottom() - (float) TouchModel.requestToTrack(model.visualTarget(point)) * (trackBottom() - trackTop());
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || model.size() == 0 || getWidth() <= 0) return false;
        if (locked) return true;
        if (ghost && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // Explicit touch cancels playback. The same gesture can pick up its current paw.
            ghost = false;
            if (listener != null) listener.onPlaybackTouched();
        }
        lastInputUptime = event.getEventTime();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            if (backgroundPlayback) releaseForBackground(); else cancelAndStop();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN && model.activeTouchCount() > 0) {
            // Recover conservatively if an earlier gesture was lost without an UP/CANCEL.
            cancelAndStop();
            return true;
        }
        double[] before = model.targets();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                int pointerIndex = event.getActionIndex();
                float x = event.getX(pointerIndex);
                int point = Math.min(model.size() - 1, Math.max(0, (int) (x * model.size() / getWidth())));
                boolean accepted = model.begin(event.getPointerId(pointerIndex), point,
                    position(event.getY(pointerIndex)), dp(28) / (double) (trackBottom() - trackTop()));
                if (accepted) clearFeedback();
                else if (!model.isPointActive(point)) showPickupFeedback(point);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                // Event array order can change as fingers are added or removed. IDs do not.
                for (int i = 0; i < event.getPointerCount(); i++)
                    model.move(event.getPointerId(i), position(event.getY(i)));
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                model.move(event.getPointerId(event.getActionIndex()), position(event.getY(event.getActionIndex())));
                model.end(event.getPointerId(event.getActionIndex()));
                clearFeedback();
                if (action == MotionEvent.ACTION_UP) {
                    allowParentInterception();
                    performClick();
                }
                break;
            default:
                return true;
        }
        notifyTargetsIfChanged(before);
        invalidate();
        notifyState();
        return true;
    }

    @Override public boolean performClick() {
        // A click alone never engages vibration. Touch engagement must pass pickup checks.
        return super.performClick();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        if (width <= 2 || height <= 2) return;
        bounds.set(1, 1, width - 1, height - 1);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(background);
        canvas.drawRoundRect(bounds, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(border);
        canvas.drawRoundRect(bounds, dp(12), dp(12), paint);
        float restTop = trackBottom() - (float) TouchModel.REST_TRACK * (trackBottom() - trackTop());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(restShelf);
        bounds.set(dp(2), restTop, width - dp(2), height - dp(2));
        canvas.drawRoundRect(bounds, dp(10), dp(10), paint);
        // The exact cutoff is horizontal; the rounded corners stay below it.
        canvas.drawRect(dp(2), restTop, width - dp(2), restTop + dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(wine);
        canvas.drawLine(dp(12), restTop, width - dp(12), restTop, paint);
        // Blanket stitching stays outside the coordinate field, below baseline.
        paint.setPathEffect(heldDash);
        canvas.drawLine(dp(12), height - dp(5), width - dp(12), height - dp(5), paint);
        paint.setPathEffect(null);
        paint.setColor(border);
        for (int i = 0; i <= 4; i++) {
            float y = trackBottom() - (float) TouchModel.requestToTrack(i / 4.0) * (trackBottom() - trackTop());
            canvas.drawLine(dp(12), y, width - dp(12), y, paint);
        }
        if (model.size() == 2) {
            paint.setPathEffect(dividerDash);
            canvas.drawLine(width / 2, dp(12), width / 2, height - dp(12), paint);
            paint.setPathEffect(null);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(muted);
        paint.setTextSize(sp(11));
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("100%", width - dp(12), dp(17), paint);
        canvas.drawText("Rest · 0%", width - dp(12), height - dp(10), paint);
        // Tiny horn curves occupy the top margin, away from the track.
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(wine);
        bounds.set(dp(11), dp(5), dp(27), dp(21)); canvas.drawArc(bounds, 15, 285, false, paint);
        bounds.set(dp(17), dp(9), dp(24), dp(16)); canvas.drawArc(bounds, 210, 210, false, paint);
        paint.setStyle(Paint.Style.FILL);
        if (model.size() == 0) {
            paint.setColor(foreground);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(sp(14));
            canvas.drawText("No vibration controls", width / 2, height / 2, paint);
            return;
        }
        float zoneWidth = width / model.size();
        for (int i = 0; i < model.size(); i++) {
            int accent = i == 0 ? pointOne : pointTwo;
            if (!isEnabled()) accent = withAlpha(accent, 115);
            float x = zoneWidth * (i + .5f), y = levelY(i);
            boolean active = model.isPointActive(i);
            boolean held = model.isHolding() && model.target(i) > 0 && !active;
            paint.setColor(accent);
            paint.setStrokeWidth(dp(2));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(zoneWidth * i + dp(11), y, zoneWidth * (i + 1) - dp(11), y, paint);
            if (active || rejectedPoint == i) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(accent, 45));
                canvas.drawCircle(x, y, dp(25), paint);
            }
            // Numbered paw: a broad central pad and four toes, outlined like
            // the personal sticker. Geometry is fixed; it never lags the input.
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
            paint.setPathEffect(held ? heldDash : null); paint.setColor(accent);
            if (held) { paint.setColor(withAlpha(accent, 125)); canvas.drawCircle(x, y, dp(25), paint); }
            paint.setPathEffect(null);
            if (ghost) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(withAlpha(accent, 65)); canvas.drawCircle(x, y + dp(8), dp(30), paint); }
            int layer = ghost ? canvas.saveLayerAlpha(x-dp(35), y-dp(35), x+dp(35), y+dp(38), 145) : -1;
            pawOval(canvas, x - dp(14), y - dp(4), x + dp(14), y + dp(16), accent);
            pawOval(canvas, x - dp(21), y - dp(14), x - dp(12), y - dp(2), accent);
            pawOval(canvas, x - dp(11), y - dp(22), x - dp(1), y - dp(8), accent);
            pawOval(canvas, x + dp(1), y - dp(22), x + dp(11), y - dp(8), accent);
            pawOval(canvas, x + dp(12), y - dp(14), x + dp(21), y - dp(2), accent);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.parseColor("#F4EFE9"));
            paint.setTextSize(sp(13)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = paint.getFontMetrics();
            // Dark numeral keeps its contrast on both cyan and violet palettes.
            paint.setColor((Color.red(accent) * 299 + Color.green(accent) * 587 + Color.blue(accent) * 114) / 1000 > 150 ? Color.parseColor("#1D191A") : Color.parseColor("#F4EFE9"));
            canvas.drawText(Integer.toString(i + 1), x, y + dp(6) - (fm.ascent + fm.descent) / 2f, paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
            if (layer >= 0) canvas.restoreToCount(layer);
        }
    }

    private void pawOval(Canvas canvas, float left, float top, float right, float bottom, int accent) {
        bounds.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(5)); paint.setColor(Color.parseColor("#1D191A"));
        canvas.drawOval(bounds, paint);
        paint.setStrokeWidth(dp(3)); paint.setColor(Color.parseColor("#F4EFE9")); canvas.drawOval(bounds, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(accent); canvas.drawOval(bounds, paint);
    }

    private void cancelAndStop() {
        clear();
        if (listener != null) listener.onCancelRequested();
    }
    private void notifyTargetsIfChanged(double[] before) {
        double[] current = model.targets();
        if (!Arrays.equals(before, current) && listener != null) listener.onTargetsChanged(current);
    }
    private void notifyState() { if (listener != null) listener.onStateChanged(); }
    private void allowParentInterception() {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
    }
    private void showPickupFeedback(int point) {
        rejectedPoint = point;
        long epoch = ++feedbackEpoch;
        postDelayed(() -> { if (epoch == feedbackEpoch) { rejectedPoint = -1; invalidate(); } }, 700);
    }
    private void clearFeedback() { feedbackEpoch++; rejectedPoint = -1; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
    private static int withAlpha(int color, int alpha) { return (color & 0x00ffffff) | (alpha << 24); }
}
