#!/usr/bin/env python
"""Phase 1 close-out: live proof for the three things
docs/ocr/phase1-verification.md's "Open items" left verified-by-design
rather than verified-by-running.

    open item #3  ->  `fields`   selectable field extraction end to end
                                 through the REAL upload API, with
                                 dms-core's poll-time re-apply switched
                                 off so only the event path can be
                                 responsible for the result.
    open item #4  ->  `reaper`   kill a pool worker mid-document; the
                                 lease must expire, the reaper must
                                 requeue, and attempt 2 must complete.
    open item #1  ->  `timeout`  a per-page budget the document cannot
                                 meet; TIMEOUT must be recorded, exactly
                                 one pool worker replaced, and the next
                                 job must still run.

Run it from inside the compose network (it resolves `dms-core`,
`keycloak`, `postgres` and `rabbitmq` by service name):

    docker run --rm --network local_default \\
        -v "<repo>:/repo" ocr-worker-test:latest \\
        python /repo/scripts/dev/phase1_closeout_verification.py fields \\
            --report-json /repo/docs/ocr/phase1-closeout-fields.json

`reaper` and `timeout` need ocr-worker restarted with different settings
first (short lease / tiny per-page budget) -- each subcommand prints the
exact environment it expects and refuses to run against the wrong one,
rather than producing a result that looks like a pass for the wrong
reason.

Every run stages its own tenant-scoped fixtures under a fresh `--run-id`,
so re-running never collides with a previous run's rows.
"""
from __future__ import annotations

import argparse
import io
import json
import http.client
import os
import socket
import sys
import time
import uuid
from datetime import datetime, timezone

import psycopg
import requests

DMS_CORE_URL = os.environ.get("DMS_CORE_URL", "http://dms-core:8080")
KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "http://keycloak:8080")
KEYCLOAK_REALM = "oak-dms"
KEYCLOAK_CLIENT_ID = "oak-dms-web"

# ABC Company -- the one seeded tenant whose INVOICE document type has a
# real metadata schema (11 fields, 6 mandatory). Phase 1's own harness used
# a throwaway document type with NO schema, which is precisely why
# `document_ocr_field_results` came back empty and open item #3 could not
# be closed by that run.
TENANT_ID = os.environ.get("VERIFY_TENANT_ID", "92f0a443-076e-490f-904c-cc45b6d1b690")
DOCUMENT_TYPE_CODE = "INVOICE"

# Every mandatory field of that schema (upload rejects a requestedFields
# set that omits one -- see DocumentServiceImpl.validateRequestedFields)
# plus one optional field, so the run also covers a field that is NOT
# forced to be present.
REQUESTED_FIELDS = [
    "invoice_number",
    "invoice_date",
    "amount",
    "currency",
    "vendor_supplier_name",
    "classification_level",
    "purchase_order_number",
]

TERMINAL_OCR_STATUSES = {"completed", "failed"}


# ----------------------------------------------------------------------
# Fixtures
# ----------------------------------------------------------------------
def build_invoice_pdf(*, scanned: bool) -> bytes:
    """A realistic single-page invoice.

    Labels are chosen to match `extraction.py`'s `_LABEL_ALIASES` for the
    requested fields -- the point of this run is to prove the WIRING
    (upload -> queue -> worker -> field results -> event -> metadata
    values), not to benchmark the extractor against adversarial layouts.
    An extractor miss would leave a `not_found` row, which is still a row,
    and the assertions below say exactly which fields carried a value.

    `scanned=True` rasterizes the same page and rebuilds it as an
    image-only PDF with no text layer at all, forcing the PaddleOCR branch
    -- that is what makes the `timeout` run take real inference time
    instead of finishing in a second on Docling's native-text path.
    """
    import pymupdf

    doc = pymupdf.open()
    page = doc.new_page()
    lines = [
        ("Northwind Trading Ltd", 22),
        ("", 10),
        ("INVOICE", 16),
        ("", 8),
        ("Vendor / Supplier: Northwind Trading Ltd", 11),
        ("Invoice Number: INV-2026-00184", 11),
        ("Invoice Date: 2026-03-14", 11),
        ("PO Number: PO-55821", 11),
        ("Currency: GBP", 11),
        ("Classification: Confidential", 11),
        ("", 10),
        ("Bill To: Acme Corporation, 14 Harbour Road, Bristol", 11),
        ("", 14),
        ("Description                          Qty        Unit Price", 11),
        ("Managed hosting, March 2026            1           1,850.00", 11),
        ("Out-of-hours support retainer          1             420.00", 11),
        ("Onboarding workshop                    2             115.00", 11),
        ("", 14),
        ("Total Amount: 2500.00", 13),
        ("", 10),
        ("Payment terms: net 30 days from the invoice date.", 10),
    ]
    y = 60
    for text, size in lines:
        if text:
            page.insert_text((60, y), text, fontsize=size)
        y += size + 8

    if not scanned:
        return doc.tobytes()

    # Rasterize -> image-only PDF: no text layer, so every page routes to
    # PaddleOCR (see README's "Native-text vs. PaddleOCR decision logic").
    pixmap = page.get_pixmap(dpi=200)
    doc.close()
    scanned_doc = pymupdf.open()
    scanned_page = scanned_doc.new_page(width=pixmap.width * 0.75, height=pixmap.height * 0.75)
    scanned_page.insert_image(scanned_page.rect, stream=pixmap.tobytes("png"))
    return scanned_doc.tobytes()


