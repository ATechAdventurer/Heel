package com.nonpolynomial.btleplug.android.impl;

import android.bluetooth.le.ScanRecord;
import android.os.ParcelUuid;
import java.util.*;

/** Heel admission policy, before JNI discovery or GATT. Generic UART UUIDs are not identity. */
final class LovenseScanPolicy {
    // Exact Lovense-specific services from the pinned Buttplug 9.0.9 configuration.
    private static final Set<String> SERVICES = new HashSet<>(Arrays.asList(
        "50300001-0024-4bd4-bbd5-a6920e4c5653", "57300001-0023-4bd4-bbd5-a6920e4c5653",
        "5a300001-0024-4bd4-bbd5-a6920e4c5653", "50300001-0023-4bd4-bbd5-a6920e4c5653",
        "53300001-0023-4bd4-bbd5-a6920e4c5653", "5a300001-0023-4bd4-bbd5-a6920e4c5653",
        "4f300001-0023-4bd4-bbd5-a6920e4c5653", "42300001-0023-4bd4-bbd5-a6920e4c5653",
        "43300001-0023-4bd4-bbd5-a6920e4c5653", "4c300001-0023-4bd4-bbd5-a6920e4c5653",
        "4c410001-0023-4bd4-bbd5-a6920e4c5653", "56300001-0023-4bd4-bbd5-a6920e4c5653",
        "58300001-0023-4bd4-bbd5-a6920e4c5653", "52300001-0023-4bd4-bbd5-a6920e4c5653",
        "46300001-0023-4bd4-bbd5-a6920e4c5653", "50300011-0023-4bd4-bbd5-a6920e4c5653",
        "4a300001-0023-4bd4-bbd5-a6920e4c5653", "45440001-0023-4bd4-bbd5-a6920e4c5653",
        "45420001-0023-4bd4-bbd5-a6920e4c5653", "54300001-0023-4bd4-bbd5-a6920e4c5653",
        "45490001-0023-4bd4-bbd5-a6920e4c5653", "4e300001-0023-4bd4-bbd5-a6920e4c5653",
        "45410001-0023-4bd4-bbd5-a6920e4c5653", "51300001-0023-4bd4-bbd5-a6920e4c5653",
        "45460001-0023-4bd4-bbd5-a6920e4c5653", "454c0001-0023-4bd4-bbd5-a6920e4c5653",
        "55300001-0023-4bd4-bbd5-a6920e4c5653", "53440001-0023-4bd4-bbd5-a6920e4c5653",
        "48300001-0023-4bd4-bbd5-a6920e4c5653", "46530001-0023-4bd4-bbd5-a6920e4c5653",
        "42410001-0023-4bd4-bbd5-a6920e4c5653", "43410001-0023-4bd4-bbd5-a6920e4c5653",
        "4f430001-0023-4bd4-bbd5-a6920e4c5653", "455a0001-0023-4bd4-bbd5-a6920e4c5653"));
    static boolean matches(ScanRecord packet) {
        if (packet == null) return false;
        return matches(packet.getDeviceName(), packet.getServiceUuids(), packet.getManufacturerSpecificData(620));
    }
    static boolean matches(String name, List<ParcelUuid> services, byte[] manufacturer) {
        if (name != null && (name.startsWith("LVS-") && name.length() > 4 || name.startsWith("LOVE-") && name.length() > 5)) return true;
        if (services != null) for (ParcelUuid service : services) if (SERVICES.contains(service.toString())) return true;
        return manufacturer != null && manufacturer.length >= 2 && (manufacturer[0] & 255) == 255 && (manufacturer[1] & 255) == 33;
    }
}
