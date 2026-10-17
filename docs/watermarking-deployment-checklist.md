# Watermarking - deployment checklist

Work through this before switching `dms.watermark.enabled` on in any
environment. The flag is **off by default** and the tables are additive, so
nothing here is urgent - but most of it is much cheaper to do before the first
user asks for a copy than after.

---

## 1. Fonts in the real image

A watermark carries a person's name. If the runtime image has no font for their
script, the JDK draws hollow boxes and the job still reports success.

- [ ] The CI job **Backend (dms-core) - rendering tests in the runtime font
      stack** is green. It does two separate things, and both matter: it runs
      the rendering tests inside the real base image with the Dockerfile's font
      packages, **and** it builds the shipped image and fails if any of
      `en el ru ar he zh-cn ja ko hi th bn ta` has zero fonts.
- [ ] If you change `WATERMARK_FONT_PACKAGES` in `backend/Dockerfile`, re-read
      that job's output rather than assuming - the package names differ between
      Alpine releases.
- [ ] `-Djava.awt.headless=true` is set (it is, in the Dockerfile). Without it
      a container with no X11 can fail font initialisation at startup, and
      `WatermarkAvailability` will log an ERROR and answer 503 to every request.

## 2. nginx must be the first proxy

Per-IP rate limiting reads the client address from `X-Forwarded-For`.

- [ ] nginx (or whatever terminates TLS) is the **first** proxy in front of
      dms-core and sets `X-Forwarded-For` itself rather than appending to a
      header the client sent.
- [ ] If anything else sits in front of nginx (a cloud load balancer, a CDN),
      confirm which hop is trustworthy and that Spring's forwarded-headers
      handling matches. **Otherwise a caller can spoof the header and the
      per-IP limit becomes per-whatever-they-typed** - the per-user limit and
      `max-active-jobs-per-user` still hold, but the IP limit stops meaning
      anything.

## 3. Redis

- [ ] Redis is reachable. The watermark rate limiter **fails open**: if Redis is
      down, requests are allowed through and `max-active-jobs-per-user` (which
      is in the database) becomes the only limit. That is deliberate - a Redis
      outage should not take the feature down - but it does mean a Redis outage
      silently removes the rate limit, so alert on Redis separately.
- [ ] The preview endpoint creates no job and so has no database backstop; it
      falls back to an in-memory limiter per instance
      (`rate-limit.preview-fallback-per-window`).

## 4. Database

- [ ] **V68 applied against a copy of production first.** It creates
      `watermark_templates` and `watermark_jobs` and seeds four system
      templates with fixed UUIDs. It is additive - no existing table is altered
      - but run it on a copy and check the timing on your real row counts.
- [ ] Confirm the four system templates exist afterwards
      (`select count(*) from watermark_templates where tenant_id is null` = 4).

## 5. Storage and scanning cost

- [ ] Every watermarked copy is written to the tenant's **staging** location
      under `{tenantId}/tmp/watermarks/{jobId}/`, encrypted.
- [ ] **If your object storage has a cloud malware scanner attached (GuardDuty
      Malware Protection, Defender for Storage), it will scan every staged
      output.** That is a per-object charge on files that are copies of
      documents already scanned clean on the way in. Decide deliberately
      whether to exclude the `tmp/watermarks/` prefix, and remember that
      excluding it means those objects are not scanned.
- [ ] Budget the storage: peak usage is roughly
      `(copies produced per day) x (average document size) x (output-ttl in days)`.

## 6. Alerts worth having

| Alert | Condition | Why |
|---|---|---|
| **Abandoned format threads** | `watermark_format_threads_abandoned > 0` | POI and PDFBox do not check the interrupt flag, so a timed-out job's thread is never reclaimed. **At `executor.format-max-size` (default 4) the feature has stopped working and only a restart fixes it.** Page on that; warn on anything above zero. |
| Failure ratio | `rate(watermark_jobs_failed_total[15m]) / rate(watermark_jobs_started_total[15m]) > 0.2` | Split by `reason` - a spike in `WATERMARK_IDENTITY_UNAVAILABLE` is a Keycloak problem, not a watermark problem. |
| Queue depth / rejections | 503s carrying `WATERMARK_QUEUE_FULL` | Means `executor.job-queue-capacity` is too small for the load, or jobs are running too long. |
| Job duration | p95 of `watermark_job_duration_seconds` by `format` | Expect sub-second for docx/pptx and ~17 s for a 10 MB xlsx (see docs/watermarking.md §7). |

## 7. Sweeper and TTL

- [ ] `output-ttl` (default 24 h) is how long a copy stays downloadable. It is
      also how long a leaked link stays live, so shorten it if that matters more
      than convenience.
- [ ] `sweep-interval` (default 15 min). The sweeper shares the one
      `@Scheduled` thread with `OutboxRelay` (every 2 s), `RecycleBinService`
      and `KeyRotationScheduler`, so it takes a capped batch of 200 per run.
      **`spring.task.scheduling.pool.size` is deliberately not changed by this
      feature** - if you raise it, do so for the whole application knowingly.
- [ ] On multiple instances the sweeper takes a Postgres advisory lock, so only
      one sweeps at a time. Nothing to configure.

## 8. Turning it on

Per environment, in this order:

1. Confirm the rendering CI job is green for the image you are deploying.
2. Deploy with the flag still **off** and confirm the application starts
   normally (the endpoints answer 404; no watermark threads exist).
3. Set `WATERMARK_ENABLED=true` in that environment only.
4. Check the startup log for `WatermarkAvailability`. An ERROR there means the
   JVM cannot render text and every request will be 503 - go back to §1.
5. Produce one copy of each format by hand and **open them** - in desktop
   Office, not the in-app preview, which may not show the mark.

## 9. Rollback

- Set `WATERMARK_ENABLED=false` and redeploy. The endpoints stop being mapped;
  no code path in any other feature touches watermarking.
- Jobs already `pending` stay pending and are picked up if the flag is ever
  turned back on. Copies already staged keep their `expires_at`, but the
  sweeper is also conditional on the flag, **so they will not be deleted while
  it is off** - either turn it back on briefly to let the sweep run, or delete
  the `tmp/watermarks/` prefix by hand.
- The V68 tables are additive and can be left in place. Nothing else reads
  them.
- `document_types.requires_watermark` is not enforced by anything, so rolling
  back cannot change document behaviour.