# ----------------------------------------------------------------------
# Auth -- a dedicated verification user, never the developer's own account
# ----------------------------------------------------------------------
def _admin_token(admin_password: str) -> str:
    response = requests.post(
        f"{KEYCLOAK_URL}/realms/master/protocol/openid-connect/token",
        data={"client_id": "admin-cli", "username": "admin", "password": admin_password, "grant_type": "password"},
        timeout=30,
    )
    response.raise_for_status()
    return response.json()["access_token"]


def ensure_verification_user(conn, admin_password: str, *, username: str, password: str) -> str:
    """Create (idempotently) a Keycloak user in the oak-dms realm AND its
    matching `users` row, and return a bearer token for it.

    The local row is what actually carries tenancy -- TenantContextFilter
    resolves `users.tenant_id` by `keycloak_id = <jwt sub>`, it does not
    read a tenant claim -- so both halves are required. Inserted directly,
    the same way scripts/dev/phase1_verification.py stages its own rows;
    dms-core's real provisioning flow needs an already-authenticated admin,
    which is the chicken-and-egg this sidesteps.
    """
    token = _admin_token(admin_password)
    headers = {"Authorization": f"Bearer {token}"}
    users_api = f"{KEYCLOAK_URL}/admin/realms/{KEYCLOAK_REALM}/users"

    existing = requests.get(users_api, params={"username": username, "exact": "true"}, headers=headers, timeout=30)
    existing.raise_for_status()
    if existing.json():
        keycloak_id = existing.json()[0]["id"]
    else:
        created = requests.post(
            users_api,
            headers=headers,
            json={
                "username": username,
                "email": f"{username}@verification.local",
                "firstName": "Phase1",
                "lastName": "Closeout",
                "enabled": True,
                "emailVerified": True,
                "attributes": {"tenant_id": [TENANT_ID]},
                "credentials": [{"type": "password", "value": password, "temporary": False}],
            },
            timeout=30,
        )
        if created.status_code not in (201, 409):
            raise RuntimeError(f"could not create keycloak user: {created.status_code} {created.text}")
        lookup = requests.get(users_api, params={"username": username, "exact": "true"}, headers=headers, timeout=30)
        lookup.raise_for_status()
        keycloak_id = lookup.json()[0]["id"]

    with conn.cursor() as cur:
        cur.execute("select user_id from users where keycloak_id = %s", (keycloak_id,))
        row = cur.fetchone()
        if row is None:
            cur.execute(
                """
                insert into users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name,
                                   username, status, verified, verified_at)
                values (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, 'active', true, now())
                returning user_id
                """,
                (TENANT_ID, keycloak_id, f"{username}@verification.local", "Phase1 Closeout",
                 "Phase1", "Closeout", username),
            )
            row = cur.fetchone()
        conn.commit()
        user_id = row[0]

    token_response = requests.post(
        f"{KEYCLOAK_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token",
        data={
            "client_id": KEYCLOAK_CLIENT_ID,
            "grant_type": "password",
            "username": username,
            "password": password,
            "scope": "openid",
        },
        timeout=30,
    )
    if token_response.status_code != 200:
        raise RuntimeError(f"password grant failed: {token_response.status_code} {token_response.text}")
    return token_response.json()["access_token"], str(user_id), keycloak_id


# ----------------------------------------------------------------------
# The real upload API
# ----------------------------------------------------------------------
def upload_document(access_token: str, *, filename: str, content: bytes, requested_fields: list[str]) -> dict:
    response = requests.post(
        f"{DMS_CORE_URL}/api/v1/documents",
        headers={"Authorization": f"Bearer {access_token}"},
        files={"file": (filename, io.BytesIO(content), "application/pdf")},
        data={"documentType": DOCUMENT_TYPE_CODE, "requestedFields": json.dumps(requested_fields)},
        timeout=120,
    )
    if response.status_code != 201:
        raise RuntimeError(f"upload failed: {response.status_code} {response.text}")
    return response.json()


