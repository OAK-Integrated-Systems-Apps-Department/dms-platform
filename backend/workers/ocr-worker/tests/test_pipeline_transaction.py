from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from conftest import seed_zone  # noqa: E402
from ocr_worker.config import Settings  # noqa: E402
from ocr_worker.layout import NativeTextBlock, PageImage, TableBlock, TableCellBlock  # noqa: E402
from ocr_worker.models import DocumentVersion, OcrOutput, OutboxEvent, ProcessingJob  # noqa: E402
from ocr_worker.ocr_engine import OcrResult, WordResult  # noqa: E402
from ocr_worker.pipeline import OcrPipeline  # noqa: E402
from ocr_worker.repository import VersionNotEligible  # noqa: E402


class FakeImage:
    def crop(self, box):
        return self


class FakeLayoutDocument:
    """`native_text_by_page` is keyed by Docling's 1-indexed page numbers,
    matching the real API (see layout.py) -- None or a missing key means
    "no native text layer", forcing the PaddleOCR path.
    """

    def __init__(
        self,
        page_count: int = 1,
        width: int = 1000,
        height: int = 800,
        native_text_by_page: dict | None = None,
        native_text_lines_by_page: dict | None = None,
        tables_by_page: dict | None = None,
        fail_render: bool = False,
    ):
        self._page_count = page_count
        self._width = width
        self._height = height
        self._native_text_by_page = native_text_by_page or {}
        # 0-indexed (PyMuPDF convention), unlike native_text_by_page above --
        # matches the real native_text_lines_for_page. Defaults to empty so
        # existing tests (which never set this) exercise the "no line-level
        # data available" fallback path, same as a real zone/rubber-band call.
        self._native_text_lines_by_page = native_text_lines_by_page or {}
        self._tables_by_page = tables_by_page or {}
        self._fail_render = fail_render

    def page_count(self) -> int:
        return self._page_count

    def render_page(self, page_no: int, dpi: int) -> PageImage:
        if self._fail_render:
            raise RuntimeError("corrupt page data")
        return PageImage(page_no=page_no, width_px=self._width, height_px=self._height, image=FakeImage())

    def native_text_blocks_for_page(self, page_no: int, dpi: int):
        return self._native_text_by_page.get(page_no)

    def native_text_lines_for_page(self, page_no: int, dpi: int):
        return self._native_text_lines_by_page.get(page_no)

    def tables_for_page(self, page_no: int, dpi: int):
        return self._tables_by_page.get(page_no)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeLayoutEngine:
    def __init__(self, doc: FakeLayoutDocument):
        self._doc = doc

    def load(self, file_bytes, mime_type, original_filename):
        return self._doc


class FakeOcrEngine:
    """Tracks every image it was actually called with, so tests can assert
    PaddleOCR was (or was never) invoked with real image data -- not just
    that *a* result came back.
    """

    def __init__(self, text: str, confidence: float, words: list[WordResult] | None = None):
        self.text = text
        self.confidence = confidence
        self.words = words if words is not None else [WordResult(text=text, confidence=confidence, bbox=(0, 0, 50, 20))]
        self.calls: list[object] = []

    def recognize(self, image, lang: str = "en") -> OcrResult:
        self.calls.append(image)
        return OcrResult(text=self.text, mean_confidence=self.confidence, words=self.words)


class FakeStorage:
    def __init__(self, source_bytes: bytes = b"%PDF-fake%", fail_fetch: bool = False):
        self.written: dict[str, dict] = {}
        self._source_bytes = source_bytes
        self._fail_fetch = fail_fetch

    def get_object_bytes(self, object_key: str) -> bytes:
        if self._fail_fetch:
            raise RuntimeError("object not found in MinIO")
        return self._source_bytes

    def put_ocr_output_json(self, object_key: str, data: dict) -> str:
        self.written[object_key] = data
        return object_key


def make_pipeline(session_factory, *, layout_doc, ocr_text="", ocr_confidence=0.99, threshold=0.95, storage=None):
    settings = Settings(min_confidence_threshold=threshold, ocr_languages="en", ocr_render_dpi=200)
    storage = storage or FakeStorage()
    layout_engine = FakeLayoutEngine(layout_doc)
    ocr_engine = FakeOcrEngine(text=ocr_text, confidence=ocr_confidence)

    pipeline = OcrPipeline(
        settings=settings,
        session_factory=session_factory,
        storage=storage,
        layout_engine=layout_engine,
        ocr_engine=ocr_engine,
    )
    return pipeline, storage, ocr_engine


