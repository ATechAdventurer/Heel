package dev.prototype.lovense;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BuildVariantTest {
    @Test public void packageLabelAndDefaultRoleAgree() {
        Context context = RuntimeEnvironment.getApplication();
        String id = context.getPackageName();
        String role = id.equals("dev.prototype.lovense.devowner") ? "owner"
                : id.equals("dev.prototype.lovense.devpet") ? "pet" : "";
        assertEquals("dev.prototype.lovense" + (role.isEmpty() ? "" : ".dev" + role), id);
        assertEquals(role, context.getString(R.string.default_role));
        String label = role.isEmpty() ? "Heel" : "Heel Dev " + (role.equals("owner") ? "Owner" : "Pet");
        assertEquals(label, context.getApplicationInfo().loadLabel(context.getPackageManager()).toString());
    }

    @Test public void rememberedRoleTakesPriorityOverDeveloperDefaultWithoutGrantingControl() {
        Context context = RuntimeEnvironment.getApplication();
        try {
            for (String role : new String[]{"owner", "pet"}) {
                RolePreferences.choose(context, role);
                try (var controller = Robolectric.buildActivity(WelcomeActivity.class).setup()) {
                    Intent route = Shadows.shadowOf(controller.get()).getNextStartedActivity();
                    assertEquals(PairingActivity.class.getName(), route.getComponent().getClassName());
                    assertEquals(context.getPackageName(), route.getComponent().getPackageName());
                    assertEquals(role, route.getStringExtra(WelcomeActivity.EXTRA_ROLE));
                    assertFalse(route.getBooleanExtra(PairingActivity.EXTRA_REMOTE, false));
                }
            }
        } finally { RolePreferences.reset(context); }
    }

    @Test public void connectionNotificationIdentifiesItsAppAndKeepsPause() throws Exception {
        // Build without starting the service: this inspects the real notification without connecting.
        ConnectionService service = Robolectric.buildService(ConnectionService.class).get();
        Method method = ConnectionService.class.getDeclaredMethod("notification", String.class);
        method.setAccessible(true);
        Notification notification = (Notification) method.invoke(service, "Paused");
        assertEquals(service.getString(R.string.app_name), notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Pause", notification.actions[0].title.toString());
        Intent pause = Shadows.shadowOf(notification.actions[0].actionIntent).getSavedIntent();
        assertEquals(service.getPackageName(), pause.getComponent().getPackageName());
        assertEquals(ConnectionService.class.getName(), pause.getComponent().getClassName());
        assertEquals(ConnectionService.PAUSE, pause.getAction());
    }
}
