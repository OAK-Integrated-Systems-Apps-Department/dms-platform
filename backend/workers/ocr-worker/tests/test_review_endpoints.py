"""The two read-only routes the review screen runs on (Phase 4c).

Both exist because the obvious alternative is destructive.
`/ocr/rubber-band` finalizes: it replaces the version's ENTIRE field set
and page list with whatever the one crop produced. Pointing a review
screen at it would wipe every other field on a document each time a
reviewer re-checked one of them -- so the preview route OCRs the box and
writes nothing, and the page-image route renders a page and writes
nothing.

These tests pin exactly that: no job claimed, no row written, no event
published.
"""
from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from ocr_worker.api import create_app
from ocr_worker.config import Settings
from ocr_worker.pipeline import PageRender
from ocr_worker.repository import VersionNotEligible, VersionNotFound

TOKEN = "test-internal-token"
VERSION = uuid.uuid4()

PNG = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
    b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\nIDATx\x9cc\x00\x01"
    b"\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
)


class _Future:
    def __init__(self, value=None, exc=None):
        self._value = value
        self._exc = exc

    def result(self):
        if self._exc is not None:
            raise self._exc
        return self._value


class _Pool:
    """Records what it was asked to do, so a test can assert the route
    never reached the *finalizing* scheduler by accident."""

    def __init__(self, render=None, preview=None, exc=None):
        self.calls: list[tuple] = []
        self._render = render
        self._preview = preview
        self._exc = exc

    def is_ready(self):
        return True

    def schedule_render_page(self, version_id, page, dpi=None):
        self.calls.append(("render", version_id, page, dpi))
        return _Future(self._render, self._exc)

    def schedule_preview_rubber_band(self, version_id, page, box):
        self.calls.append(("preview", version_id, page, box))
        return _Future(self._preview, self._exc)

    def schedule_process_rubber_band(self, *args, **kwargs):  # pragma: no cover
        raise AssertionError("the review screen must never reach the finalizing path")

    def schedule_process_version(self, *args, **kwargs):  # pragma: no cover
        raise AssertionError("the review screen must never reach the finalizing path")


def _client(pool):
    settings = Settings(internal_service_token=TOKEN)
    return TestClient(create_app(pool, lambda: None, lambda: True, settings))


def _auth():
    return {"X-Internal-Token": TOKEN}


class TestPageImage:
    def test_it_returns_the_png_with_its_pixel_size(self):
        pool = _Pool(render=PageRender(png=PNG, width_px=1131, height_px=1600, page_count=3))
        response = _client(pool).get(f"/ocr/page-image/{VERSION}?page=1", headers=_auth())

        assert response.status_code == 200
        assert response.headers["content-type"] == "image/png"
        assert response.content == PNG
        # The frontend places highlights from these before the image
        # decodes -- and the renderer may have downscaled to stay under
        # ocr_render_max_mp, so dpi x page size is NOT a valid substitute.
        assert response.headers["X-Page-Width"] == "1131"
        assert response.headers["X-Page-Height"] == "1600"
        assert response.headers["X-Page-Count"] == "3"

    def test_it_renders_the_page_asked_for(self):
        pool = _Pool(render=PageRender(png=PNG, width_px=10, height_px=10, page_count=5))
        _client(pool).get(f"/ocr/page-image/{VERSION}?page=4", headers=_auth())

        assert pool.calls == [("render", VERSION, 4, None)]

    def test_page_zero_is_the_default(self):
        pool = _Pool(render=PageRender(png=PNG, width_px=10, height_px=10, page_count=1))
        _client(pool).get(f"/ocr/page-image/{VERSION}", headers=_auth())

        assert pool.calls[0][2] == 0

    def test_it_requires_the_internal_token(self):
        pool = _Pool(render=PageRender(png=PNG, width_px=1, height_px=1, page_count=1))
        assert _client(pool).get(f"/ocr/page-image/{VERSION}").status_code == 401
        assert pool.calls == [], "an unauthenticated call must not reach the pool"

    @pytest.mark.parametrize(
        "exc,status",
        [
            (VersionNotFound(str(VERSION)), 404),
            (VersionNotEligible(VERSION, "pending"), 409),
            (ValueError("page 9 out of range (document has 2 pages)"), 422),
            (TimeoutError(), 504),
        ],
    )
    def test_it_classifies_failures_the_same_way_the_other_routes_do(self, exc, status):
        response = _client(_Pool(exc=exc)).get(f"/ocr/page-image/{VERSION}", headers=_auth())
        assert response.status_code == status


class TestRubberBandPreview:
    def test_it_returns_what_the_box_says(self):
        answer = {
            "version_id": str(VERSION),
            "page": 0,
            "bbox_norm": [0.1, 0.2, 0.3, 0.4],
            "text": "1,234.56",
            "confidence": 0.91,
            "words": [{"text": "1,234.56", "confidence": 0.91}],
        }
        pool = _Pool(preview=answer)
        response = _client(pool).post(
            "/ocr/rubber-band/preview",
            json={"version_id": str(VERSION), "page": 0, "x0": 0.1, "y0": 0.2, "x1": 0.3, "y1": 0.4},
            headers=_auth(),
        )

        assert response.status_code == 200
        assert response.json() == answer

    def test_it_goes_to_the_non_finalizing_scheduler(self):
        """_Pool.schedule_process_rubber_band raises if reached -- the
        finalizing path would replace the version's whole field set."""
        pool = _Pool(preview={"text": ""})
        _client(pool).post(
            "/ocr/rubber-band/preview",
            json={"version_id": str(VERSION), "page": 2, "x0": 0.0, "y0": 0.0, "x1": 0.5, "y1": 0.5},
            headers=_auth(),
        )

        assert pool.calls == [("preview", VERSION, 2, (0.0, 0.0, 0.5, 0.5))]

    def test_it_requires_the_internal_token(self):
        pool = _Pool(preview={"text": ""})
        response = _client(pool).post(
            "/ocr/rubber-band/preview",
            json={"version_id": str(VERSION), "x0": 0.0, "y0": 0.0, "x1": 0.5, "y1": 0.5},
        )
        assert response.status_code == 401
        assert pool.calls == []

    def test_a_degenerate_selection_is_a_422(self):
        pool = _Pool(exc=ValueError("rubber-band selection is degenerate"))
        response = _client(pool).post(
            "/ocr/rubber-band/preview",
            json={"version_id": str(VERSION), "x0": 0.5, "y0": 0.5, "x1": 0.5, "y1": 0.5},
            headers=_auth(),
        )
        assert response.status_code == 422