# ----------------------------------------------------------------------
# DB helpers
# ----------------------------------------------------------------------
def connect(dsn: str) -> psycopg.Connection:
    return psycopg.connect(dsn)


def version_row(conn, version_id) -> dict | None:
    with conn.cursor() as cur:
        cur.execute(
            "select ocr_status, document_id, requested_fields from document_versions where version_id = %s",
            (version_id,),
        )
        row = cur.fetchone()
    if row is None:
        return None
    return {"ocr_status": row[0], "document_id": row[1], "requested_fields": row[2]}


def job_rows(conn, version_id) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            """
            select job_id, status, attempts, last_error, lease_expires_at, next_attempt_at, started_at, finished_at
            from processing_jobs
            where reference_type = 'document_versions' and reference_id = %s and job_type = 'ocr'
            order by created_at
            """,
            (version_id,),
        )
        cols = [c.name for c in cur.description]
        return [dict(zip(cols, r)) for r in cur.fetchall()]


def field_result_rows(conn, document_id) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            """
            select field_key, value, confidence, status, source_page
            from document_ocr_field_results where document_id = %s order by field_key
            """,
            (document_id,),
        )
        cols = [c.name for c in cur.description]
        return [dict(zip(cols, r)) for r in cur.fetchall()]


def metadata_value_rows(conn, document_id) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            """
            select mf.field_key, v.value_text, v.value_number, v.value_date, v.source, v.confidence_score
            from document_metadata_values v
            join metadata_fields mf on mf.field_id = v.field_id
            where v.document_id = %s and v.is_deleted = false
            order by mf.field_key, v.value_ordinal
            """,
            (document_id,),
        )
        cols = [c.name for c in cur.description]
        return [dict(zip(cols, r)) for r in cur.fetchall()]


def outbox_rows(conn, version_id) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            """
            select event_type, status, attempts, created_at
            from outbox_events where aggregate_id = %s order by created_at
            """,
            (version_id,),
        )
        cols = [c.name for c in cur.description]
        return [dict(zip(cols, r)) for r in cur.fetchall()]


def wait_for(predicate, *, timeout_s: float, interval_s: float = 1.0, description: str = ""):
    """Poll `predicate` until it returns something truthy. Returns
    (value, elapsed_s); value is None on timeout -- callers report that as
    a failure rather than raising, so a partial run still prints its
    evidence.
    """
    started = time.monotonic()
    while time.monotonic() - started < timeout_s:
        value = predicate()
        if value:
            return value, time.monotonic() - started
        time.sleep(interval_s)
    print(f"  ! timed out after {timeout_s:.0f}s waiting for {description}", file=sys.stderr)
    return None, time.monotonic() - started


# ----------------------------------------------------------------------
# Worker introspection (pool worker PIDs, container settings)
#
# Spoken to over the Docker Engine API on the mounted unix socket rather
# than by shelling out to `docker`: this harness runs INSIDE a container on
# the compose network (it has to, to resolve `dms-core`/`postgres` by
# service name), and that container has no docker CLI. Mount the socket:
#     -v /var/run/docker.sock:/var/run/docker.sock
# ----------------------------------------------------------------------
DOCKER_SOCKET = os.environ.get("DOCKER_SOCKET", "/var/run/docker.sock")


class _UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, socket_path: str):
        super().__init__("localhost")
        self._socket_path = socket_path

    def connect(self):
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(30)
        sock.connect(self._socket_path)
        self.sock = sock


def _docker(method: str, path: str, body: dict | None = None) -> tuple[int, bytes]:
    conn = _UnixHTTPConnection(DOCKER_SOCKET)
    payload = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if payload else {}
    conn.request(method, path, body=payload, headers=headers)
    response = conn.getresponse()
    data = response.read()
    conn.close()
    return response.status, data


def _docker_json(method: str, path: str, body: dict | None = None):
    status, data = _docker(method, path, body)
    if status >= 400:
        raise RuntimeError(f"docker API {method} {path} -> {status}: {data[:400]!r}")
    return json.loads(data) if data else None


def worker_container_env(container: str) -> dict[str, str]:
    inspected = _docker_json("GET", f"/containers/{container}/json")
    env = {}
    for item in inspected["Config"]["Env"]:
        key, _, value = item.partition("=")
        env[key] = value
    return env


