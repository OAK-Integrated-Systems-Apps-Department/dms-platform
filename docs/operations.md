# Operating many client installations

What to do beyond deploying: backups, getting back from a bad upgrade, keeping clients
within reach of each other, knowing when one is down, and where secrets live. Setting a
client up is in `multi-client-deployment.md`; choosing its database and storage is in
`external-services.md`.

## Backups

`infra/production/backup.sh` (on each client's Docker server) dumps the two databases,
`dms_core_db` and `keycloak_db`, checks that each dump can be read, and writes them with a
`MANIFEST` (release, time, checksums) to `infra/production/backups/<time>-<label>/`
(mode 700). It keeps the newest 7 (`BACKUP_KEEP`). It works with the bundled PostgreSQL
and with the client's own (RDS, Azure, ...), using the same `DB_*` settings as the stack.

- **Before every upgrade** `deploy.sh` runs it automatically. If the backup fails, the
  deploy stops before changing anything (`SKIP_BACKUP=1` overrides, deliberately). A
  first install has nothing to back up.
- **Routine backups**: add it to cron, e.g. nightly at 02:00:
  `0 2 * * * cd /opt/dms/infra/production && ./backup.sh --label nightly >> backups/cron.log 2>&1`
- **Document files**: `./backup.sh --with-files` also archives the `documents_data` volume
  (and the bundled SeaweedFS volume). Documents kept in the client's own S3 or Azure Blob
  are not copied: use the provider's versioning or replication.
- **Copy backups off the server.** A backup on the same disk does not survive the disk.
- **The server's `.env` is not in the backup** (it holds the secrets). Keep a copy of it
  in the client's secret store; without it a restored database cannot be used.

## Getting back from a bad upgrade

A failed deploy puts the previous *images* back by itself. It does **not** undo a database
migration that already ran. When that matters, `deploy.sh` prints the pre-upgrade backup
and the command:

```
./restore.sh --list                        # available backups, newest first
./restore.sh backups/<time>-pre-<tag>      # asks you to type 'restore'
./deploy.sh <tag from the MANIFEST>        # start the release the backup belongs to
```

`restore.sh` checks the dump checksums first (a damaged backup changes nothing), stops the
application containers, replaces both databases, and tells you which release to start.
Files are restored separately (the command is in the script header).

**Practise a restore** on a spare server before relying on it. The script's round trip has
been tested only against a throwaway PostgreSQL container, not against a client's real
data or an RDS/Azure database.

## Upgrade rule

Because an upgrade can leave the database migrated, **every release must work on the
schema of the previous one** (add columns and tables first; remove or rename only in a
later release, after nothing reads them). That is what makes an image rollback safe
without restoring data, and it is a review rule: nothing in CI enforces it yet.

Keep clients within a few releases of each other: a client that skips many releases runs
every migration in between at once. Use a staging client for the first look at each
release: add a client with `auto: true` in `infra/clients.yml` and every merge to `main`
deploys there first; the rest are deployed by hand once it is healthy.

## Is every client up?

`Fleet status` (Actions) runs daily and on demand. For each client with a `url` in
`infra/clients.yml` it checks, from that client's runner, the login page, the API through
nginx and Keycloak's issuer; a failing client fails the run, which notifies the repository
watchers. It does not show versions or container health - on the server, `./deploy.sh
--status` does.

## Server size

`deploy.sh` warns if a server has under 14 GB of memory or 30 GB free disk (`MIN_MEM_GB`,
`MIN_DISK_GB`). These are estimates: the bundled stack idles near 3.5 GB and the OCR worker
may use up to 8.5 GiB. A client on its own database and storage needs less. Measure with
`docker stats` on the first real deploy.

## Where the secrets are

- A client's `.env` lives on its Docker server (mode 600). That is the working copy.
- `new-client.sh` also leaves a copy of the `.env` and the deploy private key in
  `~/.dms-clients/<name>/` on the machine that ran it. Run it with
  `--provision --github --cleanup` to delete those local copies once the server and GitHub
  hold theirs; otherwise protect that folder like the secrets it is.
- The deploy key and host key are GitHub environment secrets, one set per client.
- Rotating a secret means editing the server's `.env` (or OpenBao) and redeploying; there
  is no central secret store for the fleet yet.

## Still not covered

Central monitoring of all clients' metrics and alerts in one place (each install still has
its own Grafana), automated tests of a release against a staging copy of a client's data,
and automatic rotation of secrets.
