package dev.prototype.lovense;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.RobolectricTestRunner;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WelcomeUiTest {
    @Before public void clearRole() { RolePreferences.reset(org.robolectric.RuntimeEnvironment.getApplication()); }
    @Test public void welcomeRolesOpenPairingAndRenderLight() throws Exception {
        try (ActivityController<WelcomeActivity> controller = Robolectric.buildActivity(WelcomeActivity.class).setup()) {
            WelcomeActivity activity = controller.get();
            if (assertDeveloperRoute(activity)) return;
            View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            render(root, 360, 800, "welcome-light");
            View owner = root.findViewWithTag("owner"), pet = root.findViewWithTag("pet");
            assertTrue(owner.isFocusable()); assertTrue(pet.isFocusable());
            assertTrue(owner.getWidth() >= 100 && pet.getWidth() >= 100);
            for (String role : new String[]{"owner", "pet"}) {
                assertTrue(root.findViewWithTag(role).performClick());
                Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(intent);
                assertEquals(PairingActivity.class.getName(), intent.getComponent().getClassName());
                assertEquals(role, intent.getStringExtra(WelcomeActivity.EXTRA_ROLE));
            }
            render(root, 320, 640, "welcome-narrow");
            assertTrue(owner.getRight() <= ((View) owner.getParent()).getWidth());
            assertTrue(pet.getRight() <= ((View) pet.getParent()).getWidth());
        }
    }

    @Test @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    public void welcomeDarkThemeRenders() throws Exception {
        try (ActivityController<WelcomeActivity> controller = Robolectric.buildActivity(WelcomeActivity.class).setup()) {
            if (assertDeveloperRoute(controller.get())) return;
            View root = ((ViewGroup) controller.get().findViewById(android.R.id.content)).getChildAt(0);
            render(root, 360, 800, "welcome-dark");
        }
    }

    @Test public void rememberedRoleSkipsPickAndLogoHasSettingsTarget() throws Exception {
        RolePreferences.choose(org.robolectric.RuntimeEnvironment.getApplication(), "pet");
        try (ActivityController<WelcomeActivity> controller = Robolectric.buildActivity(WelcomeActivity.class).setup()) {
            Intent route = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(PairingActivity.class.getName(), route.getComponent().getClassName()); assertEquals("pet", route.getStringExtra(WelcomeActivity.EXTRA_ROLE)); assertTrue(controller.get().isFinishing());
        }
        RolePreferences.reset(org.robolectric.RuntimeEnvironment.getApplication());
        try (ActivityController<WelcomeActivity> controller = Robolectric.buildActivity(WelcomeActivity.class).setup()) {
            if (assertDeveloperRoute(controller.get())) return;
            View root = ((ViewGroup)controller.get().findViewById(android.R.id.content)).getChildAt(0); render(root,360,800,"welcome-settings");
            View settings = findDescription(root,"Settings"); assertNotNull(settings); assertTrue(settings.getHeight()>=48); assertTrue(settings.performClick());
            assertNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        }
    }
    private static boolean assertDeveloperRoute(WelcomeActivity activity) {
        String role = activity.getString(R.string.default_role);
        if (role.isEmpty()) return false;
        Intent route = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(route);
        assertEquals(PairingActivity.class.getName(), route.getComponent().getClassName());
        assertEquals(role, route.getStringExtra(WelcomeActivity.EXTRA_ROLE));
        assertFalse(route.getBooleanExtra(PairingActivity.EXTRA_REMOTE, false));
        assertTrue(activity.isFinishing());
        return true;
    }
    private static View findDescription(View view,String description) { if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view; if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findDescription(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null; }

    private static void render(View root, int width, int height, String name) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        settle(root);
        Bitmap bitmap = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.scale(2, 2); root.draw(canvas);
        File directory = new File("build/outputs/ui-preview");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }
    private static void settle(View view) {
        view.jumpDrawablesToCurrentState();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) settle(((ViewGroup) view).getChildAt(i));
    }
}
