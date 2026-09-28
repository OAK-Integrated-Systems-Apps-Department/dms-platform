"""Live end-to-end run through the RUNNING stack: MinIO + Postgres +
ocr-worker (PaddleOCR/Docling) + the `llm` service (Qwen via Ollama).

For each document: fixture rows in a dedicated test tenant (never a real
tenant) -> file into MinIO -> POST /ocr/process/{version_id} with requested
fields, exactly as dms-core's OcrWorkerClient calls it -> wait until
document_versions.ocr_status = 'completed' (it stays 'processing' while
Qwen refines the requested fields) -> read back what the DMS shows.

Writes, per document:
  <stem>.liveA.json  the rule-based field values from the /ocr/process
                     response (PaddleOCR + Docling + existing extraction)
  <stem>.liveB.json  what the DMS shows after Qwen: document_ocr_field_results
                     for the requested fields, plus Qwen's tables/sections/
                     document type from GET /ocr/structured/{version_id}
Both in the structured-result shape, so evaluate_quality.py scores them.

Runs in a container on local_default with ocr-worker's environment
(DB_URL, MINIO_*, INTERNAL_SERVICE_TOKEN). usage:
  python live_e2e.py <results_dir> <docs_dir> <stem>[=<file>] ...
"""
import json
import mimetypes
import os
import re
import sys
import time
import urllib.request
import uuid
from pathlib import Path

import psycopg
from minio import Minio

QUALITY = Path(__file__).resolve().parent / "quality"
WORKER = os.environ.get("OCR_WORKER_URL", "http://ocr-worker:8090")
TOKEN = os.environ["INTERNAL_SERVICE_TOKEN"]
TENANT = uuid.UUID("7e2e0000-0000-4000-8000-00000000e2e0")  # "E2E structuring" fixture tenant
USER = uuid.UUID("7e2e0000-0000-4000-8000-00000000e2e1")
STORAGE_LOCATION = uuid.UUID("00000000-0000-0000-0000-000000000002")  # V39 platform default
TYPE_MAP = {"date": "date", "number": "number", "bool": "text", "email": "text", "text": "text"}
TIMEOUT_S = int(os.environ.get("E2E_TIMEOUT_SECONDS", "2400"))


def specs() -> dict:
    data = json.loads((QUALITY / "expected.json").read_text(encoding="utf-8"))
    local = QUALITY / "expected.local.json"
    if local.exists():
        data.update(json.loads(local.read_text(encoding="utf-8")))
    return data


def requested_fields(spec: dict) -> list[dict]:
    out = []
    for entry in spec.get("fields", []) + spec.get("null_fields", []) + spec.get("repeated", []):
        out.append({"field_key": entry["keys"][0].lstrip("="), "data_type": TYPE_MAP.get(entry.get("type", "text"), "text"), "options": []})
    return out


def dsn() -> str:
    return re.sub(r"^postgresql\+psycopg://", "postgresql://", os.environ["DB_URL"])


def bootstrap(conn, stem: str, filename: str, mime: str, size: int) -> dict:
    document_type_id, document_id, version_id = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    key = f"tenants/{TENANT}/documents/{document_id}/versions/{version_id}/{filename}"
    with conn.cursor() as cur:
        cur.execute("INSERT INTO tenants (tenant_id, tenant_name, domain, subscription_tier, status) VALUES (%s, 'E2E Structuring Test Tenant', 'e2e-structuring.test.local', 'standard', 'active') ON CONFLICT DO NOTHING", (TENANT,))
        cur.execute("INSERT INTO users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name, username, status) VALUES (%s, %s, %s, 'e2e@e2e-structuring.test.local', 'E2E Bot', 'E2E', 'Bot', 'e2e-structuring-bot', 'active') ON CONFLICT DO NOTHING", (USER, TENANT, USER))
        cur.execute("INSERT INTO document_types (document_type_id, tenant_id, type_code, type_name, created_by) VALUES (%s, %s, %s, %s, %s)", (document_type_id, TENANT, f"E2E_{uuid.uuid4().hex[:8].upper()}", f"E2E {stem}", USER))
        cur.execute(
            """WITH d AS (INSERT INTO documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id, created_by)
                          VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING document_id)
               INSERT INTO document_versions (version_id, document_id, tenant_id, version_number, major_version, minor_version, storage_location_id,
                                              storage_object_key, original_filename, mime_type, file_hash, size_bytes, created_reason, scan_status, ocr_status, created_by)
               SELECT %s, document_id, %s, 1, 1, 0, %s, %s, %s, %s, 'e2e', %s, 'initial_upload', 'clean', 'processing', %s FROM d""",
            (document_id, TENANT, document_type_id, f"E2E {stem}", version_id, USER, USER,
             version_id, TENANT, STORAGE_LOCATION, key, filename, mime, size, USER),
        )
    conn.commit()
    return {"document_id": document_id, "version_id": version_id, "key": key}


