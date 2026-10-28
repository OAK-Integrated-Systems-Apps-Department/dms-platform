from __future__ import annotations

import sys
import uuid
from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool
from sqlalchemy.orm import sessionmaker

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.models import Base, Document, DocumentType, DocumentTypeOcrZone, DocumentVersion  # noqa: E402


@pytest.fixture()
def session_factory():
    """StaticPool + check_same_thread=False, not SQLAlchemy's default for
    an in-memory SQLite URL.

    The default (SingletonThreadPool) hands every *thread* its own fresh
    connection -- and for `:memory:` a connection IS the database, so a
    session opened on a second thread sees an empty schema. That is only a
    test artifact (Postgres has no such behavior), but it would make the
    per-document hard-timeout watchdog -- which deliberately writes its
    failure record from its own thread, see deadline.py -- untestable
    here. StaticPool shares the one connection across threads instead.
    """
    engine = create_engine(
        "sqlite:///:memory:", poolclass=StaticPool, connect_args={"check_same_thread": False}
    )
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
