"""Error-code taxonomy for OCR pipeline failures (Phase 1 stability --
docs/ocr/ocr-production-prompt.md section 3.12; verified need for this via
docs/ocr/failure-analysis.md #9, where a real password-protected PDF and a
real corrupt PDF both surfaced as raw, unclassified internal exception text
-- Docling's own parser error message and PyMuPDF's own `FileDataError`,
neither of which is anything a user or support engineer should ever see
directly).

Each subclass carries a fixed `code` used to format
`processing_jobs.last_error`/the `.failed` event's `error` field as
`f"{code}: {message}"` (see `formatted()`) -- dms-core maps the leading
code to a friendly, format-specific message; the frontend never sees the
raw text.

`FILE_REPAIRED` is deliberately NOT one of these -- it is not a failure
(the job still completes), it's a `flags` entry on an otherwise-successful
result, exactly like `low_confidence`. See `layout.py`'s
`DoclingLayoutDocument.file_repaired` / `pipeline.py`'s `_finalize_success`.

`LOW_IMAGE_QUALITY` is defined here for forward-compatibility with Phase 2
(blur/contrast/skew scoring) but nothing raises it yet in Phase 1 -- there
is no quality-assessment step to produce that signal.
"""
from __future__ import annotations


class OcrEngineError(Exception):
    """Base class for every classified OCR pipeline failure. Never raised
    directly -- always one of the subclasses below, each with its own fixed
    `code`.
    """

    code: str = "ENGINE_ERROR"

    def formatted(self) -> str:
        return f"{self.code}: {self}"


class UnsupportedFormatError(OcrEngineError):
    """MIME type not in SUPPORTED_MIME_TYPES (layout.py), or a real but
    unimplemented format this phase deliberately doesn't convert (e.g.
    legacy DOC/XLS/PPT -- no LibreOffice conversion in Phase 1, see
    docs/ocr/failure-analysis.md's Phase 1 plan, Decision 3).
    """

    code = "UNSUPPORTED_FORMAT"


class EncryptedPdfError(OcrEngineError):
    """`fitz.open(...).needs_pass` was True. Never attempt to guess a
    password -- this is a definite, permanent failure for this pipeline,
    not a retryable one.
    """

    code = "ENCRYPTED_PDF"


class CorruptFileError(OcrEngineError):
    """The file could not be opened at all (a real `fitz.open` exception,
    or a zero-page document). Reserved for files that never opened --
    NOT for a file PyMuPDF silently repaired and successfully read (see
    docs/ocr/failure-analysis.md #9's live finding that PyMuPDF tolerates
    far more corruption than expected); a repaired-but-opened file is
    `flags.file_repaired`, not this.
    """

    code = "CORRUPT_FILE"


class TooManyPagesError(OcrEngineError):
    """Exceeds `Settings.ocr_max_pages` -- checked right after
    `page_count()`, before any rendering/Docling work, so an oversized
    document never even starts consuming a worker slot.
    """

    code = "TOO_MANY_PAGES"


class FileTooLargeError(OcrEngineError):
    """Exceeds `Settings.ocr_max_file_size_bytes`."""

    code = "FILE_TOO_LARGE"


class OcrTimeoutError(OcrEngineError):
    """Per-document timeout (`Settings.ocr_timeout_base_s +
    page_count * ocr_timeout_per_page_s`) elapsed before the pool worker
    finished. Raised by the dispatching side once it observes the
    timeout -- the pool worker itself is terminated by `pebble` and never
    gets to run its own finalize step, so the caller writes this failure
    record instead.
    """

    code = "TIMEOUT"


class WorkerCrashError(OcrEngineError):
    """Raised by the reaper (see `repository.py`'s reaper queries) when a
    job's lease expired and it has exhausted `max_delivery_attempts` --
    the worker that held the lease never came back to report success or
    failure at all (e.g. an OOM kill), so nothing else would ever move
    this version out of 'processing'.
    """

    code = "WORKER_CRASH"


class EngineError(OcrEngineError):
    """Catch-all for a real Docling/PaddleOCR exception that isn't one of
    the more specific cases above -- classified rather than the raw
    `str(exc)` this pipeline used to record verbatim (see
    docs/ocr/failure-analysis.md #9).
    """

    code = "ENGINE_ERROR"


class LowImageQualityError(OcrEngineError):
    """Defined for forward-compatibility with Phase 2's quality-assessment
    step (blur/contrast/skew scoring) -- nothing raises this in Phase 1.
    """

    code = "LOW_IMAGE_QUALITY"


# Every code this worker can record, as a set, so configuration that
# names a code can be validated at startup rather than failing silently
# at the moment it would have mattered (see
# `Settings.retry_attempts_by_code`). Derived from the classes above so
# a new error type cannot be added without appearing here.
KNOWN_ERROR_CODES: frozenset[str] = frozenset(
    subclass.code
    for subclass in OcrEngineError.__subclasses__()
    if getattr(subclass, "code", None)
)


def error_code_of(message: str | None) -> str | None:
    """The code prefix of a `CODE: human message` string, or None.

    `processing_jobs.last_error` stores failures in that shape (section
    3.12), so this is how a later reader -- the reaper deciding whether a
    job may be retried -- recovers the cause without a second column.
    Returns None for anything that is not a recognized code, which the
    caller must treat as "unknown cause", never as "no failure".
    """
    if not message:
        return None
    code, separator, _ = str(message).partition(":")
    code = code.strip().upper()
    if not separator or code not in KNOWN_ERROR_CODES:
        return None
    return code
