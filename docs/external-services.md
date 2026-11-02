# Using the client's own database, storage and services

By default one install runs everything in containers: PostgreSQL, Redis, RabbitMQ,
SeaweedFS (S3), a mail sink, ClamAV and Gotenberg. A client can replace any of them
with its own - AWS RDS, Azure Database for PostgreSQL, a managed Redis or RabbitMQ,
Amazon S3 or Azure Blob, a real SMTP relay, an existing ClamAV - without any change
to the code or the images. It is all in that client's `infra/production/.env`
(and OpenBao for the secrets). See also `multi-client-deployment.md`.

Always run here, whatever you choose: dms-core, the frontend, the OCR worker and
Keycloak (Keycloak's database is configurable, below).

## How it works

`COMPOSE_PROFILES` in `.env` lists the bundled services to run:

```
COMPOSE_PROFILES=postgres,redis,rabbitmq,seaweedfs,clamav,gotenberg
```

Remove a name and that container (and its metrics exporter) is not started; fill in
the settings for its replacement. Nothing set = the bundled container, exactly as
before, and a server whose `.env` has no `COMPOSE_PROFILES` at all keeps running
everything. `deploy.sh` stops **before changing anything** if a service is switched
off but its replacement is not configured, or if a secret the running services need
is missing.

| Profile | Runs | Replace it with |
|---|---|---|
| `postgres` | PostgreSQL + its exporter | `DB_*` |
| `redis` | Redis + its exporter | `REDIS_*` |
| `rabbitmq` | RabbitMQ | `RABBITMQ_*` |
| `seaweedfs` | the bundled S3 | `STORAGE_PROVIDER=S3` or `AZURE_BLOB` + `S3_*` / `AZURE_STORAGE_*` |
| `clamav` | malware scanner | `CLAMAV_HOST`, `CLAMAV_PORT` |
| `gotenberg` | Office-to-PDF previews | `PREVIEW_CONVERTER_URL` |

## Database (RDS, Azure, or the client's own server)

1. The client's DBA creates, on their server, what `postgres/init-multiple-dbs.sh`
   creates in the bundled one:
   - a database `dms_core_db` and a login `dms_core_user` that owns it
     (the application runs its own schema migrations, so it needs DDL rights there);
   - a database `keycloak_db` and a login for Keycloak that owns it.
   Managed services do not hand out a superuser, so use a normal login for Keycloak
   too, not `postgres`.
2. In `.env`: remove `postgres` from `COMPOSE_PROFILES` and set

```
DB_HOST=mydb.abc123.eu-west-1.rds.amazonaws.com
DB_PORT=5432
DB_SSLMODE=require              # disable | prefer | require | verify-full
KEYCLOAK_DB_USER=keycloak
KEYCLOAK_DB_PASSWORD=...        # a secret
DMS_CORE_DB_PASSWORD=...        # a secret; DMS_CORE_DB_USER defaults to dms_core_user
```

3. Passwords must not contain `/ @ : ? #` (the OCR worker puts them in a URL).
4. The server running the stack must be able to reach the database (security group,
   firewall, VNet or private endpoint).
5. `POSTGRES_PASSWORD` and `POSTGRES_METRICS_PASSWORD` are no longer needed.

Database metrics: the bundled exporter is not started, so the "database down" alert
stays quiet; use the provider's own monitoring.

## Document storage (S3, Azure Blob)

Documents are stored on the server's `documents_data` volume unless told otherwise.

**Amazon S3 or any S3-compatible service** (the bucket must already exist):

```
STORAGE_PROVIDER=S3
S3_ENDPOINT=                 # empty for AWS itself; a URL for MinIO, Ceph, Wasabi, ...
S3_REGION=eu-west-1
S3_BUCKET=acme-dms-documents
S3_ACCESS_KEY=...            # secrets
S3_SECRET_KEY=...
S3_PATH_STYLE_ACCESS=false   # true for most self-hosted S3
S3_SSE=AES256                # optional server-side encryption
```

`S3_ENDPOINT` has to be present and **empty** for AWS: left out, it points at the
bundled SeaweedFS. **Azure Blob Storage:**

```
STORAGE_PROVIDER=AZURE_BLOB
AZURE_STORAGE_ENDPOINT=https://acme.blob.core.windows.net
AZURE_STORAGE_ACCOUNT_NAME=acme
AZURE_STORAGE_CONTAINER=dms-documents
AZURE_STORAGE_ACCOUNT_KEY=...    # a secret
```

Scanning staging and quarantine can use their own buckets or containers
(`SCAN_STAGING_BUCKET`, `SCAN_QUARANTINE_BUCKET`); see `storage-architecture.md`. A
tenant can also be given its own storage location inside the app, and that overrides
this default. Remove `seaweedfs` from `COMPOSE_PROFILES` once nothing uses the
bundled S3. Moving existing documents between stores is a data migration, not a
setting - plan it before switching a live client.

## Redis, RabbitMQ, mail, scanning, previews

```
# Redis (REDIS_PASSWORD may be empty if the service has none)
REDIS_HOST=...  REDIS_PORT=6380  SPRING_DATA_REDIS_SSL_ENABLED=true

# RabbitMQ (RABBITMQ_DEFAULT_PASS is then the password of RABBITMQ_USERNAME there)
RABBITMQ_HOST=...  RABBITMQ_PORT=5671  RABBITMQ_USERNAME=dms
RABBITMQ_SCHEME=amqps  SPRING_RABBITMQ_SSL_ENABLED=true

# Mail: always an external relay (Gmail by default) - set SMTP_USERNAME/SMTP_PASSWORD
SMTP_HOST=smtp.example.com  SMTP_PORT=587  SMTP_AUTH=true  SMTP_STARTTLS=true
SMTP_USERNAME=...  SMTP_PASSWORD=...  SMTP_FROM=no-reply@client.example

CLAMAV_HOST=...  CLAMAV_PORT=3310
PREVIEW_CONVERTER_URL=http://converter.example.com:3000
```

## Secrets

All of them stay in `secret-keys.txt`, which is the full set OpenBao stores. The ones
only some setups have are listed in `secret-profiles.txt`:

- the secrets of a bundled service (`POSTGRES_PASSWORD`, `SEAWEEDFS_S3_*`, ...) are
  required only while that service's profile is on;
- `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `AZURE_STORAGE_ACCOUNT_KEY`, `KEYCLOAK_DB_PASSWORD`
  are never required, and passed through when present.

The OpenBao renderer and importer skip those when OpenBao does not hold them.

## Checking a configuration

```
cd infra/production
COMPOSE_PROFILES= DB_HOST=... docker compose --env-file .env config --services
```

lists what would run. CI checks every push that an all-external configuration is
valid and starts none of the bundled containers.

## Known limits

- **Keycloak** is not replaceable here, only its database. An external identity
  provider is a larger change (realm bootstrap, roles, token issuer).
- **Monitoring of the client's own services:** with `rabbitmq`, `postgres` or `redis`
  switched off, their exporters and scrape targets are not there, so their "down"
  alerts stay quiet instead of firing. (`deploy.sh` rewrites
  `infra/monitoring/prometheus/targets/rabbitmq.yml` on every deploy to match.) Use the
  provider's own monitoring for those services.
- **Memory limits and startup order:** a service's `depends_on` on a bundled
  container no longer blocks start-up if that container is unhealthy; dms-core and
  the others reconnect and `deploy.sh` still waits for dms-core and the frontend to be
  healthy.
- **Not tested against real RDS, Azure or managed Redis/RabbitMQ** - verify the
  first client of each kind on a test install: connection, TLS mode, and that the
  application's migrations succeed.
