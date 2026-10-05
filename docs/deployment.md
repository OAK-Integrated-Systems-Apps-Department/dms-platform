# Production deployment (oak-app / 192.168.0.10)

This replaces the old manual process (`docker save` on Windows → `scp` →
`docker load`). GitHub Actions builds and tests everything, publishes images
to GitHub Container Registry (GHCR), and deploys them to the Ubuntu server
with Docker Compose behind the host's nginx.

```
push / PR ──► ci.yml ─────────── backend tests (mvn verify, real Postgres)
                                 frontend lint* + typecheck
                                 production Docker images (built, not pushed)
                                 compose validation
push to main ─► deploy.yml ──── ci.yml (same checks)
                                 build + push images to GHCR  (tag = commit SHA)
                                 SSH ─► oak-app: deploy.sh <sha>
                                          pull → compose up -d → health checks
                                          → auto-rollback on failure

browser ─► http://192.168.0.10  (host nginx, port 80, the only public port)
             /            → frontend   127.0.0.1:3000
             /api/        → dms-core   127.0.0.1:8080
             /realms/     → Keycloak   127.0.0.1:8081  (email links, account console)
             /resources/  → Keycloak   (static assets for those pages)
```
\* lint is non-blocking until the existing lint errors are fixed (see ci.yml).

## Files

| File | Purpose |
|---|---|
| `.github/workflows/ci.yml` | PR / `develop` checks; also called by deploy.yml |
| `.github/workflows/deploy.yml` | Build + push to GHCR, deploy over SSH |
| `infra/production/docker-compose.yml` | Production stack (registry images, no public ports) |
| `infra/production/.env.example` | Template for the server-side `.env` (never commit the real one) |
| `infra/production/deploy.sh` | Server-side deploy / rollback / status script |
| `infra/production/nginx/dms.conf` | Host nginx site |
| `infra/production/postgres/init-multiple-dbs.sh` | First-start DB/user creation (password from `.env`) |

The dev stack (`infra/local/`) is unchanged in how you use it.

## Why the frontend now calls `/api/...`

`frontend/lib/api-config.ts` builds every request as
`${NEXT_PUBLIC_API_BASE_URL}/api/v1/...`. `NEXT_PUBLIC_*` values are inlined
into the JavaScript bundle **at `next build` time**; they cannot be changed
when the container starts. The old production image never received a value
(`frontend/.dockerignore` excludes `.env*`), so it fell back to
`http://localhost:8080`, which in a user's browser means *their own PC*.

`frontend/Dockerfile` now builds with `NEXT_PUBLIC_API_BASE_URL=""` (same
origin, so requests go to `/api/v1/...`) and
`NEXT_PUBLIC_KEYCLOAK_ACCOUNT_URL=/realms/oak-dms/account`. The image
contains no hostname, so the same image works on any host behind nginx, and
because browser, frontend and API share one origin there is no CORS
involved. `bun dev` on a developer machine still defaults to `localhost`.

## 1. GitHub configuration

### Secrets (Settings → Secrets and variables → Actions → Secrets)

| Secret | Value |
|---|---|
| `DEPLOY_SSH_PRIVATE_KEY` | Private half of a key made **only** for deploys (see 2.3). |
| `DEPLOY_SSH_KNOWN_HOSTS` | The server's host key line(s) (see 2.3). Host key checking stays strict. |

The application secrets (DB passwords, Keycloak secrets, …) are **not** stored
in GitHub. With the `BAO_ADDR` variable set they live in **OpenBao** and each
deploy reads them with a short-lived GitHub OIDC login, into RAM only. See
**[secrets-openbao.md](secrets-openbao.md)**. Without it, they live in the
server's `infra/production/.env`. Either way, a leaked GitHub secret can't
expose them. GHCR needs no secret: the workflow uses the job's own
short-lived `GITHUB_TOKEN`.

### Variables (Settings → Secrets and variables → Actions → Variables)

| Variable | Example | Notes |
|---|---|---|
| `DEPLOY_HOST` | `192.168.0.10` | SSH target |
| `DEPLOY_USER` | `oak` | SSH user (must be in the `docker` group) |
| `DEPLOY_PATH` | `/opt/dms` | Optional, default `/opt/dms` |
| `PUBLIC_URL` | `http://192.168.0.10` | Used for the post-deploy check |
| `DEPLOY_RUNNER_LABEL` | `self-hosted` | Optional, runner label for the deploy job |
| `BAO_ADDR` | `http://127.0.0.1:8200` | Optional: read secrets from OpenBao ([secrets-openbao.md](secrets-openbao.md)); unset = secrets in the server `.env` |

