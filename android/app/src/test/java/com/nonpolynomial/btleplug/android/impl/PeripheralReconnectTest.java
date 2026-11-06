package com.nonpolynomial.btleplug.android.impl;

import android.bluetooth.*;
import io.github.gedgygedgy.rust.future.FutureException;
import io.github.gedgygedgy.rust.task.PollResult;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowBluetoothGatt;
import java.lang.reflect.Field;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={PeripheralWriteTest.MinimalDevice.class,PeripheralReconnectTest.ManualGatt.class})
public class PeripheralReconnectTest {
    @Implements(BluetoothGatt.class) public static class ManualGatt extends ShadowBluetoothGatt {
        @Implementation protected boolean connect(){return true;}
    }
    private Peripheral peripheral;private BluetoothGatt gatt;private BluetoothGattCallback callback;
    private static Object get(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @Before @SuppressWarnings("unchecked") public void setup()throws Exception {
        BleRecovery.discover();
        Adapter adapter=new Adapter(){@Override public void onConnectionStateChanged(String address,boolean connected){}};
        Field allow=Adapter.class.getDeclaredField("allowedDevices");allow.setAccessible(true);((Set<String>)allow.get(adapter)).add("00:11:22:33:44:55");
        peripheral=new Peripheral(adapter,"00:11:22:33:44:55");gatt=ShadowBluetoothGatt.newInstance(BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55"));set(peripheral,"gatt",gatt);callback=(BluetoothGattCallback)get(peripheral,"callback");
    }
    @After public void cleanup(){BleRecovery.discover();}
    @Test public void cleanDisconnectDuringConnectFailsFutureAndFreesGattForNextAttempt()throws Exception {
        Object pending=peripheral.connect();assertNull(get(pending,"result"));callback.onConnectionStateChange(gatt,0,BluetoothGatt.STATE_DISCONNECTED);
        assertThrows(FutureException.class,()->((PollResult<?>)get(pending,"result")).get());assertNull(get(peripheral,"gatt"));assertFalse((boolean)get(peripheral,"executingCommand"));
    }
    @Test public void safeWordRejectsAnInFlightConnectionAndLateCallbacksCannotReviveIt()throws Exception {
        Object pending=peripheral.connect();BleRecovery.block();callback.onConnectionStateChange(gatt,0,BluetoothGatt.STATE_CONNECTED);
        assertThrows(FutureException.class,()->((PollResult<?>)get(pending,"result")).get());assertFalse(peripheral.isConnected());assertNull(get(peripheral,"gatt"));
        BleRecovery.discover();callback.onConnectionStateChange(gatt,0,BluetoothGatt.STATE_CONNECTED);assertFalse(peripheral.isConnected());
    }
    @Test public void recoveryAdmissionAcceptsOnlyExactAddressAndNeverInvalidIdentity() {
        assertTrue(BleRecovery.target("PeripheralId(AA:BB:CC:DD:EE:01)"));assertTrue(BleRecovery.allows("aa:bb:cc:dd:ee:01"));assertFalse(BleRecovery.allows("AA:BB:CC:DD:EE:02"));
        assertFalse(BleRecovery.target("Lovense Hush"));assertFalse(BleRecovery.allows("AA:BB:CC:DD:EE:01"));
    }
}
