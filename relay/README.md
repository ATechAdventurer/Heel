# Host your own Heel relay

The relay passes messages between two paired phones. The Pet phone handles Bluetooth locally. Both phones must use the same relay address. There is no web dashboard.

## Easiest install

Use a **fresh Ubuntu 24.04 VPS**, a domain/subdomain you control, and an SSH login with sudo access. A small server is enough for personal testing; no capacity guarantee is made.

1. Point a DNS **A record** such as `heel.example.com` at your VPS's public IPv4 address. If an AAAA record exists, it must point to working IPv6 on that same server; otherwise remove the stale record. Use a DNS-only record for initial setup.
2. In your VPS provider's firewall, allow inbound TCP **80 and 443**, and keep your SSH access allowed. Ensure nothing else uses ports 80/443. Do not expose port 8787.
3. SSH into the VPS and run these commands, replacing `heel.example.com` with your hostname:

```sh
sudo apt-get update && sudo apt-get install -y git
git clone https://github.com/sshado/Heel.git
cd Heel
sudo bash relay/install.sh heel.example.com --install-docker
```

That's the installation. The script installs Docker from its official signed apt repository if missing, builds the relay, starts Caddy for automatic HTTPS, and prints **your relay address and private phone setup code**. It does not change your firewall or SSH configuration. Existing Docker installs need the Compose plugin; the script won't replace them. Automatic Docker installation also supports Debian with an official Docker repository.

Wait about a minute, then open `https://heel.example.com/health` in a browser. A successful response contains `"ok":true`. That verifies the public HTTPS path; it is not a web UI.

On **both phones**: choose a role → **Set relay address** → enter `https://heel.example.com` → enter the printed phone setup code. The Owner then creates a separate invitation to pair with the Pet. Use the same Android APK for both.

Already have Docker Engine and Compose? Omit `--install-docker`. Rerunning the installer with the same domain preserves identities, pair data and the setup code, but briefly restarts the services.

## Keep this private

The phone setup code can enroll additional phones now or later. It does not expire and is **not** the short-lived pairing invitation. Give it only to people you intend to admit to your server. Anyone with that code can register a phone; pair approval is still required before control.

To show your code again, from `Heel/relay`:

```sh
sudo cat .setup-code
```

`.setup-code`, `.env` and `.runtime/` are ignored by Git. Do not upload or paste them into issues. The relay stores only a hash of the enrollment code; the installer keeps the plaintext separately in `.setup-code` for your convenience. Unpairing does not revoke enrolled phones. This beta does not provide an admin UI for individual phone revocation.

## Updates, logs and backups

From `Heel/relay`:

```sh
# See status and recent logs
sudo docker compose ps
sudo docker compose logs --tail=60 relay caddy

# Update (ends active sessions; users must explicitly begin again)
git pull --ff-only
sudo docker compose pull
sudo docker compose up -d --build --force-recreate

# Stop without deleting identities or HTTPS certificates
sudo docker compose down
```

Use `--force-recreate` when updating: Caddy shares the relay's network namespace, so recreate both together. Caddy alone exposes 80/443; Node listens on loopback inside that namespace. Caddy overwrites the client-IP header used for rate limiting. Docker-published ports can bypass host UFW rules; use your provider firewall as described in [Docker's firewall guidance](https://docs.docker.com/engine/install/ubuntu/#firewall-limitations).

For a consistent private backup, stop the stack with `docker compose down`, then copy `.runtime/`, `.env` and `.setup-code` to secure storage and restart with `docker compose up -d`. Backups contain pairing profiles and server credentials. Caddy's `heel_caddy_data` and `heel_caddy_config` Docker volumes hold certificate state; preserve them across upgrades. Never use `down -v` as an ordinary update step.

The runtime directory belongs to UID 1000 for the unprivileged Node container. The installer sets this up. Restoring a backup may require `sudo chown -R 1000:1000 .runtime` and `sudo chmod 700 .runtime` before starting.

## If setup gets stuck

- **HTTPS fails:** check A/AAAA records, inbound 80/443, existing web servers, and `docker compose logs --tail=60 caddy`. Certificate issuance can take time. [Caddy's HTTPS prerequisites](https://caddyserver.com/docs/automatic-https#overview) explain what must be reachable.
- **Setup code rejected:** use the long code from `.setup-code`, not the pair invitation; ensure both phones use this server's URL. Repeated attempts are rate-limited, so wait a minute.
- **Docker install conflicts:** this quick path is for a fresh VPS. For an existing machine, follow [Docker's official installation instructions](https://docs.docker.com/engine/install/ubuntu/) without removing services you still need.
- **Changed relay URL:** use Heel → Settings → Advanced connection settings. Resetting the address removes that phone's saved server identity and requires enrollment again.
- **Server restored/restarted:** persisted pairing remains, but active control authority is not restored. Follow Ready/Start again.

The installer targets personal, small-scale hosting. You are responsible for server updates, DNS and private backups. It does not configure a hosted service, monitoring or end-to-end encryption for you.

## Development without Docker

Node.js 22+ is required (the container uses Node 24). From this folder:

```sh
npm ci
npm test
```

`server.mjs` accepts `HOST`, `PORT`, `HEEL_DATA_FILE`, `HEEL_ENROLLMENT_FILE`, and `HEEL_TRUST_LOOPBACK_PROXY`. Enrollment JSON uses `oneTimeHashes` and/or `reusableHashes` arrays of SHA-256 hashes. See `setup.mjs` for generation and `test/` for examples. Without enrollment hashes, new phones cannot register. Do not expose the development HTTP listener directly to the internet; the Android app requires valid HTTPS.

Original relay code: BSD-3-Clause. `ws`: [MIT](licenses/ws-MIT.txt).
