package dev.prototype.lovense;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import static org.junit.Assert.*;

/** Renders real Android drawing code in Robolectric; no JNI or Bluetooth is loaded. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class VisualPreviewTest {
    @Test public void renderLightDualAndSingle() throws Exception {
        render("light-dual", true);
        render("light-single", false);
    }

    @Test @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    public void renderDarkDual() throws Exception { render("dark-dual", true); }

    private void render(String name, boolean dual) throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, new android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(),MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE,true).putExtra(WelcomeActivity.EXTRA_ROLE,"owner")).setup()) {
            MainActivity activity = controller.get();
            try {
                JSONArray features = new JSONArray().put(new JSONObject().put("index",0).put("label","Vibration 1").put("steps",20));
                if(dual)features.put(new JSONObject().put("index",1).put("label","Vibration 2").put("steps",100));
                JSONObject device = new JSONObject().put("id",7).put("name","Preview device").put("features",features);
                activity.onRelay(RelayClient.json("state","phase","paired","role","owner","epoch","fixture","online",true,"active",false,"ready",true,"device",device));
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
                View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
                measure(root);
                // Enable the drawn controls without executing JNI. No looper drain occurs
                // while this fixture flag is true; cleanup restores it before Activity.pause().
                set(activity, "relayOnline", true);
                SessionGate gate=(SessionGate)field(activity,"session"); gate.requestStart();gate.state("fixture",true,true,false,true);
                set(activity, "acceptedValues", dual ? new double[]{.4,0} : new double[]{.4});
                set(activity, "visible", true); set(activity, "stopping", false);
                method("updateEnabled").invoke(activity);
                TouchPadView pad = (TouchPadView) field(activity, "pad");
                pad.setHold(true);
                pad.setTargets(dual ? new double[]{.4, 0} : new double[]{.4});
                ((TextView) field(activity, "status")).setText("Connected · preview setpoints");
                method("renderReadouts").invoke(activity);
                measure(root);
                settleDrawables(root);
                Button stop = (Button) field(activity, "stop");
                assertTrue("Stop remains visible", stop.getTop() >= 0 && stop.getBottom() <= 800);
                assertTrue("Pad has useful height", pad.getHeight() >= 140);
                Bitmap bitmap = Bitmap.createBitmap(720, 1600, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap); canvas.scale(2, 2); root.draw(canvas);
                File directory = new File("build/outputs/ui-preview");
                assertTrue(directory.isDirectory() || directory.mkdirs());
                File output = new File(directory, name + ".png");
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                }
                assertTrue(output.length() > 10000);
                bitmap.recycle();
                System.out.println("Rendered preview: " + output.getCanonicalPath());
            } finally { set(activity, "initialized", false); set(activity, "ready", false); }
        }
    }
    private static void measure(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 360, 800);
    }
    private static void settleDrawables(View view) {
        view.jumpDrawablesToCurrentState();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) settleDrawables(group.getChildAt(i));
        }
    }
    private static Method method(String name, Class<?>... types) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); return method;
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
}
