# Production secrets with OpenBao

Production secrets (database, Keycloak, RabbitMQ, Redis, SMTP and app
signing secrets) live in **OpenBao**, not in git and not in files on disk.
Each deploy reads them with a short-lived identity and they exist on the
server only in RAM, only while the deploy runs.

```
GitHub Actions deploy job (self-hosted runner on oak-app)
  │ 1. GitHub OIDC token  (audience "openbao"; claims: this repo,
  │                        environment=production, ref=main)
  ▼
OpenBao  127.0.0.1:8200  ── auth/jwt role "dms-deploy" ──► 10-min token,
  │                                                        policy: read ONE path
  │ 2. read secret/dms/production/app
  ▼
render-secrets.py (in memory) ──ssh──► /dev/shm/oakdms/secrets.env (RAM, 0600)
                                           │ 3. deploy.sh: compose --env-file .env
                                           │                     --env-file secrets.env
                                           ▼
                                       containers start; file deleted at job end
```

**Nothing long-lived can read the secrets:** there's no token in GitHub,
no token on the server and no secret in git. Humans use a separate
operator login (`ops`) that expires after 1 hour.

| Piece | Where |
|---|---|
| OpenBao server | `infra/openbao/docker-compose.yml` (its own compose project `openbao`), data in volume `openbao_openbao_data` |
| Server config | `infra/openbao/config.hcl` (raft storage, audit log, loopback-only HTTP) |
| Policies | `infra/openbao/policies/` (`dms-deploy` = read one path; `dms-operator` = manage DMS secrets + snapshots) |
| Operator tool | `infra/openbao/bao-ops.sh` (needs only `curl` + `python3` on the host) |
| Which keys are secret | `infra/production/secret-keys.txt` (the one list everything reads) |
| Renderer | `infra/production/render-secrets.py` |
| Audit log | `/openbao/logs/audit.log` in volume `openbao_openbao_audit` (values HMAC'd, never plain) |

All commands below run on **oak-app as `oak`**, from `/opt/dms/infra/openbao`.
The deploy uploads these files, so run one normal deploy of this change first.

---

## 1. One-time setup

### 1.1 Start and initialise
```bash
cd /opt/dms/infra/openbao
bash bao-ops.sh up
bash bao-ops.sh init
```
`init` prints **3 unseal keys and a root token, once**. They are stored
nowhere else:
- Give each unseal key to a **different trusted person**, who keeps it in a password manager. **Any 2 of the 3** unseal OpenBao.
- **Losing 2 keys means losing every secret permanently.** There is no recovery.
- Never paste them into chat, tickets, email or git.
- The root token is used once in the next step, then revoked.

### 1.2 Unseal and configure
```bash
bash bao-ops.sh unseal          # two people each enter their key
bash bao-ops.sh configure       # asks for the root token and a new 'ops' password (16+ chars)
```
`configure` does the following, and is safe to re-run:
- turns on the KV v2 secrets store at `secret/`;
- checks the audit log is active;
- writes both policies;
- sets up the GitHub OIDC trust, with role `dms-deploy` bound to
  `OAK-Integrated-Systems-Apps-Department/dms-platform`, environment
  `production`, ref `refs/heads/main`;
- creates the `ops` operator login;
- finally **revokes the root token**.

### 1.3 Move the secrets from `.env` into OpenBao
```bash
bash bao-ops.sh import /opt/dms/infra/production/.env   # copies the 11 keys in secret-keys.txt
bash bao-ops.sh verify /opt/dms/infra/production/.env   # "all 11 secrets match"
bash bao-ops.sh strip  /opt/dms/infra/production/.env   # removes them from .env (asks first)
```
Import refuses to run if any value is still `CHANGE_ME`. `strip` only runs
after `verify` passes. Afterwards `.env` holds configuration only (URL,
image tag, SMTP host, …).

### 1.4 First backup
```bash
bash bao-ops.sh snapshot ~/openbao-$(date +%F).snap
```
Copy the file **off the server**, e.g. with `scp` to a backup location,
then delete it from oak-app. A snapshot is useless without the unseal keys,
so the keys and the snapshots must never be stored together.

### 1.5 Switch deploys to OpenBao
GitHub → Settings → Secrets and variables → Actions → **Variables** →
add **`BAO_ADDR` = `http://127.0.0.1:8200`**.

**`127.0.0.1` here means oak-app itself, not your PC.** The only thing
that uses `BAO_ADDR` is the deploy job's "Fetch secrets from OpenBao" step.
That step runs on the self-hosted runner, which is installed **on oak-app**,
the same machine as OpenBao, so it connects over the server's own loopback.
Do **not** use `192.168.0.10:8200`: OpenBao's port is deliberately bound to
`127.0.0.1` only (`infra/openbao/docker-compose.yml`) and serves plain HTTP,
so nothing on the network can reach it. Only if the runner ever moves to
another machine would you need `https://192.168.0.10:8200`, and only after
adding TLS in `config.hcl` and changing that port binding.

Check it from the runner's point of view, on oak-app:
```bash
sudo -u gh-runner curl -s http://127.0.0.1:8200/v1/sys/seal-status   # JSON with "sealed": false
```

Then **Actions → Deploy → Run workflow** (branch `main`, `image_tag` = the
current tag from `bash ../production/deploy.sh --status`). You should see:
- the "Fetch secrets from OpenBao" step succeed;
- deploy.sh logging `secrets: from OpenBao (/dev/shm/oakdms/secrets.env)`;
- "Remove rendered secrets from the server" run at the end.

To go back, delete the `BAO_ADDR` variable and put the secrets back in `.env`.

---

## 2. Day-to-day operations

### After a server reboot
- **The app comes back by itself.** Docker restarts the containers with their existing configuration.
- **OpenBao starts sealed.** Nothing can read secrets until two key holders unseal it.
- **Deploys are blocked until then**, and the workflow fails with "OpenBao is sealed":
  ```bash
  bash bao-ops.sh status
  bash bao-ops.sh unseal
  ```

### Change a secret
```bash
bash bao-ops.sh set SMTP_PASSWORD      # hidden prompt, asked twice; changes only this key
```
It takes effect on the **next deploy** (Actions → Deploy → Run workflow with
the current tag).

For passwords that another service also stores, **change that service first**,
then OpenBao, then deploy:

| Secret | Change it in the service first |
|---|---|
| `DMS_CORE_DB_PASSWORD` | `docker exec -it oakdms-postgres psql -U postgres -c "ALTER USER dms_core_user PASSWORD '…'"` |
| `POSTGRES_PASSWORD` | `ALTER USER postgres PASSWORD '…'` (Keycloak also uses it) |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | Keycloak admin → Clients → dms-core-admin → Credentials → Regenerate |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak admin console → master realm → admin user |
| `RABBITMQ_DEFAULT_PASS`, `REDIS_PASSWORD`, `SEAWEEDFS_*`, `SMTP_PASSWORD`, `AUDIT_CORRELATION_SECRET`, `IMPERSONATION_SECRET` | Nothing else to change; the next deploy applies them. Changing either of the last two signs out anyone mid-impersonation |

### Manual deploy (GitHub Actions unavailable)
```bash
cd /opt/dms/infra/openbao
bash bao-ops.sh render                       # ops login; writes /dev/shm/oakdms/secrets.env
cd ../production && bash ./deploy.sh <full-commit-sha>
bash ../openbao/bao-ops.sh clear             # always remove it again
```

### Status and logs (no secrets needed)
```bash
bash /opt/dms/infra/production/deploy.sh --status
docker logs --tail=200 oakdms-core
docker exec openbao tail -n 20 /openbao/logs/audit.log    # who read what, when
```

### Backups
Take a snapshot at least **weekly** and after every secret change. Copy it
off the server each time:
```bash
bash bao-ops.sh snapshot ~/openbao-$(date +%F).snap
```
**Restoring** needs a root token. Generate one with the unseal keys
(`docker exec -it openbao bao operator generate-root`), then run
`docker exec -i openbao bao operator raft snapshot restore -` < file, and
revoke the root token afterwards.

### Lost the `ops` password
Generate a root token with the unseal keys
(`docker exec -it openbao bao operator generate-root -init`, then each key holder), then run
`bash bao-ops.sh configure --reset-ops-password`, which revokes that root token at the end.

---

## 3. Security notes and limits

- **Plain HTTP on loopback.** OpenBao is bound to `127.0.0.1` and the runner is on the same machine, so nothing crosses the network. Add TLS in `config.hcl` **before** OpenBao is ever reached from another host.
- **Manual unseal.** There's no cloud KMS, so a reboot needs two key holders before the next deploy. The running app is not affected. With a cloud account (AWS KMS, Azure Key Vault, GCP KMS) or a second OpenBao, switch to auto-unseal.
- **Docker keeps container environment in its own config** (`/var/lib/docker`, root-only). That's how containers restart after a reboot without OpenBao. It's inherent to environment-variable configuration; anyone with root or `docker` group access on oak-app can read it. Treat those accounts as fully privileged.
- **Single node.** Protect against data loss with the snapshots, which must be stored off-host.
- **When moving to Kubernetes (RKE2/Helm):** keep this OpenBao. Add its Kubernetes auth method or the External Secrets Operator; the KV paths and policies stay the same.
- **Not in OpenBao yet:** the optional `infra/production/dms-core.env` (per-StorageLocation `STORAGE_CRED_*` pairs). Keep it `chmod 600` or move those keys to a second KV path in a follow-up.
