#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -lt 1 || $# -gt 2 || ( $# -eq 2 && "$2" != "--install-docker" ) ]]; then
    echo 'Usage: sudo bash relay/install.sh relay.example.com [--install-docker]' >&2
    exit 1
fi
domain="${1,,}"
if [[ ${#domain} -gt 253 || ! "$domain" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]]; then
    echo 'Use a public hostname, without https:// or a path (example: relay.example.com).' >&2
    exit 1
fi
if [[ $EUID -ne 0 ]]; then
    echo 'Run with sudo on your Linux VPS.' >&2
    exit 1
fi
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if ! command -v docker >/dev/null 2>&1; then
    if [[ "${2:-}" != '--install-docker' ]]; then
        echo 'Docker is missing. On a fresh Ubuntu/Debian VPS, rerun with --install-docker.' >&2
        exit 1
    fi
    # Install from Docker's signed apt repository; never remove existing packages.
    source /etc/os-release
    if [[ "$ID" != ubuntu && "$ID" != debian ]]; then
        echo 'Automatic Docker setup supports Ubuntu and Debian. Install Docker Engine + Compose first on other distributions.' >&2
        exit 1
    fi
    apt-get update
    apt-get install -y ca-certificates curl
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL "https://download.docker.com/linux/$ID/gpg" -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/%s %s stable\n' \
        "$(dpkg --print-architecture)" "$ID" "$VERSION_CODENAME" > /etc/apt/sources.list.d/docker.list
    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    systemctl enable --now docker
fi
docker compose version >/dev/null || { echo 'Install the Docker Compose plugin first.' >&2; exit 1; }
docker info >/dev/null
docker run --rm --mount "type=bind,source=$PWD,target=/setup" -w /setup node:24-alpine node setup.mjs "$domain"
# The unprivileged Node container needs access only to its private runtime data.
chown -R 1000:1000 .runtime
chmod 700 .runtime
docker compose config --quiet
docker compose up -d --build --force-recreate
printf '\nRelay address: https://%s\nPrivate phone setup code (share only with your partner):\n' "$domain"
cat .setup-code
printf '\nCaddy is obtaining HTTPS automatically. Check https://%s/health before connecting phones.\n' "$domain"
echo 'If it is not ready after a minute, run: sudo docker compose logs --tail=60 caddy relay'
