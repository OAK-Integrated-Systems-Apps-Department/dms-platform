"""Contract test: do the worker's SQLAlchemy models still match the REAL,
Flyway-migrated dms-core schema?

Every other test in this suite runs against an in-memory SQLite database
built from `ocr_worker.models` itself (see conftest.py) - which proves the
worker's own logic is internally consistent, but proves nothing about
whether its models still match what dms-core's migrations actually produce.
That gap is exactly how `document_type_fields` kept existing here for a
real OCR run to crash on (`UndefinedTable`) long after dms-core replaced it
with `document_type_schemas` (one JSONB `fields` array per published
schema version) - every unit test in this suite kept passing throughout,
because none of them touch a real Postgres.

This test does. It is SKIPPED, not failed, when `OCR_CONTRACT_DB_URL` is not
set - a laptop running plain `pytest tests -q` should not need a live,
migrated Postgres. Wire it into CI by running dms-core's Flyway migrations
against a throwaway database (the same way the `integration` job in
`.github/workflows/ci.yml` starts Postgres for dms-core's own *IT tests) and
setting that env var to point at it - nothing here applies migrations
itself, matching this repo's one-way rule that Flyway migrations are
dms-core's, not this worker's, to run.

Standing rule for whoever runs this locally: point OCR_CONTRACT_DB_URL at a
THROWAWAY database, never at a shared dev database - this test's own writes
are harmless (one document type, one schema, one field, one version, all
with random UUIDs) but there is no reason to risk it.
"""
from __future__ import annotations

import os
import uuid

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

from ocr_worker.models import DocumentTypeSchema, DocumentVersion
from ocr_worker.repository import OcrRepository

CONTRACT_DB_URL = os.environ.get("OCR_CONTRACT_DB_URL")

pytestmark = pytest.mark.skipif(
    not CONTRACT_DB_URL,
    reason="OCR_CONTRACT_DB_URL not set - point it at a throwaway, Flyway-migrated Postgres to run this",
)


@pytest.fixture()
def real_session_factory():
    engine = create_engine(CONTRACT_DB_URL)
    return sessionmaker(bind=engine, expire_on_commit=False)


@pytest.fixture(autouse=True)
def _require_flyway_history(real_session_factory):
    """Fail loudly, not with a confusing downstream error, if someone points
    this at a database Flyway never migrated."""
    with real_session_factory() as session:
        try:
            session.execute(text("select 1 from flyway_schema_history limit 1"))
        except Exception as exc:  # noqa: BLE001 - re-raised with context
            pytest.fail(
                f"OCR_CONTRACT_DB_URL does not look like a Flyway-migrated dms-core database: {exc}"
            )


def test_every_mapped_table_and_column_exists_in_the_real_schema(real_session_factory):
    """Generic drift detector: for every ocr_worker model, select every
    mapped column. A renamed/dropped table or column fails here with the
    real `UndefinedTable`/`UndefinedColumn` from Postgres, in CI, instead of
    a worker crashing on a real OCR job in production - which is exactly
    what happened with `document_type_fields` -> `document_type_schemas`.
    """
    import ocr_worker.models as models_module
    from sqlalchemy.orm import class_mapper
    from sqlalchemy import inspect as sa_inspect

    model_classes = [
        obj for obj in vars(models_module).values()
        if isinstance(obj, type) and issubclass(obj, models_module.Base) and obj is not models_module.Base
    ]
    assert model_classes, "no mapped model classes found - the import above is probably stale"

    with real_session_factory() as session:
        failures = []
        for model in model_classes:
            mapper = class_mapper(model)
            columns = [c.key for c in mapper.columns]
            col_list = ", ".join(columns)
            try:
                session.execute(text(f'select {col_list} from {mapper.local_table.name} limit 0'))
            except Exception as exc:  # noqa: BLE001 - collected, not raised immediately
                failures.append(f"{model.__name__} ({mapper.local_table.name}): {exc}")
        assert not failures, "model/schema drift:\n" + "\n".join(failures)


