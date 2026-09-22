#!/usr/bin/env python
"""Stand-in for scan-worker, which does not exist yet (see
backend/workers/ocr-worker/README.md and docs/events/ocr-scanning-events.md).

Nothing in this codebase currently flips document_versions.scan_status to
'clean' or publishes a "version is clean" event -- that's the real-world
trigger ocr-worker's RabbitMQ consumer is built against. This script plays
that producer's part by hand, end to end:

  1. Bootstraps (idempotently) a minimal tenant/user/document_type/document/
     document_version fixture chain directly in Postgres, with
     scan_status='clean' -- OR, given --version-id, just flips an existing
     row to scan_status='clean'.
  2. Uploads a sample file (a synthetic "invoice" image if --file isn't
     given) to MinIO at that version's storage_object_key.
  3. Publishes a document.version.scan.completed message onto the
     oak.dms.events topic exchange, exactly like scan-worker is expected to.

Run it against the local docker-compose stack (needs postgres, rabbitmq,
and minio up) from ocr-worker's own virtualenv, since it reuses that
service's dependencies (psycopg, pika, minio, Pillow):

    cd backend/workers/ocr-worker
    pip install -e ".[dev]"
    python ../../../scripts/dev/publish_fake_scan_event.py

Then either let ocr-worker's queue consumer pick the event up, or skip
RabbitMQ entirely and call the dev-trigger endpoint directly:

    curl -X POST http://localhost:8090/ocr/process/<version_id>
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import uuid
from pathlib import Path

import pika
import psycopg
from minio import Minio

# Fixed namespace so re-running this script against the same DB reuses the
# same fixture rows (via uuid5) instead of piling up duplicates every run.
FIXTURE_NAMESPACE = uuid.UUID("6f8f5f2a-6b3a-4a34-9f0a-0f6a6b1f2c3d")

EXCHANGE_NAME = "oak.dms.events"
ROUTING_KEY = "document.version.scan.completed"


def fixture_id(name: str) -> uuid.UUID:
    return uuid.uuid5(FIXTURE_NAMESPACE, name)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db-url", default="postgresql://dms_core_user:dms_core_pass@localhost:5433/dms_core_db")
    parser.add_argument("--rabbitmq-url", default="amqp://dms:changeme@localhost:5672/")
    parser.add_argument("--minio-endpoint", default="localhost:9000")
    parser.add_argument("--minio-access-key", default="minioadmin")
    parser.add_argument("--minio-secret-key", default="minioadmin")
    parser.add_argument("--minio-secure", action="store_true")
    parser.add_argument("--minio-source-bucket", default="oakdms-documents")
    parser.add_argument("--document-type", default="invoice", help="document_types.type_name (type_code is this, uppercased); needs a matching document_type_ocr_zones row (docs/database/V11) to exercise zone OCR")
    parser.add_argument("--file", type=Path, default=None, help="Local file to upload as the source document; a synthetic sample invoice image is generated if omitted")
    parser.add_argument("--version-id", default=None, help="Reuse an existing document_versions row instead of bootstrapping a new fixture chain")
    parser.add_argument("--fixture-name", default="ocr-dev-fixture", help="Distinguishes independent fixture chains from repeated runs")
    return parser.parse_args()


def bootstrap_fixture(conn: psycopg.Connection, *, fixture_name: str, document_type_name: str, mime_type: str, original_filename: str) -> dict:
    tenant_id = fixture_id(f"{fixture_name}:tenant")
    user_id = fixture_id(f"{fixture_name}:user")
    document_type_id = fixture_id(f"{fixture_name}:document_type:{document_type_name}")
    document_id = fixture_id(f"{fixture_name}:document")
    version_id = fixture_id(f"{fixture_name}:version")
    keycloak_id = fixture_id(f"{fixture_name}:keycloak")
    storage_object_key = f"tenants/{tenant_id}/documents/{document_id}/versions/{version_id}/{original_filename}"

    with conn.cursor() as cur:
        cur.execute("SELECT 1 FROM document_versions WHERE version_id = %s", (version_id,))
        already_exists = cur.fetchone() is not None

        if already_exists:
            cur.execute("UPDATE document_versions SET scan_status = 'clean' WHERE version_id = %s", (version_id,))
            conn.commit()
            return {"tenant_id": tenant_id, "document_id": document_id, "version_id": version_id, "storage_object_key": storage_object_key}

        cur.execute(
            """
            INSERT INTO tenants (tenant_id, tenant_name, domain, subscription_tier, status)
            VALUES (%s, %s, %s, 'standard', 'active')
            ON CONFLICT (tenant_id) DO NOTHING
            """,
            (tenant_id, "OCR Dev Tenant", f"{fixture_name}.ocr-dev.local"),
        )
        cur.execute(
            """
            INSERT INTO users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name, username, status)
            VALUES (%s, %s, %s, %s, 'OCR Dev User', 'OCR', 'Dev User', %s, 'active')
            ON CONFLICT (user_id) DO NOTHING
            """,
            (user_id, tenant_id, keycloak_id, f"{fixture_name}@ocr-dev.local", fixture_name),
        )
        cur.execute(
            """
            INSERT INTO document_types (document_type_id, tenant_id, type_code, type_name, created_by)
            VALUES (%s, %s, %s, %s, %s)
            ON CONFLICT (document_type_id) DO NOTHING
            """,
            (document_type_id, tenant_id, document_type_name.upper(), document_type_name, user_id),
        )

        # documents.latest_version_id and document_versions.document_id are
        # both NOT NULL foreign keys into each other (see V4's own comment
        # on the circular reference between the two tables). Postgres only
        # defers a NOT DEFERRABLE FK check to the end of the *statement* it
        # was violated in, not the end of the row -- so both rows have to
        # be created by ONE statement for either check to see the other
        # row in time. A CTE chaining the two INSERTs is exactly that.
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
            SELECT %s, document_id, %s, 1, 1, 0, %s, %s, %s, 'dev-fixture', 0, 'initial_upload', 'clean', 'pending', %s
            FROM new_document
            """,
            (
                document_id, tenant_id, document_type_id, f"Sample {document_type_name}", version_id, user_id, user_id,
                version_id, tenant_id, storage_object_key, original_filename, mime_type, user_id,
            ),
        )
    conn.commit()

    return {"tenant_id": tenant_id, "document_id": document_id, "version_id": version_id, "storage_object_key": storage_object_key}


