package com.nonpolynomial.btleplug.android.impl;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.*;
import android.os.ParcelUuid;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.github.gedgygedgy.rust.future.FutureException;
import io.github.gedgygedgy.rust.task.PollResult;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,shadows=PeripheralWriteTest.MinimalDevice.class)
public class LovenseScanPolicyTest {
    @Before public void freshDiscoveryGate() { BleRecovery.discover(); BleDiagnostics.drain(); }
    private static List<ParcelUuid> service(String uuid) { return Collections.singletonList(ParcelUuid.fromString(uuid)); }
    private static ScanResult result(String name) {
        byte[] text=name.getBytes(StandardCharsets.UTF_8), packet=new byte[text.length+3];packet[0]=(byte)(text.length+1);packet[1]=9;System.arraycopy(text,0,packet,2,text.length);
        ScanRecord record=ReflectionHelpers.callStaticMethod(ScanRecord.class,"parseFromBytes",ReflectionHelpers.ClassParameter.from(byte[].class,packet));
        return new ScanResult(BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:01"),record,-50,0);
    }
    @Test public void genericUartThermostatsAndUnknownAdvertisementsAreNotCandidates() {
        assertFalse(LovenseScanPolicy.matches("Thermostat",service("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),null));
        assertFalse(LovenseScanPolicy.matches(null,service("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),null));
        assertFalse(LovenseScanPolicy.matches("Sensor",service("0000fff0-0000-1000-8000-00805f9b34fb"),null));
        assertFalse(LovenseScanPolicy.matches("LVS-",null,null));assertFalse(LovenseScanPolicy.matches((ScanRecord)null));
    }
    @Test public void oldAndNewLovenseIdentitiesWorkWithoutGenericServiceAdmission() {
        assertTrue(LovenseScanPolicy.matches("LVS-A011",null,null));assertTrue(LovenseScanPolicy.matches("LVS-Hush",null,null));
        assertTrue(LovenseScanPolicy.matches("LOVE-Edge",null,null));
        assertTrue(LovenseScanPolicy.matches(null,service("50300001-0023-4bd4-bbd5-a6920e4c5653"),null));
        assertTrue(LovenseScanPolicy.matches(null,null,new byte[]{(byte)255,33,5}));
        assertFalse(LovenseScanPolicy.matches(null,null,new byte[]{(byte)255,32,5}));
        assertFalse(LovenseScanPolicy.matches(null,service("abcd0001-0023-4bd4-bbd5-a6920e4c5653"),null));
    }
    @Test public void scanCallbackDiscardsUnrelatedDevicesBeforeJniAndLogs() throws Exception {
        Adapter adapter=new Adapter();Field callback=Adapter.class.getDeclaredField("callback");callback.setAccessible(true);
        BleDiagnostics.drain();((ScanCallback)callback.get(adapter)).onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,result("Thermostat"));
        assertFalse(adapter.isAllowedDevice("00:11:22:33:44:55"));assertTrue(BleDiagnostics.drain().isEmpty());
        // Calling JNI for this result would throw UnsatisfiedLinkError in the unit test.
        assertTrue(adapter.admit(result("LVS-Hush")));assertTrue(adapter.isAllowedDevice("00:11:22:33:44:55"));
    }
    @Test public void unapprovedPeripheralCannotReachGattEvenIfCreatedDirectly() throws Exception {
        Peripheral peripheral=new Peripheral(new Adapter(),"AA:BB:CC:DD:EE:02");Object future=peripheral.connect();
        Field result=future.getClass().getDeclaredField("result");result.setAccessible(true);
        assertNotNull(result.get(future));assertThrows(FutureException.class,()->((PollResult<?>)result.get(future)).get());
        Field gatt=Peripheral.class.getDeclaredField("gatt");gatt.setAccessible(true);assertNull(gatt.get(peripheral));
    }
}
