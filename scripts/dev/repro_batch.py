#!/usr/bin/env python
"""Section-1 reproduction batch: bootstraps every fixture as its own
document/version (same pattern as scripts/dev/smoke_test_ocr.py) and drives
it through the real running ocr-worker via its dev-trigger endpoint.

Modes:
  --mode sequential   one request at a time, full JSON + timing printed
  --mode concurrent   fires --concurrency requests in parallel against
                       DISTINCT fixtures/version_ids, for hypothesis 2/reaper
                       -style races and peak-memory measurement

Run from inside the ocr-worker container's network (see smoke_test_ocr.py's
own docstring for the docker run invocation this mirrors).
"""
from __future__ import annotations

import argparse
import concurrent.futures
import io
import json
import sys
import time
import uuid
from pathlib import Path

import psycopg
import requests
from minio import Minio

FIXTURE_NAMESPACE = uuid.UUID("2f6a1a1a-4b7a-4a0a-9c0a-1a2b3c4d5e6f")
DOCUMENT_TYPE_NAME = "ocr-repro-doc"

FIXTURES = [
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
    ("sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
]


def fixture_id(name: str) -> uuid.UUID:
    return uuid.uuid5(FIXTURE_NAMESPACE, name)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fixtures-dir", type=Path, required=True)
    parser.add_argument("--db-url", default="postgresql://dms_core_user:dms_core_pass@postgres:5432/dms_core_db")
    parser.add_argument("--minio-endpoint", default="minio:9000")
    parser.add_argument("--minio-access-key", default="minioadmin")
    parser.add_argument("--minio-secret-key", default="minioadmin")
    parser.add_argument("--minio-source-bucket", default="oakdms-documents")
    parser.add_argument("--ocr-worker-url", default="http://ocr-worker:8090")
    parser.add_argument("--internal-token", default=None)
    parser.add_argument("--mode", choices=["sequential", "concurrent"], default="sequential")
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--only", nargs="*", help="restrict to these filenames")
    parser.add_argument("--results-json", type=Path, default=None, help="append one JSON line per result")
    return parser.parse_args()


def bootstrap_fixture(conn: psycopg.Connection, *, fixture_name: str, mime_type: str, original_filename: str) -> dict:
    tenant_id = fixture_id("repro:tenant")
    user_id = fixture_id("repro:user")
    keycloak_id = fixture_id("repro:keycloak")
    document_type_id = fixture_id(f"repro:document_type:{DOCUMENT_TYPE_NAME}")
    document_id = fixture_id(f"repro:document:{fixture_name}")
    version_id = fixture_id(f"repro:version:{fixture_name}")
    storage_object_key = f"tenants/{tenant_id}/documents/{document_id}/versions/{version_id}/{original_filename}"

    with conn.cursor() as cur:
        cur.execute("SELECT 1 FROM document_versions WHERE version_id = %s", (version_id,))
        if cur.fetchone() is not None:
            cur.execute(
                "UPDATE document_versions SET scan_status = 'clean', ocr_status = 'pending' WHERE version_id = %s",
                (version_id,),
            )
            conn.commit()
            return {"tenant_id": tenant_id, "document_id": document_id, "version_id": version_id, "storage_object_key": storage_object_key}

        cur.execute(
            "INSERT INTO tenants (tenant_id, tenant_name, domain, subscription_tier, status) "
            "VALUES (%s, 'OCR Repro Tenant', 'ocr-repro.local', 'standard', 'active') "
            "ON CONFLICT (tenant_id) DO NOTHING",
            (tenant_id,),
        )
        cur.execute(
            "INSERT INTO users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name, username, status) "
            "VALUES (%s, %s, %s, 'ocr-repro@local', 'OCR Repro User', 'OCR', 'Repro', 'ocr-repro', 'active') "
            "ON CONFLICT (user_id) DO NOTHING",
            (user_id, tenant_id, keycloak_id),
        )
        cur.execute(
            "INSERT INTO document_types (document_type_id, tenant_id, type_code, type_name, created_by) "
            "VALUES (%s, %s, %s, %s, %s) ON CONFLICT (document_type_id) DO NOTHING",
            (document_type_id, tenant_id, DOCUMENT_TYPE_NAME.upper(), DOCUMENT_TYPE_NAME, user_id),
        )
        cur.execute(
            """
            WITH new_document AS (
                INSERT INTO documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id, created_by)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                RETURNING document_id
            )
            INSERT INTO document_versions (
                version_id, document_id, tenant_id, version_number, major_version, minor_version,
                storage_object_key, original_filename, mime_type, file_hash, size_bytes,
                created_reason, scan_status, ocr_status, created_by
            )
            SELECT %s, document_id, %s, 1, 1, 0, %s, %s, %s, 'ocr-repro', 0, 'initial_upload', 'clean', 'pending', %s
            FROM new_document
            """,
            (
                document_id, tenant_id, document_type_id, f"OCR repro: {fixture_name}", version_id, user_id, user_id,
                version_id, tenant_id, storage_object_key, original_filename, mime_type, user_id,
            ),
        )
    conn.commit()
    return {"tenant_id": tenant_id, "document_id": document_id, "version_id": version_id, "storage_object_key": storage_object_key}


def run_one(args: argparse.Namespace, token: str, minio_client: Minio, filename: str, mime_type: str) -> dict:
    path = args.fixtures_dir / filename
    result = {"filename": filename, "mime_type": mime_type}
    if not path.exists():
        result["error"] = "fixture file missing"
        return result

    file_bytes = path.read_bytes()
    with psycopg.connect(args.db_url) as conn:
        fixture = bootstrap_fixture(conn, fixture_name=filename, mime_type=mime_type, original_filename=filename)
    minio_client.put_object(
        args.minio_source_bucket, fixture["storage_object_key"],
        data=io.BytesIO(file_bytes), length=len(file_bytes), content_type=mime_type,
    )
    result["version_id"] = str(fixture["version_id"])

    start = time.time()
    try:
        response = requests.post(
            f"{args.ocr_worker_url}/ocr/process/{fixture['version_id']}",
            headers={"X-Internal-Token": token},
            timeout=180,
        )
        result["elapsed_s"] = round(time.time() - start, 1)
        result["http_status"] = response.status_code
        try:
            body = response.json()
        except Exception:
            body = {"raw_text": response.text[:2000]}
        result["body_summary"] = {
            k: body.get(k) for k in ("status", "error", "flags") if k in body
        }
        if "fields" in body:
            result["fields"] = body["fields"]
        if "detail" in body:
            result["detail"] = body["detail"]
    except requests.exceptions.RequestException as exc:
        result["elapsed_s"] = round(time.time() - start, 1)
        result["request_exception"] = f"{type(exc).__name__}: {exc}"

    return result


def main() -> int:
    args = parse_args()
    token = args.internal_token or __import__("os").environ.get("INTERNAL_SERVICE_TOKEN")
    if not token:
        print("ERROR: --internal-token or $INTERNAL_SERVICE_TOKEN is required", file=sys.stderr)
        return 1

    minio_client = Minio(args.minio_endpoint, access_key=args.minio_access_key, secret_key=args.minio_secret_key, secure=False)
    if not minio_client.bucket_exists(args.minio_source_bucket):
        minio_client.make_bucket(args.minio_source_bucket)

    fixtures = FIXTURES if not args.only else [(f, m) for f, m in FIXTURES if f in args.only]

    exit_code = 0
    results: list[dict] = []

    if args.mode == "sequential":
        for filename, mime_type in fixtures:
            print(f"\n{'=' * 70}\nFIXTURE: {filename}\n{'=' * 70}", flush=True)
            r = run_one(args, token, minio_client, filename, mime_type)
            print(json.dumps(r, indent=2, default=str))
            results.append(r)
            if r.get("http_status") != 200 or r.get("request_exception"):
                exit_code = 1
    else:
        print(f"Firing {len(fixtures)} requests with concurrency={args.concurrency}", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            futures = {pool.submit(run_one, args, token, minio_client, f, m): f for f, m in fixtures}
            for fut in concurrent.futures.as_completed(futures):
                r = fut.result()
                print(json.dumps(r, indent=2, default=str), flush=True)
                results.append(r)
                if r.get("http_status") != 200 or r.get("request_exception"):
                    exit_code = 1

    if args.results_json:
        with open(args.results_json, "a") as f:
            for r in results:
                f.write(json.dumps(r, default=str) + "\n")

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