# Reads the pool workers straight out of the container's own /proc, and
# reports its own namespace's PIDs.
#
# NOT `docker top`: that reports HOST-namespace pids. The first attempt at
# the reaper check used them, and `kill -9 <host pid>` run inside the
# container hit a pid that simply doesn't exist there -- the kill silently
# did nothing, the document completed normally, and the check "failed" for
# a reason that had nothing to do with the reaper. Reading and killing in
# the same namespace is the only way that is not a trap.
#
# /proc rather than `ps` because the runtime image is python:3.12-slim,
# which has no procps.
_PROC_SNAPSHOT_SOURCE = """
import json, os
out = {}
for entry in os.listdir("/proc"):
    if not entry.isdigit():
        continue
    try:
        cmdline = open("/proc/%s/cmdline" % entry, "rb").read().decode("utf-8", "replace")
        # `spawn`'s children run: python -c 'from multiprocessing.spawn import spawn_main; ...'
        if "spawn_main" not in cmdline:
            continue
        fields = open("/proc/%s/stat" % entry).read().rsplit(")", 1)[1].split()
        out[entry] = (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")
    except OSError:
        pass
print(json.dumps(out))
"""


def _pool_worker_cpu_times(container: str) -> dict[int, float]:
    exit_code, stdout = container_exec_output(container, ["python", "-c", _PROC_SNAPSHOT_SOURCE])
    if exit_code != 0:
        raise RuntimeError(f"pool-worker snapshot failed in {container} (exit {exit_code}): {stdout!r}")
    return {int(pid): cpu for pid, cpu in json.loads(stdout.strip().splitlines()[-1]).items()}


def pool_worker_pids(container: str) -> list[int]:
    """The pebble pool's worker PROCESSES -- children of the uvicorn
    parent, each a fresh interpreter started by `spawn` running
    `_pool_worker_init`. Used to prove that a timeout/crash replaced
    exactly one of them and left the rest alone.
    """
    return sorted(_pool_worker_cpu_times(container))


def busiest_pool_worker(container: str, *, sample_s: float = 2.0) -> int | None:
    """Which pool worker is actually running the document right now.

    Picking a pool worker at random to SIGKILL is not a test of anything:
    the first attempt at this killed the idle one, the document finished
    normally on the other, and the check "failed" for a reason that had
    nothing to do with the reaper. Cumulative CPU time alone doesn't
    separate them either (both burned comparable CPU during model warm-up),
    so this samples the DELTA over a couple of seconds -- the worker doing
    inference is the one whose CPU time is still climbing.
    """
    before = _pool_worker_cpu_times(container)
    time.sleep(sample_s)
    after = _pool_worker_cpu_times(container)
    deltas = {pid: after[pid] - before.get(pid, 0.0) for pid in after}
    if not deltas:
        return None
    busiest = max(deltas, key=deltas.get)
    print(f"  pool worker CPU deltas over {sample_s}s: {deltas} -> busiest={busiest}")
    return busiest if deltas[busiest] > 0 else None


def container_exec_output(container: str, cmd: list[str]) -> tuple[int, str]:
    """Run a command in a container and return (exit_code, stdout+stderr).

    `/exec/{id}/start` with Detach=false hijacks the connection and streams
    Docker's own multiplexed frame format: an 8-byte header
    (stream id, 3 reserved bytes, big-endian uint32 length) per chunk.
    """
    created = _docker_json(
        "POST", f"/containers/{container}/exec",
        {"Cmd": cmd, "AttachStdout": True, "AttachStderr": True, "Tty": False},
    )
    conn = _UnixHTTPConnection(DOCKER_SOCKET)
    payload = json.dumps({"Detach": False, "Tty": False}).encode()
    conn.request("POST", f"/exec/{created['Id']}/start", body=payload,
                 headers={"Content-Type": "application/json"})
    response = conn.getresponse()
    raw = response.read()
    conn.close()

    chunks, offset = [], 0
    while offset + 8 <= len(raw):
        length = int.from_bytes(raw[offset + 4:offset + 8], "big")
        chunks.append(raw[offset + 8:offset + 8 + length])
        offset += 8 + length
    output = b"".join(chunks).decode("utf-8", "replace")

    for _ in range(50):
        state = _docker_json("GET", f"/exec/{created['Id']}/json")
        if not state["Running"]:
            return state["ExitCode"], output
        time.sleep(0.1)
    raise RuntimeError(f"exec {cmd} in {container} never finished")


def kill_pool_worker(container: str, pid: int) -> None:
    """SIGKILL one pool worker, from inside the container so the pid is in
    the right namespace. `os.kill` rather than `kill(1)`, since the runtime
    image has no coreutils/procps.
    """
    exit_code, output = container_exec_output(
        container, ["python", "-c", f"import os, signal; os.kill({pid}, signal.SIGKILL)"]
    )
    if exit_code != 0:
        raise RuntimeError(f"could not SIGKILL pid {pid} in {container} (exit {exit_code}): {output!r}")


