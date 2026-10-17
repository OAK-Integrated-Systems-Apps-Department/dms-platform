from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from conftest import seed_zone  # noqa: E402
from ocr_worker.extraction import FieldSpec  # noqa: E402
from ocr_worker.models import DocumentOcrFieldResult  # noqa: E402
from test_pipeline_transaction import FakeLayoutDocument, make_pipeline  # noqa: E402


# ----------------------------------------------------------------------
# The single hardest requirement of this feature, exercised end-to-end
# through the real pipeline (not just extraction.py in isolation): a
# document that would otherwise trip the legacy invoice-regex fallback
# for several fields must still come back with ONLY the requested field
# in both the HTTP response `fields` map and document_ocr_field_results.
# ----------------------------------------------------------------------
def test_process_version_with_requested_fields_returns_only_those_fields(session_factory, seeded_version):
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(
        session_factory,
        layout_doc=layout_doc,
        ocr_text="Invoice No: INV-4471\nAuthor: Jane Doe\nTotal Due: $500.00",
        ocr_confidence=0.97,
    )

    outcome = pipeline.process_version(
        seeded_version["version_id"], requested_fields=[FieldSpec("author", "text")]
    )

    assert outcome.status == "completed"
    assert set(outcome.output["fields"].keys()) == {"author"}
    assert outcome.output["fields"]["author"]["value"] == "Jane Doe"

    with session_factory() as session:
        rows = session.query(DocumentOcrFieldResult).filter_by(document_id=seeded_version["document_id"]).all()
        assert {r.field_key for r in rows} == {"author"}
        assert rows[0].status == "extracted"


def test_process_version_requested_fields_none_preserves_legacy_behavior(session_factory, seeded_version):
    """Omitting requested_fields entirely (RabbitMQ consumer path, or any
    caller that hasn't adopted field selection yet) must behave exactly as
    before -- the full invoice-regex fallback, and no document_ocr_field_results
    rows at all (that table is only ever written for the new, opt-in path).
    """
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="Invoice No: INV-4471\nTotal Due: $500.00", ocr_confidence=0.97,
    )

    outcome = pipeline.process_version(seeded_version["version_id"])  # requested_fields omitted

    assert outcome.output["fields"]["invoice_number"]["value"] == "INV-4471"
    with session_factory() as session:
        assert session.query(DocumentOcrFieldResult).filter_by(document_id=seeded_version["document_id"]).count() == 0


def test_not_found_field_still_gets_a_row_so_the_ui_can_show_not_found(session_factory, seeded_version):
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="nothing useful in here", ocr_confidence=0.9,
    )

    outcome = pipeline.process_version(
        seeded_version["version_id"], requested_fields=[FieldSpec("author", "text")]
    )

    assert "author" not in outcome.output["fields"]  # not_found fields are omitted from the sparse HTTP dict
    with session_factory() as session:
        row = session.query(DocumentOcrFieldResult).filter_by(document_id=seeded_version["document_id"], field_key="author").one()
        assert row.status == "not_found"
        assert row.value is None


def test_rerun_replaces_the_field_result_row_instead_of_accumulating(session_factory, seeded_version):
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="Author: Jane Doe", ocr_confidence=0.97,
    )
    pipeline.process_version(seeded_version["version_id"], requested_fields=[FieldSpec("author", "text")])

    # second run with different OCR text for the same field
    pipeline2, _, _ = make_pipeline(session_factory, layout_doc=layout_doc, ocr_text="Author: John Smith", ocr_confidence=0.97)
    pipeline2.process_version(seeded_version["version_id"], requested_fields=[FieldSpec("author", "text")])

    with session_factory() as session:
        rows = session.query(DocumentOcrFieldResult).filter_by(document_id=seeded_version["document_id"], field_key="author").all()
        assert len(rows) == 1  # replaced, not accumulated
        assert rows[0].value == "John Smith"


def test_zone_template_field_takes_priority_over_label_proximity_in_the_real_pipeline(session_factory, seeded_version):
    seed_zone(
        session_factory,
        tenant_id=seeded_version["tenant_id"],
        document_type_id=seeded_version["document_type_id"],
        zone_name="author_zone",
        field_name="author",
        page=0, x0=0.0, y0=0.0, x1=0.3, y1=0.1,
    )
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="Jane From Zone", ocr_confidence=0.95,
    )

    outcome = pipeline.process_version(
        seeded_version["version_id"], requested_fields=[FieldSpec("author", "text")]
    )

    assert outcome.output["fields"]["author"]["value"] == "Jane From Zone"
    assert outcome.output["fields"]["author"]["bbox"] is not None