def mark_existing_version_clean(conn: psycopg.Connection, version_id: str) -> dict:
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE document_versions SET scan_status = 'clean' WHERE version_id = %s "
            "RETURNING version_id, document_id, tenant_id, storage_object_key",
            (version_id,),
        )
        row = cur.fetchone()
    if row is None:
        raise SystemExit(f"No document_versions row found for version_id={version_id}")
    conn.commit()
    return {"version_id": row[0], "document_id": row[1], "tenant_id": row[2], "storage_object_key": row[3]}


def sample_invoice_image_bytes() -> bytes:
    """A 1600x2000 synthetic "scanned" invoice with each field's text placed
    to actually land inside the matching zone from whatever
    document_type_ocr_zones rows (docs/database/V11) are configured for
    the "invoice" document type -- these pixel boxes are hand-picked to
    match this script's own historical zone layout (invoice_number top
    right, supplier_name top left, total_amount bottom right) and need to
    stay in sync by hand with whatever rows actually exist in the DB.
    """
    from PIL import Image, ImageDraw, ImageFont

    width, height = 1600, 2000
    image = Image.new("RGB", (width, height), color="white")
    draw = ImageDraw.Draw(image)

    try:
        font = ImageFont.load_default(size=28)
    except TypeError:  # older Pillow without the `size` kwarg
        font = ImageFont.load_default()

    def draw_in_zone(x0_frac, y0_frac, x1_frac, y1_frac, text, *, padding=12):
        x0, y0 = round(x0_frac * width) + padding, round(y0_frac * height) + padding
        draw.text((x0, y0), text, fill="black", font=font)

    draw_in_zone(0.03, 0.04, 0.5, 0.14, "Acme Supplies Ltd.")
    draw_in_zone(0.68, 0.04, 0.98, 0.11, "INV-58421")
    draw_in_zone(0.68, 0.12, 0.98, 0.18, "2026-01-15")
    draw_in_zone(0.62, 0.86, 0.98, 0.94, "Total Due: 850.00")

    # Body text outside any configured zone, so full-page OCR (the
    # fallback path for document types with no template) still has
    # something realistic to read.
    body_lines = [
        "Bill To: Oak DMS Test Account",
        "",
        "Description                Amount",
        "Widgets (x100)              820.00",
        "Shipping                    30.00",
    ]
    draw.text((80, 500), "\n".join(body_lines), fill="black", font=font)

    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def upload_source_file(args: argparse.Namespace, storage_object_key: str, file_bytes: bytes, content_type: str) -> None:
    client = Minio(args.minio_endpoint, access_key=args.minio_access_key, secret_key=args.minio_secret_key, secure=args.minio_secure)
    if not client.bucket_exists(args.minio_source_bucket):
        client.make_bucket(args.minio_source_bucket)
    client.put_object(args.minio_source_bucket, storage_object_key, io.BytesIO(file_bytes), length=len(file_bytes), content_type=content_type)


