# Privacy and beta limitations

Heel is intended for consenting adults who know and trust one another. It has no public discovery, chat, media sharing, advertising SDK or analytics SDK.

## Data flow

The Owner sends control commands to the relay. The relay forwards them to the paired Pet phone, which sends Bluetooth commands to the selected device. Device capability/status information and acknowledgments travel back through the relay. The phone setup code enrolls a device identity; a separate invitation and approval link the two identities.

HTTPS/WSS encrypts transport between each phone and the relay. The relay terminates that encryption and **can read messages**. This is not end-to-end encrypted. Self-host or trust your host accordingly.

The relay persists phone identity/token hashes, pairing profiles (including chosen names/species), and Safe Word state. Active output levels and live control grants are not persisted across relay restart. Network infrastructure can still observe IP addresses and connection timing. The provided Caddy configuration does not enable access logs, but operational error logs and infrastructure records can exist. Rate-limit counters are in memory.

The Android app stores role/settings, paired-session state and local recordings. Its relay token is encrypted using Android Keystore; application backup is disabled. Diagnostics can include identifying device or connection details. Export and share logs only after reviewing/redacting them.

The iOS app stores role/settings, the Pet's Safe Word latch, local Spellbook recordings and the relay address in the app's own storage, and its relay token in the Keychain. The token is marked this-device-only, so it does not sync through iCloud Keychain and cannot be restored onto a different phone. Unlike Android, app backup is not disabled: iOS backups can include the stored role, settings, recordings and relay address. The Pet's iPhone keeps the chosen device's Bluetooth identity locally so it can find the same device again after a drop; it is not sent to the relay. The app asks only for Bluetooth permission (no location, contacts, photos or microphone), declares no tracking in its privacy manifest, and has no debug log or diagnostics export yet.

## Deletion and access

Unpairing removes the pair relationship, not the enrolled phone identities. Clearing app data or uninstalling removes the phone's local data but does not delete its old identity from the server. A host can stop the relay and delete its private state to reset the entire server, which invalidates all enrolled identities and requires everyone to enroll/pair again. There is no per-phone admin deletion UI in this beta. Hosts control their backups and retention.

## Known limitation requiring physical testing

Unexpected vibration has been reported after Safe Word, a Bluetooth/device disconnect, and reconnection. It is unresolved. A Ready-up softlock fix is included, but automated tests do not prove that a physical device remains stopped. Keep a physical way to turn the device off and test off-body first. Do not advertise Safe Word, disconnection or phone power loss as a guaranteed physical stop.

During an otherwise authorized session, reconnection is intentionally allowed to restore the last acknowledged Hold level. Pause should stay paused; auto-release recovers at zero. A new app process, relay restart or device change requires fresh session authorization. These are intended behaviors covered by software tests, not a guarantee across every phone/device combination.

On iOS the same caution applies, and automated tests cannot prove it on a physical device. If the Pet's phone loses the device mid-session it looks for the same one and, once found, waits for the Pet to tap Resume instead of restarting output by itself; Android resumes automatically. iOS also suspends an app shortly after it leaves the foreground, which drops its relay connection. Keep Heel open on both phones during a session, and do not treat a locked or backgrounded phone as a guaranteed stop.