### Environment

Create an environment named **`production`** (Settings → Environments). The
deploy job uses it. Add *Required reviewers* there if every production deploy
should need a manual approval, and restrict it to the `main` branch.

### Self-hosted runner (required)

`192.168.0.10` is a private LAN address. **GitHub-hosted runners cannot reach
it**, so the deploy job runs on a self-hosted runner inside that network.
Build/test jobs still run on GitHub's runners.

Install one on any always-on Linux machine on the LAN (it can be `oak-app`
itself): Settings → Actions → Runners → *New self-hosted runner* → Linux, and
follow the commands shown. It needs `ssh`, `tar` and `curl`, but not Docker.
Run it as a dedicated unprivileged user and install it as a service
(`sudo ./svc.sh install && sudo ./svc.sh start`). For an organization repo,
limit the runner to this repository.

If the server is ever reachable through a VPN or public SSH endpoint instead,
set `DEPLOY_RUNNER_LABEL=ubuntu-latest` and `DEPLOY_HOST` accordingly.

## 2. One-time server setup (Ubuntu, as `oak`)

### 2.1 Docker access and directory
```bash
sudo usermod -aG docker oak          # log out/in afterwards
docker version && docker compose version
sudo mkdir -p /opt/dms && sudo chown oak:oak /opt/dms
```

### 2.2 Existing data (read before the first deploy)

Compose names volumes `<project>_postgres_data` and so on. The production file uses
the **same volume keys** as `infra/local`, so it takes over the existing data
only if `COMPOSE_PROJECT_NAME` matches the project that currently runs:
```bash
docker compose ls                   # NAME column, e.g. "local"
docker volume ls | grep postgres_data
```
Put that name in `.env` (`COMPOSE_PROJECT_NAME=local`). Then:

- **Back up first:**
  `docker exec oakdms-postgres pg_dumpall -U postgres | gzip > ~/dms-backup-$(date +%F).sql.gz`
- `DMS_CORE_DB_PASSWORD` must be the password `dms_core_user` already has
  (the local init script created it as `dms_core_pass`; change it later
  with `ALTER USER`).
- `POSTGRES_PASSWORD` must be the current postgres password (Keycloak
  connects as `postgres` with it).
- `KEYCLOAK_ADMIN_CLIENT_SECRET` must equal the `dms-core-admin` client
  secret stored in the existing realm (Keycloak admin console → Clients →
  dms-core-admin → Credentials).
- The old containers use the same `oakdms-*` names. If the old stack ran
  under a *different* project name, stop it first **without `-v`**:
  `docker compose -p <old-name> down`.

### 2.3 Deploy SSH key
On your PC:
```bash
ssh-keygen -t ed25519 -f dms_deploy -C "github-deploy" -N ""
ssh-keyscan -t ed25519 192.168.0.10 > dms_known_hosts
```
Verify that fingerprint against the server itself (`ssh-keygen -lf
/etc/ssh/ssh_host_ed25519_key.pub` on oak-app) before trusting it. Then:
- Put `dms_deploy` (private key) into the `DEPLOY_SSH_PRIVATE_KEY` secret
  and `dms_known_hosts` into `DEPLOY_SSH_KNOWN_HOSTS`.
- On the server, append `dms_deploy.pub` to `/home/oak/.ssh/authorized_keys`,
  restricted to the runner's address:
  ```
  from="<runner-ip>",no-agent-forwarding,no-port-forwarding,no-X11-forwarding,no-pty ssh-ed25519 AAAA... github-deploy
  ```
- Delete the local key files afterwards.

Note: `oak` is in the `docker` group, which is effectively root on that
machine. Anyone holding this key can control the server, so keep the
`from=` restriction and rotate the key if the runner is rebuilt.

### 2.4 Server-side `.env`
The first deploy uploads `infra/production/.env.example`. Before that, or after
a first (failing) run:
```bash
cd /opt/dms/infra/production
cp .env.example .env && chmod 600 .env
nano .env        # replace every CHANGE_ME, set COMPOSE_PROJECT_NAME (2.2)
openssl rand -base64 36   # for any new secret
```
`deploy.sh` refuses to run while a `CHANGE_ME` remains. Values that come from
`infra/secrets/dms-core.secrets.env` (OpenBao) keep the same names.
`KC_DB_PASSWORD` is not used in production (Keycloak uses `POSTGRES_PASSWORD`).

