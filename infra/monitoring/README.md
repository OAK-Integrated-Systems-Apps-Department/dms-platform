# Monitoring (Prometheus + Grafana)

Live health and resource usage of the OAK DMS stack. One question, answered
at the top of the dashboard: **is the DMS healthy, and if not, what is
causing it?**

Everything here is config, shared by `infra/local` and `infra/production` —
the two compose files mount this directory and differ only in ports,
retention and credentials. Nothing in the DMS depends on any of it: stop the
whole monitoring stack and uploads, workflows and sign-in carry on.

## Layout

| Path | What it is |
|---|---|
| `prometheus/prometheus.yml` | Scrape targets. Service names resolve on the compose network. |
| `prometheus/alerts.yml` | Alert rules, evaluated by Prometheus and listed in Grafana. |
| `grafana/provisioning/datasources/` | The Prometheus datasource, created on startup. |
| `grafana/provisioning/dashboards/` | Tells Grafana to load the JSON below. |
| `grafana/dashboards/oak-dms-overview.json` | The dashboard. Edit this file, not the browser. |

## Where the numbers come from

| Area | Source | Notes |
|---|---|---|
| Server CPU, RAM, disk, I/O, network, uptime | `node-exporter` | Under Docker Desktop this is the Linux VM, not Windows. |
| Containers: CPU, memory, network, restarts | `cadvisor` | Reads the Docker socket read-only. Never published. |
| API rate, latency, errors; JVM; connection pool | `dms-core` `/actuator/prometheus` | Actuator + Micrometer. |
| Documents, scans, OCR backlog, workflow activity | `dms-core`, `dms_*` metrics | `DmsMetrics` / `DmsMetricsGauges`. |
| PostgreSQL | `postgres-exporter` | Connections, transactions, database size. |
| Redis | `redis-exporter` | Memory, clients, hit rate, command rate. |
| RabbitMQ | RabbitMQ itself, port 15692 | `rabbitmq_prometheus` ships enabled in the official image — no exporter needed. |

## Running it

Local — the stack starts with everything else:

```bash
cd infra/local
docker compose up -d
```

- Dashboard, embedded: **DMS → Governance → System Monitoring** (platform
  admins only).
- Grafana directly: <http://localhost:3001/grafana/> (`admin` / `admin` by
  default; override with `GRAFANA_ADMIN_*` in `infra/local/.env`).
- Prometheus, for raw queries and scrape-target health:
  <http://localhost:9090> → Status → Targets.

Production: same services, but Grafana is on `127.0.0.1:3001` and reached
through the host nginx at `/grafana/`; Prometheus has no host port at all
(SSH-tunnel 9090 if you need the query UI). `deploy.sh` does **not**
health-gate these — a monitoring outage must never roll back a good release.

Before the first production deploy, create the exporter's read-only login:

```sql
CREATE ROLE dms_metrics LOGIN PASSWORD '<POSTGRES_METRICS_PASSWORD>';
GRANT pg_monitor TO dms_metrics;
```

and set `GRAFANA_ADMIN_PASSWORD` and `POSTGRES_METRICS_PASSWORD` in
`infra/production/.env` (both are listed in `secret-keys.txt`, so OpenBao
renders them if it is in use).

## One stack per client, and a central view later

Every client runs their own DMS stack, so every client has their own
Prometheus and Grafana. Their administrator sees their own server and nothing
else — which is why System Monitoring is open to `TENANT_ADMIN` as well as
`PLATFORM_ADMIN` (see `nav-config.ts`, `adminOnly`).

A central dashboard covering **all** clients is not built. When it is, the
route is `remote_write` in `prometheus.yml`, pointing each client's Prometheus
at one you run:

```yaml
remote_write:
  - url: https://metrics.example.com/api/v1/write
    basic_auth:
      username: "${MONITORING_REMOTE_USER}"
      password: "${MONITORING_REMOTE_PASSWORD}"
```

`MONITORING_CLIENT` exists now so that day is cheap — it is already in
`external_labels`, so each client's data arrives under its own name with no
config change at the client end.

One thing that surprises people: **external labels are not stored locally.**
Prometheus attaches them only when data leaves — `remote_write`, federation,
or alerts to an Alertmanager. So `client` and `environment` will not appear
in this stack's own queries or Grafana panels, and that is correct rather
than broken. Confirm what is loaded with:

```bash
curl -s localhost:9090/api/v1/status/config | grep -A3 external_labels
```

Set it per client at install time and leave it alone once central monitoring
is live: changing it then splits that client into two unrelated timelines.
`infra/production/.env.example` ships it as a bare `CHANGE_ME` so `deploy.sh`
prompts for it instead of letting a stack go out anonymous.

