#!/usr/bin/env python
"""Required verification script for ocr-worker (see backend/workers/ocr-worker/README.md
"Required verification"): runs each real fixture in tests/fixtures/ through the
actual running service via its dev-trigger endpoint and prints the real output
JSON to stdout -- proof PaddleOCR/Docling are genuinely invoked, not a
description of what the output "should" look like.

ocr-worker has no published host port (internal-only, see README "Auth &
service exposure"), so this must run on the same docker network as the
compose stack, not from the bare host:

    cd infra/local
    docker compose up -d postgres rabbitmq minio ocr-worker
    docker run --rm --network local_default \\
      -v "$(pwd)/../../backend/workers/ocr-worker/tests/fixtures:/fixtures" \\
      -v "$(pwd)/../../scripts:/scripts" \\
      -e INTERNAL_SERVICE_TOKEN=<value from infra/secrets/ocr-worker.secrets.env> \\
      python:3.12-slim bash -c "
        pip install --quiet psycopg[binary] minio requests
        python /scripts/dev/smoke_test_ocr.py --fixtures-dir /fixtures
      "

Each fixture is bootstrapped as its own document/version (scan_status
already 'clean', same idea as publish_fake_scan_event.py) under a document
type with NO zone template configured, so the full-document native-text-
vs-PaddleOCR decision logic (README "Native-text vs. PaddleOCR decision
logic") is what actually runs, end to end, against the real service.
"""
from __future__ import annotations

import argparse
import json
import sys
import uuid
from pathlib import Path

import psycopg
import requests
from minio import Minio

FIXTURE_NAMESPACE = uuid.UUID("2f6a1a1a-4b7a-4a0a-9c0a-1a2b3c4d5e6f")
DOCUMENT_TYPE_NAME = "smoke-test-doc"

FIXTURES = [
    ("native_text.pdf", "application/pdf"),
    ("scanned_image_only.pdf", "application/pdf"),
    ("scanned_image.png", "image/png"),
]


def fixture_id(name: str) -> uuid.UUID:
    return uuid.uuid5(FIXTURE_NAMESPACE, name)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fixtures-dir", type=Path, default=Path(__file__).resolve().parent.parent.parent / "backend" / "workers" / "ocr-worker" / "tests" / "fixtures")
    parser.add_argument("--db-url", default="postgresql://dms_core_user:dms_core_pass@postgres:5432/dms_core_db")
    parser.add_argument("--minio-endpoint", default="minio:9000")
    parser.add_argument("--minio-access-key", default="minioadmin")
    parser.add_argument("--minio-secret-key", default="minioadmin")
    parser.add_argument("--minio-source-bucket", default="oakdms-documents")
    parser.add_argument("--ocr-worker-url", default="http://ocr-worker:8090")
    parser.add_argument("--internal-token", default=None, help="Defaults to $INTERNAL_SERVICE_TOKEN")
    return parser.parse_args()


def bootstrap_fixture(conn: psycopg.Connection, *, fixture_name: str, mime_type: str, original_filename: str) -> dict:
    tenant_id = fixture_id("smoke:tenant")
    user_id = fixture_id("smoke:user")
    keycloak_id = fixture_id("smoke:keycloak")
    document_type_id = fixture_id(f"smoke:document_type:{DOCUMENT_TYPE_NAME}")
    document_id = fixture_id(f"smoke:document:{fixture_name}")
    version_id = fixture_id(f"smoke:version:{fixture_name}")
    storage_object_key = f"tenants/{tenant_id}/documents/{document_id}/versions/{version_id}/{original_filename}"

    with conn.cursor() as cur:
        cur.execute("SELECT 1 FROM document_versions WHERE version_id = %s", (version_id,))
        if cur.fetchone() is not None:
            cur.execute("UPDATE document_versions SET scan_status = 'clean' WHERE version_id = %s", (version_id,))
            conn.commit()
            return {"tenant_id": tenant_id, "document_id": document_id, "version_id": version_id, "storage_object_key": storage_object_key}

        cur.execute(
            "INSERT INTO tenants (tenant_id, tenant_name, domain, subscription_tier, status) "
            "VALUES (%s, 'OCR Smoke Test Tenant', 'ocr-smoke-test.local', 'standard', 'active') "
            "ON CONFLICT (tenant_id) DO NOTHING",
            (tenant_id,),
        )
        cur.execute(
            "INSERT INTO users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name, username, status) "
            "VALUES (%s, %s, %s, 'ocr-smoke-test@local', 'OCR Smoke Test User', 'OCR', 'Smoke Test', 'ocr-smoke-test', 'active') "
            "ON CONFLICT (user_id) DO NOTHING",
            (user_id, tenant_id, keycloak_id),
        )
        cur.execute(
            "INSERT INTO document_types (document_type_id, tenant_id, document_type_name, created_by) "
            "VALUES (%s, %s, %s, %s) ON CONFLICT (document_type_id) DO NOTHING",
            (document_type_id, tenant_id, DOCUMENT_TYPE_NAME, user_id),
        )
        # documents.latest_version_id and document_versions.document_id are
        # mutually NOT NULL foreign keys -- one statement (a CTE), not two
        # separate INSERTs, so Postgres's per-statement FK check sees both
        # rows by the time it runs. See publish_fake_scan_event.py's
        # bootstrap_fixture for the full explanation.
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
            SELECT %s, document_id, %s, 1, 1, 0, %s, %s, %s, 'smoke-test', 0, 'initial_upload', 'clean', 'pending', %s
            FROM new_document
            """,
            (
                document_id, tenant_id, document_type_id, f"Smoke test: {fixture_name}", version_id, user_id, user_id,
                version_id, tenant_id, storage_object_key, original_filename, mime_type, user_id,
            ),
        )
    conn.commit()
    return {"tenant_id": tenant_id, "document_id": document_id, "version_id": version_id, "storage_object_key": storage_object_key}


def main() -> int:
    args = parse_args()
    token = args.internal_token or __import__("os").environ.get("INTERNAL_SERVICE_TOKEN")
    if not token:
        print("ERROR: --internal-token or $INTERNAL_SERVICE_TOKEN is required", file=sys.stderr)
        return 1

    minio_client = Minio(args.minio_endpoint, access_key=args.minio_access_key, secret_key=args.minio_secret_key, secure=False)
    if not minio_client.bucket_exists(args.minio_source_bucket):
        minio_client.make_bucket(args.minio_source_bucket)

    exit_code = 0
    with psycopg.connect(args.db_url) as conn:
        for filename, mime_type in FIXTURES:
            path = args.fixtures_dir / filename
            if not path.exists():
                print(f"ERROR: missing fixture {path}", file=sys.stderr)
                exit_code = 1
                continue

            print(f"\n{'=' * 70}\nFIXTURE: {filename}\n{'=' * 70}")
            file_bytes = path.read_bytes()
            fixture = bootstrap_fixture(conn, fixture_name=filename, mime_type=mime_type, original_filename=filename)
            minio_client.put_object(
                args.minio_source_bucket, fixture["storage_object_key"],
                data=__import__("io").BytesIO(file_bytes), length=len(file_bytes), content_type=mime_type,
            )

            response = requests.post(
                f"{args.ocr_worker_url}/ocr/process/{fixture['version_id']}",
                headers={"X-Internal-Token": token},
                timeout=120,
            )
            print(f"HTTP {response.status_code}")
            body = response.json()
            print(json.dumps(body, indent=2))

            if response.status_code != 200 or body.get("status") == "failed":
                exit_code = 1

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