# ----------------------------------------------------------------------
# (a) native-text path never calls PaddleOCR
# ----------------------------------------------------------------------
def test_native_text_page_never_calls_paddleocr(session_factory, seeded_version):
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [NativeTextBlock(text="Invoice No: INV-4471", bbox=(10, 10, 300, 40))]},
    )
    pipeline, storage, ocr_engine = make_pipeline(session_factory, layout_doc=layout_doc)

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "completed"
    assert ocr_engine.calls == []  # never invoked -- this is the whole point of the native-text path
    page = outcome.output["pages"][0]
    assert page["method"] == "native_text"
    assert page["confidence"] == 1.0
    assert "INV-4471" in page["text"]
    assert page["blocks"][0]["bbox"] == [10, 10, 300, 40]
    assert page["tables"] == []  # no table on this page -- structure list stays empty, not omitted


# ----------------------------------------------------------------------
# (a2) a real table on a native-text page produces structured rows/
# columns AND keeps the cell text in the flat blocks/text (see
# pipeline.py's _merge_native_text_and_tables) -- enabling table
# structure recognition must not silently drop a table's content from
# anything that only reads `text`/`blocks`.
# ----------------------------------------------------------------------
def test_native_text_page_with_table_produces_structured_table_and_flat_blocks(session_factory, seeded_version):
    table = TableBlock(
        bbox=(0, 100, 300, 200),
        num_rows=2,
        num_cols=2,
        cells=[
            TableCellBlock(text="Item", row=0, col=0, row_span=1, col_span=1, bbox=(0, 100, 150, 130), column_header=True, row_header=False),
            TableCellBlock(text="Qty", row=0, col=1, row_span=1, col_span=1, bbox=(150, 100, 300, 130), column_header=True, row_header=False),
            TableCellBlock(text="Widget A", row=1, col=0, row_span=1, col_span=1, bbox=(0, 130, 150, 200), column_header=False, row_header=False),
            TableCellBlock(text="2", row=1, col=1, row_span=1, col_span=1, bbox=(150, 130, 300, 200), column_header=False, row_header=False),
        ],
    )
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [
            NativeTextBlock(text="Invoice Table Test", bbox=(0, 0, 300, 30)),
            NativeTextBlock(text="Thank you", bbox=(0, 250, 150, 280)),
        ]},
        tables_by_page={1: [table]},
    )
    pipeline, storage, ocr_engine = make_pipeline(session_factory, layout_doc=layout_doc)

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "completed"
    page = outcome.output["pages"][0]

    # structured table: real grid, not flattened
    assert len(page["tables"]) == 1
    result_table = page["tables"][0]
    assert result_table["num_rows"] == 2 and result_table["num_cols"] == 2
    cells_by_pos = {(c["row"], c["col"]): c["text"] for c in result_table["cells"]}
    assert cells_by_pos == {(0, 0): "Item", (0, 1): "Qty", (1, 0): "Widget A", (1, 1): "2"}
    assert result_table["cells"][0]["column_header"] is True

    # flat text/blocks still contain every cell, in reading order between
    # the paragraphs that come before/after the table on the page
    assert page["text"] == "Invoice Table Test\nItem\nQty\nWidget A\n2\nThank you"


# ----------------------------------------------------------------------
# (b) image-only path calls PaddleOCR with real image data, non-empty blocks
# ----------------------------------------------------------------------
def test_image_only_page_calls_paddleocr_and_produces_blocks(session_factory, seeded_version):
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})  # no native text on any page
    pipeline, storage, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="Invoice No: INV-9001", ocr_confidence=0.97,
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "completed"
    assert len(ocr_engine.calls) == 1
    assert isinstance(ocr_engine.calls[0], FakeImage)  # the actual page image render_page produced, not a placeholder

    page = outcome.output["pages"][0]
    assert page["method"] == "paddleocr"
    assert page["blocks"], "expected non-empty blocks from a real OCR call"
    assert page["blocks"][0]["text"] == "Invoice No: INV-9001"
    assert page["blocks"][0]["confidence"] == 0.97


