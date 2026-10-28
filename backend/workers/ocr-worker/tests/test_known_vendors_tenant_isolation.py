"""The known-vendor list is tenant data, and leaking it would be worse
than the problem it solves.

`known_vendor_names` reads vendor names a reviewer confirmed, and
`_known_vendor_match` then writes one of them into another document's
`vendor_supplier_name` field. If the list were ever built across tenants,
tenant A would not merely *see* tenant B's customer list -- A's invoices
would be stamped with B's supplier names, as `extracted`, with a review
flag that says nothing is wrong. That is a cross-tenant data leak wearing
a correctness bug's clothes, which is why it is tested here rather than
left to the `WHERE tenant_id = :tenant_id` being visibly present.

Two layers are covered: the query itself, and the matcher fed by it.
"""

from __future__ import annotations

import sys
import uuid
from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool
from sqlalchemy.orm import sessionmaker

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.extraction import (  # noqa: E402
    FieldSpec,
    extract_requested_fields,
)
from ocr_worker.models import Base, DocumentOcrCorrection  # noqa: E402
from ocr_worker.repository import OcrRepository  # noqa: E402


TENANT_A = uuid.UUID("aaaaaaaa-0000-0000-0000-000000000001")
TENANT_B = uuid.UUID("bbbbbbbb-0000-0000-0000-000000000002")

A_VENDOR = "Santa Dora Hospital (PVT) Ltd"
B_VENDOR = "Northwind Trading Company (Pvt) Ltd"


@pytest.fixture()
def corrections_session():
    engine = create_engine(
        "sqlite:///:memory:", poolclass=StaticPool, connect_args={"check_same_thread": False}
    )
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)

    with factory() as session:
        session.add_all([
            # Tenant A: one corrected, one accepted. `corrected_value` is
            # populated for both (V43), which is what makes this table
            # usable as "what a human settled on".
            DocumentOcrCorrection(
                tenant_id=TENANT_A, field_key="vendor_supplier_name",
                corrected_value=A_VENDOR,
            ),
            DocumentOcrCorrection(
                tenant_id=TENANT_A, field_key="vendor_supplier_name",
                corrected_value="Edirisinghe Ayurveda",
            ),
            # Tenant B, same field, a vendor A has never seen.
            DocumentOcrCorrection(
                tenant_id=TENANT_B, field_key="vendor_supplier_name",
                corrected_value=B_VENDOR,
            ),
            DocumentOcrCorrection(
                tenant_id=TENANT_B, field_key="vendor_supplier_name",
                corrected_value="Globex Shipping PLC",
            ),
            # A different field on tenant A -- must not appear either.
            DocumentOcrCorrection(
                tenant_id=TENANT_A, field_key="invoice_number",
                corrected_value="INV-9001",
            ),
        ])
        session.commit()
    return factory


def _names(factory, tenant_id):
    with factory() as session:
        return OcrRepository(session).known_vendor_names(tenant_id)


# ----------------------------------------------------------------------
# The query
# ----------------------------------------------------------------------
def test_each_tenant_sees_only_its_own_confirmed_vendors(corrections_session):
    assert _names(corrections_session, TENANT_A) == [
        "Edirisinghe Ayurveda",
        A_VENDOR,
    ]
    assert _names(corrections_session, TENANT_B) == [
        "Globex Shipping PLC",
        B_VENDOR,
    ]


def test_tenant_bs_vendors_are_absent_from_tenant_as_list(corrections_session):
    a_list = _names(corrections_session, TENANT_A)
    for leaked in (B_VENDOR, "Globex Shipping PLC"):
        assert leaked not in a_list


def test_another_fields_corrections_are_not_vendors(corrections_session):
    """`field_key` is part of the filter, not an assumption. A corrected
    invoice number is a confirmed value too, and it is not a vendor."""
    assert "INV-9001" not in _names(corrections_session, TENANT_A)


def test_an_unknown_tenant_gets_an_empty_list_not_everything(corrections_session):
    """The failure mode worth naming: a missing or mistyped tenant id must
    return nothing, never fall back to the whole table."""
    assert _names(corrections_session, uuid.uuid4()) == []


def test_null_and_blank_corrected_values_are_skipped(corrections_session):
    with corrections_session() as session:
        session.add_all([
            DocumentOcrCorrection(
                tenant_id=TENANT_A, field_key="vendor_supplier_name", corrected_value=None,
            ),
            DocumentOcrCorrection(
                tenant_id=TENANT_A, field_key="vendor_supplier_name", corrected_value="   ",
            ),
        ])
        session.commit()
    assert _names(corrections_session, TENANT_A) == ["Edirisinghe Ayurveda", A_VENDOR]


# ----------------------------------------------------------------------
# The matcher fed by the query
# ----------------------------------------------------------------------
def _vendor_for(page_line: str, known_vendors: list[str]) -> str | None:
    results = extract_requested_fields(
        specs=[FieldSpec("vendor_supplier_name", "text")],
        text=page_line,
        zone_fields={},
        lines_by_page={1: [(page_line, (100, 100, 700, 130))]},
        known_vendors=known_vendors,
    )
    return results[0].value


def test_a_tenant_bs_vendor_is_never_written_onto_a_tenant_a_document(corrections_session):
    """The end the isolation exists to protect. Tenant B has confirmed
    "Northwind Trading Company (Pvt) Ltd"; a tenant A document whose
    letterhead OCR'd imperfectly as "Northwind Trading Company" must NOT
    be completed from B's list.

    The same line against B's own list IS completed -- so this asserts the
    isolation, not that the matcher is broken.
    """
    page_line = "Northwind Trading Company"

    a_list = _names(corrections_session, TENANT_A)
    assert _vendor_for(page_line, a_list) == page_line  # letterhead fallback, uncompleted

    b_list = _names(corrections_session, TENANT_B)
    assert _vendor_for(page_line, b_list) == B_VENDOR  # same line, B's own vendor


def test_the_matcher_with_no_list_at_all_still_reads_the_page():
    """A tenant that has filed nothing yet -- the first document ever
    uploaded -- must still get a vendor, just an uncompleted one."""
    assert _vendor_for("Acme Supplies (Pvt) Ltd", []) == "Acme Supplies (Pvt) Ltd"
