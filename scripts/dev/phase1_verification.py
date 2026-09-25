#!/usr/bin/env python
"""Phase 1 (Stability) exit-criteria harness.

docs/ocr/ocr-production-prompt.md section 6 phase 1 exit criteria:

    50 mixed uploads including 10 concurrent produce zero crashes, zero
    stuck `processing` versions, and a clear status for every file.

Unlike scripts/dev/repro_batch.py -- which drove the *old* synchronous
`POST /ocr/process/{id}` dev-trigger endpoint to reproduce the section-1
failures -- this drives the **queue path** that replaced it: it publishes
`document.version.ocr.requested` onto `oak.dms.events` exactly as
dms-core's outbox relay does, then waits on the database for each
version's terminal state. Nothing here calls the worker over HTTP, so
what it measures is the real production trigger path.

Run it from inside the compose network (it resolves `postgres`,
`rabbitmq` and `minio` by service name):

    docker run --rm --network local_default \\
        -v "<repo>:/repo" -v "<fixtures>:/fixtures" \\
        ocr-worker-test:latest \\
        python /repo/scripts/dev/phase1_verification.py \\
            --fixtures-dir /fixtures --report-json /fixtures/report.json

Every run uses a fresh `--run-id` (default: a random uuid4 prefix) so its
50 versions are distinct rows -- re-running never collides with, or gets
deduplicated against, a previous run's jobs.
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import pika
import psycopg
from minio import Minio

EXCHANGE_NAME = "oak.dms.events"
ROUTING_KEY = "document.version.ocr.requested"
DOCUMENT_TYPE_NAME = "ocr-phase1-doc"

# Every fixture scripts/dev/build_fixtures.py emits, plus the three real
# committed fixtures it builds them from. Deliberately includes the ones
# expected to FAIL (encrypted/corrupt) -- "a clear status for every file"
# means a clean error code, not a success.
FIXTURES: list[tuple[str, str]] = [
    ("native_text.pdf", "application/pdf"),
    ("scanned_image_only.pdf", "application/pdf"),
    ("scanned_image.png", "image/png"),
    ("phone_photo_small.jpg", "image/jpeg"),
    ("phone_photo_large.jpg", "image/jpeg"),
    ("phone_photo.heic", "image/heic"),
    ("multipage.tiff", "image/tiff"),
    ("multipage_g4.tiff", "image/tiff"),
    ("rotated_90.png", "image/png"),
    ("rotated_180.png", "image/png"),
    ("rotated_270.png", "image/png"),
    ("rotated_native_text.pdf", "application/pdf"),
    ("large_35page_native.pdf", "application/pdf"),
    ("large_35page_scanned.pdf", "application/pdf"),
    ("password_protected.pdf", "application/pdf"),
    ("corrupt_truncated.pdf", "application/pdf"),
    ("corrupt_tiny_fragment.pdf", "application/pdf"),
    ("corrupt_bad_header.pdf", "application/pdf"),
    ("corrupt_mutated.pdf", "application/pdf"),
    ("sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
]

# Deliberately wrong browser-supplied Content-Type values, applied to a few
# items so the run also exercises failure-analysis.md #4's magic-byte
# sniffing on the live path rather than only in unit tests.
MIME_LIES = {
    3: "application/octet-stream",   # jpeg claiming to be a binary blob
    5: "application/octet-stream",   # what Chrome really sends for .heic
    8: "image/x-png",                # legacy alias
    6: "image/tif",                  # legacy alias
}

TERMINAL_STATUSES = {"completed", "failed"}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--fixtures-dir", type=Path, required=True)
    p.add_argument("--db-url", default="postgresql://dms_core_user:dms_core_pass@postgres:5432/dms_core_db")
    p.add_argument("--rabbitmq-url", default="amqp://dms:changeme@rabbitmq:5672/")
    p.add_argument("--minio-endpoint", default="minio:9000")
    p.add_argument("--minio-access-key", default="minioadmin")
    p.add_argument("--minio-secret-key", default="minioadmin")
    p.add_argument("--minio-source-bucket", default="oakdms-documents")
    p.add_argument("--total", type=int, default=50, help="total uploads to drive through the queue")
    p.add_argument("--burst", type=int, default=10, help="size of the final simultaneous burst")
    p.add_argument("--trickle-gap-s", type=float, default=1.0, help="gap between the pre-burst publishes")
    p.add_argument("--timeout-s", type=int, default=3600, help="give up waiting for terminal states after this")
    p.add_argument("--poll-interval-s", type=float, default=5.0)
    p.add_argument("--trigger", choices=["outbox", "direct"], default="outbox",
                   help="outbox (default): insert an outbox_events row and let dms-core's real "
                        "OutboxRelay publish it, exactly as the upload path does. "
                        "direct: publish to RabbitMQ with pika, bypassing the relay (isolates ocr-worker)")
    p.add_argument("--run-id", default=None, help="stable id for this run's fixture rows (default: random)")
    p.add_argument("--report-json", type=Path, default=None)
    return p.parse_args()


def ns(run_id: str) -> uuid.UUID:
    return uuid.uuid5(uuid.NAMESPACE_URL, f"oak-dms:ocr-phase1:{run_id}")


def rid(run_id: str, name: str) -> uuid.UUID:
    return uuid.uuid5(ns(run_id), name)


def bootstrap_shared(conn: psycopg.Connection, run_id: str) -> dict:
    """Tenant/user/document_type are shared by all items in a run."""
    tenant_id = rid(run_id, "tenant")
    user_id = rid(run_id, "user")
    keycloak_id = rid(run_id, "keycloak")
    document_type_id = rid(run_id, "document_type")
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tenants (tenant_id, tenant_name, domain, subscription_tier, status) "
            "VALUES (%s, %s, %s, 'standard', 'active') ON CONFLICT (tenant_id) DO NOTHING",
            (tenant_id, f"OCR Phase1 Tenant {run_id}", f"ocr-phase1-{run_id}.local"),
        )
        cur.execute(
            "INSERT INTO users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name, username, status) "
            "VALUES (%s, %s, %s, %s, 'OCR Phase1 User', 'OCR', 'Phase1', %s, 'active') "
            "ON CONFLICT (user_id) DO NOTHING",
            (user_id, tenant_id, keycloak_id, f"ocr-phase1-{run_id}@local", f"ocr-phase1-{run_id}"),
        )
        cur.execute(
            "INSERT INTO document_types (document_type_id, tenant_id, type_code, type_name, created_by) "
            "VALUES (%s, %s, %s, %s, %s) ON CONFLICT (document_type_id) DO NOTHING",
            (document_type_id, tenant_id, DOCUMENT_TYPE_NAME.upper(), DOCUMENT_TYPE_NAME, user_id),
        )
    conn.commit()
    return {"tenant_id": tenant_id, "user_id": user_id, "document_type_id": document_type_id}


def bootstrap_item(conn: psycopg.Connection, shared: dict, run_id: str, *, item_key: str, filename: str, mime_type: str) -> dict:
    tenant_id, user_id, document_type_id = shared["tenant_id"], shared["user_id"], shared["document_type_id"]
    document_id = rid(run_id, f"document:{item_key}")
    version_id = rid(run_id, f"version:{item_key}")
    storage_object_key = f"tenants/{tenant_id}/documents/{document_id}/versions/{version_id}/{filename}"
    with conn.cursor() as cur:
        cur.execute(
            """
            WITH new_document AS (
                INSERT INTO documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id, created_by)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (document_id) DO NOTHING
                RETURNING document_id
            )
            INSERT INTO document_versions (
                version_id, document_id, tenant_id, version_number, major_version, minor_version,
                storage_object_key, original_filename, mime_type, file_hash, size_bytes,
                created_reason, scan_status, ocr_status, created_by
            )
            VALUES (%s, %s, %s, 1, 1, 0, %s, %s, %s, 'ocr-phase1', 0, 'initial_upload', 'clean', 'pending', %s)
            ON CONFLICT (version_id) DO UPDATE SET ocr_status = 'pending', scan_status = 'clean'
            """,
            (
                document_id, tenant_id, document_type_id, f"OCR phase1: {item_key}", version_id, user_id, user_id,
                version_id, document_id, tenant_id, storage_object_key, filename, mime_type, user_id,
            ),
        )
    conn.commit()
    return {
        "item_key": item_key,
        "filename": filename,
        "declared_mime": mime_type,
        "tenant_id": tenant_id,
        "document_id": document_id,
        "version_id": version_id,
        "storage_object_key": storage_object_key,
    }


def event_payload(item: dict) -> dict:
    return {
        "schema_version": 1,
        "event_type": ROUTING_KEY,
        "tenant_id": str(item["tenant_id"]),
        "document_id": str(item["document_id"]),
        "version_id": str(item["version_id"]),
        "correlation_id": str(uuid.uuid4()),
        "occurred_at": datetime.now(timezone.utc).isoformat(),
    }


def publish_direct(channel, item: dict) -> None:
    """Straight to the broker with pika. Bypasses dms-core's OutboxRelay
    entirely -- useful for isolating ocr-worker, but NOT what the upload
    path does, so it is not the default: publishing this way is exactly
    how an earlier version of this harness accidentally hid the relay's
    double-encoding bug (see `--trigger`).
    """
    channel.basic_publish(
        exchange=EXCHANGE_NAME,
        routing_key=ROUTING_KEY,
        body=json.dumps(event_payload(item)).encode(),
        properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
    )
    item["published_at"] = time.time()


def publish_via_outbox(conn: psycopg.Connection, item: dict) -> None:
    """Insert an `outbox_events` row and let dms-core's real OutboxRelay
    publish it -- byte for byte the same wire path
    `DocumentServiceImpl.uploadDocument` produces, minus the Keycloak login
    and multipart upload.

    This is the default on purpose. The trigger direction
    (dms-core -> ocr-worker) and the outcome direction (ocr-worker ->
    dms-core) BOTH run through this one relay, so a harness that publishes
    directly to RabbitMQ only exercises half the path and silently passes
    on a relay that is broken for the other half.
    """
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO outbox_events (tenant_id, aggregate_type, aggregate_id, event_type, payload, status, attempts) "
            "VALUES (%s, 'document', %s, %s, %s, 'pending', 0)",
            (item["tenant_id"], item["document_id"], ROUTING_KEY, json.dumps(event_payload(item))),
        )
    conn.commit()
    item["published_at"] = time.time()


def snapshot(conn: psycopg.Connection, version_ids: list[uuid.UUID]) -> dict[uuid.UUID, dict]:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT v.version_id, v.ocr_status,
                   j.status, j.attempts, j.last_error, j.lease_expires_at, j.started_at, j.finished_at
            FROM document_versions v
            LEFT JOIN LATERAL (
                SELECT status, attempts, last_error, lease_expires_at, started_at, finished_at
                FROM processing_jobs
                WHERE reference_type = 'document_versions' AND reference_id = v.version_id AND job_type = 'ocr'
                ORDER BY created_at DESC LIMIT 1
            ) j ON TRUE
            WHERE v.version_id = ANY(%s)
            """,
            (version_ids,),
        )
        out = {}
        for row in cur.fetchall():
            out[row[0]] = {
                "ocr_status": row[1], "job_status": row[2], "attempts": row[3],
                "last_error": row[4], "lease_expires_at": row[5],
                "started_at": row[6], "finished_at": row[7],
            }
    conn.rollback()
    return out


