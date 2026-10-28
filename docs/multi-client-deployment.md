# Deploying to many clients

One codebase, one set of images, and a separate **installation** per client: their
own server (cloud or on-prem), their own database, Keycloak, storage and OCR worker,
their own secrets. Nothing is forked per client - what differs is configuration.

```
git tag v1.4.2  -->  images published to GHCR (tagged 1.4.2 and the commit SHA)
                       |
        +--------------+----------------------------+
        | CI can reach the server (push)            | CI cannot reach it (pull / offline)
        v                                           v
  Actions -> Deploy -> clients: "acme"        bundle (make-release-bundle.sh)
  SSH + deploy.sh on that server              or the client pulls the tag itself
                                              install-release.sh -> deploy.sh
```

## Where a client's differences live

| What | Where |
|---|---|
| Which servers CI deploys to, and when | `infra/clients.yml` |
| Host, user, path, public URL, OpenBao address | variables of the client's **GitHub environment** |
| SSH key and pinned host key | secrets of that environment |
| Who may approve a deploy there | required reviewers on that environment |
| Database, passwords, tokens, SMTP, memory limits, OCR on/off | that server's `infra/production/.env` (and OpenBao) |

A client can also use its own database (AWS RDS, Azure Database, an on-prem
server), storage (S3, Azure Blob), Redis, RabbitMQ or mail relay instead of the
bundled containers: see [external-services.md](external-services.md).

The images are identical for every client. Per-client tuning (for example
`OCR_WORKER_PROCESSES`, `OCR_WORKER_MEM_LIMIT`, `OCR_ENABLED`) goes in `.env`,
never in code.

## Adding a client CI can reach

1. **Server:** install Docker, create the install directory (default `/opt/dms`), and
   create `infra/production/.env` from `.env.example` (`chmod 600`). Decide first which
   services are bundled and which are the client's own (`COMPOSE_PROFILES`, see
   external-services.md). Use database and RabbitMQ passwords without `/ @ : ? #`. Set `INTERNAL_SERVICE_TOKEN` and
   `OCR_JOB_TOKEN_SECRET`. See `docs/deployment.md` for nginx, Keycloak and first start.
2. **Runner:** a self-hosted GitHub runner on a machine that can SSH to that server
   (usually on the same network). Note its label.
3. **GitHub environment** named e.g. `client-acme` (Settings -> Environments). Variables:
   `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_PATH`, `PUBLIC_URL`, and `BAO_ADDR` if OpenBao
   is used. Secrets: `DEPLOY_SSH_PRIVATE_KEY`, `DEPLOY_SSH_KNOWN_HOSTS`. Add required
   reviewers.
4. **`infra/clients.yml`:** add the client with `mode: push`, `auto: false`.
5. **First deploy:** Actions -> Deploy -> Run workflow, `clients: acme`.

## Rolling a release out

- Push to `main` deploys to the `auto: true` clients only. Keep that for your own
  staging or one pilot client.
- When the pilot is healthy, run Deploy manually with `clients: "acme,globex"` or
  `all` (every push-mode client). At most three servers deploy at once, and one
  failing client never stops the others.
- Rollback for one client: run Deploy with `image_tag` (an earlier full commit SHA)
  and that client's name, or on the server `./deploy.sh --rollback`.
- A client that must stay on an older version is simply not selected.

## Servers CI cannot reach

Mark the client `mode: pull` in `infra/clients.yml` (CI will refuse to target it, so it
cannot be deployed to by mistake). Two ways to get it a release:

**Offline bundle** (air-gapped, or no registry access):

```bash
# on a machine that can pull from the registry
docker login ghcr.io
bash infra/production/make-release-bundle.sh 1.4.2 ./out
# -> out/dms-release-1.4.2.tar.gz and .sha256 (about 6 GB, mostly the OCR image)

# on the client's server, after verifying the checksum
tar -xzf dms-release-1.4.2.tar.gz && cd dms-release-1.4.2
bash infra/production/install-release.sh        # DEPLOY_PATH defaults to /opt/dms
```

`install-release.sh` loads the images, updates the deployment files and runs the same
`deploy.sh` as every other deploy, so health checks, the smoke test and automatic
rollback apply. The bundle contains no secrets.

**Registry pull** (the server has internet or a mirror): give the client's IT a
read-only registry token and have them run `./deploy.sh 1.4.2` in
`infra/production`.

## Versions

Release by pushing a tag `vMAJOR.MINOR.PATCH`. That publishes the three images tagged
`MAJOR.MINOR.PATCH` and with the commit SHA, after the tests and the vulnerability scan,
and deploys nowhere. The SHA stays the exact identity of a release (`deploy.sh` records
it); the version is the name you give clients.

## Limits worth knowing

- Each client's OCR worker needs its own memory budget (about 3.4 GiB per OCR process
  plus about 1.5 GiB). Size the server from `OCR_WORKER_PROCESSES`.
- Database changes ship with the application (Flyway runs on start), so a client
  upgrading across several versions at once runs every migration in order. Keep clients
  within a few releases of each other.
- Not covered here: client onboarding of Keycloak realms and licences, backups per
  client, and monitoring the fleet from one place.