def require_env(container: str, expected: dict[str, str]) -> dict[str, str]:
    env = worker_container_env(container)
    wrong = {k: env.get(k) for k, v in expected.items() if env.get(k) != v}
    if wrong:
        print(
            f"REFUSING TO RUN: {container} is not configured for this check.\n"
            f"  expected: {expected}\n"
            f"  actual:   {wrong}\n"
            "Bring the stack up with the matching override file first (see this script's docstring).",
            file=sys.stderr,
        )
        sys.exit(2)
    return env


# ----------------------------------------------------------------------
# Checks
# ----------------------------------------------------------------------
def check_fields(args, conn) -> dict:
    """Open item #3. The whole point is the SOURCE of the writes, so the
    poll-time re-apply is required to be off AND this check never calls
    GET /ocr-status while it waits -- it polls the database. If
    `document_metadata_values` ends up populated, dms-core's
    OcrEventsConsumer is the only thing that could have done it.
    """
    core_env = worker_container_env(args.core_container)
    poll_reapply = core_env.get("OCR_POLL_REAPPLY_ENABLED")
    if poll_reapply != "false":
        print(
            "REFUSING TO RUN: dms-core still has the poll-time re-apply enabled "
            f"(OCR_POLL_REAPPLY_ENABLED={poll_reapply!r}).\n"
            "That safety net would make this check pass even if the event path were completely dead -- "
            "which is exactly how the relay defect in docs/ocr/phase1-verification.md went unnoticed.",
            file=sys.stderr,
        )
        sys.exit(2)

    token, user_id, keycloak_id = ensure_verification_user(
        conn, args.keycloak_admin_password, username=args.username, password=args.password
    )
    print(f"  verification user {args.username} (users.user_id={user_id}, keycloak_id={keycloak_id})")

    pdf = build_invoice_pdf(scanned=args.scanned)
    print(f"  uploading {'scanned (image-only)' if args.scanned else 'native-text'} invoice, {len(pdf)} bytes, "
          f"requestedFields={REQUESTED_FIELDS}")
    uploaded = upload_document(
        token, filename=f"invoice-{args.run_id}.pdf", content=pdf, requested_fields=REQUESTED_FIELDS
    )
    version_id = uploaded["versionId"]
    print(f"  versionId={version_id}")

    terminal, elapsed = wait_for(
        lambda: (lambda v: v if v and v["ocr_status"] in TERMINAL_OCR_STATUSES else None)(version_row(conn, version_id)),
        timeout_s=args.timeout_s, description="document_versions.ocr_status to become terminal",
    )
    if terminal is None:
        return {"check": "fields", "passed": False, "reason": "ocr_status never reached a terminal state",
                "version_id": version_id, "jobs": job_rows(conn, version_id)}
    document_id = terminal["document_id"]
    print(f"  ocr_status={terminal['ocr_status']} after {elapsed:.1f}s (documentId={document_id})")

    field_results = field_result_rows(conn, document_id)
    print(f"  document_ocr_field_results: {len(field_results)} row(s)")

    # The event path is asynchronous relative to the worker's own commit:
    # the '.completed' outbox row still has to be picked up by OutboxRelay
    # (2s poll) and delivered to OcrEventsConsumer.
    metadata_values, meta_elapsed = wait_for(
        lambda: metadata_value_rows(conn, document_id) or None,
        timeout_s=args.event_timeout_s, description="document_metadata_values written by OcrEventsConsumer",
    )
    metadata_values = metadata_values or []
    print(f"  document_metadata_values: {len(metadata_values)} row(s) after a further {meta_elapsed:.1f}s")

    # Only now touch the polling endpoint -- after the assertions' evidence
    # is already collected, so it cannot have contributed to it.
    status_response = requests.get(
        f"{DMS_CORE_URL}/api/v1/documents/{document_id}/ocr-status",
        headers={"Authorization": f"Bearer {token}"}, timeout=60,
    )

    extracted = [r for r in field_results if r["value"] is not None]
    ocr_sourced = [r for r in metadata_values if r["source"] == "ocr"]
    passed = bool(extracted) and bool(ocr_sourced) and terminal["ocr_status"] == "completed"

    return {
        "check": "fields",
        "passed": passed,
        "poll_reapply_enabled": poll_reapply,
        "version_id": version_id,
        "document_id": str(document_id),
        "ocr_status": terminal["ocr_status"],
        "requested_fields_column": terminal["requested_fields"],
        "seconds_to_terminal": round(elapsed, 1),
        "seconds_to_metadata_values": round(meta_elapsed, 1),
        "field_results": [
            {**r, "confidence": float(r["confidence"]) if r["confidence"] is not None else None}
            for r in field_results
        ],
        "metadata_values": [
            {
                "field_key": r["field_key"],
                "value": r["value_text"] if r["value_text"] is not None else (
                    str(r["value_number"]) if r["value_number"] is not None else (
                        r["value_date"].isoformat() if r["value_date"] is not None else None)),
                "source": r["source"],
                "confidence_score": float(r["confidence_score"]) if r["confidence_score"] is not None else None,
            }
            for r in metadata_values
        ],
        "outbox": [{**r, "created_at": r["created_at"].isoformat()} for r in outbox_rows(conn, version_id)],
        "jobs": _serialize_jobs(job_rows(conn, version_id)),
        "ocr_status_endpoint": status_response.json() if status_response.status_code == 200 else status_response.text,
    }


