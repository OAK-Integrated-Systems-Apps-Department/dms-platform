# SonarQube

Static analysis for Oak DMS: bugs, vulnerabilities, code smells, duplication
and test coverage, for both the Java backend and the Next.js frontend.

The server is a **development tool**, not part of the product. Nothing in the
DMS talks to it, it ships with nothing, and the application runs perfectly
well without it ever being started.

## Before you start it

SonarQube runs an embedded Elasticsearch and wants roughly **2–3 GB of RAM**
to itself, on top of the ~8 GB the application stack already asks for. On a
16 GB machine, do not expect to run both comfortably at once — stop what you
are not using:

```bash
docker compose -f infra/local/docker-compose.yml stop     # free the app stack
```

This is why SonarQube is a separate compose project instead of another
service in `infra/local/docker-compose.yml`: day-to-day `docker compose up`
must not drag it in.

## Start the server

```bash
docker compose -f infra/sonarqube/docker-compose.yml up -d
docker logs -f sonarqube          # wait for "SonarQube is operational"
```

Then open <http://localhost:9000>. First login is `admin` / `admin`, and it
forces a password change immediately.

It binds to `127.0.0.1` only. The server holds a full copy of the source
under analysis, so it has no business being reachable from the network.

### If the container restart-loops

Check the logs for:

```
max virtual memory areas vm.max_map_count [65530] is too low
```

Elasticsearch mmaps its indices and refuses to start under the default limit.
This applies to the Linux VM Docker runs in, not to Windows or macOS itself.

- **Docker Desktop (Windows/WSL2 or macOS):**
  ```bash
  docker run --rm --privileged --pid=host alpine nsenter -t 1 -m -u -n -i sysctl -w vm.max_map_count=524288
  ```
  That lasts until the Docker VM restarts. To make it permanent on Windows,
  add `vm.max_map_count=524288` under `[wsl2]` … `kernelCommandLine` in
  `%USERPROFILE%\.wslconfig`, then `wsl --shutdown`.

- **Linux:** `sudo sysctl -w vm.max_map_count=524288`, and add it to
  `/etc/sysctl.conf` to survive a reboot.

## Get a token

Analysis authenticates with a token, never your password.

1. <http://localhost:9000> → your avatar → **My Account** → **Security**
2. Generate a token of type **Global Analysis Token**
3. Export it — it is shown once:

```bash
export SONAR_TOKEN=squ_xxxxxxxxxxxxxxxxxxxx
```

Do not put this in a committed file. Nothing in the repo reads a token from
disk; every entry point takes it from the environment.

## Run an analysis

```bash
bash infra/sonarqube/scan.sh              # backend and frontend
bash infra/sonarqube/scan.sh backend
bash infra/sonarqube/scan.sh frontend
```

Results: <http://localhost:9000/projects>

The frontend scan needs nothing but the SonarQube server. The backend scan
needs a test database — see below.

## Backend coverage locally

By default `scan.sh backend` runs `mvn verify` so JaCoCo can measure
coverage. That runs the real test suite, and this project uses **no
Testcontainers** (`docs/workflow-phase1-plan.md`): the tests connect to a
live Postgres, defaulting to `localhost:5433/dms_dev` as user `postgres`
with password `password`.

**The local stack does not provide that.** `infra/local` publishes Postgres
on 5433, but its databases are `dms_core_db` and `keycloak_db` — there is no
`dms_dev` — and the `postgres` password comes from `infra/local/.env`. Run
the suite without setting `DB_*` and it fails with 31 errors, all
`password authentication failed for user "postgres"`.

So pick one:

**Analyse without coverage** — finds every bug, vulnerability, smell and
duplicate, because those come from reading the code. Only the coverage
number is lost (Sonar shows 0%, which is missing data rather than a real
zero):

```bash
SKIP_TESTS=1 bash infra/sonarqube/scan.sh backend
```

**Or prepare a test database** and point the build at it. Never point it at
`dms_core_db` — the suite runs migrations and would rewrite your dev data:

```bash
docker exec oakdms-postgres createdb -U postgres dms_test
export DB_URL=jdbc:postgresql://localhost:5433/dms_test
export DB_USERNAME=postgres
export DB_PASSWORD="$(grep '^POSTGRES_PASSWORD=' infra/local/.env | cut -d= -f2-)"
bash infra/sonarqube/scan.sh backend
```

The script checks for `DB_URL`/`DB_PASSWORD` up front and tells you this,
rather than failing ten minutes into a build.

CI has no such problem: the `sonarqube` job brings up its own Postgres and
sets `DB_*`, exactly as the existing `backend` job does.

## Two projects, not one

| Project key | What it covers | Scanner |
|---|---|---|
| `oak-dms-backend` | `backend/` (Java) | Maven (`sonar:sonar`) |
| `oak-dms-frontend` | `frontend/` (TypeScript) | CLI (`sonar-scanner`) |

They cannot share a key. A project key is written by one scanner run, so
pointing both at the same key would make each analysis erase the other's
findings.

## Coverage

Backend coverage comes from JaCoCo, declared in `backend/dms-core/pom.xml`.
`mvn verify` writes `dms-core/target/site/jacoco/jacoco.xml`, which is the
path the scanner reads by default.

> Note: `backend/pom.xml` aggregates the modules but is **not** their parent —
> `dms-core` declares `spring-boot-starter-parent` directly. Build plugins put
> in the aggregator are not inherited, so each module needs its own JaCoCo
> block. There is a comment to this effect in both files.

The frontend reports **no** coverage, because it has no test runner yet —
`package.json` has `lint`, `typecheck` and `build`, but no `test`. Sonar will
show 0%, which is accurate rather than missing. When a runner is added, set
`sonar.javascript.lcov.reportPaths` in `frontend/sonar-project.properties`.

## In CI

`.github/workflows/ci.yml` has a `sonarqube` job that runs both analyses. It
is gated on the repository variable `SONAR_HOST_URL` and **skips entirely**
when that is unset — a server on your localhost is not reachable from a
GitHub runner, and a job that always failed would be worse than no job.

To enable it, the server must be reachable from GitHub's runners, then set:

| Kind | Name | Value |
|---|---|---|
| Repository **variable** | `SONAR_HOST_URL` | e.g. `https://sonar.example.com` |
| Repository **secret** | `SONAR_TOKEN` | a Global Analysis Token |

The URL is a variable rather than a secret deliberately: masking it in the
logs makes a connection failure almost impossible to diagnose, and the
address of a server is not the thing worth protecting — the token is.

## Stop it

```bash
docker compose -f infra/sonarqube/docker-compose.yml down       # keeps history
docker compose -f infra/sonarqube/docker-compose.yml down -v    # wipes it
```

Its database is its own volume, entirely separate from the DMS Postgres —
analysis history can never share a backup, a restore or a `down -v` with real
document metadata.