`AUTH_COOKIE_SECURE=false` is **required while the site is plain HTTP**:
browsers drop `Secure` cookies on `http://192.168.0.10`, and login would
fail without an error message. Set it back to `true` once nginx serves HTTPS.

Optional `infra/production/dms-core.env` (also `chmod 600`) holds extra
dms-core variables such as `STORAGE_PROVIDER`, `S3_*` or
`STORAGE_CRED_<REF>_ACCESS_KEY`/`_SECRET_KEY` (names in the root `.env.example`).

### 2.5 Nginx
```bash
sudo cp /opt/dms/infra/production/nginx/dms.conf /etc/nginx/sites-available/dms
sudo ln -sf /etc/nginx/sites-available/dms /etc/nginx/sites-enabled/dms
sudo rm -f /etc/nginx/sites-enabled/default     # it also claims default_server
sudo nginx -t && sudo systemctl reload nginx
```
`deploy.sh` prints a note when a new release changes `nginx/dms.conf`, so
re-run the `cp`/`nginx -t`/`reload` lines then. Deploys never touch
`/etc/nginx` themselves (no sudo needed).

### 2.6 Firewall
Only SSH and HTTP need to be reachable:
```bash
sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw enable
```
Docker port bindings in the production compose are all `127.0.0.1:` (or none),
so Postgres, Redis, RabbitMQ, SeaweedFS, Keycloak admin and dms-core's
actuator/Swagger are not reachable from the network. Docker bypasses ufw for
*published* ports, which is why nothing is published on `0.0.0.0`.

## 3. Deploying

- **Normal:** merge `develop` → `main`. The push runs `deploy.yml`.
- **Manual:** Actions → *Deploy* → *Run workflow* → pick the branch
  (builds + deploys it), or fill **image_tag** with an existing full commit
  SHA to redeploy that image without rebuilding.

What `deploy.sh <sha>` does on the server:
1. Pulls `dms-core` and `frontend` for that tag. If this fails, nothing changes.
2. `docker compose up -d`. Only containers whose image/config changed are
   recreated; Postgres/Keycloak/etc. keep running. `keycloak-bootstrap`
   re-applies Keycloak roles, SMTP and the web client URLs every time.
3. Waits (up to 300 s) for the dms-core and frontend health checks.
4. Checks through nginx: `GET /login` → 200, `POST /api/v1/auth/refresh`
   → 401 (proves `/api/` reaches dms-core), and that Keycloak's issuer
   equals `PUBLIC_URL/realms/oak-dms` (what dms-core validates tokens against).
5. Success → records the tag in `.env` (`IMAGE_TAG`) and `.deploy-history`.
   Failure → prints container status and logs, **automatically redeploys the
   previous tag**, and exits non-zero so the workflow run fails.

Afterwards the workflow checks `PUBLIC_URL` once more from the runner, to prove
it is reachable over the LAN and not only on the server itself.

### Manual deploy (GitHub Actions unavailable)
From a checkout on the server, or after copying the same files the workflow uploads:
```bash
# one-time: a classic PAT with only read:packages
echo "<PAT>" | docker login ghcr.io -u <github-user> --password-stdin
cd /opt/dms/infra/production
bash ./deploy.sh <full-commit-sha>
```
Without GHCR at all, build and load the images yourself and tag them with the
names from the compose file:
```bash
docker build -t ghcr.io/oak-integrated-systems-apps-department/dms-core:<sha> -f backend/Dockerfile backend
docker build -t ghcr.io/oak-integrated-systems-apps-department/dms-frontend:<sha> -f frontend/Dockerfile frontend
# (docker save | ssh oak@192.168.0.10 docker load  if built elsewhere)
```
Then, on the server, run `IMAGE_TAG=<sha> docker compose up -d` and follow
section 4 to verify.

## 4. Operating

```bash
cd /opt/dms/infra/production
bash ./deploy.sh --status              # deployed tag, history, health
docker compose ps                      # all containers + health
docker compose logs -f --tail=200 dms-core
docker compose logs --tail=200 frontend keycloak keycloak-bootstrap
docker inspect --format '{{json .State.Health}}' oakdms-core
```

