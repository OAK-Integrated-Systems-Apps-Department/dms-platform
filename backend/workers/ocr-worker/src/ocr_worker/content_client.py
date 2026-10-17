"""Reading a document's bytes from dms-core.

Replaces the old direct MinIO read. The worker cannot read storage itself any
more: dms-core encrypts every object at rest, so a direct read returns
ciphertext and a presigned URL is refused outright for an encrypted object.
Teaching this process to decrypt would mean giving it data keys and key-service
access, which is a much larger thing to get wrong than one HTTP call.

So dms-core serves the plaintext, and every read presents two things:

* ``X-Internal-Token`` -- the shared secret this service already uses in the
  other direction, as a second factor only.
* ``X-Ocr-Job-Token`` -- a short-lived HMAC grant naming one job, one version,
  one tenant and an expiry. It arrives on the ``document.version.ocr.requested``
  event for a job, or on the inbound request for a page image / region read.
  This service never mints one; it only presents what it was given.

Three rules this module exists to keep:

1. **Plaintext never touches ordinary disk.** Downloads are streamed into a
   file under ``ocr_tmp_dir``, which must be a tmpfs mount, and that file is
   deleted when the context manager exits - including on failure.
2. **Content is never logged.** Sizes, status codes, version ids and durations
   are logged; bytes, filenames from the document and extracted text are not.
3. **Nothing is cached across jobs.** Each read is scoped to the grant that
   authorised it.
"""

from __future__ import annotations

import contextlib
import logging
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import httpx

from .config import Settings

logger = logging.getLogger(__name__)


class ContentUnavailable(RuntimeError):
    """dms-core would not serve this document.

    Carries the HTTP status so the caller can tell a permanent refusal (401,
    403, 404 - a revoked grant, a purged version) from something worth
    retrying (5xx, a timeout).
    """

    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status

    def __reduce__(self):
        # pebble's process pool pickles an exception to carry it back from
        # the worker subprocess to the parent; the default reduction calls
        # `type(exc)(*exc.args)`, and `args` here is only ever `(message,)`
        # (status lives on `self`, not in `args`) -- so unpickling raised
        # "missing 1 required positional argument: 'status'" instead of the
        # real error, which crashed the whole pool (BrokenProcessPool) for
        # every job in flight rather than failing just the one that hit a
        # real, recoverable refusal (401/403/404/5xx) from dms-core.
        return (self.__class__, (self.status, self.args[0] if self.args else ""))

    @property
    def retryable(self) -> bool:
        return self.status == 0 or self.status >= 500


@dataclass(frozen=True)
class ReadGrant:
    """What authorises one read. Opaque here - only dms-core can verify it."""

    token: str
    version_id: str

    def __repr__(self) -> str:  # pragma: no cover - defensive
        # Never let a grant reach a log or a traceback in full: it is a
        # bearer credential for one document.
        return f"ReadGrant(version_id={self.version_id}, token=<redacted>)"