def check_reaper(args, conn) -> dict:
    """Open item #4. Kill a pool worker mid-document and require the
    reaper -- not a human, not a restart -- to bring the version to a
    terminal state on a later attempt.
    """
    env = require_env(args.worker_container, {"OCR_LEASE_DURATION_S": args.expect_lease_s})
    lease_s = int(env["OCR_LEASE_DURATION_S"])
    reaper_interval_s = int(env.get("OCR_REAPER_INTERVAL_S", "30"))
    print(f"  ocr-worker lease={lease_s}s, reaper interval={reaper_interval_s}s")

    token, _user_id, _kc = ensure_verification_user(
        conn, args.keycloak_admin_password, username=args.username, password=args.password
    )
    pids_before = pool_worker_pids(args.worker_container)
    print(f"  pool worker pids before: {pids_before}")

    pdf = build_invoice_pdf(scanned=True)  # image-only -> real PaddleOCR time to interrupt
    uploaded = upload_document(
        token, filename=f"reaper-{args.run_id}.pdf", content=pdf, requested_fields=REQUESTED_FIELDS
    )
    version_id = uploaded["versionId"]
    print(f"  versionId={version_id}")

    claimed, _ = wait_for(
        lambda: next((j for j in job_rows(conn, version_id) if j["status"] == "processing"), None),
        timeout_s=120, interval_s=0.5, description="the job to be claimed (status='processing')",
    )
    if claimed is None:
        return {"check": "reaper", "passed": False, "reason": "job was never claimed", "version_id": version_id}
    print(f"  claimed: attempts={claimed['attempts']}, lease_expires_at={claimed['lease_expires_at']}")

    # Give inference a moment to actually be underway, then SIGKILL one
    # pool worker -- the crudest possible stand-in for an OOM kill, and
    # the one thing the reaper exists for.
    time.sleep(args.kill_after_s)
    victim_pid = busiest_pool_worker(args.worker_container)
    if victim_pid is None:
        return {"check": "reaper", "passed": False,
                "reason": "no pool worker was busy -- the document finished before it could be interrupted",
                "jobs": _serialize_jobs(job_rows(conn, version_id))}
    kill_pool_worker(args.worker_container, victim_pid)
    killed_at = datetime.now(timezone.utc)
    print(f"  SIGKILLed pool worker pid={victim_pid} at {killed_at.isoformat()}")

    requeued, requeue_elapsed = wait_for(
        lambda: next((j for j in job_rows(conn, version_id) if j["status"] == "queued"), None),
        timeout_s=lease_s + reaper_interval_s * 3 + 60, interval_s=1.0,
        description="the reaper to requeue the expired lease",
    )
    if requeued is None:
        return {"check": "reaper", "passed": False, "reason": "lease never reclaimed",
                "version_id": version_id, "jobs": _serialize_jobs(job_rows(conn, version_id))}
    print(f"  reaper requeued after {requeue_elapsed:.0f}s (attempts={requeued['attempts']}, "
          f"next_attempt_at={requeued['next_attempt_at']})")

    terminal, terminal_elapsed = wait_for(
        lambda: (lambda v: v if v and v["ocr_status"] in TERMINAL_OCR_STATUSES else None)(version_row(conn, version_id)),
        timeout_s=args.timeout_s, description="the retry to reach a terminal state",
    )
    jobs = job_rows(conn, version_id)
    final_attempts = max((j["attempts"] for j in jobs), default=0)
    pids_after = pool_worker_pids(args.worker_container)
    print(f"  pool worker pids after: {pids_after}")

    passed = bool(terminal) and terminal["ocr_status"] == "completed" and final_attempts >= 2
    return {
        "check": "reaper",
        "passed": passed,
        "version_id": version_id,
        "lease_duration_s": lease_s,
        "reaper_interval_s": reaper_interval_s,
        "killed_pid": victim_pid,
        "killed_at": killed_at.isoformat(),
        "seconds_to_requeue": round(requeue_elapsed, 1),
        "seconds_to_terminal_after_requeue": round(terminal_elapsed, 1),
        "final_ocr_status": terminal["ocr_status"] if terminal else None,
        "final_attempts": final_attempts,
        "pool_worker_pids_before": pids_before,
        "pool_worker_pids_after": pids_after,
        "jobs": _serialize_jobs(jobs),
        "outbox": [{**r, "created_at": r["created_at"].isoformat()} for r in outbox_rows(conn, version_id)],
    }


