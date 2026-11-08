# Build the Android app

One app, both roles. There are no separate Owner/Pet APK targets in this public project.

## Tools

- JDK 17, with `JAVA_HOME` set.
- Android SDK with platform 35, build-tools 35.0.0, and NDK **27.2.12479018**. Set `ANDROID_HOME` to the SDK directory (or use a local, untracked `local.properties`). Android Studio can install these from SDK Manager.
- Stable Rust and Cargo, installed with [rustup](https://rustup.rs/).
- Rust Android target and cargo-ndk:

```sh
rustup target add aarch64-linux-android
cargo install cargo-ndk --version 3.5.4 --locked
```

Gradle 8.11.1 is provided by the checked-in wrapper; it downloads on first use. The build uses Android Gradle Plugin 8.9.2 and downloads locked Rust dependencies. On Windows, install the native compiler/build prerequisites requested by rustup as well.

## Build and install

From this directory:

```sh
./gradlew assembleDebug
```

On Windows PowerShell, use `./gradlew.bat assembleDebug`.

The task builds the ARM64 Rust library automatically. Install `app/build/outputs/apk/debug/app-debug.apk` on **both phones**, using Android's APK installer or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

This is a debug-signed development build. Its key is generated locally in `.gradle/debug.keystore`, which must stay private. Keep that key if you want your future builds to upgrade your own existing installs. A build signed with a different key cannot upgrade another person's APK; uninstalling clears local identity and requires setup again. Do not confuse a CI artifact with a stable, maintainer-signed release.

No relay is hardcoded. First choose a role, set your own HTTPS relay address and enter your host's phone setup code. Existing explicitly saved relay settings are retained when upgrading with the same signing key.

## Checks

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
cd ../native
cargo test --locked
```

Robolectric tests exercise UI, permissions, session state and simulated connection boundaries. They cannot establish physical motor behavior. Hardware testing of Safe Word and Bluetooth reconnect remains necessary; see the root README's known issue.

For a store or long-term public APK release, use a private release signing key and the platform's current submission requirements. Neither release credentials nor a hosted backend are shipped in this repository.