def http(method: str, path: str, body: dict | None = None, timeout: float = 600):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(WORKER + path, data=data, method=method, headers={"X-Internal-Token": TOKEN, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read())


def main():
    results, docs = Path(sys.argv[1]), Path(sys.argv[2])
    all_specs = specs()
    minio = Minio(os.environ["MINIO_ENDPOINT"], access_key=os.environ["MINIO_ACCESS_KEY"], secret_key=os.environ["MINIO_SECRET_KEY"], secure=False)
    bucket = os.environ.get("MINIO_SOURCE_BUCKET", "oakdms-documents")
    print("llm health:", http("GET", "/ocr/structured/health", timeout=30)[1], flush=True)
    summary = []
    with psycopg.connect(dsn()) as conn:
        for arg in sys.argv[3:]:
            stem, _, file_arg = arg.partition("=")
            path = Path(file_arg) if file_arg else next(docs.glob(f"{stem}.*"))
            spec = all_specs[stem]
            spec = all_specs[spec["same_as"]] if "same_as" in spec else spec
            data = path.read_bytes()
            mime = mimetypes.guess_type(path.name.lower())[0]
            ids = bootstrap(conn, stem, path.name, mime, len(data))
            minio.put_object(bucket, ids["key"], data=__import__("io").BytesIO(data), length=len(data), content_type=mime)

            started = time.monotonic()
            status, ocr = http("POST", f"/ocr/process/{ids['version_id']}", {"requested_fields": requested_fields(spec)})
            ocr_ms = int((time.monotonic() - started) * 1000)
            while True:
                with conn.cursor() as cur:
                    cur.execute("SELECT ocr_status FROM document_versions WHERE version_id = %s", (ids["version_id"],))
                    state = cur.fetchone()[0]
                conn.commit()
                if state != "processing" or time.monotonic() - started > TIMEOUT_S:
                    break
                time.sleep(5)
            total_ms = int((time.monotonic() - started) * 1000)
            with conn.cursor() as cur:
                cur.execute("SELECT field_key, value, status, match_basis FROM document_ocr_field_results WHERE version_id = %s", (ids["version_id"],))
                rows = cur.fetchall()
            conn.commit()
            try:
                structured = http("GET", f"/ocr/structured/{ids['version_id']}", timeout=60)[1]
            except urllib.error.HTTPError:
                structured = {"status": "missing", "tables": [], "sections": [], "warnings": [], "stats": {}, "model": {"provider": "?", "name": "?"}}

            def decode(v):
                try:
                    return json.loads(v) if v and v.startswith("[") else v
                except ValueError:
                    return v

            a_fields = {k: (v.get("value") if isinstance(v, dict) else v) for k, v in (ocr.get("fields") or {}).items()}
            for f in requested_fields(spec):
                a_fields.setdefault(f["field_key"], None)
            b_fields = {k: (decode(v) if st != "not_found" else None) for k, v, st, _ in rows}
            base = {"schema_version": 1, "error": None, "warnings": [], "sections": []}
            live_a = {**base, "status": "completed" if ocr.get("pages") else "failed", "document_type": None, "fields": a_fields,
                      "tables": [], "model": {"provider": "none", "name": "live rule-based (/ocr/process response)"}, "stats": {"duration_ms": ocr_ms, "llm_calls": 0}}
            live_b = {**base, "status": structured.get("status"), "document_type": structured.get("document_type"), "fields": b_fields,
                      "tables": structured.get("tables") or [], "sections": structured.get("sections") or [], "warnings": structured.get("warnings") or [],
                      "error": structured.get("error"), "model": structured.get("model"),
                      "stats": {**(structured.get("stats") or {}), "duration_ms": total_ms}, "field_sources": structured.get("field_sources")}
            (results / f"{stem}.liveA.json").write_text(json.dumps(live_a, indent=1, ensure_ascii=False), encoding="utf-8")
            (results / f"{stem}.liveB.json").write_text(json.dumps(live_b, indent=1, ensure_ascii=False), encoding="utf-8")
            record = {"document": stem, "http": status, "final_ocr_status": state, "structured_status": structured.get("status"),
                      "error": (structured.get("error") or {}).get("code"), "ocr_ms": ocr_ms, "total_ms": total_ms,
                      "llm_fields": sum(1 for v in (structured.get("field_sources") or {}).values() if v == "llm"), "requested": len(requested_fields(spec))}
            summary.append(record)
            print(json.dumps(record), flush=True)
    (results / "live_summary.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