def check_timeout(args, conn) -> dict:
    """Open item #1. A per-page budget the document cannot possibly meet,
    on a document that really does run PaddleOCR. TIMEOUT must be
    recorded, exactly one pool worker replaced, and the pool must still
    serve the next job.
    """
    env = require_env(args.worker_container, {"OCR_TIMEOUT_PER_PAGE_S": args.expect_per_page_s})
    base_s = float(env.get("OCR_TIMEOUT_BASE_S", "60"))
    per_page_s = float(env["OCR_TIMEOUT_PER_PAGE_S"])
    grace_s = float(env.get("OCR_TIMEOUT_HARD_GRACE_S", "30"))
    print(f"  budget for a 1-page document: {base_s} + 1 x {per_page_s} = {base_s + per_page_s}s "
          f"(hard at +{grace_s}s). Old behavior would have been "
          f"{base_s} + {env.get('OCR_MAX_PAGES', '50')} x {per_page_s}s.")

    token, _user_id, _kc = ensure_verification_user(
        conn, args.keycloak_admin_password, username=args.username, password=args.password
    )
    pids_before = pool_worker_pids(args.worker_container)
    print(f"  pool worker pids before: {pids_before}")

    pdf = build_invoice_pdf(scanned=True)
    uploaded = upload_document(
        token, filename=f"timeout-{args.run_id}.pdf", content=pdf, requested_fields=REQUESTED_FIELDS
    )
    version_id = uploaded["versionId"]
    print(f"  versionId={version_id}")

    terminal, elapsed = wait_for(
        lambda: (lambda v: v if v and v["ocr_status"] in TERMINAL_OCR_STATUSES else None)(version_row(conn, version_id)),
        timeout_s=args.timeout_s, interval_s=0.5,
        description="the timed-out document to reach a terminal state",
    )
    jobs = job_rows(conn, version_id)
    last_error = jobs[-1]["last_error"] if jobs else None
    print(f"  ocr_status={terminal['ocr_status'] if terminal else None} after {elapsed:.1f}s, last_error={last_error!r}")

    # A moment to settle before the final snapshot: a `spawn`ed worker that
    # has been told to exit and its replacement briefly coexist, so an
    # immediate snapshot can show OCR_WORKER_PROCESSES+1 entries and make
    # "how many were replaced" look wrong. (Seen on the first run of this
    # check: 3 pids for a pool of 2.)
    time.sleep(5)
    pids_after_timeout = pool_worker_pids(args.worker_container)
    survived = [p for p in pids_before if p in pids_after_timeout]
    replaced = [p for p in pids_before if p not in pids_after_timeout]
    restart_count = _docker_json("GET", f"/containers/{args.worker_container}/json")["RestartCount"]
    print(f"  pool worker pids after timeout: {pids_after_timeout} "
          f"(survived: {survived}, gone: {replaced}); container RestartCount={restart_count}")

    # The next job must still run. Sized to be trivially fast: a
    # native-text PDF never touches PaddleOCR, so even a 1s/page budget
    # is enough for it.
    follow_up = upload_document(
        token, filename=f"timeout-followup-{args.run_id}.pdf",
        content=build_invoice_pdf(scanned=False), requested_fields=REQUESTED_FIELDS,
    )
    follow_up_version_id = follow_up["versionId"]
    follow_up_terminal, follow_up_elapsed = wait_for(
        lambda: (lambda v: v if v and v["ocr_status"] in TERMINAL_OCR_STATUSES else None)(
            version_row(conn, follow_up_version_id)),
        timeout_s=args.timeout_s, description="the FOLLOW-UP document to reach a terminal state",
    )
    print(f"  follow-up versionId={follow_up_version_id}: "
          f"ocr_status={follow_up_terminal['ocr_status'] if follow_up_terminal else None} "
          f"after {follow_up_elapsed:.1f}s")

    timeout_recorded = bool(last_error and last_error.startswith("TIMEOUT: "))
    # "Only that worker is replaced" means: something really was killed,
    # the REST of the pool carried on untouched, and the service itself
    # never restarted. Deliberately not "exactly N-1 survived" -- the pool
    # also recycles workers on OCR_WORKER_MAX_TASKS_PER_PROCESS, so pid-set
    # arithmetic over a window is not a stable way to say that.
    worker_was_replaced = bool(replaced)
    rest_of_pool_untouched = bool(survived)
    service_not_restarted = restart_count == 0
    follow_up_ok = bool(follow_up_terminal) and follow_up_terminal["ocr_status"] == "completed"

    return {
        "check": "timeout",
        "passed": timeout_recorded and worker_was_replaced and rest_of_pool_untouched
                  and service_not_restarted and follow_up_ok,
        "budget_s": base_s + per_page_s,
        "hard_deadline_s": base_s + per_page_s + grace_s,
        "old_uniform_ceiling_s": base_s + float(env.get("OCR_MAX_PAGES", "50")) * per_page_s,
        "version_id": version_id,
        "ocr_status": terminal["ocr_status"] if terminal else None,
        "seconds_to_terminal": round(elapsed, 1),
        "last_error": last_error,
        "timeout_recorded": timeout_recorded,
        "pool_worker_pids_before": pids_before,
        "pool_worker_pids_after": pids_after_timeout,
        "pool_workers_replaced": replaced,
        "pool_workers_survived": survived,
        "worker_was_replaced": worker_was_replaced,
        "rest_of_pool_untouched": rest_of_pool_untouched,
        "container_restart_count": restart_count,
        "service_not_restarted": service_not_restarted,
        "follow_up_version_id": follow_up_version_id,
        "follow_up_ocr_status": follow_up_terminal["ocr_status"] if follow_up_terminal else None,
        "follow_up_seconds": round(follow_up_elapsed, 1),
        "jobs": _serialize_jobs(jobs),
        "outbox": [{**r, "created_at": r["created_at"].isoformat()} for r in outbox_rows(conn, version_id)],
    }


