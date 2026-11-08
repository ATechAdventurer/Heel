# lovense-native

The Buttplug/btleplug device-control core, shared by the Android app (`../android`) and the
iOS app (`../ios`). Device protocol handling, output validation/quantization and the live-frame
scheduler live here once; each platform adds its own thin FFI boundary and BLE transport:

- Android: JNI functions in `src/lib.rs` under `#[cfg(target_os = "android")]`, using btleplug's
  Android backend (vendored Java support in `../android/vendor`).
- iOS: C-ABI functions under `#[cfg(target_os = "ios")]`, using btleplug's CoreBluetooth backend.

Run `cargo test --locked` from this directory for the platform-independent tests (validation,
quantization, the live-frame queue). Platform-specific code is only exercised by the Android or
iOS app builds, since it needs a real radio.
