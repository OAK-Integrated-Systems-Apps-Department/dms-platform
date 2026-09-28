from __future__ import annotations

import sys
import uuid
from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.models import (  # noqa: E402
    Base,
    Document,
    DocumentType,
    DocumentTypeOcrZone,
    DocumentVersion,
    MetadataField,
    MetadataFieldLabelAlias,
)


@pytest.fixture()
def session_factory():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, expire_on_commit=False)


@pytest.fixture()
def seeded_version(session_factory):
    """A clean, OCR-eligible document_versions row (+ its document and
    document_type) with type_name='invoice' and NO zone template
    seeded -- tests that want zones use `seed_zone` themselves so the
    "no template configured" (full-document) path is this fixture's
    default, matching most tests' needs.
    """
    tenant_id = uuid.uuid4()
    document_type_id = uuid.uuid4()
    document_id = uuid.uuid4()
    version_id = uuid.uuid4()

    with session_factory() as session:
        session.add(DocumentType(document_type_id=document_type_id, tenant_id=tenant_id, type_name="invoice"))
        session.add(Document(document_id=document_id, tenant_id=tenant_id, document_type_id=document_type_id, title="Sample Invoice"))
        session.add(
            DocumentVersion(
                version_id=version_id,
                document_id=document_id,
                tenant_id=tenant_id,
                storage_object_key=f"tenants/{tenant_id}/{version_id}/original.pdf",
                original_filename="invoice.pdf",
                mime_type="application/pdf",
                scan_status="clean",
                ocr_status="pending",
            )
        )
        session.commit()

    return {"tenant_id": tenant_id, "document_id": document_id, "document_type_id": document_type_id, "version_id": version_id}


def seed_zone(session_factory, *, tenant_id, document_type_id, zone_name, field_name, page=0, x0=0.0, y0=0.0, x1=1.0, y1=1.0):
    """Test helper mirroring docs/database/V11__create_ocr_templates.sql --
    inserts one document_type_ocr_zones row so pipeline tests can exercise
    the zone-OCR path without a JSON config file.
    """
    with session_factory() as session:
        session.add(
            DocumentTypeOcrZone(
                tenant_id=tenant_id,
                document_type_id=document_type_id,
                zone_name=zone_name,
                field_name=field_name,
                page_number=page,
                x0=x0, y0=y0, x1=x1, y1=y1,
            )
        )
        session.commit()


def seed_metadata_field(session_factory, *, tenant_id, field_key, label, is_deleted=False):
    """Test helper mirroring one dms-core metadata_fields row (V28) -- only
    the columns ocr-worker maps. Returns the new field_id."""
    field_id = uuid.uuid4()
    with session_factory() as session:
        session.add(MetadataField(field_id=field_id, tenant_id=tenant_id, field_key=field_key, label=label, is_deleted=is_deleted))
        session.commit()
    return field_id


def seed_label_aliases(session_factory, *, tenant_id, field_id, aliases, document_type_id=None):
    """Test helper mirroring dms-core's metadata_field_label_aliases writes
    (V40): one row per hint, display_order = (index + 1) * 10 in list order,
    document_type_id None for the field's global hints."""
    with session_factory() as session:
        for index, alias in enumerate(aliases):
            session.add(
                MetadataFieldLabelAlias(
                    tenant_id=tenant_id,
                    field_id=field_id,
                    document_type_id=document_type_id,
                    alias_text=alias,
                    display_order=(index + 1) * 10,
                )
            )
        session.commit()
