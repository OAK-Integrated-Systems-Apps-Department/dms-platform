from __future__ import annotations

import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from conftest import seed_label_aliases, seed_metadata_field, seed_zone  # noqa: E402
from ocr_worker.extraction import FieldSpec  # noqa: E402
from ocr_worker.layout import NativeTextBlock, TableBlock, TableCellBlock  # noqa: E402
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


def test_description_field_ignores_a_real_line_items_tables_own_column_header_end_to_end(session_factory, seeded_version):
    """Real bug found live against an actual purchase order: a line-items
    table's header row structurally marks "Description" as
    column_header=True in Docling's own table model, immediately followed
    by "Size" (also a column header) in the same row -- description's
    bare "description" alias matched that header cell as if it were a
    document-level field label and returned "Size" as the field's
    "extracted" value (see extraction.py's own unit test for the same real
    bbox data in isolation). This exercises the full pipeline.py plumbing
    end-to-end -- _run_full_document must actually collect Docling's
    column_header cells into table_header_texts and pass them through to
    extract_requested_fields, not just extraction.py's own function
    signature accepting the parameter.
    """
    table = TableBlock(
        bbox=(600, 400, 1700, 500),
        num_rows=1,
        num_cols=2,
        cells=[
            TableCellBlock(text="Description", row=0, col=0, row_span=1, col_span=1, bbox=(712, 421, 844, 435), column_header=True, row_header=False),
            TableCellBlock(text="Size", row=0, col=1, row_span=1, col_span=1, bbox=(1003, 421, 1051, 435), column_header=True, row_header=False),
        ],
    )
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [NativeTextBlock(text="Purchase Order 4400051211", bbox=(0, 0, 300, 30))]},
        native_text_lines_by_page={0: [
            NativeTextBlock(text="Description", bbox=(712, 421, 844, 435)),
            NativeTextBlock(text="Size", bbox=(1003, 421, 1051, 435)),
        ]},
        tables_by_page={1: [table]},
    )
    pipeline, storage, ocr_engine = make_pipeline(session_factory, layout_doc=layout_doc)

    outcome = pipeline.process_version(
        seeded_version["version_id"], requested_fields=[FieldSpec("description", "long_text")]
    )

    assert outcome.status == "completed"
    result = outcome.output["fields"]["description"]
    assert result["value"] != "Size"
    assert result["status"] == "low_confidence"  # free-text fallback, not a real label match


# ----------------------------------------------------------------------
# Tenant-configurable label hints, end-to-end: the vocabulary must come
# from metadata_field_label_aliases for THIS document's tenant and type
# (not from code), and every result's match_basis must land in
# document_ocr_field_results -- including not_found rows and re-runs.
# ----------------------------------------------------------------------
def test_label_hints_are_loaded_from_the_database_and_match_basis_is_persisted(session_factory, seeded_version):
    tenant_id, document_type_id = seeded_version["tenant_id"], seeded_version["document_type_id"]
    # "Order Ref" is neither the field's key nor its label -- only the
    # type-scoped hint row can make this field findable.
    po_ref = seed_metadata_field(session_factory, tenant_id=tenant_id, field_key="po_ref", label="Purchase Order Reference")
    seed_label_aliases(session_factory, tenant_id=tenant_id, field_id=po_ref, aliases=["Order Ref"], document_type_id=document_type_id)
    seed_metadata_field(session_factory, tenant_id=tenant_id, field_key="review_due_date", label="Review Due Date")  # no hints

    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(session_factory, layout_doc=layout_doc, ocr_text="Order Ref: PO-12345", ocr_confidence=0.97)

    outcome = pipeline.process_version(
        seeded_version["version_id"], requested_fields=[FieldSpec("po_ref", "text"), FieldSpec("review_due_date", "text")]
    )

    assert outcome.status == "completed"
    assert outcome.output["fields"]["po_ref"]["value"] == "PO-12345"
    assert outcome.output["fields"]["po_ref"]["match_basis"] == "label_hint"
    with session_factory() as session:
        rows = {
            r.field_key: r
            for r in session.query(DocumentOcrFieldResult).filter_by(document_id=seeded_version["document_id"]).all()
        }
        assert (rows["po_ref"].status, rows["po_ref"].match_basis) == ("extracted", "label_hint")
        # not_found still says what was searched: no hints -> the field's own name
        assert (rows["review_due_date"].status, rows["review_due_date"].match_basis) == ("not_found", "field_name")


def test_another_document_types_hints_are_not_used_by_the_pipeline(session_factory, seeded_version):
    tenant_id = seeded_version["tenant_id"]
    po_ref = seed_metadata_field(session_factory, tenant_id=tenant_id, field_key="po_ref", label="Purchase Order Reference")
    seed_label_aliases(session_factory, tenant_id=tenant_id, field_id=po_ref, aliases=["Order Ref"], document_type_id=uuid.uuid4())

    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(session_factory, layout_doc=layout_doc, ocr_text="Order Ref: PO-12345", ocr_confidence=0.97)

    outcome = pipeline.process_version(seeded_version["version_id"], requested_fields=[FieldSpec("po_ref", "text")])

    assert "po_ref" not in outcome.output["fields"]
    with session_factory() as session:
        row = session.query(DocumentOcrFieldResult).filter_by(document_id=seeded_version["document_id"], field_key="po_ref").one()
        assert (row.status, row.match_basis) == ("not_found", "field_name")


def test_rerun_updates_match_basis_on_the_existing_row(session_factory, seeded_version):
    """A hint added after a first not_found attempt: the re-run's upsert
    must rewrite match_basis along with value/status, not leave the stale
    "field_name" from the first attempt on the replaced row."""
    tenant_id, document_type_id = seeded_version["tenant_id"], seeded_version["document_type_id"]
    po_ref = seed_metadata_field(session_factory, tenant_id=tenant_id, field_key="po_ref", label="Purchase Order Reference")
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})

    pipeline, _, _ = make_pipeline(session_factory, layout_doc=layout_doc, ocr_text="Order Ref: PO-12345", ocr_confidence=0.97)
    pipeline.process_version(seeded_version["version_id"], requested_fields=[FieldSpec("po_ref", "text")])

    seed_label_aliases(session_factory, tenant_id=tenant_id, field_id=po_ref, aliases=["Order Ref"], document_type_id=document_type_id)
    pipeline2, _, _ = make_pipeline(session_factory, layout_doc=layout_doc, ocr_text="Order Ref: PO-12345", ocr_confidence=0.97)
    pipeline2.process_version(seeded_version["version_id"], requested_fields=[FieldSpec("po_ref", "text")])

    with session_factory() as session:
        rows = session.query(DocumentOcrFieldResult).filter_by(document_id=seeded_version["document_id"], field_key="po_ref").all()
        assert len(rows) == 1
        assert (rows[0].value, rows[0].status, rows[0].match_basis) == ("PO-12345", "extracted", "label_hint")
