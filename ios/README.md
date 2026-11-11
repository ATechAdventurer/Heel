# Build the iOS app

One app, both roles, like Android. It needs iOS 16.1 or later.

**Status: in progress.** There is no iOS release, so you build it from source. It speaks the same relay protocol as the Android app, so it uses the same relay. Treat it as unproven with a real toy: keep the toy's physical off switch within reach, and read the known issue in the root README.

## Tools

- A Mac with a recent Xcode that includes the iOS SDK.
- Stable Rust and Cargo, installed with [rustup](https://rustup.rs/), plus the iOS targets:

```sh
rustup target add aarch64-apple-ios-sim   # iOS Simulator on Apple silicon
rustup target add aarch64-apple-ios       # a physical iPhone
```

- [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen`. `Heel.xcodeproj` is generated from `project.yml` and is not checked in.

## Build and run

From this directory:

```sh
cp Config/Local.xcconfig.example Config/Local.xcconfig
xcodegen generate
open Heel.xcodeproj
```

Choose the **Heel** scheme and a destination, then run. An Xcode pre-build step compiles the shared Rust core (`../native`) for that destination and links it in, so the first build downloads and compiles the Rust dependencies and takes a while.

- **Simulator.** Works without any signing setup. The simulator has no Bluetooth, so the Pet role cannot find a device there; pairing and the Owner role work.
- **A physical iPhone.** Put your Apple developer team ID in `Config/Local.xcconfig` (`DEVELOPMENT_TEAM`, which is git-ignored) and choose your phone. Development-signed builds only install on devices registered to your team, and they expire on Apple's schedule (a free account's lasts about a week). Install on **both phones** to use both roles.

No relay is hardcoded. First choose a role, set your own HTTPS relay address and enter your host's phone setup code.

## Checks

```sh
xcrun simctl list devices available    # pick any iPhone simulator from this list
xcodebuild -project Heel.xcodeproj -scheme Heel \
  -destination 'platform=iOS Simulator,name=<simulator name>' test
cd ../native
cargo test --locked
```

CI picks an available simulator automatically. The tests drive the session logic with fake relay and engine objects. They cannot establish physical motor behavior or that a toy reconnects. Hardware testing of Safe Word and Bluetooth reconnect remains necessary.

## How it differs from Android

- **Keep Heel open.** iOS suspends an app shortly after it leaves the foreground, and its relay connection with it. There is no equivalent of Android's connection service, so a session only runs while Heel is in front on **both** phones. Heel stops output when it loses its connection, but don't treat a locked or backgrounded phone as a guaranteed stop.
- **Lost Bluetooth device.** If the Pet's phone loses the toy mid-session it looks for the same toy again, and once it is found waits for the Pet to tap **Resume** instead of restarting output by itself. Android resumes automatically. iOS also can't limit reconnection to one toy at the Bluetooth layer the way Android's patched stack does: a different toy that connects during the search is simply never driven.
- **VoiceOver and Switch Control.** With either on, the Owner's touch pad is replaced by one slider with Start and Stop for each vibration control.
- **Not ported yet:** the debug log, haptic diagnostics and the device battery level.

## Layout

- `Heel/` holds the app: `Models/` (session gate, touch model, Spellbook), `Services/` (relay client, native engine wrapper, session runtime), `Views/` and `App/`.
- `HeelTests/` holds the unit tests.
- `project.yml` is the XcodeGen project definition; `Config/` holds the local signing settings.
- `../native/` is the Rust device-control core shared with Android.