def test_resolve_requested_field_specs_against_a_real_published_schema(real_session_factory):
    """The actual regression: seed exactly what dms-core's upload + schema-
    publish path would have written, through raw SQL that satisfies the
    REAL table's full NOT NULL/FK/trigger shape (the worker's own ORM
    models only map the handful of columns resolution reads - inserting
    through them would violate columns dms-core requires but this worker
    has no reason to know about, e.g. document_versions.storage_location_id
    and the V39 scan-gate trigger). Mirrors dms-core's own
    ScanGateFixture.java test helper. Then runs the real query path
    end-to-end against real Postgres.
    """
    tenant_id = uuid.uuid4()
    user_id = uuid.uuid4()
    document_type_id = uuid.uuid4()
    field_id = uuid.uuid4()
    document_location_id = uuid.uuid4()
    staging_location_id = uuid.uuid4()
    scan_engine_id = uuid.uuid4()
    scan_result_id = uuid.uuid4()
    document_id = uuid.uuid4()
    version_id = uuid.uuid4()
    label = "contract-test"

    with real_session_factory() as session:
        session.execute(text("""
            insert into tenants (tenant_id, tenant_name, domain, subscription_tier)
            values (:id, :name, :domain, 'standard')
        """), {"id": tenant_id, "name": f"{label} {tenant_id}", "domain": f"{label}-{tenant_id}.invalid"})

        session.execute(text("""
            insert into users (user_id, tenant_id, keycloak_id, email, username, first_name, display_name)
            values (:id, :tenant_id, :keycloak_id, :email, :username, 'Contract', 'Contract Test Runner')
        """), {"id": user_id, "tenant_id": tenant_id, "keycloak_id": uuid.uuid4(),
               "email": f"{user_id}@{label}.invalid", "username": str(user_id)})

        session.execute(text("""
            insert into document_types (document_type_id, tenant_id, type_code, type_name, current_schema_version)
            values (:id, :tenant_id, :code, 'Contract Test Invoice', 1)
        """), {"id": document_type_id, "tenant_id": tenant_id, "code": f"CONTRACT_{str(document_type_id)[:8].upper()}"})

        session.execute(text("""
            insert into metadata_fields (field_id, tenant_id, field_key, label, data_type, is_ocr_extractable)
            values (:id, :tenant_id, :key, 'Invoice Number', 'text', true)
        """), {"id": field_id, "tenant_id": tenant_id, "key": "contract_invoice_number"})

        session.add(DocumentTypeSchema(
            tenant_id=tenant_id, document_type_id=document_type_id, schema_version=1,
            fields=[{
                "mappingId": str(uuid.uuid4()), "fieldId": str(field_id), "mandatory": True,
                "hidden": False, "requiresBackfill": False, "displayOrder": 10,
                "fieldGroup": None, "defaultValueOverride": None,
            }],
        ))

        for loc_id, purpose in ((document_location_id, "document"), (staging_location_id, "staging")):
            # tenant_id set: a platform-wide default (tenant_id IS NULL) is
            # unique per purpose (uq_storage_locations_platform_purpose),
            # and a fully-migrated database already seeds one.
            session.execute(text("""
                insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose,
                                                base_path, status)
                values (:id, :tenant_id, :name, 'FILE_SYSTEM', :purpose, :path, 'active')
            """), {"id": loc_id, "tenant_id": tenant_id, "name": f"{label}-{purpose}-{loc_id}",
                   "purpose": purpose, "path": f"/tmp/{label}/{purpose}/{loc_id}"})

        # tenant_id set: a platform-wide default (tenant_id IS NULL) is a
        # singleton (uq_scan_engines_platform_default), and a fully-migrated
        # database already seeds one.
        session.execute(text("""
            insert into scan_engines (scan_engine_id, tenant_id, name, provider, scan_mode, timeout_seconds,
                                       max_file_size_bytes)
            values (:id, :tenant_id, :name, 'CLAMAV', 'pre_store_stream', 60, 104857600)
        """), {"id": scan_engine_id, "tenant_id": tenant_id, "name": f"{label}-engine-{scan_engine_id}"})

        session.execute(text("""
            insert into scan_results (scan_result_id, tenant_id, scan_engine_id, staging_location_id,
                                       original_filename, mime_type, scan_mode, uploaded_by, ingestion_kind,
                                       created_reason, status, document_version_id, file_hash, engine_name,
                                       document_type_id, title, started_at, completed_at, created_by)
            values (:id, :tenant_id, :engine_id, :staging_id, :filename, 'application/pdf', 'pre_store_stream',
                    :user_id, 'new_document', 'initial_upload', 'clean', :version_id, :file_hash, 'clamav',
                    :document_type_id, :filename, now(), now(), :user_id)
        """), {"id": scan_result_id, "tenant_id": tenant_id, "engine_id": scan_engine_id,
               "staging_id": staging_location_id, "filename": "contract.pdf", "user_id": user_id,
               "version_id": version_id, "file_hash": "contracttesthash", "document_type_id": document_type_id})

        # documents.latest_version_id <-> document_versions.document_id are
        # mutual DEFERRABLE FKs (V32) - both inserts share this one
        # transaction, validated only at commit.
        session.execute(text("""
            insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id,
                                    current_version_id, owner_user_id, created_by)
            values (:id, :tenant_id, :document_type_id, 'Contract test document', :version_id, :version_id,
                    :user_id, :user_id)
        """), {"id": document_id, "tenant_id": tenant_id, "document_type_id": document_type_id,
               "version_id": version_id, "user_id": user_id})

        session.execute(text("""
            insert into document_versions (version_id, document_id, tenant_id, version_number, major_version,
                                            minor_version, storage_location_id, storage_object_key,
                                            original_filename, mime_type, file_hash, size_bytes, created_reason,
                                            scan_status, ocr_status, requested_fields, created_by)
            values (:id, :document_id, :tenant_id, 1, 1, 0, :location_id, :key, 'contract.pdf',
                    'application/pdf', :file_hash, 123, 'initial_upload', 'clean', 'pending',
                    :requested_fields, :user_id)
        """), {"id": version_id, "document_id": document_id, "tenant_id": tenant_id,
               "location_id": document_location_id,
               "key": f"documents/{tenant_id}/{document_id}/v1/{version_id}",
               "file_hash": "contracttesthash",
               "requested_fields": '["contract_invoice_number"]', "user_id": user_id})

        session.commit()

        version = session.get(DocumentVersion, version_id)
        repo = OcrRepository(session)
        specs = repo.resolve_requested_field_specs(version=version, document_type_id=document_type_id)

    assert specs is not None
    assert [s.field_key for s in specs] == ["contract_invoice_number"]
    assert specs[0].is_mandatory is True
    assert specs[0].ocr_extractable is True
