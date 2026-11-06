package com.nonpolynomial.btleplug.android.impl;

import android.bluetooth.*;
import io.github.gedgygedgy.rust.future.Future;
import io.github.gedgygedgy.rust.task.PollResult;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowBluetoothGatt;
import java.lang.reflect.Field;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, shadows={PeripheralWriteTest.ManualGatt.class,PeripheralWriteTest.MinimalDevice.class})
public class PeripheralWriteTest {
    // The stock 4.17 device shadow references a newer nested API class on API28.
    // This fixture only needs identity; GATT behavior is exercised below.
    @Implements(BluetoothDevice.class)
    public static class MinimalDevice {
        @Implementation protected String getAddress() { return "00:11:22:33:44:55"; }
    }
    @Implements(BluetoothGatt.class)
    public static class ManualGatt extends ShadowBluetoothGatt {
        @Implementation protected boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) { return true; }
    }
    @Test public void failedGattCallbackFailsFutureAndReleasesSerializedQueue() throws Exception {
        BluetoothDevice device=BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55");
        BluetoothGatt gatt=ShadowBluetoothGatt.newInstance(device);
        UUID uuid=UUID.randomUUID();
        BluetoothGattService service=new BluetoothGattService(UUID.randomUUID(),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic characteristic=new BluetoothGattCharacteristic(uuid,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE);
        service.addCharacteristic(characteristic);
        Shadows.shadowOf(gatt).addDiscoverableService(service); gatt.discoverServices();
        Peripheral peripheral=new Peripheral(null,"00:11:22:33:44:55");
        set(peripheral,"connected",true); set(peripheral,"gatt",gatt);
        BluetoothGattCallback callback=(BluetoothGattCallback)get(peripheral,"callback");
        Future<Void> failed=peripheral.write(uuid,new byte[]{1},BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        Future<Void> next=peripheral.write(uuid,new byte[]{2},BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        assertNull(get(failed,"result")); assertNull(get(next,"result"));
        callback.onCharacteristicWrite(gatt,characteristic,133);
        PollResult<?> failure=(PollResult<?>)get(failed,"result"); assertNotNull(failure);
        try { failure.get(); fail("A failed GATT callback must never acknowledge output"); }
        catch (RuntimeException expected) { assertTrue(expected.toString().contains("133") || expected.getCause().toString().contains("133")); }
        assertNull("Next operation stays pending until its own callback",get(next,"result"));
        callback.onCharacteristicWrite(gatt,characteristic,BluetoothGatt.GATT_SUCCESS);
        assertNotNull(get(next,"result")); assertNull(((PollResult<?>)get(next,"result")).get());
        assertFalse((boolean)get(peripheral,"executingCommand"));
    }
    private static Object get(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
