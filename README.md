# Heel 🐾

A small, personal Android peripheral controller with a little bite. Built as a gift, shared as a hobby project.

Heel connects two consenting adults over the internet. One phone controls; the other connects directly to a supported Lovense Bluetooth device. **The same Android app includes both Owner and Pet roles.** You choose on first launch, and Heel remembers.

## Beta status — please read first

This is an early beta, not a safety-certified controller. **Unexpected vibration after Safe Word followed by a device disconnect/reconnect has been reported and is not yet resolved.** The related Ready-up softlock has a software fix, but physical-device retesting is still needed. Test with the device off-body and its physical power control within reach; do not rely on the app as your only way to stop it.

This source release does not include access to a hosted relay. Run your own, or get an address and private phone setup code from someone you trust. The relay operator can read control traffic; transport uses HTTPS/WSS, **not end-to-end encryption**.

## What is here

- A large, smooth touch pad with one or two vibration controls, depending on the device.
- Hold on release, optional controller-phone haptics, and a roomy zero/rest area.
- A Spellbook for recording and replaying gestures, with a faint ghost-paw trace.
- Pair invitations and approval, temporary Pause, and a slide-to-activate Safe Word.
- Background connection handling and reconnection. An authorized Hold session can restore its acknowledged level after a connection drop; auto-release returns to zero. Safe Word is intended to block recovery until the Pet explicitly resumes (see the known issue above).
- Direct Bluetooth through embedded [Buttplug](https://buttplug.io/); no separate Intiface app required.
- Dark/light appearance and a few very personal stickers.

Android 8.0+ on an **ARM64** phone is required. Heel exposes vibration channels supported by its pinned Buttplug device configuration. It is not a promise of support for every Lovense product or for non-vibration functions. Hush has been used during development; broader hardware testing is welcome. The Owner phone controls through the relay; it does not scan for local devices.

## Get started

1. [Build the Android app](android/README.md), or use an APK explicitly provided by the maintainer. Install the **same APK on both phones**. This repository does not contain signing keys or APK binaries.
2. [Set up a relay](relay/README.md). On a fresh Ubuntu VPS with a domain pointing at it, the installer handles Docker, the relay, HTTPS and a private setup code.
3. Open Heel on each phone and choose Owner or Pet. Tap **Set relay address**, enter the same `https://relay.your-domain.example` address, then enter the host's phone setup code.
4. The Owner creates an invitation; the Pet enters that invitation code. Compare the confirmation number and approve the pair.
5. The Pet grants Bluetooth permissions and connects their device. Follow Ready/Start in the app to begin. Tap the Heel logo for Settings, connection details and diagnostics.

The reusable **phone setup code** admits a phone to your server. The short-lived **pair invitation** links two phones. They are different codes; share both privately. Unpairing does not revoke a phone's server identity.

## Repository

| Folder | Contents |
| --- | --- |
| [`android/`](android/) | Unified Android app, Rust bridge, vendored Android BLE support and tests |
| [`relay/`](relay/) | Node.js relay, tests, Docker Compose and self-host installer |
| [`docs/`](docs/) | Privacy and known limitations |

## Feedback

[Open an issue](https://github.com/sshado/Heel/issues) with your phone model, Android version, device model, app version and steps to reproduce. Remove setup/invitation codes, names, IP addresses and Bluetooth addresses from any logs before posting. Don't publish private session data.

Read [privacy and security notes](docs/PRIVACY.md) before hosting for others. This is a small hobby project with no uptime promise. It is not affiliated with or endorsed by Lovense, Intiface or the Buttplug maintainers. Android only for now; Play Store availability is not promised.

## License

Original application and relay code are licensed under [BSD-3-Clause](LICENSE). Dependencies retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md). **Character artwork is excluded from BSD-3-Clause** and retains its existing rights; replace it in independently distributed forks unless you have permission. Nunito is licensed separately under the SIL Open Font License.
