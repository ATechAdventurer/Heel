package com.nonpolynomial.btleplug.android.impl;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local, bounded diagnostics for this prototype. No network or file output. */
public final class BleDiagnostics {
    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private static final Map<String, Integer> ids = new HashMap<>();
    private static final Set<String> seen = new HashSet<>();
    private static int nextId = 1, reports, ignored;
    private static volatile boolean verbose;
    public static boolean isVerbose() { return verbose; }
    public static void setVerbose(boolean enabled) { verbose = enabled; }
    private BleDiagnostics() {}

    public static synchronized void record(String message) {
        if (events.size() >= 256) events.removeFirst();
        events.addLast(message + " [uptime=" + android.os.SystemClock.uptimeMillis() + "ms]");
    }
    public static synchronized List<String> drain() {
        List<String> result = new ArrayList<>(events);
        events.clear();
        return result;
    }
    static synchronized String id(String address) {
        if (!ids.containsKey(address)) {
            if (ids.size() >= 256) ids.clear();
            ids.put(address, nextId++);
        }
        return "BLE#" + ids.get(address);
    }
    static synchronized void scanStarted() {
        seen.clear(); reports = ignored = 0;
        record("BLE scan entered Android API: Lovense advertisement admission before discovery/GATT");
    }
    static synchronized void scanStopped() {
        record("BLE scan stopped: " + reports + " Lovense advertisements, " + seen.size() + " candidates; " + ignored + " unrelated advertisements ignored");
    }
    static synchronized void ignoredAdvertisement() { if (ignored < Integer.MAX_VALUE) ignored++; }
    static synchronized void advertisement(ScanResult result) {
        reports++;
        String address = result.getDevice().getAddress();
        if (!seen.add(address)) return;
        if (seen.size() > 40) {
            if (seen.size() == 41) record("BLE advertisement detail limit reached (40); counting remaining devices only");
            return;
        }
        ScanRecord packet = result.getScanRecord();
        record(id(address) + " advertisement: name=" + (packet == null ? "none" : packet.getDeviceName())
            + " RSSI=" + result.getRssi() + " services=" + (packet == null ? "none" : packet.getServiceUuids())
            + " bytes=" + (packet == null ? "none" : hex(packet.getBytes(), 80)));
    }
    public static String hex(byte[] bytes, int limit) {
        if (bytes == null) return "none";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(limit, bytes.length); i++) {
            result.append(Character.forDigit((bytes[i] >>> 4) & 15, 16));
            result.append(Character.forDigit(bytes[i] & 15, 16));
        }
        if (bytes.length > limit) result.append("…");
        return result.toString();
    }
    static String scanError(int code) {
        switch (code) {
            case 1: return "already started";
            case 2: return "application registration failed";
            case 3: return "Bluetooth internal error";
            case 4: return "scan settings unsupported";
            case 5: return "out of hardware resources";
            case 6: return "scanning too frequently; wait before retrying";
            default: return "unknown scan error";
        }
    }
}
