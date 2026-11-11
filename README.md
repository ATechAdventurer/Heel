# Heel 🐾

A small, personal peripheral controller with a little bite. Android is the supported app; an iOS port is in progress. I originally built this as a gift but I ended up polishing it to the point where I'm happy to share it.

Heel connects two consenting adults over the internet. One phone is the owner; the pet connects directly to a supported Lovense Bluetooth device. If you would like other brands or devices to be supported then please leave a Github Issue or contact me on discord (username `sshado`). If it's not a device I'm familiar with then I can try to reverse engineer it for you.

**The Android and iOS apps each include both Owner and Pet roles.** You choose your role on first launch, but you can change it by hitting the logo in the top left.

## Beta status: please read first

This is an early beta, not safety-certified. There are small bugs I have not yet found. The app does its best to prevent unwanted vibration, but always be prepared to use the toy's physical off switch if something breaks. That said, it has been tested and has received a thumbs up from those testers so far. Please reach out if you experience any glitches, confusion, or bugs.

For the app to function, a relay server exists to establish a connection between your two phones. This source release does not include access to a hosted relay. Run your own, or get an address and private phone setup code from someone you trust. The relay operator can technically read control traffic; transport uses HTTPS/WSS, **but end-to-end encryption is not yet fully implemented**.

**This means you should use a relay operator that you trust, or simply install your own relay for personal use and friends.**

## What is here right now

- A large, smooth touch pad with one or two vibration controls, depending on the device.
- Hold on release with optional controller-phone haptics.
- A "Spellbook" for recording and replaying gestures.
- Owner/pet dynamics, temporary Pause, and a Safe Word slider to halt the session.
- Background connection handling and reconnection.
- Direct Bluetooth through embedded [Buttplug](https://buttplug.io/). <-- Huge shoutout to these folks. They're incredible engineers and work very hard. Go check out their community!
- Dark/light appearance and a few personal stickers. In the future I plan to allow customization of the stickers!

Android 8.0+ on an **ARM64** phone is required. The iOS app needs iOS 16.1+, has no release yet, and is built from source: see [`ios/README.md`](ios/README.md). Heel exposes vibration channels supported by its pinned ButtplugIO device configuration. I cannot promise of support for every Lovense product or for non-vibration functions. Hush has been tested thoroughly during development but broader hardware testing is welcome.

## Get started

1. **[Download the current release: Heel 0.9.2 Beta](https://github.com/sshado/Heel/releases/tag/v0.9.2-beta)** and install `Heel-v0.9.2-beta.apk` on **both phones**. You can also [build the Android app](android/README.md) yourself, or [build the iOS app](ios/README.md) for an iPhone. Both apps use the same relay, so an iPhone and an Android phone should be able to pair.
2. [Set up a relay](relay/README.md). On a fresh Ubuntu VPS with a domain pointing at it, the installer handles Docker, the relay, HTTPS and a private setup code.
3. Open Heel on each phone and choose Owner or Pet. Tap **Set relay address**, enter the same `https://relay.your-domain.example` address, then enter the host's phone setup code.
4. The Owner creates an invitation; the Pet enters that invitation code. Compare the confirmation number and approve the pair.
5. The Pet grants Bluetooth permissions and connects their device. Both parties hit Ready/Start in the app to begin. Tap the Heel logo for Settings, connection details and diagnostics.

The reusable **phone setup code** is what allows a phone to connect to the relay server. The short-lived **pair invitation** links an owner with a pet. They are **different codes**; share both **privately**. You only need to connect to the Relay server once, and then you can pair and unpair as many times as you want.

## Repository

| Folder | Contents |
| --- | --- |
| [`android/`](android/) | Unified Android app, Rust bridge, vendored Android BLE support and tests |
| [`ios/`](ios/) | iOS app (in progress), XcodeGen project and tests |
| [`native/`](native/) | Shared Rust device-control core used by both apps |
| [`relay/`](relay/) | Node.js relay, tests, Docker Compose and self-host installer |
| [`docs/`](docs/) | Privacy and known limitations |

## Feedback

[Open an issue](https://github.com/sshado/Heel/issues) with your phone model, Android or iOS version, device model, app version and steps to reproduce. Remove setup/invitation codes, names, IP addresses and Bluetooth addresses from any logs before posting. Don't publish private session data.

Read [privacy and security notes](docs/PRIVACY.md) before hosting for others. This is a small hobby project with no uptime promise. It is not affiliated with or endorsed by Lovense, Intiface or the Buttplug maintainers. Android is the supported app for now and iOS is in progress. Store availability is not promised.

# LLM Disclaimer

This code was written in tandem by myself and GPT-6 Astra. I am a BLE engineer in my day job and have been a professional software developer for over ten years, but I also use AI tools to help me code. This is a passion project, not something I will ever be paid for.

If you wish to use your own LLM to submit Github Issues or Pull Requests, then I welcome it. All contributors are invited to collaborate and participate. You can always reach out to me on Discord at (username: `sshado`) or through the issues page here.
## License

Original application and relay code are licensed under [BSD-3-Clause](LICENSE). Dependencies retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md). **Character artwork is excluded from BSD-3-Clause** and retains its existing rights; replace it in independently distributed forks unless you have permission from the repo's maintainer. Nunito is licensed separately under the SIL Open Font License.
