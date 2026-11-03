# Server deployment (Ubuntu VPS)

Target host: Ubuntu 22.04/24.04, 4 cores, 8 GB RAM. The Node process is
single-threaded, so the deployment reserves ~1 core for it and leaves the rest
for nginx, SQLite I/O, and the OS.

## What runs where

| Piece | Path |
| --- | --- |
| Release code | `/opt/flockcam/server` |
| SQLite database | `/var/lib/flockcam/flockcam.db` |
| Environment file | `/etc/flockcam/flockcam.env` (0600) |
| Play service account | `/etc/flockcam/play-service-account.json` (0600) |
| systemd unit | `/etc/systemd/system/flockcam.service` |
| nginx site | `/etc/nginx/sites-available/flockcam` |

Node listens on `127.0.0.1:8080`; nginx terminates TLS on 443 and is the only
public listener. The server does not need a writable directory besides
`/var/lib/flockcam`, which `ProtectSystem=strict` enforces.

## First install

```bash
git clone https://github.com/techted89/F-Flock-Cam.git /srv/flockcam-src
cd /srv/flockcam-src
sudo bash server/deploy/install.sh
sudo editor /etc/flockcam/flockcam.env     # fill in secrets
sudo systemctl restart flockcam
curl -fsS http://127.0.0.1:8080/api/health
```

`install.sh` installs Node 20, creates the `flockcam` system user, copies the
server to `/opt/flockcam/server`, runs `npm ci --omit=dev`, installs the unit
and nginx site, and enables the service. It never overwrites an existing env
file.

## TLS

```bash
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d api.flockcam.app
sudo systemctl reload nginx
```

Set `PUBLIC_BASE_URL=https://api.flockcam.app` in the env file before restarting
so PayPal return URLs and payment records use the public host.

## Deploying a new version

```bash
cd /srv/flockcam-src
git fetch --tags && git checkout vX.Y.Z
sudo bash server/deploy/install.sh   # idempotent; keeps env + database
sudo systemctl restart flockcam
curl -fsS https://api.flockcam.app/api/health
```

The database and env file live outside the release directory, so redeploys are
safe. `rsync --delete` only prunes `/opt/flockcam/server`, never
`/var/lib/flockcam`.

## Docker alternative

```bash
cd server
docker build -t flockcam-server .
docker run -d --name flockcam \
  --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -v /var/lib/flockcam:/data \
  --env-file /etc/flockcam/flockcam.env \
  -e DB_PATH=/data/flockcam.db \
  flockcam-server
```

The image runs as the unprivileged `node` user and drops build tooling from the
final layer. Put nginx in front exactly as above.

## Resource profile

- `MemoryMax=900M` / `MemoryHigh=700M` in the unit file; the hot Deflock cache
  is bounded (192 entries) and SQLite uses a 16 MB page cache.
- `CPUQuota=150%` leaves headroom for nginx and the OS on a 4-core box.
- `PREMIUM_CONCURRENCY` / `FREE_CONCURRENCY` gate camera work: premium requests
  keep a reserved budget and free requests yield while premium work is active.
- Camera payloads are compact JSON, gzipped at 1 KB+, with ETag/`Cache-Control`
  so phones re-use a region for two minutes instead of re-querying.
- `startMaintenance` prunes expired sessions, codes, ad rewards, finished
  actions, and week-old cached tiles every hour.
- The app requests one region per radius change and filters cameras locally by
  radius and route, so the server never streams a route-sized dataset.

## Checks before declaring a deploy good

```bash
systemctl status flockcam --no-pager
journalctl -u flockcam -n 100 --no-pager
curl -fsS https://api.flockcam.app/api/health
curl -fsS https://api.flockcam.app/api/payments/plans
```

The health response must be `{"status":"ok"}` and `journalctl` must show no
unhandled rejections. Rotate secrets by editing the env file and restarting;
sessions survive because only their hashes are stored.
