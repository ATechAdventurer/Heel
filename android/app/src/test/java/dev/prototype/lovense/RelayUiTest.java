package dev.prototype.lovense;

import android.content.*;
import android.bluetooth.BluetoothAdapter;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import okhttp3.*;
import okio.ByteString;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28, qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class RelayUiTest {
    private final List<JSONObject> nativeCommands = new ArrayList<>(), wire = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private RelayClient relay;
    @Before public void resetClient() throws Exception { field(ControlRuntime.class,"instance").set(null,null); field(RelayClient.class,"instance").set(null,null); RuntimeEnvironment.getApplication().getSharedPreferences("heel-session",Context.MODE_PRIVATE).edit().clear().commit(); RolePreferences.reset(RuntimeEnvironment.getApplication()); }
    private final class Socket implements WebSocket {
        public Request request() { return new Request.Builder().url("https://example.test").build(); }
        public long queueSize() { return 0; }
        public boolean send(String text) { try { JSONObject m=new JSONObject(text); wire.add(m); order.add("wire:"+m.optString("type")); return true; } catch(Exception e) { throw new AssertionError(e); } }
        public boolean send(ByteString bytes) { return false; }
        public boolean close(int code,String reason) { return true; }
        public void cancel() {}
    }
    private ActivityController<MainActivity> petActivity() throws Exception { return petActivity(false); }
    private ActivityController<MainActivity> petActivity(boolean identity) throws Exception {
        ControlRuntime previous=(ControlRuntime)field(ControlRuntime.class,"instance").get(null);
        if (previous != null) previous.stop();
        field(ControlRuntime.class,"instance").set(null,null);field(RelayClient.class,"instance").set(null,null);
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE,true).putExtra(WelcomeActivity.EXTRA_ROLE,"pet");
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,intent).setup();
        MainActivity a=controller.get();
        ControlRuntime runtime = (ControlRuntime)get(a,"runtime"); runtime.engine = new ControlRuntime.Engine() {
            public String initialize() { return ""; }
            public String drain() { return "[]"; }
            public String submit(String text) { try { JSONObject m=new JSONObject(text); nativeCommands.add(m); order.add("native:"+m.optString("type")); return ""; } catch(Exception e) { throw new AssertionError(e); } }
        }; runtime.initialized = runtime.ready = true;
        set(a,"initialized",true); set(a,"ready",true); set(a,"visible",true); stopped(a);
        JSONArray scalar=new JSONArray().put(new JSONObject().put("ActuatorType","Rotate")).put(new JSONObject().put("ActuatorType","Vibrate").put("StepCount",20)).put(new JSONObject().put("ActuatorType","Rotate")).put(new JSONObject().put("ActuatorType","Vibrate").put("StepCount",100));
        JSONObject device=new JSONObject().put("id",7).put("name","Demo device").put("messages",new JSONObject().put("ScalarCmd",scalar));
        if (identity) device.put("identity","lovense:AA:BB:CC:DD:EE:01").put("address","AA:BB:CC:DD:EE:01");
        relay=RelayClient.get(a); set(relay,"connected",true); set(relay,"socket",new Socket()); ((ControlRuntime)get(a,"runtime")).onConnection(true,"Connected"); a.onConnection(true,"Connected");
        deliver(a,state("idle",false,false)); runtime.nativeEvent(RelayClient.json("devices","devices",new JSONArray().put(device))); stopped(a); deliver(a,state("deviceReady",false,false)); method("sessionActionTapped").invoke(a);
        deliver(a,state("live",true,false)); deliver(a,state("live",false,true));
        assertTrue(((SessionGate)get(a,"session")).active()); return controller;
    }
    private void deliver(MainActivity a,JSONObject message) throws Exception { if (message.optString("type").equals("state")) set(RelayClient.get(a),"state",message); ((ControlRuntime)get(a,"runtime")).onRelay(message); a.onRelay(message); }
    private void stopped(MainActivity a) throws Exception { ((ControlRuntime)get(a,"runtime")).nativeEvent(RelayClient.json("stopped","confirmed",true,"active",true,"devices",new JSONArray().put(7))); }
    private JSONObject state(String epoch,boolean ready,boolean active) throws Exception {
        return RelayClient.json("state","phase","paired","role","pet","profile",new JSONObject().put("owner","Alex").put("pet","Artemis").put("species","puppy"),"epoch",epoch,"online",true,"ready",ready,"active",active,"reason",active?"Session active":"Paused");
    }
    private JSONObject frame(long seq,double a,double b) throws JSONException { return RelayClient.json("frame","epoch","live","seq",seq,"values",new JSONArray(new double[]{a,b}),"hold",true); }
    private List<JSONObject> ofType(List<JSONObject> source,String type) { List<JSONObject> out=new ArrayList<>(); for(JSONObject m:source) if(type.equals(m.optString("type")))out.add(m); return out; }
    private void acknowledge(MainActivity a,JSONObject request) throws Exception { ((ControlRuntime)get(a,"runtime")).nativeEvent(RelayClient.json("vibration_applied","device",7,"request",request.getLong("request"))); }

    @Test public void toyDropKeepsTheSelectedControlsOnScreenWithoutSendingManualPause() throws Exception {
        try(var c=petActivity(true)) {
            MainActivity a=c.get();ControlRuntime runtime=(ControlRuntime)get(a,"runtime");wire.clear();
            runtime.nativeEvent(RelayClient.json("devices","devices",new JSONArray()));
            assertTrue(runtime.recoveringDevice);assertEquals(7,get(a,"selectedId"));
            assertEquals(2,((TouchPadView)get(a,"pad")).getTargets().length);
            assertTrue(ofType(wire,"pause").isEmpty());assertTrue(ofType(wire,"stop").isEmpty());
            assertTrue(ofType(wire,"device").stream().noneMatch(m->m.isNull("device")));
        }
    }
    @Test public void nativeAcknowledgmentsStayAssociatedWithTheirExactFrames() throws Exception {
        try(var c=petActivity()) { MainActivity a=c.get(); deliver(a,frame(1,.4,.6)); deliver(a,frame(2,.5,.7));
            List<JSONObject> commands=ofType(nativeCommands,"vibration_frame"); assertEquals(2,commands.size());
            assertEquals(1,commands.get(0).getJSONArray("values").getJSONObject(0).getInt("index")); assertEquals(3,commands.get(0).getJSONArray("values").getJSONObject(1).getInt("index"));
            acknowledge(a,commands.get(0)); acknowledge(a,commands.get(1));
            List<JSONObject> acks=ofType(wire,"applied"); assertEquals(2,acks.size()); assertEquals(1,acks.get(0).getLong("seq")); assertEquals(.4,acks.get(0).getJSONArray("values").getDouble(0),0); assertEquals(.5,acks.get(1).getJSONArray("values").getDouble(0),0);
        }
    }
    @Test public void safeWordRevokesLocallyBeforeWireAndRejectsLateOutputEvenOffline() throws Exception {
        try(var c=petActivity()) { MainActivity a=c.get(); deliver(a,frame(1,.4,.6));
            SafeWordView safe=(SafeWordView)get(a,"safeWord"); safe.layout(0,0,320,64); order.clear();
            motion(safe,0,32,32); motion(safe,2,290,32); motion(safe,1,290,32);
            assertFalse(((SessionGate)get(a,"session")).active()); assertTrue(order.indexOf("native:stop")>=0); assertTrue(order.indexOf("native:stop")<order.indexOf("wire:safeword"));
            int count=ofType(nativeCommands,"vibration_frame").size(); deliver(a,frame(2,1,1)); assertEquals(count,ofType(nativeCommands,"vibration_frame").size());
            deliver(a,state("paused",false,false)); stopped(a); method("sessionActionTapped").invoke(a); deliver(a,state("live2",true,false)); deliver(a,state("live2",false,true)); assertTrue(((SessionGate)get(a,"session")).active());
            set(relay,"connected",false); safe.setLatched(false); order.clear(); motion(safe,0,32,32); motion(safe,2,290,32); motion(safe,1,290,32);
            assertFalse(((SessionGate)get(a,"session")).active()); assertTrue(order.contains("native:stop"));
        }
    }
    @Test public void staleLeaseAndBluetoothLossImmediatelyRevokeReadyAndOutput() throws Exception {
        try(var c=petActivity()) { MainActivity a=c.get(); order.clear(); deliver(a,RelayClient.json("lease","epoch","live","active",true,"peerFresh",false));
            assertFalse(((SessionGate)get(a,"session")).active()); assertTrue(order.contains("native:stop"));
        }
        try(var c=petActivity()) { MainActivity a=c.get(); ((ControlRuntime)get(a,"runtime")).bluetoothOff();
            assertFalse(((SessionGate)get(a,"session")).active()); List<JSONObject> advertisements=ofType(wire,"device"); assertTrue(advertisements.get(advertisements.size()-1).isNull("device"));
        }
    }
    @Test public void lockedPadNeverReportsTouchesOrSendsOutput() throws Exception {
        try(var c=petActivity()) { MainActivity a=c.get(); TouchPadView pad=(TouchPadView)get(a,"pad"); pad.layout(0,0,360,240); wire.clear(); nativeCommands.clear();
            motion(pad,0,90,212); motion(pad,1,90,212); assertEquals(0,ofType(wire,"attempt").size());
            motion(pad,0,90,212); motion(pad,2,90,180); motion(pad,2,90,120); motion(pad,1,90,120);
            assertEquals(0,ofType(wire,"attempt").size()); assertEquals(0,ofType(nativeCommands,"vibration_frame").size()); assertArrayEquals(new double[]{0,0},pad.getTargets(),0);
        }
    }
    @Test public void renderPetAndSafeWordWithPinnedActions() throws Exception {
        try(var c=petActivity()) { MainActivity a=c.get(); deliver(a,frame(1,.4,.65)); acknowledge(a,ofType(nativeCommands,"vibration_frame").get(0)); render(a,"relay-pet",360,800);
            View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0); root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(520,View.MeasureSpec.EXACTLY));root.layout(0,0,320,520);
            SafeWordView safe=(SafeWordView)get(a,"safeWord"); Button stop=(Button)get(a,"stop");
            assertTrue(safe.getTop()>=0); assertTrue(safe.getHeight()>=64); assertTrue(safe.getBottom()<=stop.getTop()); assertTrue(stop.getHeight()>=48); assertTrue(stop.getBottom()<=520);
            safe.performAccessibilityAction(SafeWordView.ACTIVATE_SAFE_WORD,null); assertFalse(((CheckBox)get(a,"hold")).isChecked()); assertFalse(((TouchPadView)get(a,"pad")).isHolding()); render(a,"relay-safe-word",360,800);
        }
    }
    @Test public void renderOwnerAndPairing() throws Exception {
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE,true).putExtra(WelcomeActivity.EXTRA_ROLE,"owner");
        try(var c=Robolectric.buildActivity(MainActivity.class,intent).setup()) { MainActivity a=c.get(); relay=RelayClient.get(a); set(relay,"connected",true);set(relay,"socket",new Socket());((ControlRuntime)get(a,"runtime")).onConnection(true,"Connected"); a.onConnection(true,"Connected");
            JSONObject s=state("ownerReady",true,false); s.put("role","owner").put("device",new JSONObject().put("id",7).put("name","Partner’s device").put("features",new JSONArray().put(new JSONObject().put("index",1).put("steps",20).put("label","Vibration 1")).put(new JSONObject().put("index",3).put("steps",100).put("label","Vibration 2"))));
            deliver(a,s); method("sessionActionTapped").invoke(a); s.put("ready",false).put("active",true).put("reason","Session active"); deliver(a,s); render(a,"relay-owner",360,800);
        }
        for(String role:new String[]{"owner","pet"})try(var c=Robolectric.buildActivity(PairingActivity.class,new Intent(RuntimeEnvironment.getApplication(),PairingActivity.class).putExtra(WelcomeActivity.EXTRA_ROLE,role)).setup()) { c.get().onConnection(false,"Enter this phone’s setup code once."); render(c.get(),"pairing-"+role,360,800); }
    }
    @Test public void resetRoleStopsThenUnpairsBeforeResetAndKeepsPhoneIdentity() throws Exception {
        try(var c=petActivity()) { MainActivity a=c.get(); RolePreferences.choose(a,"pet");
            a.getSharedPreferences("heel-relay",Context.MODE_PRIVATE).edit().putString("token","test-saved-identity").commit();
            order.clear(); method("showSettings").invoke(a); assertTrue(order.contains("native:stop"));
            android.app.AlertDialog settings=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); int resetIndex=menuIndex(settings,"Reset role choice"); settings.getListView().performItemClick(null,resetIndex,resetIndex);
            android.app.AlertDialog confirm=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); confirm.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("pet",RolePreferences.get(a)); assertTrue(order.indexOf("native:stop") < order.indexOf("wire:unpair"));
            deliver(a,RelayClient.json("state","phase","unpaired","role",JSONObject.NULL));
            assertEquals("",RolePreferences.get(a)); assertTrue(a.getSharedPreferences("heel-relay",Context.MODE_PRIVATE).contains("token"));
            assertEquals(WelcomeActivity.class.getName(),Shadows.shadowOf(a).getNextStartedActivity().getComponent().getClassName());
        }
    }
    @Test public void settingsLogoReplacesFrontFacingDebugChrome() throws Exception {
        try(var c=petActivity()) { MainActivity a=c.get(); render(a,"relay-settings-entry",360,800); View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);
            List<String> labels=new ArrayList<>(); collectLabels(root,labels); assertFalse(labels.contains("Logs")); for(String label:labels)assertFalse(label.contains("v0.6") || label.contains("Local control build") || label.contains("pairing comes next"));
            ImageView logo=(ImageView)get(a,"sticker"); assertEquals("Settings",logo.getContentDescription()); assertTrue(logo.getWidth()>=48 && logo.getHeight()>=48); logo.performClick();
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); assertTrue(menuIndex(dialog,"Debug log")>=0);
        }
    }
    @Test public void legacyMainIntentRedirectsWithoutBleOrControls() throws Exception {
        try(var c=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=c.get(); assertTrue(a.isFinishing()); assertFalse((boolean)get(a,"initialized")); assertNull(get(a,"pad"));
            method("scanTapped").invoke(a); assertFalse((boolean)get(a,"initialized"));
            assertEquals(WelcomeActivity.class.getName(),Shadows.shadowOf(a).getNextStartedActivity().getComponent().getClassName());
        }
    }
    @Test public void ownerCannotScanEvenWhenHandlerCalledAndRoleMismatchRevokesPet() throws Exception {
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE,true).putExtra(WelcomeActivity.EXTRA_ROLE,"owner");
        try(var c=Robolectric.buildActivity(MainActivity.class,intent).setup()) {
            MainActivity a=c.get(); ((ControlRuntime)get(a,"runtime")).onConnection(true,"Connected"); a.onConnection(true,"Connected"); JSONObject s=state("ownerIdle",false,false);s.put("role","owner");deliver(a,s);
            method("scanTapped").invoke(a); assertFalse((boolean)get(a,"initialized")); assertFalse(((Button)get(a,"scan")).isEnabled());
        }
        try(var c=petActivity()) {
            MainActivity a=c.get(); assertTrue((boolean)method("pairedPet").invoke(a)); deliver(a,state("changed",false,false).put("role","owner"));
            assertFalse(((SessionGate)get(a,"session")).active()); assertFalse((boolean)method("pairedPet").invoke(a)); assertTrue(a.isFinishing());
            int before=nativeCommands.size();method("scanTapped").invoke(a);assertEquals(before,nativeCommands.size());
        }
    }
    @Test public void pairingSettingsNeverOfferLocalControlsAndPetScanNeedsPairedRole() throws Exception {
        for(String role:new String[]{"owner","pet"})try(var c=Robolectric.buildActivity(PairingActivity.class,new Intent(RuntimeEnvironment.getApplication(),PairingActivity.class).putExtra(WelcomeActivity.EXTRA_ROLE,role)).setup()) {
            Method settings=PairingActivity.class.getDeclaredMethod("showSettings");settings.setAccessible(true);settings.invoke(c.get());
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();for(int i=0;i<dialog.getListView().getCount();i++)assertFalse(dialog.getListView().getAdapter().getItem(i).toString().contains("Local"));
        }
        try(var c=petActivity()) { MainActivity a=c.get(); deliver(a,state("paused",false,false));stopped(a);method("updateEnabled").invoke(a);
            assertTrue(((Button)get(a,"scan")).isEnabled());assertTrue(((Button)get(a,"stop")).isEnabled());assertTrue(((SafeWordView)get(a,"safeWord")).isEnabled());
        }
    }
    @Test public void accessibleLockedControlsNeverProduceNotesOrOutput() throws Exception {
        try(var c=petActivity()) {MainActivity a=c.get();method("selectMode",boolean.class).invoke(a,true);wire.clear();nativeCommands.clear();
            @SuppressWarnings("unchecked") ArrayList<SeekBar> sliders=(ArrayList<SeekBar>)get(a,"sliders");SeekBar slider=sliders.get(0);slider.layout(0,0,360,52);
            motion(slider,0,20,26);for(int i=0;i<35;i++)motion(slider,2,30+i*9,26);motion(slider,1,345,26);
            int notes=ofType(wire,"attempt").size();assertEquals(0,notes);assertEquals(0,ofType(nativeCommands,"vibration_frame").size());assertTrue(((SessionGate)get(a,"session")).active());
        }
    }
    @Test public void backgroundAndActivityRecreationKeepTheSocketAndApplyFreshFrames() throws Exception {
        var c=petActivity(); MainActivity a=c.get(); ControlRuntime runtime=(ControlRuntime)get(a,"runtime");
        nativeCommands.clear();wire.clear();c.pause().stop();
        assertTrue(relay.connected());assertTrue(runtime.gate.active());assertNull(get(runtime,"observer"));
        assertTrue(ofType(nativeCommands,"suspend").isEmpty());assertTrue(ofType(wire,"pause").isEmpty());
        runtime.onRelay(frame(1,.35,.6));acknowledge(a,ofType(nativeCommands,"vibration_frame").get(0));
        assertEquals(1,ofType(wire,"applied").size());c.destroy();
        try(var reopened=Robolectric.buildActivity(MainActivity.class,new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE,true).putExtra(WelcomeActivity.EXTRA_ROLE,"pet")).setup()) {
            assertSame(runtime,get(reopened.get(),"runtime"));assertTrue(runtime.gate.active());
            assertArrayEquals(new double[]{.35,.6},((TouchPadView)get(reopened.get(),"pad")).getTargets(),0);
            assertTrue(ofType(nativeCommands,"suspend").isEmpty());assertTrue(ofType(wire,"pause").isEmpty());
        }
    }
    @Test public void temporaryPauseCanResumeButSafeWordRejectsTheSameRecoveryAcrossRecreation() throws Exception {
        try(var c=petActivity()) { MainActivity a=c.get();ControlRuntime runtime=(ControlRuntime)get(a,"runtime");
            runtime.pause(true);stopped(a);deliver(a,state("paused",false,false).put("paused",true).put("resumeAllowed",true));
            assertTrue(runtime.gate.canRecover());wire.clear();method("sessionActionTapped").invoke(a);
            assertEquals(1,ofType(wire,"resume").size());
            deliver(a,state("resumed",false,true).put("resumed",true).put("resumeAllowed",true));assertTrue(runtime.gate.active());
            runtime.safeWord();stopped(a);deliver(a,state("word",false,false).put("safeWord",true));
            deliver(a,state("forbidden",false,true).put("resumed",true));assertFalse(runtime.gate.active());
            field(ControlRuntime.class,"instance").set(null,null);ControlRuntime fresh=ControlRuntime.get(a);fresh.configure(true);
            assertTrue(fresh.safeWord);assertFalse(fresh.gate.canRecover());assertTrue((boolean)get(relay,"retryBlocked"));
        }
    }
    @Test public void reconnectStopsOldEpochAndRequestsRecoveryOnlyAfterNativeStopAcknowledges() throws Exception {
        try(var c=petActivity()) { MainActivity a=c.get();ControlRuntime runtime=(ControlRuntime)get(a,"runtime");
            runtime.onConnection(false,"Lost");assertFalse(runtime.gate.active());assertTrue(runtime.gate.canRecover());
            runtime.onConnection(true,"Connected");wire.clear();
            deliver(a,state("reconnected",false,false).put("resumeAllowed",true));assertTrue(ofType(wire,"resume").isEmpty());
            stopped(a);assertEquals(1,ofType(wire,"resume").size());
            deliver(a,state("recovered",false,true).put("resumeAllowed",true).put("resumed",true));assertTrue(runtime.gate.active());
            int frames=ofType(nativeCommands,"vibration_frame").size();runtime.onRelay(frame(900,1,1));assertEquals(frames,ofType(nativeCommands,"vibration_frame").size());
            runtime.onRelay(frame(1,.2,.4).put("epoch","recovered"));assertEquals(frames+1,ofType(nativeCommands,"vibration_frame").size());
            runtime.onRelay(state("recovered",false,true).put("resumed",true));runtime.onRelay(frame(1,1,1).put("epoch","recovered"));assertEquals(frames+1,ofType(nativeCommands,"vibration_frame").size());
        }
    }
    @Test public void foregroundServiceProvidesPauseAndKeepsRuntimeAliveWithoutAnActivity() throws Exception {
        try(var c=petActivity()) {
            var service=Robolectric.buildService(ConnectionService.class).create();
            try {
            assertNotNull(Shadows.shadowOf(service.get()).getLastForegroundNotification());
            ControlRuntime runtime=(ControlRuntime)get(c.get(),"runtime");c.pause().stop();
            assertTrue(runtime.running);assertTrue(runtime.gate.active());
            service.get().onStartCommand(new Intent().setAction(ConnectionService.PAUSE),0,1);
            assertFalse(runtime.gate.active());assertTrue(runtime.gate.canRecover());assertFalse(runtime.safeWord);
            } finally { service.destroy(); }
        }
    }
    @Test public void aNewProcessCannotInheritAnOldSessionGrant() throws Exception {
        try(var c=petActivity()) { MainActivity a=c.get();ControlRuntime runtime=(ControlRuntime)get(a,"runtime");
            runtime.gate=new SessionGate(true);wire.clear();
            runtime.onRelay(state("old-grant",false,false).put("resumeAllowed",true));
            assertEquals(1,ofType(wire,"stop").size());assertFalse(runtime.gate.active());assertFalse(runtime.gate.canRecover());
        }
    }
    @Test public void authorizedOwnerRestoresHoldOnceAsANewFrameAndUpdatesControlsAfterAck() throws Exception {
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE,true).putExtra(WelcomeActivity.EXTRA_ROLE,"owner");
        try(var c=Robolectric.buildActivity(MainActivity.class,intent).setup()) {
            MainActivity a=c.get();ControlRuntime runtime=(ControlRuntime)get(a,"runtime");relay=runtime.relay;
            set(relay,"connected",true);set(relay,"socket",new Socket());runtime.onConnection(true,"Connected");a.onConnection(true,"Connected");
            JSONObject device=new JSONObject().put("id",7).put("name","Partner device").put("features",new JSONArray().put(new JSONObject().put("index",1).put("steps",20).put("label","Vibration 1")).put(new JSONObject().put("index",3).put("steps",100).put("label","Vibration 2")));
            JSONObject initial=state("start",true,false).put("role","owner").put("device",device);
            deliver(a,initial);method("sessionActionTapped").invoke(a);deliver(a,state("start",false,true).put("role","owner").put("device",device));
            runtime.onConnection(false,"Lost");runtime.onConnection(true,"Connected");wire.clear();
            deliver(a,state("back",false,false).put("role","owner").put("device",device).put("resumeAllowed",true));
            JSONObject recovered=state("new",false,true).put("role","owner").put("device",device).put("resumeAllowed",true).put("resumed",true).put("values",new JSONArray(new double[]{0,0})).put("restore",new JSONObject().put("device",device).put("values",new JSONArray(new double[]{.4,.65})).put("hold",true));
            deliver(a,recovered);deliver(a,recovered);
            List<JSONObject> sent=ofType(wire,"frame");assertEquals(1,sent.size());assertEquals("new",sent.get(0).getString("epoch"));assertEquals(1,sent.get(0).getLong("seq"));assertTrue(sent.get(0).getBoolean("hold"));assertEquals(.65,sent.get(0).getJSONArray("values").getDouble(1),0);
            deliver(a,RelayClient.json("applied","epoch","new","seq",1,"values",new JSONArray(new double[]{.4,.65}),"hold",true));
            assertTrue(((CheckBox)get(a,"hold")).isChecked());assertArrayEquals(new double[]{.4,.65},((TouchPadView)get(a,"pad")).getTargets(),0);
            runtime.pause(true);wire.clear();deliver(a,recovered.put("epoch","late"));assertTrue(ofType(wire,"frame").isEmpty());
        }
    }
    @Test public void restartedOwnerDoesNotRestoreServerHeldLevelsWithoutLocalAuthority() throws Exception {
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE,true).putExtra(WelcomeActivity.EXTRA_ROLE,"owner");
        try(var c=Robolectric.buildActivity(MainActivity.class,intent).setup()) {
            MainActivity a=c.get();ControlRuntime runtime=(ControlRuntime)get(a,"runtime");relay=runtime.relay;set(relay,"connected",true);set(relay,"socket",new Socket());runtime.onConnection(true,"Connected");
            JSONObject device=new JSONObject().put("id",7).put("features",new JSONArray().put(new JSONObject().put("index",0).put("steps",20)));
            runtime.onRelay(state("unowned",false,true).put("role","owner").put("resumeAllowed",true).put("resumed",true).put("device",device).put("restore",new JSONObject().put("device",device).put("values",new JSONArray(new double[]{.9})).put("hold",true)));
            assertTrue(ofType(wire,"frame").isEmpty());assertFalse(runtime.gate.active());assertFalse(ofType(wire,"stop").isEmpty());
        }
    }
    private ActivityController<MainActivity> ownerActivity() throws Exception {
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra(PairingActivity.EXTRA_REMOTE,true).putExtra(WelcomeActivity.EXTRA_ROLE,"owner");
        var c=Robolectric.buildActivity(MainActivity.class,intent).setup(); MainActivity a=c.get();
        relay=RelayClient.get(a);set(relay,"connected",true);set(relay,"socket",new Socket());
        ((ControlRuntime)get(a,"runtime")).onConnection(true,"Connected");a.onConnection(true,"Connected");
        JSONObject s=state("ownerReady",true,false).put("role","owner").put("device",new JSONObject().put("id",7).put("name","Partner’s device").put("features",new JSONArray().put(new JSONObject().put("index",0).put("steps",20).put("label","Vibration"))));
        deliver(a,s);method("sessionActionTapped").invoke(a);s.put("ready",false).put("active",true).put("reason","Session active");deliver(a,s);return c;
    }
    @Test public void hapticIsOptInOwnerOnlyAndCancelsOnZeroDisableAndBackground() throws Exception {
        try(var c=ownerActivity()) {
            MainActivity a=c.get();CheckBox haptic=(CheckBox)get(a,"haptic");assertFalse(haptic.isChecked());
            OwnerHapticsTest.Output feedback=new OwnerHapticsTest.Output();set(a,"ownerHaptics",new OwnerHaptics(feedback));
            TouchPadView pad=(TouchPadView)get(a,"pad");pad.setTargets(new double[]{.6});pad.setHold(true);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(feedback.strengths.isEmpty());
            haptic.setChecked(true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,feedback.strengths.size());
            pad.setTargets(new double[]{0});assertEquals(1,feedback.cancels);
            pad.setTargets(new double[]{.6});Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(2,feedback.strengths.size());
            haptic.setChecked(false);assertEquals(2,feedback.cancels);
            haptic.setChecked(true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(3,feedback.strengths.size());
            assertTrue(a.getPreferences(Context.MODE_PRIVATE).getBoolean("haptic",false));
            c.pause();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
            assertEquals(3,feedback.strengths.size());assertEquals(3,feedback.cancels);
        }
        resetClient();try(var c=petActivity()) { assertEquals(View.GONE,((CheckBox)get(c.get(),"haptic")).getVisibility()); }
    }
    @Test public void hapticStopsForPauseSafeWordAndConnectionLoss() throws Exception {
        for(String interruption:new String[]{"paused","safeWord","deviceOnline","network"}) {
            resetClient();try(var c=ownerActivity()) {
                MainActivity a=c.get();OwnerHapticsTest.Output feedback=new OwnerHapticsTest.Output();set(a,"ownerHaptics",new OwnerHaptics(feedback));
                ((CheckBox)get(a,"haptic")).setChecked(true);((TouchPadView)get(a,"pad")).setTargets(new double[]{.8});
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,feedback.strengths.size());
                if(interruption.equals("network")) a.onConnection(false,"Reconnecting");
                else { ((JSONObject)get(a,"relayState")).put(interruption,!interruption.equals("deviceOnline"));method("updateEnabled").invoke(a); }
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
                assertEquals(interruption,1,feedback.strengths.size());assertEquals(interruption,1,feedback.cancels);
            }
        }
    }
    @Test public void compactOwnerUiHasNoModeTabsOrPawNotes() throws Exception {
        try(var c=ownerActivity()) {
            MainActivity a=c.get();View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);
            List<String> labels=new ArrayList<>();collectLabels(root,labels);
            assertFalse(labels.contains("Sliders"));assertFalse(labels.contains("Touch"));
            for(String label:labels) assertFalse(label.toLowerCase().contains("paw notes"));
            assertEquals("Haptic",((CheckBox)get(a,"haptic")).getText().toString());
        }
    }
    @Test public void phoneDiagnosticsPauseToyAndNeverSendToyFrames()throws Exception {
        try(var c=ownerActivity()) {
            MainActivity a=c.get();wire.clear();method("showHapticDiagnostics").invoke(a);
            assertFalse(ofType(wire,"pause").isEmpty());
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            View root=dialog.getWindow().getDecorView();
            clickLabeled(root,"B · Short pulse");clickLabeled(root,"Copy haptic report");
            assertTrue(ofType(wire,"frame").isEmpty());
            String report=a.getSystemService(android.content.ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString();
            assertTrue(report.contains("Heel haptic diagnostics"));assertTrue(report.contains("TEST B pulse18ms"));
            assertFalse(report.contains("test-saved-identity"));dialog.dismiss();
        }
        resetClient();try(var c=petActivity()) { method("showHapticDiagnostics").invoke(c.get());assertFalse((boolean)get(c.get(),"diagnosticsOpen")); }
    }
    private static boolean clickLabeled(View view,String label) {
        if(view instanceof Button && label.contentEquals(((Button)view).getText()))return view.performClick();
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(clickLabeled(((ViewGroup)view).getChildAt(i),label))return true;
        return false;
    }
    private static Method spellMethod(String name,Class<?>... args)throws Exception {Method m=MainActivity.class.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    @Test public void spellPlaybackRequantizesAndEndsAtZeroWithoutHold()throws Exception {
        try(var c=ownerActivity()) {MainActivity a=c.get();
            spellMethod("startPlayback",SpellBook.Spell.class).invoke(a,SpellBookTest.sample(1));
            ((CheckBox)get(a,"hold")).setChecked(true);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));
            assertNotNull(get(a,"playing"));assertFalse(((CheckBox)get(a,"hold")).isChecked());
            List<JSONObject> frames=ofType(wire,"frame");assertTrue(frames.size()>2);assertTrue(frames.size()<25);
            for(JSONObject frame:frames){assertFalse(frame.optBoolean("hold"));double value=frame.getJSONArray("values").getDouble(0);assertEquals(Math.round(value*20),value*20,.00001);}
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
            assertNull(get(a,"playing"));frames=ofType(wire,"frame");assertEquals(0,frames.get(frames.size()-1).getJSONArray("values").getDouble(0),0);
            assertTrue(((SessionGate)get(a,"session")).active());
        }
    }
    @Test public void interruptedSpellsNeverScheduleOutputAgain()throws Exception {
        for(String cause:new String[]{"pause","background","network","device","safeWord"}) {
            resetClient();try(var c=ownerActivity()){MainActivity a=c.get();
                spellMethod("startPlayback",SpellBook.Spell.class).invoke(a,SpellBookTest.sample(1));Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));
                if(cause.equals("pause"))spellMethod("requestStop",String.class).invoke(a,"Paused");
                else if(cause.equals("background"))c.pause();
                else if(cause.equals("network"))relay.disconnect("Reconnecting");
                else {((JSONObject)get(a,"relayState")).put(cause.equals("device")?"deviceOnline":"safeWord",cause.equals("safeWord"));method("updateEnabled").invoke(a);}
                assertNull(cause,get(a,"playing"));int before=ofType(wire,"frame").size();
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500));
                for(JSONObject frame:ofType(wire,"frame").subList(before,ofType(wire,"frame").size()))assertEquals(cause,0,frame.getJSONArray("values").getDouble(0),0);
            }
        }
    }
    @Test public void spellCompatibilityAndPetRoleCannotBeBypassed()throws Exception{
        try(var c=ownerActivity()){MainActivity a=c.get();spellMethod("startPlayback",SpellBook.Spell.class).invoke(a,SpellBookTest.sample(2));assertNull(get(a,"playing"));}
        resetClient();try(var c=petActivity()){MainActivity a=c.get();spellMethod("startPlayback",SpellBook.Spell.class).invoke(a,SpellBookTest.sample(2));spellMethod("startRecording").invoke(a);assertNull(get(a,"playing"));assertNull(get(a,"recording"));assertEquals(View.GONE,((View)get(a,"spellbookButton")).getVisibility());}
    }
    @Test public void openingSpellbookRestsOutputButKeepsSessionAvailable()throws Exception{
        try(var c=ownerActivity()){MainActivity a=c.get();TouchPadView pad=(TouchPadView)get(a,"pad");pad.setTargets(new double[]{.8});pad.setHold(true);
            method("showSpellbook").invoke(a);assertTrue(((SessionGate)get(a,"session")).active());assertArrayEquals(new double[]{0},pad.getTargets(),0);assertFalse(pad.isHolding());
            android.app.AlertDialog book=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(book.isShowing());book.dismiss();
            method("startRecording").invoke(a);assertNotNull(get(a,"recording"));
        }
    }
    @Test public void recordingSavesContinuousGestureNameAndSticker()throws Exception{
        try(var c=ownerActivity()){MainActivity a=c.get();a.getSharedPreferences("spellbook",0).edit().clear().commit();
            method("startRecording").invoke(a);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
            ((TouchPadView)get(a,"pad")).setVisualTargets(new double[]{.333});Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            method("finishRecording").invoke(a);assertNull(get(a,"recording"));assertArrayEquals(new double[]{0},((TouchPadView)get(a,"pad")).getTargets(),0);
            android.app.AlertDialog save=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();ViewGroup decor=(ViewGroup)save.getWindow().getDecorView();
            ((EditText)findClass(decor,EditText.class)).setText("Moon paws");((RadioGroup)findClass(decor,RadioGroup.class)).check(R.id.spell_curious);
            save.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            SpellBook.Spell spell=new SpellBook(a).all().get(0);assertEquals("Moon paws",spell.name);assertEquals(1,spell.sticker);assertTrue(spell.frames.stream().anyMatch(f -> Math.abs(f.values[0]-.333)<.00001));
        }
    }
    @Test public void touchingGhostCancelsScheduleAndMissedPickupRestsOutput()throws Exception{
        try(var c=ownerActivity()){MainActivity a=c.get();TouchPadView pad=(TouchPadView)get(a,"pad");pad.layout(0,0,360,400);
            spellMethod("startPlayback",SpellBook.Spell.class).invoke(a,SpellBookTest.sample(1));Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350));
            motion(pad,0,180,372);assertNull(get(a,"playing"));assertArrayEquals(new double[]{0},pad.getTargets(),0);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));assertArrayEquals(new double[]{0},pad.getTargets(),0);
        }
    }
    @Test public void screenReaderCanEnableInlineControlsAfterLaunch()throws Exception{
        try(var c=ownerActivity()){MainActivity a=c.get();assertFalse((boolean)get(a,"sliderMode"));
            ((android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener)get(a,"accessibilityChanged")).onTouchExplorationStateChanged(true);
            assertTrue((boolean)get(a,"sliderMode"));assertEquals(View.VISIBLE,((View)get(a,"sliderPanel")).getVisibility());
            List<String> labels=new ArrayList<>();collectLabels(((ViewGroup)a.findViewById(android.R.id.content)),labels);assertFalse(labels.contains("Sliders"));
        }
    }
    private static int menuIndex(android.app.AlertDialog dialog,String label){for(int i=0;i<dialog.getListView().getAdapter().getCount();i++)if(label.equals(dialog.getListView().getAdapter().getItem(i)))return i;throw new AssertionError("Missing "+label);}
    @Test public void queuedCreativeActionCannotStartAfterBackgroundAndReturn()throws Exception{
        try(var c=ownerActivity()){MainActivity a=c.get();int[] calls={0};
            spellMethod("postCreative",Runnable.class).invoke(a,(Runnable)()->calls[0]++);
            c.pause();c.resume();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(0,calls[0]);
        }
    }
    @Test public void accessibleLiveStateRestsAlongWithSpellbookOpening()throws Exception{
        try(var c=ownerActivity()){MainActivity a=c.get();set(a,"sliderMode",true);
            ((double[])get(a,"drafts"))[0]=.7;((boolean[])get(a,"sliderRunning"))[0]=true;method("submitSliders").invoke(a);
            method("showSpellbook").invoke(a);assertEquals(0,((double[])get(a,"drafts"))[0],0);assertFalse(((boolean[])get(a,"sliderRunning"))[0]);assertTrue(((SessionGate)get(a,"session")).active());
        }
    }
    @Test public void pickedUpGhostBecomesAnOrdinaryLiveTouch()throws Exception{
        try(var c=ownerActivity()){MainActivity a=c.get();TouchPadView pad=(TouchPadView)get(a,"pad");pad.layout(0,0,360,400);
            spellMethod("startPlayback",SpellBook.Spell.class).invoke(a,SpellBookTest.sample(1));Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350));
            float bottom=pad.getHeight()-28,travel=pad.getHeight()-56;
            float y=(float)(bottom-TouchModel.requestToTrack(.8)*travel);motion(pad,0,180,y);assertNull(get(a,"playing"));assertEquals(1,pad.getActiveTouchCount());assertEquals(.8,pad.getTargets()[0],.00001);
            motion(pad,2,180,bottom);assertEquals(0,pad.getTargets()[0],0);motion(pad,1,180,bottom);
        }
    }
    @Test public void stopUnconfirmedRemainsVisibleAlongsidePause()throws Exception{
        try(var c=ownerActivity()){MainActivity a=c.get();spellMethod("requestStop",String.class).invoke(a,"Paused");
            spellMethod("setStatus",String.class).invoke(a,"Stop unconfirmed. Check the device.");String state=((TextView)get(a,"status")).getText().toString();assertTrue(state.contains("Paused"));assertTrue(state.contains("unconfirmed"));
        }
    }
    private static View findClass(View view,Class<?> type){if(type.isInstance(view))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=findClass(((ViewGroup)view).getChildAt(i),type);if(match!=null)return match;}return null;}
    private static void collectLabels(View view,List<String> labels) { if(view instanceof TextView)labels.add(((TextView)view).getText().toString());if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectLabels(((ViewGroup)view).getChildAt(i),labels); }
    private static void motion(View view,int action,float x,float y) { MotionEvent e=MotionEvent.obtain(1,2,action,x,y,0);view.onTouchEvent(e);e.recycle(); }
    private static Field field(Class<?> type,String name)throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f; }
    private static Object get(Object o,String name)throws Exception{return field(o.getClass(),name).get(o);}
    private static void set(Object o,String name,Object value)throws Exception{field(o.getClass(),name).set(o,value);}
    private static Method method(String name,Class<?>...parameters)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,parameters);m.setAccessible(true);return m;}
    private static void render(android.app.Activity a,String name,int w,int h)throws Exception {
        View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
        settle(root); Bitmap b=Bitmap.createBitmap(w*2,h*2,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.scale(2,2);root.draw(canvas);File dir=new File("build/outputs/ui-preview");dir.mkdirs();try(var out=new FileOutputStream(new File(dir,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
    }
    private static void settle(View view) { view.jumpDrawablesToCurrentState(); if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)settle(((ViewGroup)view).getChildAt(i)); }
}


