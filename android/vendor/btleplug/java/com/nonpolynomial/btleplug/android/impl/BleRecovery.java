package com.nonpolynomial.btleplug.android.impl;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Process-local connection gate. Addresses never leave the pet phone. */
public final class BleRecovery {
    private static final Pattern ADDRESS = Pattern.compile("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}");
    private static String target = "";
    private static boolean blocked;
    private static final Set<String> lost = new HashSet<>();
    private BleRecovery() {}
    public static String address(String identifier) {
        Matcher match = ADDRESS.matcher(identifier == null ? "" : identifier);
        return match.find() ? match.group().toUpperCase(java.util.Locale.ROOT) : "";
    }
    public static synchronized void discover() { target = ""; blocked = false; }
    public static synchronized void block() { blocked = true; }
    public static synchronized boolean target(String identifier) {
        target = address(identifier); blocked = target.isEmpty(); return !blocked;
    }
    public static synchronized String targetAddress() { return target; }
    public static synchronized boolean allows(String value) { return !blocked && (target.isEmpty() || target.equals(address(value))); }
    public static synchronized void connection(String value, boolean connected) {
        String key = address(value);
        if (connected) lost.remove(key); else { if (lost.size() >= 256) lost.clear(); lost.add(key); }
    }
    public static synchronized boolean disconnected(String value) { return lost.contains(address(value)); }
}
