# ---------------------------------------------------------------------------
# OUT OF DATE as of the OCR/watermarking merge -- this script stages its
# fixtures by writing straight into MinIO, and both halves of that are gone:
#
#   1. MinIO is removed. Objects go through ObjectStorageServiceFactory
#      (SeaweedFS via the S3 adapter locally); there is no `minio` compose
#      service to write to any more.
#   2. Every stored object is ENCRYPTED AT REST. A fixture written straight
#      into a bucket would be plaintext where ciphertext is expected, and
#      ocr-worker now fetches documents from dms-core's internal endpoint
#      rather than from storage -- so it would be handed bytes it cannot
#      decrypt and fail somewhere unhelpful.
#
# Staging a fixture now means going through the upload path, which is the
# only thing that produces a correctly encrypted object, a scan verdict and
# a servable version: POST /api/v1/documents. That path publishes
# document.version.ocr.requested with its read grant by itself, so most of
# what this script does by hand is no longer needed.
#
# Left runnable rather than disabled, deliberately: how (and whether) to
# rebuild these harnesses is a call for whoever uses them. See
# docs/ocr/internal-endpoint.md for the shape they would target.
# ---------------------------------------------------------------------------
"""Phase 2 Step 3 -- the one thing the eval harness cannot prove.

`eval/run.py` drives the real `OcrPipeline` but builds it itself, in its
own process, against in-memory SQLite and an in-memory object store. It
therefore never exercises `worker_pool.py`'s pool-worker initializer, the
RabbitMQ path, or what actually lands in `ocr_outputs`.

This stages one genuinely rotated page in Postgres + MinIO, publishes
`document.version.ocr.requested` through `outbox_events` exactly as
dms-core's own OutboxRelay does, waits for the terminal state, and then
reads the stored output JSON back out of MinIO to check that the
orientation decision is really in it and that the page was really read.

Run from inside the compose network:

    docker run --rm --network local_default -v "$(pwd):/repo" \
        ocr-worker-test:latest \
        python /repo/scripts/dev/step3_orientation_live_check.py
"""
from __future__ import annotations

import io
import json
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, "/repo/scripts/dev")

import psycopg
from minio import Minio

from phase1_verification import bootstrap_item, bootstrap_shared, publish_via_outbox

DB_URL = "postgresql://dms_core_user:dms_core_pass@postgres:5432/dms_core_db"
CORPUS = Path("/repo/backend/workers/ocr-worker/eval/corpus")
# 90 degrees clockwise, a real raster rotation with no PDF /Rotate hint --
# the case the classifier has to catch on its own.
FIXTURE = CORPUS / "invoice-rotated_90-00.png"


def main() -> int:
    run_id = f"step3-{uuid.uuid4().hex[:8]}"
    minio = Minio("minio:9000", access_key="minioadmin", secret_key="minioadmin", secure=False)

    with psycopg.connect(DB_URL) as conn:
        shared = bootstrap_shared(conn, run_id)
        item = bootstrap_item(
            conn, shared, run_id, item_key="rotated90", filename=FIXTURE.name, mime_type="image/png"
        )

        data = FIXTURE.read_bytes()
        minio.put_object(
            "oakdms-documents", item["storage_object_key"], io.BytesIO(data), len(data),
            content_type="image/png",
        )
        publish_via_outbox(conn, item)
        print(f"published version {item['version_id']} ({FIXTURE.name}, {len(data)} bytes)", flush=True)

        deadline = time.time() + 600
        status = ref = err = None
        while time.time() < deadline:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT v.ocr_status, o.extracted_text_ref, j.last_error "
                    "FROM document_versions v "
                    "LEFT JOIN ocr_outputs o ON o.version_id = v.version_id "
                    "LEFT JOIN LATERAL (SELECT last_error FROM processing_jobs WHERE reference_type = 'document_versions' AND reference_id = v.version_id AND job_type = 'ocr' ORDER BY created_at DESC LIMIT 1) j ON TRUE "
                    "WHERE v.version_id = %s",
                    (item["version_id"],),
                )
                row = cur.fetchone()
            conn.rollback()
            status, ref, err = row if row else (None, None, None)
            if status in ("completed", "failed"):
                break
            time.sleep(3)

        print(f"ocr_status={status} last_error={err}")
        if status != "completed" or not ref:
            return 1

        obj = minio.get_object("oakdms-ocr-output", ref)
        output = json.loads(obj.read())
        obj.close()
        obj.release_conn()

    page = output["pages"][0]
    print(f"schema_version={output.get('schema_version')}")
    print(f"method={page['method']}  geometry={page.get('geometry')}")
    print(f"preprocessing={json.dumps(page.get('preprocessing'))}")
    print(f"blocks={len(page['blocks'])}  first={json.dumps(page['blocks'][0])[:160] if page['blocks'] else None}")
    print("--- text (first 300 chars) ---")
    print(output["text"][:300])

    orientation = (page.get("preprocessing") or {}).get("orientation")
    ok = (
        orientation is not None
        and orientation["angle"] == 90
        and orientation["applied"] is True
        and len(page["blocks"]) > 10
        and "INV-" in output["text"]
    )
    # Every bbox must sit inside the page as STORED (portrait here, since
    # a 90-degree rotation swapped it) -- the whole point of mapping back.
    geometry = page["geometry"]
    out_of_page = [
        b["bbox"] for b in page["blocks"]
        if b["bbox"][2] > geometry["width_px"] + 1 or b["bbox"][3] > geometry["height_px"] + 1
        or b["bbox"][0] < -1 or b["bbox"][1] < -1
    ]
    print(f"blocks outside the stored page: {len(out_of_page)} {out_of_page[:3]}")
    print("RESULT:", "OK" if ok and not out_of_page else "FAILED")
    return 0 if (ok and not out_of_page) else 1


if __name__ == "__main__":
    sys.exit(main())