def main() -> int:
    args = parse_args()
    run_id = args.run_id or uuid.uuid4().hex[:8]
    print(f"== Phase 1 verification run {run_id} ==", flush=True)

    minio_client = Minio(args.minio_endpoint, access_key=args.minio_access_key,
                         secret_key=args.minio_secret_key, secure=False)
    if not minio_client.bucket_exists(args.minio_source_bucket):
        minio_client.make_bucket(args.minio_source_bucket)

    missing = [f for f, _ in FIXTURES if not (args.fixtures_dir / f).exists()]
    if missing:
        print(f"ERROR: missing fixtures in {args.fixtures_dir}: {missing}", file=sys.stderr)
        return 1

    # Build the 50-item plan by cycling the fixture list, so every fixture
    # type is driven at least twice and the mix stays even.
    plan: list[tuple[str, str, str]] = []
    for i in range(args.total):
        filename, real_mime = FIXTURES[i % len(FIXTURES)]
        declared = MIME_LIES.get(i % len(FIXTURES), real_mime)
        plan.append((f"{i:03d}-{filename}", filename, declared))

    conn = psycopg.connect(args.db_url)
    shared = bootstrap_shared(conn, run_id)

    items: list[dict] = []
    print(f"Bootstrapping + uploading {len(plan)} versions...", flush=True)
    for item_key, filename, declared in plan:
        item = bootstrap_item(conn, shared, run_id, item_key=item_key, filename=filename, mime_type=declared)
        data = (args.fixtures_dir / filename).read_bytes()
        minio_client.put_object(args.minio_source_bucket, item["storage_object_key"],
                                data=io.BytesIO(data), length=len(data), content_type=declared)
        items.append(item)
    print(f"  {len(items)} versions staged in Postgres + MinIO", flush=True)

    connection = None
    if args.trigger == "direct":
        connection = pika.BlockingConnection(pika.URLParameters(args.rabbitmq_url))
        channel = connection.channel()
        channel.exchange_declare(exchange=EXCHANGE_NAME, exchange_type="topic", durable=True)
        emit = lambda item: publish_direct(channel, item)  # noqa: E731
    else:
        emit = lambda item: publish_via_outbox(conn, item)  # noqa: E731

    trickle = items[: len(items) - args.burst]
    burst = items[len(items) - args.burst:]

    run_started = time.time()
    print(f"Trigger mode: {args.trigger}", flush=True)
    print(f"Publishing {len(trickle)} events at {args.trickle_gap_s}s intervals...", flush=True)
    for item in trickle:
        emit(item)
        time.sleep(args.trickle_gap_s)

    print(f"Publishing the final {len(burst)}-event simultaneous burst...", flush=True)
    burst_started = time.time()
    for item in burst:
        emit(item)
    if connection is not None:
        connection.close()
    print(f"  burst published in {time.time() - burst_started:.2f}s", flush=True)

    version_ids = [i["version_id"] for i in items]
    by_version = {i["version_id"]: i for i in items}

    deadline = run_started + args.timeout_s
    last_counts = None
    while time.time() < deadline:
        state = snapshot(conn, version_ids)
        counts = Counter(v["ocr_status"] for v in state.values())
        if counts != last_counts:
            print(f"  [{time.time() - run_started:7.1f}s] {dict(counts)}", flush=True)
            last_counts = counts
        if all(v["ocr_status"] in TERMINAL_STATUSES for v in state.values()):
            break
        time.sleep(args.poll_interval_s)

    total_elapsed = time.time() - run_started
    state = snapshot(conn, version_ids)

    results = []
    for vid in version_ids:
        item, st = by_version[vid], state[vid]
        results.append({
            "item_key": item["item_key"],
            "filename": item["filename"],
            "declared_mime": item["declared_mime"],
            "version_id": str(vid),
            "ocr_status": st["ocr_status"],
            "job_status": st["job_status"],
            "attempts": st["attempts"],
            "last_error": st["last_error"],
            "elapsed_s": round((st["finished_at"] - st["started_at"]).total_seconds(), 1)
            if st["started_at"] and st["finished_at"] else None,
        })

    stuck = [r for r in results if r["ocr_status"] not in TERMINAL_STATUSES]
    status_counts = Counter(r["ocr_status"] for r in results)
    error_codes = Counter(
        (r["last_error"] or "").split(":")[0] for r in results if r["ocr_status"] == "failed"
    )

    print("\n" + "=" * 78)
    print(f"RUN {run_id}: {len(results)} uploads, {total_elapsed:.1f}s wall clock")
    print("=" * 78)
    print(f"ocr_status:  {dict(status_counts)}")
    print(f"stuck (non-terminal): {len(stuck)}")
    if error_codes:
        print(f"failure codes: {dict(error_codes)}")
    print("-" * 78)
    print(f"{'item':28} {'ocr_status':11} {'job':11} {'att':>3} {'sec':>7}  error")
    for r in sorted(results, key=lambda r: r["item_key"]):
        err = (r["last_error"] or "")[:60]
        secs = f"{r['elapsed_s']:.1f}" if r["elapsed_s"] is not None else "-"
        print(f"{r['item_key']:28} {str(r['ocr_status']):11} {str(r['job_status']):11} {str(r['attempts']):>3} {secs:>7}  {err}")

    report = {
        "run_id": run_id,
        "started_at": datetime.fromtimestamp(run_started, timezone.utc).isoformat(),
        "total_uploads": len(results),
        "burst_size": args.burst,
        "wall_clock_s": round(total_elapsed, 1),
        "status_counts": dict(status_counts),
        "failure_codes": dict(error_codes),
        "stuck_non_terminal": len(stuck),
        "results": results,
    }
    if args.report_json:
        args.report_json.write_text(json.dumps(report, indent=2, default=str))
        print(f"\nwrote {args.report_json}")

    conn.close()
    return 0 if not stuck else 2


if __name__ == "__main__":
    raise SystemExit(main())
