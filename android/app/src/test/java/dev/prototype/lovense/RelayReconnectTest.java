package dev.prototype.lovense;

import android.content.Context;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.Field;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class RelayReconnectTest {
    private RelayClient client;
    @Before public void setup() throws Exception {
        field("instance").set(null,null); client=RelayClient.get(RuntimeEnvironment.getApplication());
        client.endpoint("https://relay.example.test");
        // Deliberately undecryptable identity prevents these scheduler tests from opening any network socket.
        RuntimeEnvironment.getApplication().getSharedPreferences("heel-relay",Context.MODE_PRIVATE).edit().putString("token","invalid-test-identity").commit();
        field("wanted").set(client,true);
    }
    @After public void stop() {client.shutdown();}
    private static Field field(String name)throws Exception {Field f=RelayClient.class.getDeclaredField(name);f.setAccessible(true);return f;}
    @Test public void leavingAnActivityDoesNotCloseItsSocketOrChangeGeneration() throws Exception {
        field("connected").set(client,true);
        RelayClient.Listener listener=new RelayClient.Listener(){public void onRelay(org.json.JSONObject message){} public void onConnection(boolean connected,String reason){}};
        client.attach(listener);int before=field("generation").getInt(client);client.detach(listener);
        assertTrue(client.connected());assertEquals(before,field("generation").getInt(client));
    }
    @Test public void lostConnectionRetriesAfter250MillisecondsWithoutReplayingPendingOutput() throws Exception {
        field("pendingFrame").set(client,RelayClient.json("frame","seq",99));client.disconnect("test network loss");
        int generation=field("generation").getInt(client);assertNull(field("pendingFrame").get(client));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(249));assertEquals(generation,field("generation").getInt(client));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertEquals(generation+1,field("generation").getInt(client));
    }
    @Test public void safeWordCancelsQueuedRetriesAndNetworkChangeCannotBypassIt() throws Exception {
        client.disconnect("lost");client.safeWord();int generation=field("generation").getInt(client);
        client.networkAvailable();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals(generation,field("generation").getInt(client));assertEquals("safeword",field("pendingStop").get(client));
        client.pause();assertEquals("safeword",field("pendingStop").get(client));
        client.clearSafeWordForReady();client.networkAvailable();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(generation+1,field("generation").getInt(client));
    }
    @Test public void retriesBackOffAndStayBoundedWhilePauseKeepsRetryEnabled() throws Exception {
        for(int i=0;i<20;i++)client.disconnect("lost");client.pause();
        int generation=field("generation").getInt(client);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4999));assertEquals(generation,field("generation").getInt(client));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertEquals(generation+1,field("generation").getInt(client));
        assertFalse(field("retryBlocked").getBoolean(client));
    }
}