class DocumentContentClient:
    """Fetches document bytes from dms-core's internal endpoint."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._base_url = settings.dms_core_base_url.rstrip("/")
        self._timeout = httpx.Timeout(
            connect=settings.dms_core_connect_timeout_s,
            read=settings.dms_core_read_timeout_s,
            write=settings.dms_core_read_timeout_s,
            pool=settings.dms_core_connect_timeout_s,
        )

    @contextlib.contextmanager
    def download(self, grant: ReadGrant) -> Iterator[Path]:
        """Stream one document to a temp file and yield its path.

        Streamed rather than returned as bytes: a 25 MB scan held in memory per
        concurrent job is how a worker pool runs a container out of heap, and
        the layout engines all take a path anyway.

        The file lives in ``ocr_tmp_dir`` - which must be a tmpfs mount, so
        plaintext never reaches a real disk - and is removed in the ``finally``
        below whether the caller succeeded, raised, or the process is unwinding
        from a timeout.
        """
        tmp_dir = Path(self._settings.ocr_tmp_dir)
        tmp_dir.mkdir(parents=True, exist_ok=True)
        fd, raw_path = tempfile.mkstemp(dir=tmp_dir, prefix="ocr-", suffix=".bin")
        path = Path(raw_path)
        os.close(fd)

        url = f"{self._base_url}/internal/v1/ocr/versions/{grant.version_id}/content"
        try:
            written = 0
            with httpx.Client(timeout=self._timeout) as client:
                with client.stream("GET", url, headers=self._headers(grant)) as response:
                    if response.status_code != 200:
                        # read() so the body is available; it is an error
                        # envelope, never document content.
                        response.read()
                        raise ContentUnavailable(
                            response.status_code,
                            f"dms-core refused content for version {grant.version_id}: "
                            f"HTTP {response.status_code}",
                        )
                    with path.open("wb") as sink:
                        for chunk in response.iter_bytes():
                            sink.write(chunk)
                            written += len(chunk)
            logger.info(
                "Fetched version %s from dms-core (%d bytes)", grant.version_id, written
            )
            yield path
        except httpx.HTTPError as exc:
            # str(exc) is a URL and a reason, never a body.
            raise ContentUnavailable(0, f"Could not reach dms-core: {exc}") from exc
        finally:
            # Best effort, always: a leftover plaintext document in tmpfs
            # outlives the job that was allowed to read it.
            with contextlib.suppress(OSError):
                path.unlink()

    def _headers(self, grant: ReadGrant, accept: str = "application/octet-stream") -> dict[str, str]:
        return {
            "X-Internal-Token": self._settings.internal_service_token,
            "X-Ocr-Job-Token": grant.token,
            "Accept": accept,
        }

    def put_output(self, grant: ReadGrant, payload: bytes) -> str:
        """Store the OCR result JSON against its version.

        Idempotent on dms-core's side: the key is derived from the version, so
        a retried job overwrites its own output instead of leaving an orphan.
        That is why this is a PUT and why it carries no id of its own.
        """
        return self._put(grant, "output", payload, "application/json")

    def put_rendition(self, grant: ReadGrant, pdf: bytes) -> str:
        """Store the searchable PDF. Same idempotence as `put_output`."""
        return self._put(grant, "rendition", pdf, "application/pdf")

    def _put(self, grant: ReadGrant, kind: str, body: bytes, content_type: str) -> str:
        url = f"{self._base_url}/internal/v1/ocr/versions/{grant.version_id}/{kind}"
        # dms-core answers a successful PUT with a JSON {"objectKey": ...}
        # body, never octet-stream (that Accept default is for the GET below,
        # which really does want raw document bytes) - asking for a
        # representation the endpoint cannot produce here turned into Spring
        # negotiation failing the whole response, which this worker then saw
        # as an opaque 401 with no hint that Accept was the actual problem.
        headers = self._headers(grant, accept="application/json")
        headers["Content-Type"] = content_type
        # Content-Length explicitly: dms-core refuses a chunked artifact so it
        # can enforce its size cap before reading anything.
        headers["Content-Length"] = str(len(body))
        try:
            with httpx.Client(timeout=self._timeout) as client:
                response = client.put(url, content=body, headers=headers)
            if response.status_code not in (200, 204):
                raise ContentUnavailable(
                    response.status_code,
                    f"dms-core refused the {kind} for version {grant.version_id}: "
                    f"HTTP {response.status_code}",
                )
            logger.info(
                "Stored %s for version %s (%d bytes)", kind, grant.version_id, len(body)
            )
            # dms-core owns the key format and hands it back, so the completed
            # event's extracted_text_ref is the key that actually exists rather
            # than one this service guessed.
            return str(response.json().get("objectKey", ""))
        except httpx.HTTPError as exc:
            raise ContentUnavailable(0, f"Could not reach dms-core: {exc}") from exc
