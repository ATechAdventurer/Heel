package dev.prototype.lovense;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.Shadows;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Android runtime/UI checks without initializing JNI or connecting any Bluetooth hardware. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class ControlUiTest {
    @Test public void activityCreatesAndCapabilityCallbacksDoNotRecurse() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, new android.content.Intent(RuntimeEnvironment.getApplication(), MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE, true).putExtra(WelcomeActivity.EXTRA_ROLE, "owner")).setup()) {
            MainActivity activity = controller.get();
            injectDevice(activity);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            TouchPadView pad = field(activity, "pad", TouchPadView.class);
            assertEquals(2, pad.getFeatureCount());
            assertEquals(1, pad.getFeature(0).scalarIndex);
            assertEquals(3, pad.getFeature(1).scalarIndex);
            assertArrayEquals(new double[] {0, 0}, pad.getTargets(), 0);
            // Activity state refreshes should be repeatable without recursive listeners.
            Method update = MainActivity.class.getDeclaredMethod("updateEnabled"); update.setAccessible(true);
            for (int i = 0; i < 10; i++) update.invoke(activity);
            assertEquals(0, pad.getActiveTouchCount());
            assertFalse(field(activity, "hold", CheckBox.class).isChecked());
        }
    }

    @Test public void shortScreenKeepsStopVisibleAndPadScrollableAtLargeText() throws Exception {
        Resources resources = RuntimeEnvironment.getApplication().getResources();
        Configuration original = new Configuration(resources.getConfiguration());
        DisplayMetrics originalMetrics = new DisplayMetrics(); originalMetrics.setTo(resources.getDisplayMetrics());
        Configuration large = new Configuration(original); large.fontScale = 1.8f;
        DisplayMetrics largeMetrics = new DisplayMetrics(); largeMetrics.setTo(originalMetrics); largeMetrics.scaledDensity = largeMetrics.density * 1.8f;
        resources.updateConfiguration(large, largeMetrics);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, new android.content.Intent(RuntimeEnvironment.getApplication(), MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE, true).putExtra(WelcomeActivity.EXTRA_ROLE, "owner")).setup()) {
            MainActivity activity = controller.get(); injectDevice(activity);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            ViewGroup content = activity.findViewById(android.R.id.content);
            View root = content.getChildAt(0);
            int width = 320, height = 420;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            Button stop = field(activity, "stop", Button.class);
            TouchPadView pad = field(activity, "pad", TouchPadView.class);
            assertTrue("Stop remains inside the available body height", stop.getBottom() <= height - root.getPaddingBottom());
            assertTrue("Stop is not clipped above the viewport", stop.getTop() >= 0);
            assertTrue("Stop has an effective touch target", stop.getHeight() >= 48);
            assertTrue("Touch area must not collapse under weighted ScrollView measurement", pad.getMeasuredHeight() >= 140);
            assertTrue("Large text actually reached the Activity", stop.getTextSize() >= 20);
            // Landscape-height equivalent: body scrolls, bottom action remains reachable.
            root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 640, 280);
            assertTrue(stop.getTop() >= 0 && stop.getBottom() <= 280 - root.getPaddingBottom());
            assertTrue(pad.getMeasuredHeight() >= 140);
        } finally { resources.updateConfiguration(original, originalMetrics); }
    }

    @Test public void actualMotionEventsKeepPointersIndependentAndStopOldGestures() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            TouchPadView pad = new TouchPadView(controller.get());
            pad.configure(new TouchModel.Feature[] {new TouchModel.Feature(2,"A",20),new TouchModel.Feature(5,"B",100)});
            ArrayList<double[]> frames = new ArrayList<>(); int[] cancels = {0};
            pad.setListener(new TouchPadView.Listener() {
                @Override public void onTargetsChanged(double[] values) { frames.add(values); }
                @Override public void onStateChanged() {}
                @Override public void onCancelRequested() { cancels[0]++; }
            });
            controller.get().setContentView(pad);
            pad.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY)); pad.layout(0,0,360,240);
            float bottom = 212, travel = 184;
            dispatch(pad,MotionEvent.ACTION_DOWN,new int[]{9},new float[]{90},new float[]{bottom});
            dispatch(pad,MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),new int[]{9,3},new float[]{90,270},new float[]{bottom,bottom});
            assertEquals(2,pad.getActiveTouchCount()); assertTrue(frames.isEmpty());
            // Reorder the MotionEvent array while moving; pointer IDs remain authoritative.
            dispatch(pad,MotionEvent.ACTION_MOVE,new int[]{3,9},new float[]{270,90},new float[]{bottom-travel*(float)TouchModel.requestToTrack(.3),bottom-travel*(float)TouchModel.requestToTrack(.6)});
            assertArrayEquals(new double[]{.6,.3},pad.getTargets(),1e-6);
            dispatch(pad,MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),new int[]{3,9},new float[]{270,90},new float[]{bottom-travel*(float)TouchModel.requestToTrack(.3),bottom-travel*(float)TouchModel.requestToTrack(.6)});
            assertArrayEquals(new double[]{0,.3},pad.getTargets(),1e-6); assertEquals(1,pad.getActiveTouchCount());
            pad.clear();
            dispatch(pad,MotionEvent.ACTION_MOVE,new int[]{3},new float[]{270},new float[]{28});
            assertArrayEquals(new double[]{0,0},pad.getTargets(),0);
            dispatch(pad,MotionEvent.ACTION_UP,new int[]{3},new float[]{270},new float[]{28});
            pad.setHold(true);
            dispatch(pad,MotionEvent.ACTION_DOWN,new int[]{0},new float[]{90},new float[]{bottom});
            dispatch(pad,MotionEvent.ACTION_MOVE,new int[]{0},new float[]{90},new float[]{bottom-travel*(float)TouchModel.requestToTrack(.4)});
            dispatch(pad,MotionEvent.ACTION_CANCEL,new int[]{0},new float[]{90},new float[]{bottom-travel*(float)TouchModel.requestToTrack(.4)});
            assertArrayEquals(new double[]{0,0},pad.getTargets(),0); assertFalse(pad.isHolding()); assertEquals(1,cancels[0]);
        }
    }

    @Test public void disabledPadRetainsSynchronizedSliderTargetsDuringRefresh() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            TouchPadView pad = new TouchPadView(controller.get());
            pad.configure(new TouchModel.Feature[]{new TouchModel.Feature(0,"Vibration",20)});
            pad.setEnabled(false); pad.setTargets(new double[]{.5}); pad.setEnabled(false);
            assertArrayEquals(new double[]{.5},pad.getTargets(),0);
            pad.clear(); assertArrayEquals(new double[]{0},pad.getTargets(),0);
        }
    }

    @Test public void absoluteRestStopsHeldPointAfterOffsetPickupAndCrossing() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            TouchPadView pad = new TouchPadView(controller.get());
            pad.configure(new TouchModel.Feature[]{new TouchModel.Feature(0,"A",20),new TouchModel.Feature(1,"B",100)});
            controller.get().setContentView(pad);
            pad.layout(0,0,360,240); pad.setTargets(new double[]{.5,.4}); pad.setHold(true);
            float bottom=212, travel=184;
            float ringA=bottom-travel*(float)TouchModel.requestToTrack(.5);
            float ringB=bottom-travel*(float)TouchModel.requestToTrack(.4);
            dispatch(pad,MotionEvent.ACTION_DOWN,new int[]{9},new float[]{90},new float[]{ringA+15});
            dispatch(pad,MotionEvent.ACTION_POINTER_DOWN | (1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new int[]{9,3},new float[]{90,270},new float[]{ringA+15,ringB});
            assertArrayEquals(new double[]{.5,.4},pad.getTargets(),0);
            dispatch(pad,MotionEvent.ACTION_MOVE,new int[]{3,9},new float[]{40,320},new float[]{ringB,bottom-4});
            assertArrayEquals(new double[]{0,.4},pad.getTargets(),0);
            assertEquals(2,pad.getActiveTouchCount());
            dispatch(pad,MotionEvent.ACTION_MOVE,new int[]{3,9},new float[]{40,320},new float[]{ringB,bottom-travel*(float)TouchModel.requestToTrack(.1)});
            assertArrayEquals(new double[]{.1,.4},pad.getTargets(),1e-6);
            pad.clear();
        }
    }

    @Test public void sliderDraftAndMirroredTargetsShareInclusiveRest() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, new android.content.Intent(RuntimeEnvironment.getApplication(), MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE, true).putExtra(WelcomeActivity.EXTRA_ROLE, "owner")).setup()) {
            MainActivity activity=controller.get(); injectDevice(activity);
            Field mode=MainActivity.class.getDeclaredField("sliderMode");mode.setAccessible(true);mode.setBoolean(activity,true);
            double[] drafts=field(activity,"drafts",double[].class); boolean[] running=field(activity,"sliderRunning",boolean[].class);
            drafts[0]=.05; drafts[1]=.326; running[0]=running[1]=true;
            Method submit=MainActivity.class.getDeclaredMethod("submitSliders"); submit.setAccessible(true);
            submit.invoke(activity); // Closed control gate must not restore targets.
            assertArrayEquals(new double[]{0,0},field(activity,"pad",TouchPadView.class).getTargets(),0);
            // An already accepted identical canonical frame avoids JNI in this UI test.
            Field accepted=MainActivity.class.getDeclaredField("lastSubmittedValues"); accepted.setAccessible(true); accepted.set(activity,new double[]{0,.33});
            Field online=MainActivity.class.getDeclaredField("relayOnline"); online.setAccessible(true); online.setBoolean(activity,true);
            SessionGate gate=field(activity,"session",SessionGate.class); gate.state("fixture",true,true,true,false);gate.requestStart();gate.state("fixture",true,true,false,true);
            submit.invoke(activity);
            assertArrayEquals(new double[]{0,.33},field(activity,"pad",TouchPadView.class).getTargets(),0);
            assertEquals(">5%",MainActivity.formatPercent(.051)); assertEquals("0%",MainActivity.formatPercent(0));
        }
    }

    @Test public void smoothVisualMotionDoesNotSendExtraMotorStagesAndStopsImmediately() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            TouchPadView pad = new TouchPadView(controller.get());
            pad.configure(new TouchModel.Feature[]{new TouchModel.Feature(0,"Vibration",20)});
            ArrayList<double[]> frames = new ArrayList<>();
            pad.setListener(new TouchPadView.Listener() {
                public void onTargetsChanged(double[] values) { frames.add(values); }
                public void onStateChanged() {}
                public void onCancelRequested() {}
            });
            controller.get().setContentView(pad); pad.layout(0,0,360,240);
            dispatch(pad,MotionEvent.ACTION_DOWN,new int[]{0},new float[]{180},new float[]{212});
            for(double value:new double[]{.501,.505,.511,.519}) {
                float y=212-184*(float)TouchModel.requestToTrack(value);
                dispatch(pad,MotionEvent.ACTION_MOVE,new int[]{0},new float[]{180},new float[]{y});
                assertEquals(value,pad.getVisualTargets()[0],1e-6);
                assertEquals(.5,pad.getTargets()[0],0);
            }
            assertEquals("Visual changes within a motor stage must not send more frames",1,frames.size());
            pad.setHold(true);
            dispatch(pad,MotionEvent.ACTION_UP,new int[]{0},new float[]{180},new float[]{212-184*(float)TouchModel.requestToTrack(.519)});
            assertEquals(.519,pad.getVisualTargets()[0],1e-6);
            dispatch(pad,MotionEvent.ACTION_DOWN,new int[]{5},new float[]{180},new float[]{212-184*(float)TouchModel.requestToTrack(.519)});
            dispatch(pad,MotionEvent.ACTION_MOVE,new int[]{5},new float[]{180},new float[]{212});
            assertEquals(0,pad.getTargets()[0],0); assertEquals(0,pad.getVisualTargets()[0],0);
            assertEquals(0,frames.get(frames.size()-1)[0],0);
        }
    }

    private static void injectDevice(MainActivity activity) throws Exception {
        JSONObject device=new JSONObject().put("id",7).put("name","Runtime test device").put("features",new JSONArray().put(new JSONObject().put("index",1).put("label","Vibration 1").put("steps",20)).put(new JSONObject().put("index",3).put("label","Vibration 2").put("steps",100)));
        activity.onRelay(RelayClient.json("state","phase","paired","role","owner","epoch","fixture","online",true,"active",false,"ready",false,"device",device));
    }
    private static <T> T field(Object object,String name,Class<T> type) throws Exception {
        Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(object));
    }
    private static void dispatch(TouchPadView pad,int action,int[] ids,float[] xs,float[] ys) {
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[ids.length];
        for(int i=0;i<ids.length;i++) {
            properties[i]=new MotionEvent.PointerProperties(); properties[i].id=ids[i]; properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i]=new MotionEvent.PointerCoords(); coordinates[i].x=xs[i]; coordinates[i].y=ys[i]; coordinates[i].pressure=1; coordinates[i].size=.1f;
        }
        long now=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(now,now,action,ids.length,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try { assertTrue(pad.dispatchTouchEvent(event)); } finally {event.recycle();}
    }
}