## Changing things

- **A panel** → edit `grafana/dashboards/oak-dms-overview.json`. Grafana
  reloads it within 30s. UI edits are deliberately not saved
  (`allowUiUpdates: false`): these dashboards are code, and a change made in
  the browser would be lost on the next deploy. "Save as" still works for
  ad-hoc exploration.
- **An alert threshold** → edit `prometheus/alerts.yml`, then
  `curl -X POST http://localhost:9090/-/reload`.
- **A new DMS metric** → add it in `backend/dms-core/.../observability/`,
  then graph it. Keep tag values a closed set: never tag by user, tenant,
  file name or threat name, or Prometheus grows a series per distinct value.

## Two things to know

**Alerts are delivered by email, if you say where.** The rules in `alerts.yml`
are evaluated by Prometheus and sent to Alertmanager, which groups them and
emails them. It reuses the SMTP relay the rest of the stack already sends mail
through (`SMTP_*` in `infra/production/.env`); the only new setting is who
receives them:

- `ALERT_EMAIL_TO` - one address, or several separated by commas. **Empty by
  default** (production and local), so nothing is sent until you set it.
  Locally it goes out through the Gmail account in `infra/local/.env`
  (`SMTP_USERNAME` / `SMTP_PASSWORD`).
- `ALERT_LINK` - optional; put the DMS address in the email as a link to the
  System Monitoring screen.

Critical alerts repeat hourly while they last, warnings every four hours, and
a "resolved" email follows when the problem clears. A warning is suppressed
while a critical alert is firing for the same component. Every message names
the `client` and `environment` labels, so one inbox can watch several
installations.

Whether or not email is set up, everything firing is also on the Alerts card
on the System Monitoring screen. Alertmanager is on the Services board too, so
"alerts are firing but not being delivered" is itself visible.

**Some rules can be blind.** Every rule declares the metric it reads under
`annotations.requires`. The screen checks that metric has any series at all:
a rule whose input does not exist cannot fire, and Prometheus still calls it
healthy. On Docker Desktop the container rules are the example - cAdvisor
cannot read containers there - and the Alerts card marks them "no data"
instead of letting them read as "not firing". Add `requires` to any new rule.

**Grafana is not exposed.** The System Monitoring screen embeds its panels
through the DMS frontend's own `/monitoring/panels/` route, which asks the
backend whether the caller is a platform administrator before forwarding
anything, and hands Grafana the username through its auth proxy. Anonymous
access is off, nginx has no `/grafana/` location, and in production Grafana
publishes no port. Do not re-enable anonymous access to make a panel load:
that is what the route exists to avoid. Container logs (Loki, via Promtail)
are read the same way, through the backend, and are platform-admin only.

## Known gaps

- **Container panels are empty on Docker Desktop (Windows/macOS).** cAdvisor
  identifies containers by reading Docker's image layer database, and Docker
  Desktop uses the `overlayfs` storage driver whose layout cAdvisor does not
  understand (`failed to identify the read-write layer ID` in its log). The
  series it emits then carry only a cgroup `id`, not the `name` the dashboard
  groups by, so the Containers row reads "No data". Verified against
  cAdvisor 0.52.1 with and without the `/var/lib/docker` mount — it is a
  cAdvisor/Docker Desktop limitation, not a configuration mistake here.
  On the production Ubuntu server (`overlay2`) the same config names
  containers correctly. Locally, use `docker stats` instead. Everything
  else on the dashboard works locally. The two alert rules that read these
  series (`ContainerRestarting`, `ContainerMemoryNearLimit`) cannot fire
  here either; the Alerts card marks them "no data" rather than "not firing".

- **OCR metrics have nothing behind them yet.** `dms_ocr_documents` reads
  the real `document_versions.ocr_status` column, but no OCR worker exists in
  this repo (`backend/workers/ocr-worker/` is an empty placeholder), so every
  version sits at its default status. The panel and the `OcrFailuresHigh`
  alert are wired to the real metric and start meaning something the day that
  worker lands — no change needed here.
- **Logs are collected, but not redacted.** Promtail ships every container's
  logs to Loki (14 days), and the System Monitoring screen shows them per
  service to platform administrators. A log line is free text written by
  whoever wrote the code, so it can carry anything the service printed -
  usernames, ids, document titles in an error message. Today's logs hold none
  of that, but the sample is a dev stack with seven documents. Masking emails
  and tokens in the Promtail pipeline, and recording each log view in the
  audit log, are the two obvious hardening steps and neither is done.
- **Single-node only.** Targets are hardcoded service names. A second server
  needs service discovery rather than a longer `static_configs` list.
