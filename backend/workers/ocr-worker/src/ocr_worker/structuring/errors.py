"""Stable error codes for structured extraction. They appear in the stored
result's `error.code`/`warnings[].code` so callers (dms-core, the UI) can
branch on them. Every message here is written to be safe to log and store:
never document text, never an extracted value, never raw model output.
"""
from __future__ import annotations

EMPTY_OCR_RESULT = "EMPTY_OCR_RESULT"
MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"
LLM_TIMEOUT = "LLM_TIMEOUT"
LLM_SERVER_ERROR = "LLM_SERVER_ERROR"
LLM_REQUEST_REJECTED = "LLM_REQUEST_REJECTED"
LLM_ENDPOINT_NOT_LOCAL = "LLM_ENDPOINT_NOT_LOCAL"
LLM_MISCONFIGURED = "LLM_MISCONFIGURED"
INVALID_JSON = "INVALID_JSON"
SCHEMA_VALIDATION_FAILED = "SCHEMA_VALIDATION_FAILED"
INCOMPLETE_RESPONSE = "INCOMPLETE_RESPONSE"
CONTEXT_OVERFLOW = "CONTEXT_OVERFLOW"
DOCUMENT_TOO_LARGE = "DOCUMENT_TOO_LARGE"
MALFORMED_TABLE = "MALFORMED_TABLE"
UNGROUNDED_VALUE = "UNGROUNDED_VALUE"
TRAILING_TEXT_IGNORED = "TRAILING_TEXT_IGNORED"
INTERNAL_ERROR = "INTERNAL_ERROR"

# Failures that will just repeat for every remaining chunk of the same
# document -- structuring stops at the first one instead of paying the
# timeout (or connection error) once per chunk.
FATAL_CODES = frozenset({MODEL_UNAVAILABLE, LLM_TIMEOUT, LLM_ENDPOINT_NOT_LOCAL, LLM_MISCONFIGURED})

RETRYABLE_CODES = frozenset({MODEL_UNAVAILABLE, LLM_TIMEOUT, LLM_SERVER_ERROR, INVALID_JSON, INCOMPLETE_RESPONSE, SCHEMA_VALIDATION_FAILED})


class StructuringError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message

    @property
    def retryable(self) -> bool:
        return self.code in RETRYABLE_CODES

    def to_dict(self) -> dict:
        return {"code": self.code, "message": self.message, "retryable": self.retryable}
