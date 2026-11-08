# Third-party notices

This project uses Buttplug 9.0.9 and btleplug 0.11.8 under their upstream licenses. The matching btleplug Android Java sources and jni-utils 0.1.1 Java sources are vendored from the published crates. Copyright and redistribution notices are in `licenses/`.

Prototype changes to btleplug Java support in v0.2: added `BleDiagnostics.java`; instrumented scan and GATT callbacks; enabled foreground low-latency scanning; forwarded batched scan results; logged `onScanFailed`; and routed service-discovery failure through its existing future so it cannot escape the callback and leave the command queue blocked. jni-utils Java sources are unmodified.

v0.4 additionally propagates failed characteristic-write callback statuses through the existing future, makes verbose packet tracing optional, and adds monotonic millisecond timestamps to diagnostic records. The underlying GATT command serialization remains in place.

v0.7.1 adds LovenseScanPolicy before scan callbacks reach JNI and an approved-address check before GATT connection. It accepts Lovense names, exact manufacturer-specific service UUIDs from the pinned Buttplug Lovense configuration, or its manufacturer signature. The shared Nordic UART service alone is excluded. Unrelated advertisements are counted in aggregate without per-device packet logging. Native Rust dependencies and binaries are unchanged.

- Buttplug: https://github.com/buttplugio/buttplug — BSD 3-Clause; native dependency and bundled device configuration.
- btleplug: https://github.com/deviceplug/btleplug — BSD/MIT/Apache notices as reproduced in `licenses/btleplug.txt`; native BLE transport and Java support.
- jni-utils: https://github.com/gedgygedgy/jni-utils-rs — BSD 3-Clause; JNI support classes and native dependency.
- Gradle wrapper: https://github.com/gradle/gradle — Apache 2.0 and bundled notices in `licenses/gradle-Apache-2.0.txt`.
- Nunito: https://github.com/google/fonts/tree/main/ofl/nunito — SIL Open Font License 1.1, bundled in `licenses/Nunito-OFL.txt`. The unmodified variable font is bundled for offline welcome-screen typography.

Additional Rust dependencies are locked in `../native/Cargo.lock`. The crate is shared with the iOS app; see `../native/README.md`. License texts collected from the resolved registry packages are included in `licenses/rust-dependency-notices.txt` (including host build/test packages). Android Gradle tooling is resolved from Google's Maven repository. This prototype is not affiliated with or endorsed by Lovense, Intiface, or the Buttplug maintainers.

The character images under `app/src/main/res/drawable-nodpi/` are personal artwork supplied for Heel. They are excluded from the BSD-3-Clause code license and retain the rights of their respective rights holders. Inclusion does not grant permission for independent artwork reuse or redistribution. See the root `THIRD_PARTY_NOTICES.md`.

## Bluetooth recovery added in v0.8

BleRecovery adds a process-local exact-address gate and radio-loss tracking. Recovery scanning uses an Android address filter, retaining the Lovense identity checks. Safe Word blocks future and in-flight connection attempts. The GATT bridge rejects callbacks from closed/replaced connections, closes disconnected handles and completes a pending connect future even for a clean disconnection. Native snapshots now include local server device identity for matching the selected physical toy; it is excluded from relay messages.

## Android network runtime added in v0.6

- OkHttp Android 5.1.0 — https://github.com/square/okhttp — Apache 2.0. Pinned to the SDK35-compatible Android release; 5.5.1 requires compileSDK37.
- Okio JVM 3.15.0 — https://github.com/square/okio — Apache 2.0.
- Kotlin standard library 2.2.0 and JetBrains annotations13.0 — https://github.com/JetBrains/kotlin — Apache 2.0.
- AndroidX annotation1.9.1, startup-runtime1.2.0 and tracing1.0.0 — https://android.googlesource.com/platform/frameworks/support — Apache 2.0.

The resolved runtime list and Apache license are in `licenses/Android-network-dependencies.txt`, also bundled as an APK asset.
