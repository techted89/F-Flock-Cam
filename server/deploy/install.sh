#!/usr/bin/env bash
# Provision the F-Flock-Cam server on a fresh Ubuntu 22.04/24.04 VPS.
# Run as root from a checkout of the repository:  sudo bash server/deploy/install.sh
set -euo pipefail

APP_ROOT=/opt/flockcam
DATA_DIR=/var/lib/flockcam
CONF_DIR=/etc/flockcam
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ $EUID -ne 0 ]]; then
  echo "install.sh must run as root" >&2
  exit 1
fi

apt-get update
apt-get install -y --no-install-recommends nginx ca-certificates curl python3 make g++

if ! command -v node >/dev/null 2>&1; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi

if ! id flockcam >/dev/null 2>&1; then
  useradd --system --home "$APP_ROOT" --shell /usr/sbin/nologin flockcam
fi

install -d -o flockcam -g flockcam "$APP_ROOT" "$DATA_DIR"
install -d -o root -g flockcam -m 0750 "$CONF_DIR"

rsync -a --delete \
  --exclude node_modules \
  --exclude data \
  "$REPO_ROOT/server/" "$APP_ROOT/server/"

cd "$APP_ROOT/server"
sudo -u flockcam npm ci --omit=dev --no-audit --no-fund

if [[ ! -f "$CONF_DIR/flockcam.env" ]]; then
  install -o root -g flockcam -m 0640 \
    "$APP_ROOT/server/deploy/flockcam.env.example" "$CONF_DIR/flockcam.env"
  echo "Created $CONF_DIR/flockcam.env — fill in secrets, then rerun."
fi

chown -R flockcam:flockcam "$DATA_DIR"
chmod 700 "$DATA_DIR"

install -o root -g root -m 0644 "$APP_ROOT/server/deploy/flockcam.service" \
  /etc/systemd/system/flockcam.service
install -o root -g root -m 0644 "$APP_ROOT/server/deploy/nginx.conf" \
  /etc/nginx/sites-available/flockcam
ln -sf /etc/nginx/sites-available/flockcam /etc/nginx/sites-enabled/flockcam
rm -f /etc/nginx/sites-enabled/default

systemctl daemon-reload
systemctl enable --now flockcam
nginx -t
systemctl reload nginx

curl -fsS http://127.0.0.1:8080/api/health && echo
echo "Server installed. Issue the TLS certificate with certbot, then verify /api/health over HTTPS."
