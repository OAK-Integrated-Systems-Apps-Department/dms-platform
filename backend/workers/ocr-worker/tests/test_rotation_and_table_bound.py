"""Step 7 close-out: /Rotate normalisation and the table-structure bound.

Both exist because of one measured pathology -- a one-page PDF carrying
`/Rotate 90` spent 87s in Docling's table pass predicting a 378x2 grid on
a page with 24 cells, and dropped all 24. See
docs/ocr/phase2-verification.md Step 7.
"""
from __future__ import annotations

import time
from types import SimpleNamespace

import fitz
import pytest

from ocr_worker import layout


def _page_pdf(width=612, height=792, rotation=0) -> bytes:
    doc = fitz.open()
    page = doc.new_page(width=width, height=height)
    for i, line in enumerate(["Oak Coffee Roasters", "Invoice INV-2026-05426", "Amount 41252.70 EUR"]):
        page.insert_text((72, 60 + i * 24), line, fontsize=11)
    if rotation:
        page.set_rotation(rotation)
    out = doc.tobytes()
    doc.close()
    return out


class TestNormalizePageRotation:
    def test_an_unrotated_pdf_is_returned_byte_identical(self):
        """The common case must not pay for a rewrite it does not need."""
        raw = _page_pdf()
        out, changed = layout.normalize_page_rotation(raw)
        assert changed == 0
        assert out is raw

    @pytest.mark.parametrize("angle", [90, 180, 270])
    def test_rotation_is_cleared_from_the_page(self, angle):
        out, changed = layout.normalize_page_rotation(_page_pdf(rotation=angle))
        assert changed == 1
        doc = fitz.open(stream=out, filetype="pdf")
        try:
            assert doc[0].rotation == 0
        finally:
            doc.close()

    @pytest.mark.parametrize("angle", [90, 180, 270])
    def test_the_displayed_page_size_is_unchanged(self, angle):
        raw = _page_pdf(rotation=angle)
        before = fitz.open(stream=raw, filetype="pdf")
        after = fitz.open(stream=layout.normalize_page_rotation(raw)[0], filetype="pdf")
        try:
            assert before[0].rect.width == pytest.approx(after[0].rect.width, abs=1.0)
            assert before[0].rect.height == pytest.approx(after[0].rect.height, abs=1.0)
        finally:
            before.close()
            after.close()

    @pytest.mark.parametrize("angle", [90, 180, 270])
    def test_the_coordinate_invariant_holds_exactly(self, angle):
        """THE test for this change. Normalisation rewrites the PDF, so
        every word must come back at the same place -- otherwise every
        highlight in the review UI moves. Verified live at 0.000pt on the
        real fixture; asserted here at sub-pixel tolerance.
        """
        raw = _page_pdf(rotation=angle)
        before = fitz.open(stream=raw, filetype="pdf")
        after = fitz.open(stream=layout.normalize_page_rotation(raw)[0], filetype="pdf")
        try:
            bw = before[0].get_text("words")
            aw = after[0].get_text("words")
            assert [w[4] for w in bw] == [w[4] for w in aw]
            assert bw, "fixture produced no words; the test would pass vacuously"
            for b, a in zip(bw, aw):
                for i in range(4):
                    assert a[i] == pytest.approx(b[i], abs=0.1)
        finally:
            before.close()
            after.close()

    def test_an_unopenable_pdf_passes_through_instead_of_raising(self):
        """Regression, found by a live 50-upload run.

        This runs BEFORE `DoclingLayoutDocument.__init__`, which is where a
        bad PDF becomes a classified CORRUPT_FILE / ENCRYPTED_PDF. When it
        raised PyMuPDF's own error instead, both fixtures came back as a
        generic ENGINE_ERROR -- and that silently changed their retry
        budget from 0 to 3, so a password-protected file would be retried
        four times.
        """
        garbage = b"%PDF-1.4\nnot really a pdf at all"
        out, changed = layout.normalize_page_rotation(garbage)
        assert out is garbage
        assert changed == 0

    def test_a_truncated_pdf_passes_through_instead_of_raising(self):
        raw = _page_pdf(rotation=90)
        out, changed = layout.normalize_page_rotation(raw[: len(raw) // 3])
        assert changed == 0
        assert isinstance(out, bytes)

    def test_a_pdf_that_opens_but_will_not_render_falls_back_to_the_original(self, monkeypatch):
        """Opening is not the only way this can fail; re-rendering can too,
        and the same rule applies -- hand the original bytes on."""
        raw = _page_pdf(rotation=90)

        def exploding_show(self, *args, **kwargs):
            raise RuntimeError("damaged content stream")

        monkeypatch.setattr(fitz.Page, "show_pdf_page", exploding_show)
        out, changed = layout.normalize_page_rotation(raw)
        assert out is raw
        assert changed == 0

    def test_a_multipage_document_normalises_only_its_rotated_pages(self):
        doc = fitz.open()
        for angle in (0, 90, 0):
            page = doc.new_page(width=612, height=792)
            page.insert_text((72, 72), "text", fontsize=11)
            if angle:
                page.set_rotation(angle)
        raw = doc.tobytes()
        doc.close()

        out, changed = layout.normalize_page_rotation(raw)
        assert changed == 1
        result = fitz.open(stream=out, filetype="pdf")
        try:
            assert result.page_count == 3
            assert [p.rotation for p in result] == [0, 0, 0]
        finally:
            result.close()


class _FakePredictions:
    def __init__(self):
        self.tablestructure = None


class _FakePage:
    def __init__(self, page_no):
        self.page_no = page_no
        self.predictions = _FakePredictions()


class TestTableStructureBound:
    @pytest.fixture(autouse=True)
    def _reset(self, monkeypatch):
        """The install is once-per-process by design, so each test has to
        put the module back the way it found it."""
        from docling.models.stages.table_structure.table_structure_model import (
            BaseTableStructureModel,
        )

        original = BaseTableStructureModel.__call__
        monkeypatch.setattr(layout, "_TABLE_BOUND_INSTALLED", False)
        layout._TABLE_TIMEOUT_PAGES.clear()
        yield BaseTableStructureModel
        BaseTableStructureModel.__call__ = original
        layout._TABLE_TIMEOUT_PAGES.clear()

    def _run(self, model_cls, pages, predict):
        fake = SimpleNamespace(enabled=True, predict_tables=predict)
        return list(model_cls.__call__(fake, SimpleNamespace(), pages))

    def test_a_timeout_of_zero_leaves_docling_unpatched(self, _reset):
        before = _reset.__call__
        layout._install_table_structure_bound(0)
        assert _reset.__call__ is before

    def test_a_fast_prediction_is_applied_to_the_page(self, _reset):
        layout._install_table_structure_bound(5.0)
        pages = [_FakePage(0)]
        out = self._run(_reset, pages, lambda _cr, batch: ["PREDICTION"])
        assert out[0].predictions.tablestructure == "PREDICTION"
        assert layout.LayoutEngine.table_structure_skipped_pages() == set()

    def test_an_overrunning_prediction_is_abandoned_and_the_page_survives(self, _reset):
        layout._install_table_structure_bound(0.2)

        def slow(_cr, _batch):
            time.sleep(5)
            return ["NEVER"]

        pages = [_FakePage(0)]
        started = time.monotonic()
        out = self._run(_reset, pages, slow)
        elapsed = time.monotonic() - started

        # The document stopped waiting...
        assert elapsed < 2.0
        # ...the page is still yielded, with no table structure...
        assert out[0].predictions.tablestructure is None
        # ...and the skip is recorded for the page output's flag.
        assert layout.LayoutEngine.table_structure_skipped_pages() == {0}

    def test_one_slow_page_does_not_cost_the_others(self, _reset):
        layout._install_table_structure_bound(0.2)

        def slow_on_page_one(_cr, batch):
            if batch[0].page_no == 1:
                time.sleep(5)
            return ["PREDICTION"]

        pages = [_FakePage(0), _FakePage(1), _FakePage(2)]
        out = self._run(_reset, pages, slow_on_page_one)

        assert [p.predictions.tablestructure for p in out] == ["PREDICTION", None, "PREDICTION"]
        assert layout.LayoutEngine.table_structure_skipped_pages() == {1}

    def test_a_failing_prediction_is_recorded_rather_than_raised(self, _reset):
        """A table is additive structure; losing it is never worth failing
        a page whose text came out fine."""
        layout._install_table_structure_bound(5.0)

        def boom(_cr, _batch):
            raise RuntimeError("tableformer exploded")

        out = self._run(_reset, [_FakePage(0)], boom)
        assert out[0].predictions.tablestructure is None
        assert layout.LayoutEngine.table_structure_skipped_pages() == {0}

    def test_a_disabled_model_passes_pages_straight_through(self, _reset):
        layout._install_table_structure_bound(5.0)
        fake = SimpleNamespace(enabled=False, predict_tables=None)
        pages = [_FakePage(0)]
        assert list(_reset.__call__(fake, SimpleNamespace(), pages)) == pages
