package dev.prototype.lovense;

import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class OwnerHapticsTest {
    static class Output implements OwnerHaptics.Output {
        final ArrayList<Float> strengths = new ArrayList<>(); int cancels;
        public void tick(float strength) { strengths.add(strength); }
        public void cancel() { cancels++; }
    }
    @Test public void noticeableFeedbackScalesWithoutRestartingOnEveryMotion() {
        Output output = new Output(); OwnerHaptics feedback = new OwnerHaptics(output);
        feedback.update(.1); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,output.strengths.size());
        for (int i=0;i<100;i++) feedback.update(.1+i*.009);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(144));
        assertEquals(1,output.strengths.size());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertEquals(2,output.strengths.size());
        assertTrue(output.strengths.get(1)>output.strengths.get(0));
        // Holding a level keeps pulsing even without another update/touch event.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        assertEquals(12,output.strengths.size());
        for(float strength:output.strengths) assertTrue(strength>=.50f && strength<=1f);
        feedback.stop();
    }
    @Test public void restAndStopCancelPendingAndActiveFeedback() {
        Output output = new Output(); OwnerHaptics feedback = new OwnerHaptics(output);
        feedback.update(1); feedback.stop();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertTrue(output.strengths.isEmpty());
        feedback.update(.5); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,output.strengths.size());
        feedback.update(.05);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(1,output.strengths.size()); assertEquals(2,output.cancels);
    }
    @Test public void invalidOrZeroNeverBuzzes() {
        Output output = new Output(); OwnerHaptics feedback = new OwnerHaptics(output);
        feedback.update(0); feedback.update(Double.NaN); feedback.update(-1);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertTrue(output.strengths.isEmpty());
    }
    @Test public void olderPhonesReceiveShortPulseAndCancelImmediately() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.provider.Settings.System.putInt(context.getContentResolver(),
            android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        android.os.Vibrator vibrator = context.getSystemService(android.os.Vibrator.class);
        org.robolectric.shadows.ShadowVibrator motor = Shadows.shadowOf(vibrator);
        motor.setHasVibrator(true);
        for (boolean amplitudeControl : new boolean[] {false, true}) {
            motor.setHasAmplitudeControl(amplitudeControl);
            OwnerHaptics feedback = new OwnerHaptics(new android.view.View(context));
            feedback.update(.5);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(motor.isVibrating());
            assertEquals(18, motor.getMilliseconds());
            feedback.stop();
            assertFalse(motor.isVibrating());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse(motor.isVibrating());
        }
    }
    @Test public void blockedLiveBackendExplainsWhyAndManualProbeIsIndependent() {
        android.content.Context context=RuntimeEnvironment.getApplication();
        android.os.Vibrator vibrator=context.getSystemService(android.os.Vibrator.class);
        var motor=Shadows.shadowOf(vibrator);motor.setHasVibrator(true);motor.setHasAmplitudeControl(true);
        android.provider.Settings.System.putInt(context.getContentResolver(),android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,0);
        android.view.View view=new android.view.View(context);OwnerHaptics feedback=new OwnerHaptics(view);
        feedback.update(.8);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(motor.isVibrating());assertTrue(feedback.diagnostics().contains("legacy system haptic setting=0"));
        feedback.test(1,view);assertTrue(motor.isVibrating());assertEquals(18,motor.getMilliseconds());
        assertTrue(feedback.diagnostics().contains("TEST B pulse18ms requested"));
        feedback.cancelTests();assertFalse(motor.isVibrating());
        for(int i=0;i<100;i++)feedback.gate("rest",0);
        assertEquals(1,feedback.diagnostics().split("LIVE gate=rest",-1).length-1);
    }
    @Test @Config(sdk=31) public void reportedTickSupportStillUsesPerceptiblePulseWithTouchAttributes() {
        android.content.Context context=RuntimeEnvironment.getApplication();
        android.provider.Settings.System.putInt(context.getContentResolver(),android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,1);
        android.os.Vibrator vibrator=context.getSystemService(android.os.Vibrator.class);
        var motor=Shadows.shadowOf(vibrator);motor.setHasVibrator(true);
        motor.setSupportedPrimitives(java.util.List.of(android.os.VibrationEffect.Composition.PRIMITIVE_TICK));
        OwnerHaptics feedback=new OwnerHaptics(new android.view.View(context));
        feedback.update(.8);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(feedback.diagnostics().contains("backend=pulse18ms cadence=100-150ms usage=touch"));
        assertTrue(motor.getPrimitiveSegmentsInPrimitiveEffects().isEmpty());
        assertEquals(18,motor.getMilliseconds());
        android.os.VibrationAttributes attributes=(android.os.VibrationAttributes)motor.getVibrationAttributesFromLastVibration();
        assertEquals(android.os.VibrationAttributes.USAGE_TOUCH,attributes.getUsage());
        feedback.stop();assertFalse(motor.isVibrating());
        motor.setSupportedPrimitives(java.util.List.of());feedback.update(.8);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(feedback.diagnostics().contains("backend=pulse18ms cadence=100-150ms usage=touch"));
        feedback.stop();
    }
}
