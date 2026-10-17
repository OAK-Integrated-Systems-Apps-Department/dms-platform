"""`/fingerprint` -- the staleness guard's only usable channel.

Three measured runs were invalidated by a container older than the source.
The obvious check (`docker exec` + `md5sum`) cannot run from inside a
container, which is where every harness here runs, so the worker reports
its own source hashes over HTTP instead.

It is a development aid, so it is authenticated like every other /ocr
route and can be switched off entirely.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from ocr_worker.api import SOURCE_FINGERPRINT, create_app, source_fingerprint
from ocr_worker.config import Settings

TOKEN = "test-internal-token"


class _Pool:
    def is_ready(self):
        return True


def _client(**overrides):
    settings = Settings(internal_service_token=TOKEN, **overrides)
    app = create_app(_Pool(), lambda: None, lambda: True, settings)
    return TestClient(app)


class TestAuth:
    def test_it_requires_the_internal_token(self):
        assert _client().get("/fingerprint").status_code == 401

    def test_a_wrong_token_is_rejected(self):
        response = _client().get("/fingerprint", headers={"X-Internal-Token": "nope"})
        assert response.status_code == 401

    def test_the_right_token_is_accepted(self):
        response = _client().get("/fingerprint", headers={"X-Internal-Token": TOKEN})
        assert response.status_code == 200

    def test_healthz_is_still_unauthenticated(self):
        """The probe endpoints stay open; only the fingerprint moved
        behind the door."""
        assert _client().get("/healthz").status_code == 200


class TestDisabling:
    def test_it_can_be_switched_off(self):
        response = _client(ocr_fingerprint_enabled=False).get(
            "/fingerprint", headers={"X-Internal-Token": TOKEN}
        )
        assert response.status_code == 404

    def test_disabled_returns_404_not_403(self):
        """So a deployment with it off does not confirm the endpoint
        exists at all."""
        response = _client(ocr_fingerprint_enabled=False).get(
            "/fingerprint", headers={"X-Internal-Token": TOKEN}
        )
        assert response.status_code == 404
        assert "fingerprint" not in response.text.lower()

    def test_it_is_on_by_default(self):
        assert Settings().ocr_fingerprint_enabled is True


class TestContent:
    def test_it_reports_a_hash_per_source_file(self):
        body = _client().get("/fingerprint", headers={"X-Internal-Token": TOKEN}).json()
        assert body["files"] == len(body["source"])
        assert body["files"] > 20, "the package has far more modules than this"
        for name, digest in body["source"].items():
            assert name.endswith(".py")
            assert len(digest) == 32

    def test_known_modules_are_present(self):
        body = _client().get("/fingerprint", headers={"X-Internal-Token": TOKEN}).json()
        for name in ("pipeline.py", "layout.py", "searchable_pdf.py", "quality.py"):
            assert name in body["source"]

    def test_it_never_reports_compiled_caches(self):
        body = _client().get("/fingerprint", headers={"X-Internal-Token": TOKEN}).json()
        assert not [n for n in body["source"] if "__pycache__" in n or n.endswith(".pyc")]

    def test_it_is_computed_once_at_import(self):
        """The files cannot change under a running process, and a
        readiness-adjacent endpoint should not do file I/O per request."""
        body = _client().get("/fingerprint", headers={"X-Internal-Token": TOKEN}).json()
        assert body["source"] == SOURCE_FINGERPRINT

    def test_the_hashes_match_the_files_on_disk(self):
        """The whole point: a hash that did not come from the loaded file
        would make the guard confidently wrong."""
        assert source_fingerprint() == SOURCE_FINGERPRINT
