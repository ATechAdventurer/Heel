package dev.prototype.lovense;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.Looper;
import com.nonpolynomial.btleplug.android.impl.BleRecovery;
import okhttp3.*;
import okio.ByteString;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,shadows={com.nonpolynomial.btleplug.android.impl.PeripheralWriteTest.MinimalDevice.class}) @LooperMode(LooperMode.Mode.PAUSED)
public class BluetoothRecoveryTest {
    private ControlRuntime runtime;
    private final List<JSONObject> nativeCommands=new ArrayList<>(), wire=new ArrayList<>();
    private final List<String> order=new ArrayList<>();
    private JSONObject device, selected;
    private static void field(Object object,String name,Object value)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static void reset(Class<?> type)throws Exception {Field f=type.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);}
    @Before public void setup()throws Exception {
        reset(ControlRuntime.class);reset(RelayClient.class);
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("heel-session",Context.MODE_PRIVATE).edit().clear().commit();RolePreferences.choose(context,"pet");
        BleRecovery.discover();BleRecovery.connection("AA:BB:CC:DD:EE:01",true);
        Shadows.shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_ON);
        runtime=ControlRuntime.get(context);runtime.initialized=runtime.ready=true;
        runtime.engine=new ControlRuntime.Engine(){public String initialize(){return "";}public String drain(){return "[]";}public String submit(String text){try{JSONObject m=new JSONObject(text);nativeCommands.add(m);order.add("native:"+m.optString("type"));return "";}catch(Exception e){throw new AssertionError(e);}}};
        field(runtime.relay,"connected",true);field(runtime.relay,"socket",new WebSocket(){
            public Request request(){return new Request.Builder().url("https://example.test").build();}public long queueSize(){return 0;}public boolean send(ByteString b){return false;}public boolean close(int c,String r){return true;}public void cancel(){}
            public boolean send(String text){try{JSONObject m=new JSONObject(text);wire.add(m);order.add("wire:"+m.optString("type"));return true;}catch(Exception e){throw new AssertionError(e);}}
        });
        runtime.onConnection(true,"Connected");
        selected=RelayClient.json("device","id",7,"name","Lovense","features",new JSONArray().put(RelayClient.json("feature","index",0,"label","Vibration","steps",20)));
        device=RelayClient.json("device","id",7,"name","Lovense","identity","lovense:AA:BB:CC:DD:EE:01:model","address","PeripheralId(AA:BB:CC:DD:EE:01)","messages",new JSONObject().put("ScalarCmd",new JSONArray().put(new JSONObject().put("ActuatorType","Vibrate").put("StepCount",20))));
        runtime.onRelay(state("initial",false,false,true));runtime.nativeEvent(RelayClient.json("devices","devices",new JSONArray().put(device)));runtime.select(selected);stopped();
        runtime.onRelay(state("idle",false,false,true));runtime.action();runtime.onRelay(state("live",true,false,true));runtime.onRelay(state("live",false,true,true));assertTrue(runtime.gate.active());wire.clear();nativeCommands.clear();order.clear();
    }
    @After public void cleanup(){runtime.stop();BleRecovery.discover();}
    private JSONObject state(String epoch,boolean ready,boolean active,boolean link){return RelayClient.json("state","phase","paired","role","pet","epoch",epoch,"online",true,"ready",ready,"active",active,"device",selected,"deviceOnline",link,"resumeAllowed",!epoch.equals("initial")&&!epoch.equals("idle"));}
    private void stopped()throws Exception {runtime.nativeEvent(RelayClient.json("stopped","confirmed",true,"active",true,"devices",new JSONArray().put(7)));}
    private void drop()throws Exception {BleRecovery.connection(device.getString("address"),false);runtime.nativeEvent(RelayClient.json("devices","devices",new JSONArray()));stopped();runtime.onRelay(state("lost",false,false,false));}
    private void returned()throws Exception {BleRecovery.connection(device.getString("address"),true);runtime.nativeEvent(RelayClient.json("devices","devices",new JSONArray().put(device)));}
    private long sent(String type){return wire.stream().filter(m->type.equals(m.optString("type"))).count();}
    private long scans(){return nativeCommands.stream().filter(m->m.optString("type").equals("scan")&&m.optBoolean("enabled")).count();}
    @Test public void exactToyRecoversInBackgroundAfterConfirmedZeroAndFreshEpoch()throws Exception {
        drop();assertTrue(runtime.recoveringDevice);assertSame(selected,runtime.selected);assertTrue(runtime.gate.canRecover());assertFalse(runtime.gate.active());
        assertEquals(1,runtime.displayDevices().length());assertTrue(order.indexOf("native:stop")<order.indexOf("wire:device_link"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));assertEquals(1,scans());assertEquals("AA:BB:CC:DD:EE:01",BleRecovery.targetAddress());
        returned();assertTrue(runtime.recoveringDevice);assertEquals(0,sent("resume"));
        stopped();assertFalse(runtime.recoveringDevice);assertTrue(wire.stream().anyMatch(m->m.optString("type").equals("device_link")&&m.optBoolean("connected")));
        runtime.onRelay(state("lost",false,false,true));assertEquals(1,sent("resume"));
        runtime.onRelay(state("new",false,true,true).put("resumed",true));
        runtime.onRelay(RelayClient.json("frame","epoch","live","seq",5,"values",new JSONArray().put(.9),"hold",true));
        assertTrue(nativeCommands.stream().noneMatch(m->m.optString("type").equals("vibration_frame")));
        runtime.onRelay(RelayClient.json("frame","epoch","new","seq",1,"values",new JSONArray().put(.6),"hold",true));
        assertEquals(1,nativeCommands.stream().filter(m->m.optString("type").equals("vibration_frame")).count());
    }
    @Test public void recoveryCannotFinishWithFailedOrMissingSelectedDeviceStop()throws Exception {
        drop(); returned();
        runtime.nativeEvent(RelayClient.json("stopped","confirmed",false,"active",false,"devices",new JSONArray()));
        assertTrue(runtime.recoveringDevice); assertFalse(runtime.available()); assertEquals(0,sent("resume"));
        returned();
        runtime.nativeEvent(RelayClient.json("stopped","confirmed",true,"active",true,"devices",new JSONArray().put(99)));
        assertTrue(runtime.recoveringDevice); assertFalse(runtime.available()); assertEquals(0,sent("resume"));
        returned(); stopped();
        assertFalse(runtime.recoveringDevice);
    }
    @Test public void pauseDuringLossReconnectsHardwareButNeverAutomaticallyResumes()throws Exception {
        drop();runtime.pause(true);stopped();runtime.onRelay(state("paused",false,false,false).put("paused",true));
        returned();stopped();runtime.onRelay(state("paused",false,false,true).put("paused",true));
        assertFalse(runtime.recoveringDevice);assertEquals(0,sent("resume"));runtime.action();assertEquals(1,sent("resume"));assertFalse(wire.get(wire.size()-1).optBoolean("automatic"));
    }
    @Test public void safeWordCancelsScansAndExplicitPetChoiceIsRequiredToReconnect()throws Exception {
        drop();runtime.safeWord();stopped();runtime.onRelay(state("safe",false,false,false).put("safeWord",true).put("resumeAllowed",false));
        long before=scans();runtime.bluetoothOn();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(35));assertEquals(before,scans());assertFalse(BleRecovery.allows("AA:BB:CC:DD:EE:01"));
        runtime.action();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(scans()>before);assertTrue(runtime.safeWord);assertEquals(0,sent("ready"));
        returned();stopped();assertTrue(runtime.safeWord);assertEquals(1,sent("ready"));assertEquals(0,sent("resume"));
        runtime.onRelay(state("freshReady",true,false,true).put("safeWord",false).put("resumeAllowed",false));
        assertFalse(runtime.safeWord);
    }
    @Test public void sameModelAndReusedIndexCannotSubstituteForTheSelectedToy()throws Exception {
        drop();JSONObject impostor=new JSONObject(device.toString()).put("identity","lovense:AA:BB:CC:DD:EE:02:model").put("address","AA:BB:CC:DD:EE:02");
        runtime.nativeEvent(RelayClient.json("devices","devices",new JSONArray().put(impostor)));stopped();assertTrue(runtime.recoveringDevice);assertEquals(0,sent("resume"));assertFalse(BleRecovery.allows(impostor.getString("address")));
    }
    @Test public void repeatedScanWindowsRemainBoundedAndUnpairCancelsRecovery()throws Exception {
        drop();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60));assertTrue(scans()>=3&&scans()<=4);
        runtime.onRelay(RelayClient.json("state","phase","unpaired"));long before=scans();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40));assertEquals(before,scans());assertFalse(runtime.recoveringDevice);
    }
    @Test public void failedWriteFollowedByDisconnectRetainsRecoveryInsteadOfManualPause()throws Exception {
        BleRecovery.connection(device.getString("address"),false);runtime.nativeEvent(RelayClient.json("error","message","Disconnected while in write operation"));
        assertTrue(runtime.recoveringDevice);assertTrue(runtime.gate.canRecover());assertEquals(0,sent("pause"));assertEquals(0,sent("stop"));
    }
    @Test public void safeWordBeforeTheRadioDropDoesNotBrieflyReenableConnections()throws Exception {
        runtime.safeWord();stopped();runtime.onRelay(state("safe",false,false,true).put("safeWord",true).put("resumeAllowed",false));
        BleRecovery.connection(device.getString("address"),false);runtime.nativeEvent(RelayClient.json("devices","devices",new JSONArray()));stopped();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));assertEquals(0,scans());assertFalse(BleRecovery.allows("AA:BB:CC:DD:EE:01"));assertTrue(runtime.recoveringDevice);
    }
    @Test public void changedCapabilitiesRequireFreshReadinessInsteadOfRestoringOldControls()throws Exception {
        drop();JSONObject changed=new JSONObject(device.toString());changed.getJSONObject("messages").getJSONArray("ScalarCmd").getJSONObject(0).put("StepCount",100);
        BleRecovery.connection(device.getString("address"),true);runtime.nativeEvent(RelayClient.json("devices","devices",new JSONArray().put(changed)));
        assertFalse(runtime.recoveringDevice);assertFalse(runtime.gate.canRecover());assertNull(runtime.selected);assertEquals(1,sent("stop"));
    }

    private void pendingSafeWordReady()throws Exception {
        drop();runtime.safeWord();stopped();
        runtime.onRelay(state("safe",false,false,false).put("safeWord",true).put("resumeAllowed",false));
        runtime.action();Shadows.shadowOf(Looper.getMainLooper()).idle();returned();stopped();
        assertTrue(runtime.safeWord);assertEquals(1,sent("ready"));
    }
    @Test public void delayedSafeWordStateCannotUndoPendingReady()throws Exception {
        pendingSafeWordReady();
        runtime.onRelay(state("safe",false,false,true).put("safeWord",true).put("resumeAllowed",false));
        assertTrue(runtime.safeWord);assertTrue(runtime.gate.pending());
        runtime.onRelay(state("freshReady",true,false,true).put("safeWord",false).put("resumeAllowed",false));
        assertFalse(runtime.safeWord);assertTrue(runtime.gate.readyForEpoch("freshReady"));
        runtime.onRelay(state("freshReady",false,true,true).put("safeWord",false));
        assertTrue(runtime.gate.active());
    }
    @Test public void newLocalSafeWordCancelsPendingReadyAndRejectsLateAck()throws Exception {
        pendingSafeWordReady();runtime.safeWord();
        runtime.onRelay(state("lateReady",true,false,true).put("safeWord",false).put("resumeAllowed",false));
        assertTrue(runtime.safeWord);assertFalse(runtime.gate.readyForEpoch("lateReady"));
        runtime.onRelay(state("lateReady",false,true,true).put("safeWord",false));
        assertFalse(runtime.gate.active());
    }
    @Test public void rejectedReadyCanBeRetriedAfterStopAcknowledgment()throws Exception {
        pendingSafeWordReady();runtime.onRelay(RelayClient.json("error","message","Try Ready again"));stopped();
        runtime.onRelay(state("rejected",false,false,true).put("safeWord",true).put("resumeAllowed",false));
        assertTrue(runtime.safeWord);assertTrue(runtime.gate.mayRequest());
        runtime.action();assertEquals(2,sent("ready"));
    }
    @Test public void missingReadyAcknowledgmentTimesOutWithoutClearingSafeWord()throws Exception {
        pendingSafeWordReady();long before=sent("safeword");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertTrue(runtime.safeWord);assertFalse(runtime.gate.pending());assertTrue(sent("safeword")>before);
        stopped();runtime.onRelay(state("timeoutStop",false,false,true).put("safeWord",true).put("resumeAllowed",false));
        assertTrue(runtime.gate.mayRequest());
    }
    @Test public void disconnectCancelsPendingReadyWithoutClearingSafeWord()throws Exception {
        pendingSafeWordReady();runtime.onConnection(false,"lost");
        assertTrue(runtime.safeWord);assertFalse(runtime.gate.pending());
    }
    @Test public void repeatedSafeWordStatusDoesNotLockReadyBeforeItIsRequested()throws Exception {
        runtime.safeWord();stopped();
        JSONObject safe=state("safe",false,false,true).put("safeWord",true).put("resumeAllowed",false);
        runtime.onRelay(safe);runtime.onRelay(safe);runtime.onRelay(safe);
        assertTrue(runtime.safeWord);assertTrue(runtime.gate.mayRequest());
        runtime.action();assertEquals(1,sent("ready"));
        runtime.onRelay(state("freshReady",true,false,true).put("safeWord",false).put("resumeAllowed",false));
        assertFalse(runtime.safeWord);assertTrue(runtime.gate.readyForEpoch("freshReady"));
    }
    @Test public void shutdownCancelsReadyTimeout()throws Exception {
        pendingSafeWordReady();runtime.stop();long before=sent("safeword");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals(before,sent("safeword"));assertTrue(runtime.safeWord);
    }
}
