"""`OcrRepository.resolve_requested_field_specs` -- the claim-time read of
`document_versions.requested_fields` that docs/events/ocr-scanning-events.md
specified as "Phase 1 Decision 1" but that was never implemented.

Its absence is why the real upload path never wrote
`document_ocr_field_results`: the queue consumers dispatched with
`requested_fields=None`, so the pipeline took the legacy generic-regex
branch and `upsert_field_results` was never reached. Everything downstream
(dms-core's OcrEventsConsumer -> applyOcrFields ->
`document_metadata_values`) therefore had nothing to apply.

Runs against a real in-memory SQLite database, same as
test_pipeline_transaction.py, so the joins/filters are actually executed
rather than asserted against mocks.
"""
from __future__ import annotations

import uuid

import pytest

from ocr_worker.models import (
    Base,
    Document,
    DocumentType,
    DocumentTypeSchema,
    DocumentVersion,
    MetadataField,
    MetadataFieldOption,
)
from ocr_worker.repository import OcrRepository

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker


@pytest.fixture()
def schema_session_factory():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, expire_on_commit=False)


def _seed(
    session_factory,
    *,
    requested_fields,
    fields=(("invoice_number", "text"), ("currency", "dropdown")),
    currency_options=("GBP", "USD"),
    current_schema_version=2,
    mapping_schema_version=2,
):
    tenant_id = uuid.uuid4()
    document_type_id = uuid.uuid4()
    document_id = uuid.uuid4()
    version_id = uuid.uuid4()

    with session_factory() as session:
        session.add(DocumentType(
            document_type_id=document_type_id, tenant_id=tenant_id, type_name="invoice",
            current_schema_version=current_schema_version,
        ))
        session.add(Document(document_id=document_id, tenant_id=tenant_id, document_type_id=document_type_id, title="Inv"))
        session.add(DocumentVersion(
            version_id=version_id, document_id=document_id, tenant_id=tenant_id,
            storage_object_key="k", original_filename="inv.pdf", mime_type="application/pdf",
            scan_status="clean", ocr_status="pending", requested_fields=requested_fields,
        ))
        mapped_fields = []
        for order, (field_key, data_type) in enumerate(fields):
            field_id = uuid.uuid4()
            session.add(MetadataField(field_id=field_id, tenant_id=tenant_id, field_key=field_key, data_type=data_type))
            mapped_fields.append({
                "mappingId": str(uuid.uuid4()), "fieldId": str(field_id), "mandatory": False,
                "hidden": False, "requiresBackfill": False, "displayOrder": (order + 1) * 10,
                "fieldGroup": None, "defaultValueOverride": None,
            })
            if data_type == "dropdown":
                for order2, value in enumerate(currency_options):
                    session.add(MetadataFieldOption(
                        tenant_id=tenant_id, field_id=field_id, option_value=value, display_order=order2,
                    ))
        session.add(DocumentTypeSchema(
            tenant_id=tenant_id, document_type_id=document_type_id,
            schema_version=mapping_schema_version, fields=mapped_fields,
        ))
        session.commit()

    return {"tenant_id": tenant_id, "document_type_id": document_type_id, "version_id": version_id}


def _resolve(session_factory, seeded):
    with session_factory() as session:
        repo = OcrRepository(session)
        version = session.get(DocumentVersion, seeded["version_id"])
        return repo.resolve_requested_field_specs(version=version, document_type_id=seeded["document_type_id"])


def test_resolves_each_requested_key_to_its_schema_data_type(schema_session_factory):
    seeded = _seed(schema_session_factory, requested_fields=["invoice_number", "currency"])

    specs = _resolve(schema_session_factory, seeded)

    assert [(s.field_key, s.data_type) for s in specs] == [("invoice_number", "text"), ("currency", "dropdown")]


def test_option_backed_fields_carry_their_active_options_in_display_order(schema_session_factory):
    seeded = _seed(schema_session_factory, requested_fields=["currency"], currency_options=("GBP", "USD", "EUR"))

    specs = _resolve(schema_session_factory, seeded)

    assert specs[0].options == ["GBP", "USD", "EUR"]


def test_non_option_backed_fields_get_no_options(schema_session_factory):
    seeded = _seed(schema_session_factory, requested_fields=["invoice_number"])

    assert _resolve(schema_session_factory, seeded)[0].options is None


def test_empty_requested_fields_means_legacy_behavior_not_extract_nothing(schema_session_factory):
    """`None`, not `[]`. A document uploaded without a `requestedFields`
    parameter must keep getting the legacy zone-template + generic regex
    pass -- returning `[]` here would silently switch it to "extract
    exactly zero fields".
    """
    assert _resolve(schema_session_factory, _seed(schema_session_factory, requested_fields=[])) is None
    assert _resolve(schema_session_factory, _seed(schema_session_factory, requested_fields=None)) is None


def test_requested_fields_stored_as_a_json_string_is_still_decoded(schema_session_factory):
    seeded = _seed(schema_session_factory, requested_fields='["invoice_number"]')

    specs = _resolve(schema_session_factory, seeded)

    assert [s.field_key for s in specs] == ["invoice_number"]


def test_a_key_that_is_not_in_the_current_schema_is_dropped_not_fatal(schema_session_factory):
    """A document type's schema can legitimately be edited between upload
    and processing. Extract what still resolves rather than failing the
    whole document.
    """
    seeded = _seed(schema_session_factory, requested_fields=["invoice_number", "no_such_field"])

    specs = _resolve(schema_session_factory, seeded)

    assert [s.field_key for s in specs] == ["invoice_number"]


def test_fields_mapped_at_an_older_schema_version_are_not_resolved(schema_session_factory):
    """document_type_fields keeps every historical schema version's rows
    side by side; resolving without pinning to document_types
    .current_schema_version would mix them.
    """
    seeded = _seed(
        schema_session_factory, requested_fields=["invoice_number"],
        current_schema_version=3, mapping_schema_version=2,
    )

    assert _resolve(schema_session_factory, seeded) == []


def test_specs_follow_the_requested_order_not_the_schemas(schema_session_factory):
    seeded = _seed(schema_session_factory, requested_fields=["currency", "invoice_number"])

    assert [s.field_key for s in _resolve(schema_session_factory, seeded)] == ["currency", "invoice_number"]