def _serialize_jobs(jobs: list[dict]) -> list[dict]:
    out = []
    for job in jobs:
        out.append({
            k: (v.isoformat() if hasattr(v, "isoformat") else (str(v) if isinstance(v, uuid.UUID) else v))
            for k, v in job.items()
        })
    return out


# ----------------------------------------------------------------------
def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("check", choices=["fields", "reaper", "timeout"])
    parser.add_argument("--dsn", default=os.environ.get(
        "VERIFY_DSN", "postgresql://postgres:postgres@postgres:5432/dms_core_db"))
    parser.add_argument("--keycloak-admin-password", default=os.environ.get("KEYCLOAK_ADMIN_PASSWORD"))
    parser.add_argument("--username", default="phase1-closeout")
    parser.add_argument("--password", default="Phase1Closeout!2026")
    parser.add_argument("--run-id", default=uuid.uuid4().hex[:8])
    parser.add_argument("--worker-container", default="oakdms-ocr-worker")
    parser.add_argument("--core-container", default="oakdms-core")
    parser.add_argument("--timeout-s", dest="timeout_s", type=float, default=600)
    parser.add_argument("--event-timeout-s", dest="event_timeout_s", type=float, default=60)
    parser.add_argument("--scanned", action="store_true",
                        help="fields: upload an image-only invoice (real PaddleOCR) instead of a native-text one")
    parser.add_argument("--kill-after-s", dest="kill_after_s", type=float, default=8,
                        help="reaper: how long to let inference run before SIGKILLing a pool worker")
    parser.add_argument("--expect-lease-s", default="60",
                        help="reaper: the OCR_LEASE_DURATION_S the worker must be running with")
    parser.add_argument("--expect-per-page-s", default="1",
                        help="timeout: the OCR_TIMEOUT_PER_PAGE_S the worker must be running with")
    parser.add_argument("--report-json", default=None)
    return parser.parse_args()


import sys as _sys
from pathlib import Path as _Path
_sys.path.insert(0, str(_Path(__file__).resolve().parent))
from _staleness_guard import enforce as _enforce_fresh  # noqa: E402


def main() -> int:
    args = parse_args()
    _enforce_fresh("phase1-closeout")
    if not args.keycloak_admin_password:
        print("KEYCLOAK_ADMIN_PASSWORD is required (env or --keycloak-admin-password)", file=sys.stderr)
        return 2

    print(f"=== phase 1 close-out: {args.check} (run-id {args.run_id}) ===")
    with connect(args.dsn) as conn:
        result = {"fields": check_fields, "reaper": check_reaper, "timeout": check_timeout}[args.check](args, conn)

    result["run_id"] = args.run_id
    result["completed_at"] = datetime.now(timezone.utc).isoformat()
    print(json.dumps(result, indent=2, default=str))
    if args.report_json:
        with open(args.report_json, "w", encoding="utf-8") as handle:
            json.dump(result, handle, indent=2, default=str)
        print(f"wrote {args.report_json}")
    print(f"=== {args.check}: {'PASS' if result.get('passed') else 'FAIL'} ===")
    return 0 if result.get("passed") else 1


if __name__ == "__main__":
    sys.exit(main())
