package dev.prototype.lovense;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;

/** Deliberate start-to-end gesture. A tap anywhere, including the far end, never activates it. */
public final class SafeWordView extends View {
    static final int ACTIVATE_SAFE_WORD = 0x01020001;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Runnable action;
    private int pointer = -1;
    private float downX, progress;
    private boolean latched;
    public SafeWordView(Context context) {
        super(context); setFocusable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Safe Word. Slide from left to right to stop and pause the session."); setMinimumHeight(dp(64));
    }
    public void setAction(Runnable value) { action = value; }
    /** Ordinary click activation is deliberately unavailable; accessibility exposes the named stop action. */
    @Override public boolean performClick() { return false; }
    public void setLatched(boolean value) { if (latched == value) return; latched = value; progress = 0; pointer = -1; invalidate(); }
    private float radius() { return dp(24); }
    private float start() { return dp(32); }
    private float travel() { return Math.max(1, getWidth() - dp(64)); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || latched) return false;
        int kind = event.getActionMasked();
        if (kind == MotionEvent.ACTION_DOWN) {
            if (Math.abs(event.getX() - start()) > radius() || Math.abs(event.getY() - getHeight()/2f) > radius()) return false;
            pointer = event.getPointerId(0); downX = event.getX(); progress = 0;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); invalidate(); return true;
        }
        if (pointer < 0) return false;
        int index = event.findPointerIndex(pointer);
        if (kind == MotionEvent.ACTION_CANCEL || index < 0 || kind == MotionEvent.ACTION_POINTER_DOWN) { resetGesture(); return true; }
        if (kind == MotionEvent.ACTION_MOVE || kind == MotionEvent.ACTION_UP) {
            progress = Math.abs(event.getY(index)-getHeight()/2f) <= dp(48) ? Math.max(0, Math.min(1, (event.getX(index)-downX)/travel())) : 0;
            if (kind == MotionEvent.ACTION_UP) { boolean activate = progress >= .9f; resetGesture(); if (activate) activate(); }
            else invalidate();
            return true;
        }
        return true;
    }
    private void resetGesture() { pointer = -1; progress = 0; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); invalidate(); }
    private void activate() { if (latched) return; latched = true; invalidate(); if (action != null) action.run(); }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == ACTIVATE_SAFE_WORD && isEnabled() && !latched) { activate(); return true; }
        if (action == AccessibilityNodeInfo.ACTION_CLICK) return false;
        return super.performAccessibilityAction(action, arguments);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.SeekBar"); info.setClickable(false);
        info.setContentDescription(latched ? "Safe Word activated. Session stopped." : getContentDescription());
        if (!latched) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTIVATE_SAFE_WORD, "Activate Safe Word and stop the session"));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float middle = getHeight()/2f;
        paint.setColor(Color.rgb(92, 28, 49)); canvas.drawRoundRect(0, dp(4), getWidth(), getHeight()-dp(4), dp(30), dp(30), paint);
        paint.setColor(Color.WHITE); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); paint.setTextSize(dp(15)); paint.setTextAlign(Paint.Align.CENTER);
        String label = latched ? "Safe Word · stopped" : progress > .85f ? "Release to stop" : "Slide for Safe Word";
        canvas.drawText(label, getWidth()/2f + (latched ? 0 : dp(12)), middle-(paint.ascent()+paint.descent())/2, paint);
        if (!latched) {
            paint.setColor(Color.rgb(249, 214, 226)); float x = start() + progress * travel(); canvas.drawCircle(x, middle, radius(), paint);
            paint.setColor(Color.rgb(92,28,49)); paint.setStrokeWidth(dp(3)); paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(x-dp(6),middle,x+dp(6),middle,paint); canvas.drawLine(x+dp(1),middle-dp(5),x+dp(6),middle,paint); canvas.drawLine(x+dp(1),middle+dp(5),x+dp(6),middle,paint); paint.setStyle(Paint.Style.FILL);
        }
    }
    @Override protected void onDetachedFromWindow() { resetGesture(); super.onDetachedFromWindow(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
