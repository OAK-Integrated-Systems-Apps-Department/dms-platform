"""`document_ocr_field_results.source_bbox` / `document_ocr_line_items.source_bbox`
hold NORMALIZED boxes, not pixels.

The review screen draws a field highlight with
`left: ${x0 * 100}%` (`review-page-viewer.tsx`), and dms-core types the
column as `double[]` on the way there. The writers used to persist the
raw pixel tuple, so a box at x=917 px on a 906 px-wide preview was placed
at `left: 91700%` -- every highlight on every document was mounted
~830,000 px off-screen, and clicking a field card scrolled the preview
out of the document entirely. It had been that way since the review
screen shipped, invisible from the JSON output, which has always carried
a correct `bbox_norm` alongside the pixel one.

These tests pin the conversion at the two writers, because the output
builders being right is exactly what hid it.
"""

from __future__ import annotations

import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.extraction import RequestedFieldResult  # noqa: E402
from ocr_worker.layout import PageGeometry, normalize_bbox_for_page  # noqa: E402
from ocr_worker.repository import OcrRepository  # noqa: E402


class _CapturingSession:
    """Just enough Session to see what the writer would have inserted."""

    def __init__(self):
        self.added = []

    def execute(self, *_args, **_kwargs):
        class _Result:
            @staticmethod
            def scalar_one_or_none():
                return None  # always the insert branch

        return _Result()

    def add(self, obj):
        self.added.append(obj)

    def flush(self):
        pass

    def query(self, *_args, **_kwargs):
        return self

    def filter(self, *_args, **_kwargs):
        return self

    def delete(self, *_args, **_kwargs):
        return 0


def _geometry():
    # The default render budget on A4: 1131x1600 -- see test_coordinates.py.
    # `render_scale` is chosen so `exact_width_px` lands on exactly 1131,
    # which is what makes the `917 / 1131` assertion below exact rather
    # than approximate (normalization divides by the float width, not the
    # rounded one -- see PageGeometry's own comment).
    return PageGeometry(page_no=0, width_pt=595.0, height_pt=842.0,
                        render_scale=1131 / 595.0, width_px=1131, height_px=1600)


def _repo(session):
    repo = OcrRepository.__new__(OcrRepository)
    repo.session = session
    return repo


def test_a_field_bbox_is_stored_as_page_fractions_not_pixels():
    session = _CapturingSession()
    geometry = _geometry()
    result = RequestedFieldResult(
        field_key="invoice_number", value="IN/2025/01448", confidence=0.9,
        status="extracted", page=1, bbox=(917, 284, 1059, 308),
    )

    _repo(session).upsert_field_results(
        tenant_id=uuid.uuid4(), document_id=uuid.uuid4(), version_id=uuid.uuid4(),
        results=[result], geometry_by_page={1: geometry},
    )

    [row] = session.added
    assert row.source_bbox == normalize_bbox_for_page({1: geometry}, 1, (917, 284, 1059, 308))
    # Every component inside the page, which is the whole contract the
    # review screen's `x0 * 100%` depends on.
    assert all(0.0 <= v <= 1.0 for v in row.source_bbox)
    assert row.source_bbox[0] == 917 / 1131


def test_no_page_geometry_stores_no_bbox_rather_than_a_pixel_one():
    """A highlight that does not appear is a missing feature. A highlight
    over the wrong words is a reviewer confirming a value they did not
    read. The second is worse, so the ambiguous case stores nothing."""
    session = _CapturingSession()
    result = RequestedFieldResult(
        field_key="amount", value="2479475.00", confidence=0.9,
        status="extracted", page=1, bbox=(945, 786, 1060, 812),
    )

    _repo(session).upsert_field_results(
        tenant_id=uuid.uuid4(), document_id=uuid.uuid4(), version_id=uuid.uuid4(),
        results=[result], geometry_by_page=None,
    )

    [row] = session.added
    assert row.source_bbox is None


def test_a_field_with_no_bbox_at_all_is_unaffected():
    """A pattern-rule or flattened-text match has no single location --
    see `extract_requested_fields`. It must stay None, not become a
    normalized nothing."""
    session = _CapturingSession()
    result = RequestedFieldResult(
        field_key="currency", value="LKR", confidence=0.85, status="extracted",
    )

    _repo(session).upsert_field_results(
        tenant_id=uuid.uuid4(), document_id=uuid.uuid4(), version_id=uuid.uuid4(),
        results=[result], geometry_by_page={1: _geometry()},
    )

    [row] = session.added
    assert row.source_bbox is None


class _Item:
    def __init__(self):
        self.line_number = 1
        self.description = "Door to door application"
        self.quantity = None
        self.unit_price = None
        self.line_total = None
        self.raw_cells = None
        self.confidence = 0.9
        self.page = 1
        self.bbox = (335, 710, 1140, 740)


def test_a_line_item_bbox_is_normalized_the_same_way():
    """dms-core exposes it as `double[] sourceBbox` on every review
    response, so it is a published normalized coordinate whether or not
    today's UI draws it."""
    session = _CapturingSession()
    geometry = _geometry()

    _repo(session).replace_line_items(
        tenant_id=uuid.uuid4(), document_id=uuid.uuid4(), version_id=uuid.uuid4(),
        items=[_Item()], geometry_by_page={1: geometry},
    )

    [row] = session.added
    assert row.source_bbox == normalize_bbox_for_page({1: geometry}, 1, (335, 710, 1140, 740))
    assert all(0.0 <= v <= 1.0 for v in row.source_bbox)