# ----------------------------------------------------------------------
# (c) confidence below threshold sets requires_review / low_confidence
# ----------------------------------------------------------------------
def test_low_confidence_result_sets_requires_review_flag(session_factory, seeded_version):
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="barely legible scrawl", ocr_confidence=0.40, threshold=0.95,
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "completed"  # never silently dropped (OS-NFR-006)
    assert outcome.output["flags"]["low_confidence"] is True
    assert outcome.output["flags"]["requires_review"] is True


def test_high_confidence_result_does_not_require_review(session_factory, seeded_version):
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="clean text", ocr_confidence=0.99, threshold=0.95,
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.output["flags"]["low_confidence"] is False
    assert outcome.output["flags"]["requires_review"] is False


# ----------------------------------------------------------------------
# (d) DB transaction + outbox write happen atomically
# ----------------------------------------------------------------------
def test_success_writes_ocr_output_and_outbox_event_atomically(session_factory, seeded_version):
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="Total: 950.00", ocr_confidence=0.99,
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    with session_factory() as session:
        version = session.get(DocumentVersion, seeded_version["version_id"])
        assert version.ocr_status == "completed"

        ocr_outputs = session.query(OcrOutput).filter_by(version_id=seeded_version["version_id"]).all()
        assert len(ocr_outputs) == 1

        jobs = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).all()
        assert len(jobs) == 1
        assert jobs[0].status == "completed"

        events = session.query(OutboxEvent).filter_by(aggregate_id=seeded_version["version_id"]).all()
        assert len(events) == 1
        assert events[0].event_type == "document.version.ocr.completed"
        assert events[0].payload["flags"]["low_confidence"] is False

    assert outcome.output["document_version_id"] == str(seeded_version["version_id"])


def test_processing_failure_is_recorded_not_silently_dropped(session_factory, seeded_version):
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={})
    pipeline, storage, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, storage=FakeStorage(fail_fetch=True),
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "failed"
    assert "object not found" in outcome.error

    with session_factory() as session:
        version = session.get(DocumentVersion, seeded_version["version_id"])
        assert version.ocr_status == "failed"

        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        assert job.status == "failed"
        assert "object not found" in job.last_error

        event = session.query(OutboxEvent).filter_by(aggregate_id=seeded_version["version_id"]).one()
        assert event.event_type == "document.version.ocr.failed"


def test_version_not_yet_scanned_clean_is_never_processed(session_factory, seeded_version):
    with session_factory() as session:
        version = session.get(DocumentVersion, seeded_version["version_id"])
        version.scan_status = "pending"
        session.commit()

    layout_doc = FakeLayoutDocument(page_count=1)
    pipeline, storage, ocr_engine = make_pipeline(session_factory, layout_doc=layout_doc)

    with pytest.raises(VersionNotEligible):
        pipeline.process_version(seeded_version["version_id"])

    assert ocr_engine.calls == []  # never even attempted OCR (section 2.2 precondition)
    with session_factory() as session:
        from ocr_worker.models import ProcessingJob as PJ

        assert session.query(PJ).count() == 0


# ----------------------------------------------------------------------
# Zone OCR always uses PaddleOCR, even on a page with a native text layer
# ----------------------------------------------------------------------
def test_zone_ocr_always_uses_paddleocr_even_with_native_text_available(session_factory, seeded_version):
    seed_zone(
        session_factory,
        tenant_id=seeded_version["tenant_id"],
        document_type_id=seeded_version["document_type_id"],
        zone_name="invoice_number",
        field_name="invoice_number",
        page=0, x0=0.6, y0=0.0, x1=1.0, y1=0.1,
    )
    # Page 1 (Docling numbering) DOES have native text -- decision logic
    # item 4 says zone OCR ignores that and always runs PaddleOCR anyway.
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [NativeTextBlock(text="should be ignored by zone OCR", bbox=(0, 0, 10, 10))]},
    )
    pipeline, storage, ocr_engine = make_pipeline(session_factory, layout_doc=layout_doc, ocr_text="INV-7788", ocr_confidence=0.98)

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert len(ocr_engine.calls) == 1  # PaddleOCR WAS called despite native text existing
    assert outcome.output["fields"]["invoice_number"]["value"] == "INV-7788"
    assert outcome.output["pages"][0]["method"] == "paddleocr"
