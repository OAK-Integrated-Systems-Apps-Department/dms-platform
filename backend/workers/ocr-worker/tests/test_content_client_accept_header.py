"""`DocumentContentClient._put` must ask for a representation dms-core can
actually produce.

dms-core answers a successful PUT (`/rendition`, `/output`) with a JSON
`{"objectKey": ...}` body. `_headers` used to return a fixed
`Accept: application/octet-stream` for every request - correct for the GET
that fetches document bytes, wrong for these two, since nothing on the
server can satisfy "give me JSON as octet-stream". Spring's content
negotiation then failed turning the response into octet-stream and this
worker saw an opaque 401 with no hint that Accept was the actual problem
(confirmed live: the job token verified successfully every time - the
failure was never authentication at all).
"""
from __future__ import annotations

from unittest.mock import MagicMock, patch

from ocr_worker.config import Settings
from ocr_worker.content_client import DocumentContentClient, ReadGrant


def _client() -> DocumentContentClient:
    return DocumentContentClient(Settings())


def _mock_put_response(status_code=200, json_body=None):
    response = MagicMock()
    response.status_code = status_code
    response.json.return_value = json_body or {"objectKey": "ocr/some/key"}
    return response


def test_put_output_asks_for_json_not_octet_stream():
    client = _client()
    grant = ReadGrant(token="t", version_id="v1")

    with patch("httpx.Client") as mock_client_cls:
        mock_client = mock_client_cls.return_value.__enter__.return_value
        mock_client.put.return_value = _mock_put_response()

        client.put_output(grant, b'{"fields": {}}')

        _, kwargs = mock_client.put.call_args
        assert kwargs["headers"]["Accept"] == "application/json"


def test_put_rendition_asks_for_json_not_octet_stream():
    client = _client()
    grant = ReadGrant(token="t", version_id="v1")

    with patch("httpx.Client") as mock_client_cls:
        mock_client = mock_client_cls.return_value.__enter__.return_value
        mock_client.put.return_value = _mock_put_response()

        client.put_rendition(grant, b"%PDF-1.4 fake")

        _, kwargs = mock_client.put.call_args
        assert kwargs["headers"]["Accept"] == "application/json"


def test_headers_still_defaults_to_octet_stream_for_the_document_fetch():
    client = _client()
    grant = ReadGrant(token="t", version_id="v1")

    headers = client._headers(grant)

    assert headers["Accept"] == "application/octet-stream"