def publish_scan_completed_event(rabbitmq_url: str, payload: dict) -> None:
    connection = pika.BlockingConnection(pika.URLParameters(rabbitmq_url))
    try:
        channel = connection.channel()
        channel.exchange_declare(exchange=EXCHANGE_NAME, exchange_type="topic", durable=True)
        channel.basic_publish(
            exchange=EXCHANGE_NAME,
            routing_key=ROUTING_KEY,
            body=json.dumps(payload).encode("utf-8"),
            properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
        )
    finally:
        connection.close()


def main() -> None:
    args = parse_args()

    if args.file is not None:
        file_bytes = args.file.read_bytes()
        mime_type = {
            ".pdf": "application/pdf", ".tif": "image/tiff", ".tiff": "image/tiff",
            ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
        }.get(args.file.suffix.lower(), "application/octet-stream")
        original_filename = args.file.name
    else:
        file_bytes = sample_invoice_image_bytes()
        mime_type = "image/png"
        original_filename = "sample-invoice.png"

    with psycopg.connect(args.db_url) as conn:
        if args.version_id:
            fixture = mark_existing_version_clean(conn, args.version_id)
        else:
            fixture = bootstrap_fixture(
                conn,
                fixture_name=args.fixture_name,
                document_type_name=args.document_type,
                mime_type=mime_type,
                original_filename=original_filename,
            )

    upload_source_file(args, fixture["storage_object_key"], file_bytes, mime_type)

    correlation_id = uuid.uuid4()
    payload = {
        "schema_version": 1,
        "event_type": ROUTING_KEY,
        "tenant_id": str(fixture["tenant_id"]),
        "document_id": str(fixture["document_id"]),
        "version_id": str(fixture["version_id"]),
        "scan_status": "clean",
        "correlation_id": str(correlation_id),
    }
    publish_scan_completed_event(args.rabbitmq_url, payload)

    print(f"Published {ROUTING_KEY} for version_id={fixture['version_id']}")
    print(f"  storage_object_key: {fixture['storage_object_key']}")
    print(f"  correlation_id:     {correlation_id}")
    print()
    print("If ocr-worker's consumer is running, it will pick this up automatically.")
    print("Otherwise, exercise the pipeline directly via the dev-trigger endpoint:")
    print(f"  curl -X POST http://localhost:8090/ocr/process/{fixture['version_id']}")


if __name__ == "__main__":
    try:
        main()
    except psycopg.OperationalError as exc:
        print(f"Could not connect to Postgres: {exc}", file=sys.stderr)
        sys.exit(1)