### Rollback
```bash
bash ./deploy.sh --rollback            # previous tag from .deploy-history
bash ./deploy.sh <older-full-sha>      # any tag still in GHCR / on the host
```
or Actions → Deploy → Run workflow with `image_tag`. Rollback only swaps the
app images. **Flyway migrations are not undone**: an older dms-core ignores
newer migrations it doesn't know about, but if a release changed the schema
incompatibly, restore the database backup (section 2.2) instead.

Old images stay on the host so rollbacks work offline. Prune occasionally,
keeping recent ones: `docker image prune -a --filter "until=720h"`.

### Verify nginx
```bash
sudo nginx -t
curl -sI http://192.168.0.10/login | head -1                       # 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://192.168.0.10/api/v1/auth/refresh   # 401
curl -s http://192.168.0.10/realms/oak-dms/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
curl -s -o /dev/null -w '%{http_code}\n' http://192.168.0.10/admin/    # NOT Keycloak admin (frontend 404/redirect)
sudo tail -f /var/log/nginx/access.log /var/log/nginx/error.log
```

### Verify frontend → API
In the browser, open `http://192.168.0.10`, press F12 → Network, and log in.
The `login` request must go to `http://192.168.0.10/api/v1/auth/login`
(not `localhost`). Also check that the bundle contains no localhost URL:
```bash
docker exec oakdms-frontend sh -c 'grep -rlE "localhost:(8080|8081)" /app/.next /app/server.js || echo clean'
```

### Admin UIs (SSH tunnel, never public)
```bash
ssh -L 8081:localhost:8081 -L 15672:localhost:15672 -L 8026:localhost:8026 oak@192.168.0.10
# Keycloak admin http://localhost:8081/admin  ·  RabbitMQ http://localhost:15672  ·  Mailpit http://localhost:8026
```

## 5. Known limitations / follow-ups

- **HTTPS.** The site is plain HTTP on the LAN. Keycloak's realm has
  `sslRequired: external`, which only allows HTTP from private addresses, so
  it works on 192.168.x but not from a public IP. Add TLS in nginx before any
  exposure beyond the LAN, then set `PUBLIC_URL=https://…` and
  `AUTH_COOKIE_SECURE=true`.
- **Seeded Keycloak user.** `infra/local/keycloak/oak-dms-realm.json` creates
  `superadmin@gmail.com` with a password that is committed in the repo, on a
  realm's first import. Change that password immediately on any new install.
- **Keycloak DB user.** Keycloak uses the `postgres` superuser (inherited
  from the local setup). A dedicated `keycloak` role owning only
  `keycloak_db` would be better.
- **Mail.** Defaults deliver into Mailpit, so nobody receives email. Set
  `SMTP_*` to a real relay.
- **Upload size.** One file can be up to 2047 MB, the most ClamAV scans of a
  single file. That limit is set in four places that must agree:
  - nginx `client_max_body_size 2048m`
  - `spring.servlet.multipart`
  - `dms.scanning.max-file-size-bytes` (the `scan_engines` row)
  - clamd `StreamMaxLength`/`MaxFileSize`

  Uploads spool to the dms-core container's temp directory and to staging
  storage, so allow several GB of free disk for concurrent large uploads.
- **Virus scanner.** The `clamav` service (`clamav/clamav-debian:1.4.3`) has the
  same limits as the local stack, and dms-core reaches it at `clamav:3310`.
  - **Network:** it has no host port, but it needs outbound HTTPS to
    `database.clamav.net` for signature updates (every 2 hours).
  - **First deploy:** a fresh `clamav_db` volume downloads about 300 MB of
    signatures before clamd listens, which takes several minutes. Uploads made
    meanwhile wait as "being scanned" and finish by themselves once it's up.
  - **Memory:** budget about 3 GB of RAM for it (it's capped at 4 GB).
  - **Check it:** `docker exec oakdms-clamav clamdcheck.sh` should succeed, and
    `docker logs oakdms-clamav` shows "File size limit set to 2146435072
    bytes".
- **Integration tests.** The 14 `*IT` classes compile but never run (no
  maven-failsafe-plugin configured), so CI runs the 28 unit/context tests.
- **Actions pinning.** Workflows use major-version tags (`@v5`). Pin to
  commit SHAs if your org requires it.
