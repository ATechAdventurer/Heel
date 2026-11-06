package dev.prototype.lovense;

import android.content.Context;
import android.content.Intent;
import android.widget.Button;
import okhttp3.*;
import okio.ByteString;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

/** The socket and pairing state exist BEFORE the control runtime, as on a real launch. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RelayHandoffTest {
    private Context context;
    private RelayClient relay;
    private final List<JSONObject> wire = new ArrayList<>();
    private final class Socket implements WebSocket {
        public Request request() { return new Request.Builder().url("https://example.test").build(); }
        public long queueSize() { return 0; }
        public boolean send(String text) { try { wire.add(new JSONObject(text)); return true; } catch (JSONException e) { throw new AssertionError(e); } }
        public boolean send(ByteString bytes) { return false; }
        public boolean close(int code,String reason) { return true; }
        public void cancel() {}
    }
    private static final class Engine implements ControlRuntime.Engine {
        int starts;
        final List<JSONObject> commands = new ArrayList<>();
        public String initialize() { starts++; return ""; }
        public String drain() { return "[]"; }
        public String submit(String text) { try { commands.add(new JSONObject(text)); return ""; } catch (JSONException e) { throw new AssertionError(e); } }
    }
    private static Field field(Class<?> type,String name) throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f; }
    @Before public void setup() throws Exception {
        field(ControlRuntime.class,"instance").set(null,null);
        field(RelayClient.class,"instance").set(null,null);
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("heel-session",Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("heel-relay",Context.MODE_PRIVATE).edit().clear().commit();
        RolePreferences.reset(context);
        relay=RelayClient.get(context);
    }
    @After public void stop() throws Exception {
        ControlRuntime runtime=(ControlRuntime)field(ControlRuntime.class,"instance").get(null);
        if (runtime!=null) runtime.stop(); else relay.shutdown();
    }
    private JSONObject state(String role) {
        return RelayClient.json("state","phase","paired","role",role,"epoch","paired-idle","online",true,"ready",false,"active",false);
    }
    private void cached(JSONObject state,boolean connected) throws Exception {
        field(RelayClient.class,"state").set(relay,state);
        field(RelayClient.class,"connected").set(relay,connected);
        field(RelayClient.class,"socket").set(relay,connected?new Socket():null);
        field(RelayClient.class,"pendingStop").set(relay,null);
    }
    @Test public void pairedSocketBeforePairingToControlsHandoffCanInitializeAndScan() throws Exception {
        cached(state("pet"),true);
        assertNull(field(ControlRuntime.class,"instance").get(null));
        Intent main;
        try (var pairing=Robolectric.buildActivity(PairingActivity.class,new Intent(context,PairingActivity.class).putExtra(WelcomeActivity.EXTRA_ROLE,"pet")).setup()) {
            main=Shadows.shadowOf(pairing.get()).getNextStartedActivity();
            assertEquals(MainActivity.class.getName(),main.getComponent().getClassName());
            assertEquals("pet",RolePreferences.get(context));
            assertNull(field(ControlRuntime.class,"instance").get(null));
        }
        try (var controls=Robolectric.buildActivity(MainActivity.class,main).setup()) {
            MainActivity activity=controls.get();
            ControlRuntime runtime=ControlRuntime.get(context);
            Engine engine=new Engine();runtime.engine=engine;
            Button scan=(Button)field(MainActivity.class,"scan").get(activity);
            // Exercise Scan's actual Activity-to-runtime boundary after platform permission checks.
            MainActivity.DeviceBridge bridge=(MainActivity.DeviceBridge)field(MainActivity.class,"engine").get(activity);
            assertEquals("",bridge.initialize());
            assertEquals(1,engine.starts);
            assertTrue(scan.isEnabled());
            assertTrue(runtime.online);assertTrue(runtime.pairedPet());assertTrue(runtime.initialized);
            runtime.nativeEvent(RelayClient.json("ready","active",true));
            assertEquals("",bridge.submit(RelayClient.json("scan","enabled",true).toString()));
            assertTrue(engine.commands.stream().anyMatch(m->m.optString("type").equals("scan")&&m.optBoolean("enabled")));
            assertFalse(runtime.gate.active()); // Scanning is not a session grant.
        }
    }
    @Test public void unpairedAndWrongRoleSnapshotsStillRejectNativeAccess() throws Exception {
        for (String[] roles : new String[][]{{"pet","unpaired"},{"pet","owner"},{"owner","pet"},{"owner","owner"}}) {
            field(ControlRuntime.class,"instance").set(null,null);RolePreferences.choose(context,roles[0]);
            JSONObject state=roles[1].equals("unpaired")?RelayClient.json("state","phase","unpaired"):state(roles[1]);
            cached(state,true);
            ControlRuntime runtime=ControlRuntime.get(context);Engine engine=new Engine();runtime.engine=engine;
            assertEquals("Pair this phone as Pet first.",runtime.initialize());assertEquals(0,engine.starts);
            runtime.initialized=true;
            assertEquals("Pet connection is required.",runtime.submit(RelayClient.json("scan","enabled",true).toString()));
            assertTrue(engine.commands.isEmpty());assertFalse(runtime.gate.active());
        }
    }
    @Test public void disconnectedRelayDoesNotReplayItsOldPairedSnapshot() throws Exception {
        RolePreferences.choose(context,"pet");cached(state("pet"),false);
        ControlRuntime runtime=ControlRuntime.get(context);runtime.engine=new Engine();
        assertFalse(runtime.online);assertFalse(runtime.pairedPet());assertFalse(runtime.state.has("phase"));
        assertEquals("Pair this phone as Pet first.",runtime.initialize());
    }
    @Test public void newRuntimeCannotInheritCachedActiveOrResumedAuthority() throws Exception {
        for (String role:new String[]{"owner","pet"}) for (boolean resumed:new boolean[]{false,true}) {
            field(ControlRuntime.class,"instance").set(null,null);RolePreferences.choose(context,role);
            JSONObject device=RelayClient.json("fixture","id",7,"features",new JSONArray().put(RelayClient.json("feature","index",0,"steps",20)));
            cached(state(role).put("active",true).put("resumed",resumed).put("resumeAllowed",true).put("device",device)
                    .put("restore",RelayClient.json("restore","device",device,"values",new JSONArray(new double[]{.9}),"hold",true)),true);
            wire.clear();ControlRuntime runtime=ControlRuntime.get(context);Engine engine=new Engine();runtime.engine=engine;
            assertTrue(runtime.online);assertFalse(runtime.gate.active());assertFalse(runtime.gate.canRecover());
            assertTrue(wire.stream().anyMatch(m->m.optString("type").equals("stop")));
            assertTrue(wire.stream().noneMatch(m->m.optString("type").equals("frame")));
            runtime.initialized=true;
            assertFalse(runtime.submit(RelayClient.json("vibration_frame").toString()).isEmpty());
            assertTrue(engine.commands.isEmpty());
        }
    }
    @Test public void persistedSafeWordStillBlocksScanningAfterPairedHandoff() throws Exception {
        context.getSharedPreferences("heel-session",Context.MODE_PRIVATE).edit().putBoolean("safeWord",true).commit();
        RolePreferences.choose(context,"pet");cached(state("pet"),true);
        ControlRuntime runtime=ControlRuntime.get(context);Engine engine=new Engine();runtime.engine=engine;
        assertTrue(runtime.safeWord);assertFalse(runtime.gate.active());
        assertEquals("",runtime.initialize());
        assertEquals("Choose Ready again before reconnecting.",runtime.submit(RelayClient.json("scan","enabled",true).toString()));
        assertTrue(engine.commands.isEmpty());
        assertTrue(field(RelayClient.class,"retryBlocked").getBoolean(relay));
    }
}
