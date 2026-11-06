package dev.prototype.lovense;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PublicRelaySetupTest {
    @Before public void reset() throws Exception {
        Field instance = RelayClient.class.getDeclaredField("instance"); instance.setAccessible(true); instance.set(null, null);
        RuntimeEnvironment.getApplication().getSharedPreferences("heel-relay", Context.MODE_PRIVATE).edit().clear().commit();
    }
    @After public void stop() { RelayClient.get(RuntimeEnvironment.getApplication()).shutdown(); }
    @Test public void freshInstallDoesNotContactAnyDefaultServer() {
        RelayClient client = RelayClient.get(RuntimeEnvironment.getApplication());
        assertEquals("", client.endpoint());
        String[] reason = {""};
        client.attach(new RelayClient.Listener() {
            public void onRelay(org.json.JSONObject message) {}
            public void onConnection(boolean connected, String text) { reason[0] = text; }
        });
        client.connect("unused-code");
        assertFalse(client.connected());
        assertEquals("Set your relay address first.", reason[0]);
    }
    @Test public void bothRolesOfferRelaySetupBeforeEnrollment() {
        for (String role : new String[]{"owner", "pet"}) {
            Intent intent = new Intent(RuntimeEnvironment.getApplication(), PairingActivity.class).putExtra(WelcomeActivity.EXTRA_ROLE, role);
            try (var activity = Robolectric.buildActivity(PairingActivity.class, intent).setup()) {
                View root = activity.get().getWindow().getDecorView();
                assertTrue(contains(root, "Set relay address"));
                assertFalse(contains(root, "Connect this phone"));
            }
        }
    }
    @Test public void previouslySavedRelayIsRetainedAndUnsafeSchemesAreRejected() {
        CredentialStore store = new CredentialStore(RuntimeEnvironment.getApplication());
        store.endpoint("https://saved.example");
        RelayClient client = RelayClient.get(RuntimeEnvironment.getApplication());
        assertEquals("https://saved.example", client.endpoint());
        assertThrows(IllegalArgumentException.class, () -> client.endpoint("http://plain.example"));
        assertEquals("https://saved.example", client.endpoint());
    }
    private static boolean contains(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText())) return true;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) if (contains(((ViewGroup)view).getChildAt(i),text)) return true;
        return false;
    }
}
