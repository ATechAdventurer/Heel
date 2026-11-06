package com.nonpolynomial.btleplug.android.impl;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter.Builder;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

@SuppressWarnings("unused") // Native code uses this class.
class Adapter {
    private long handle;
    private final Callback callback = new Callback();
    private final LinkedHashSet<String> allowedDevices = new LinkedHashSet<>();
    synchronized boolean isAllowedDevice(String address) { return BleRecovery.allows(address) && allowedDevices.contains(address); }
    synchronized boolean admit(ScanResult result) {
        if (result == null || result.getDevice() == null || !LovenseScanPolicy.matches(result.getScanRecord())) {
            BleDiagnostics.ignoredAdvertisement(); return false;
        }
        String address = result.getDevice().getAddress();
        if (!BleRecovery.allows(address)) return false;
        if (allowedDevices.size() >= 256 && !allowedDevices.contains(address)) allowedDevices.remove(allowedDevices.iterator().next());
        allowedDevices.add(address); return true;
    }

    public Adapter() {}

    @SuppressLint("MissingPermission")
    public void startScan(ScanFilter filter) {
        synchronized (this) { allowedDevices.clear(); }
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
          throw new RuntimeException("No bluetooth adapter available.");
        }

        ArrayList<android.bluetooth.le.ScanFilter> filters = null;
        String[] uuids = filter.getUuids();
        if (!BleRecovery.targetAddress().isEmpty()) {
            filters = new ArrayList<>();
            filters.add(new Builder().setDeviceAddress(BleRecovery.targetAddress()).build());
        } else if (uuids.length > 0) {
            filters = new ArrayList<>();
            for (String uuid : uuids) {
                filters.add(new Builder().setServiceUuid(ParcelUuid.fromString(uuid)).build());
            }
        }
        ScanSettings settings;
        if (Build.VERSION.SDK_INT >= 26) {
            settings = new ScanSettings.Builder()
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setLegacy(false)
                    .build();
        } else {
            settings = new ScanSettings.Builder()
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build();
        }
        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
          throw new RuntimeException("No bluetooth scanner available for adapter");
        }
        BleDiagnostics.scanStarted();
        try {
            scanner.startScan(filters, settings, this.callback);
            BleDiagnostics.record("BLE startScan returned; waiting for advertisement callbacks");
        } catch (RuntimeException error) {
            BleDiagnostics.record("ERROR BLE startScan: " + error);
            throw error;
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner != null) {
                scanner.stopScan(this.callback);
                BleDiagnostics.scanStopped();
            }
        }
    }

    private native void reportScanResult(ScanResult result);

    public native void onConnectionStateChanged(String address, boolean connected);

    private class Callback extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!Adapter.this.admit(result)) return;
            BleDiagnostics.advertisement(result);
            Adapter.this.reportScanResult(result);
        }
        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
        }
        @Override
        public void onScanFailed(int code) {
            BleDiagnostics.record("ERROR BLE scan failed: code=" + code + " (" + BleDiagnostics.scanError(code) + ")");
        }
    }
}
