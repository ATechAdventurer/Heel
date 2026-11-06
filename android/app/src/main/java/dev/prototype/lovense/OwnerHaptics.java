package dev.prototype.lovense;

import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.*;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.Consumer;

/** Foreground phone feedback with bounded diagnostics independent of toy/relay logs. */
final class OwnerHaptics {
    interface Output { void tick(float strength); void cancel(); }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Output output;
    private final PhoneOutput phone;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private String lastGate = "";
    private double level;
    private boolean running;
    OwnerHaptics(View view) { this(new PhoneOutput(view)); }
    OwnerHaptics(Output output) {
        this.output = output; phone = output instanceof PhoneOutput ? (PhoneOutput) output : null;
        if (phone != null) phone.log = this::note;
    }
    private void note(String message) {
        history.addLast(String.format(Locale.US, "%tT +%dms %s", System.currentTimeMillis(), SystemClock.uptimeMillis(), message));
        while (history.size() > 400) history.removeFirst();
    }
    void gate(String reason, double requested) {
        String state = reason + " levelBand=" + Math.round(requested * 10);
        if (!lastGate.equals(state)) { lastGate = state; note("LIVE gate=" + state); }
    }
    void snapshot() { if (phone != null) phone.snapshot(); }
    String diagnostics() {
        return "Heel haptic diagnostics (requests are not proof of physical vibration)\n"
            + Build.MANUFACTURER + " " + Build.MODEL + " | Android " + Build.VERSION.RELEASE
            + " API " + Build.VERSION.SDK_INT + "\n" + String.join("\n", history);
    }
    void test(int kind, View source) { stop(); if (phone != null) phone.test(kind, source); }
    void cancelTests() { if (phone != null && phone.testPending) phone.cancel(); }
    private final Runnable pulse = new Runnable() {
        public void run() {
            if (!running || level <= 0) return;
            output.tick((float)(.50 + .50 * level));
            handler.postDelayed(this, Math.round(150 - 50 * level));
        }
    };
    void update(double next) {
        level = Double.isFinite(next) ? Math.max(0, Math.min(1, next)) : 0;
        if (level <= TouchModel.REST_LIMIT) { stop(); return; }
        if (!running) { running = true; note("LIVE loop started"); handler.post(pulse); }
    }
    void stop() {
        level = 0; handler.removeCallbacks(pulse);
        if (running) { running = false; output.cancel(); note("LIVE loop stopped"); }
    }
    private static final class PhoneOutput implements Output {
        private final View view;
        private final Vibrator vibrator;
        private final AudioAttributes touch = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        private Consumer<String> log = ignored -> {};
        private String lastResult = "";
        private long lastReport;
        private boolean testPending;
        PhoneOutput(View view) { this.view = view; vibrator = view.getContext().getSystemService(Vibrator.class); }
        private int setting() { return Settings.System.getInt(view.getContext().getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1); }
        private boolean tickReported() { return vibrator != null && Build.VERSION.SDK_INT >= 30 && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK); }
        void snapshot() {
            try {
                android.content.Context c = view.getContext();
                android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
                AudioManager audio = c.getSystemService(AudioManager.class);
                NotificationManager notifications = c.getSystemService(NotificationManager.class);
                log.accept("ENV package=" + c.getPackageName() + " version=" + p.versionName + " code=" + p.versionCode
                    + " vibratePermission=" + (c.checkSelfPermission(android.Manifest.permission.VIBRATE) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    + " viewHaptic=" + view.isHapticFeedbackEnabled() + " legacySetting=" + setting()
                    + " hasVibrator=" + (vibrator != null && vibrator.hasVibrator())
                    + " amplitude=" + (vibrator != null && vibrator.hasAmplitudeControl())
                    + " tickReported=" + tickReported() + " tickQueryReliable=" + (Build.VERSION.SDK_INT >= 31)
                    + " ringer=" + (audio == null ? -1 : audio.getRingerMode())
                    + " interruptionFilter=" + (notifications == null ? -1 : notifications.getCurrentInterruptionFilter()));
            } catch (Exception e) { error("snapshot", e); }
        }
        private void result(String text) {
            long now = SystemClock.uptimeMillis();
            if (!text.equals(lastResult) || now - lastReport >= 5000) {
                lastResult = text; lastReport = now; log.accept(text);
            }
        }
        private void error(String operation, Exception e) {
            result("ERROR " + operation + " " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
        private VibrationEffect shortPulse(float strength) {
            return VibrationEffect.createOneShot(18, vibrator.hasAmplitudeControl()
                ? Math.round(255 * strength) : VibrationEffect.DEFAULT_AMPLITUDE);
        }
        public void tick(float strength) {
            try {
                if (!view.isHapticFeedbackEnabled()) { result("LIVE blocked: view haptic flag off"); return; }
                if (setting() == 0) { result("LIVE blocked: legacy system haptic setting=0"); return; }
                if (vibrator == null || !vibrator.hasVibrator()) { result("LIVE blocked: no vibrator"); return; }
                // Reported primitive support does not guarantee perceptible feedback.
                // Use the short pulse verified on the recipient's phone for every live request.
                vibrator.vibrate(shortPulse(strength), touch);
                result("LIVE requested backend=pulse18ms cadence=100-150ms usage=touch; API returned without exception");
            } catch (RuntimeException e) { error("live vibration", e); }
        }
        void test(int kind, View source) {
            snapshot();
            String label = kind == 0 ? "A platform click" : kind == 1 ? "B pulse18ms" : "C custom tick";
            log.accept("TEST " + label + " tapped (manual test; no app legacy-setting prefilter)");
            try {
                if (kind == 0) {
                    boolean accepted = source.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    log.accept("TEST A performHapticFeedback returned=" + accepted + " viewHaptic=" + source.isHapticFeedbackEnabled() + " attached=" + source.isAttachedToWindow());
                    return;
                }
                if (vibrator == null || !vibrator.hasVibrator()) { log.accept("TEST skipped: no vibrator"); return; }
                if (kind == 2 && Build.VERSION.SDK_INT < 30) { log.accept("TEST C unavailable before Android 11"); return; }
                testPending = true;
                VibrationEffect effect = kind == 1 ? shortPulse(1f)
                    : VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1f).compose();
                vibrator.vibrate(effect, touch);
                log.accept("TEST " + label + " requested strength=1 usage=touch; API returned without exception");
            } catch (RuntimeException e) { error(label, e); }
        }
        public void cancel() {
            try { if (vibrator != null) vibrator.cancel(); }
            catch (RuntimeException e) { error("cancel", e); }
            testPending = false; lastResult = "";
        }
    }
}
