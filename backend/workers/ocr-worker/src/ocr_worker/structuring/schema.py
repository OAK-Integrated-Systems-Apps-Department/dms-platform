"""Output contract for structured extraction, in two forms that must agree:

- EXTRACTION_JSON_SCHEMA: sent to the model server as the decoding
  constraint (Ollama `format`, OpenAI-compatible `response_format`), so a
  schema-capable runtime can only emit JSON of this shape;
- the pydantic models below: the validation actually trusted, applied to
  every model reply whether or not the runtime honoured the schema.

The schema is written out by hand and non-recursive on purpose: grammar-
constrained decoders support inline anyOf/object/array reliably, not always
$ref/$defs. Field values may be scalars, arrays (repeated fields) or one
level of nested object (e.g. an address), which covers form-style documents
without letting the model invent deep structures.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Literal, Union

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from .errors import INCOMPLETE_RESPONSE, INVALID_JSON, SCHEMA_VALIDATION_FAILED, TRAILING_TEXT_IGNORED, StructuringError

STRUCTURED_SCHEMA_VERSION = 1

_STRING = {"type": "string"}
_NUMBER = {"type": "number"}
_BOOLEAN = {"type": "boolean"}
_NULL = {"type": "null"}
_SCALAR = {"anyOf": [_STRING, _NUMBER, _BOOLEAN, _NULL]}
_SCALAR_ARRAY = {"type": "array", "items": _SCALAR}
_NULLABLE_STRING = {"anyOf": [_STRING, _NULL]}

_FIELD_VALUE = {
    "anyOf": [
        _STRING,
        _NUMBER,
        _BOOLEAN,
        _NULL,
        {
            "type": "array",
            "items": {
                "anyOf": [
                    _STRING, _NUMBER, _BOOLEAN, _NULL,
                    {"type": "object", "additionalProperties": {"anyOf": [_STRING, _NUMBER, _BOOLEAN, _NULL, _SCALAR_ARRAY]}},
                ]
            },
        },
        {
            "type": "object",
            "additionalProperties": {
                "anyOf": [
                    _STRING, _NUMBER, _BOOLEAN, _NULL, _SCALAR_ARRAY,
                    {"type": "object", "additionalProperties": _SCALAR},
                ]
            },
        },
    ]
}

EXTRACTION_JSON_SCHEMA: dict = {
    "type": "object",
    "properties": {
        "document_type": _NULLABLE_STRING,
        "fields": {"type": "object", "additionalProperties": _FIELD_VALUE},
        "tables": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": _NULLABLE_STRING,
                    "page": {"anyOf": [{"type": "integer"}, _NULL]},
                    "headers": {"type": "array", "items": _NULLABLE_STRING},
                    "rows": {"type": "array", "items": _SCALAR_ARRAY},
                },
                "required": ["name", "page", "headers", "rows"],
            },
        },
        "sections": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"heading": _NULLABLE_STRING, "content": _NULLABLE_STRING},
                "required": ["heading", "content"],
            },
        },
    },
    "required": ["document_type", "fields", "tables", "sections"],
}

# bool before int/float: pydantic's smart union keeps the exact JSON type,
# but listing it first documents that true/false must stay booleans.
Scalar = Union[bool, int, float, str, None]
ListItem = Union[Scalar, dict[str, Union[Scalar, list[Scalar]]]]
ObjectValue = Union[Scalar, list[Scalar], dict[str, Scalar]]
FieldValue = Union[Scalar, list[ListItem], dict[str, ObjectValue]]


class _Lenient(BaseModel):
    # Unknown keys from a runtime that ignores the schema are dropped, not
    # fatal -- the keys that matter are still type-checked.
    model_config = ConfigDict(extra="ignore")


class ExtractedTable(_Lenient):
    name: str | None = None
    page: int | None = None
    headers: list[str | None] = Field(default_factory=list)
    rows: list[list[Scalar]] = Field(default_factory=list)


class ExtractedSection(_Lenient):
    heading: str | None = None
    content: str | None = None


class ExtractionResult(_Lenient):
    document_type: str | None = None
    fields: dict[str, FieldValue] = Field(default_factory=dict)
    tables: list[ExtractedTable] = Field(default_factory=list)
    sections: list[ExtractedSection] = Field(default_factory=list)


# ----------------------------------------------------------------------
# Final envelope -- what is stored in MinIO and returned to dms-core.
# ----------------------------------------------------------------------
class ResultError(BaseModel):
    code: str
    message: str
    retryable: bool = False


class ResultWarning(BaseModel):
    model_config = ConfigDict(extra="allow")
    code: str
    message: str


class ModelInfo(BaseModel):
    provider: str
    name: str


class StructuredResult(BaseModel):
    """Validated before it is stored or returned. `fields` is typed with the
    same FieldValue as the model reply, so a merge/normalization bug can't
    produce a shape the schema doesn't allow either."""

    model_config = ConfigDict(extra="forbid")

    schema_version: int = STRUCTURED_SCHEMA_VERSION
    status: Literal["pending", "completed", "partial", "failed", "skipped"]
    document_version_id: str | None = None
    ocr_output_ref: str | None = None
    document_type: str | None = None
    fields: dict[str, FieldValue] = Field(default_factory=dict)
    tables: list[ExtractedTable] = Field(default_factory=list)
    sections: list[ExtractedSection] = Field(default_factory=list)
    warnings: list[ResultWarning] = Field(default_factory=list)
    error: ResultError | None = None
    model: ModelInfo | None = None
    stats: dict[str, int | float | None] = Field(default_factory=dict)
    created_at: str = Field(default_factory=lambda: datetime.now(timezone.utc).isoformat())


# ----------------------------------------------------------------------
# Parsing a model reply
# ----------------------------------------------------------------------
def parse_model_reply(raw: str) -> tuple[ExtractionResult, list[dict]]:
    """JSON-parse and schema-validate one model reply.

    Tolerates the two harmless deviations small models produce even when
    told not to -- a ```json fence, and prose after the closing brace (the
    object is kept, the rest dropped with a warning). Distinguishes a reply
    cut off mid-object (INCOMPLETE_RESPONSE -- the caller can retry with a
    smaller chunk) from one that is simply not JSON (INVALID_JSON). Error
    messages describe positions/types only, never the reply's content.
    """
    warnings: list[dict] = []
    text = (raw or "").strip()
    if not text:
        raise StructuringError(INCOMPLETE_RESPONSE, "the model returned an empty reply")
    if text.startswith("```"):
        text = text.split("\n", 1)[1] if "\n" in text else ""
        if text.rstrip().endswith("```"):
            text = text.rstrip()[:-3]
        text = text.strip()

    start = text.find("{")
    if start < 0:
        raise StructuringError(INVALID_JSON, "the model reply contains no JSON object")
    try:
        obj, end = json.JSONDecoder().raw_decode(text, start)
    except json.JSONDecodeError as exc:
        truncated = exc.pos >= len(text) - 1 or exc.msg.startswith("Unterminated") or not _balanced(text[start:])
        if truncated:
            raise StructuringError(INCOMPLETE_RESPONSE, f"the model reply ends mid-JSON (at char {exc.pos} of {len(text)})") from None
        raise StructuringError(INVALID_JSON, f"the model reply is not valid JSON ({exc.msg} at char {exc.pos})") from None
    if start > 0 or text[end:].strip():
        warnings.append({"code": TRAILING_TEXT_IGNORED, "message": "non-JSON text around the model's JSON object was ignored"})
    if not isinstance(obj, dict):
        raise StructuringError(SCHEMA_VALIDATION_FAILED, f"expected a JSON object, got {type(obj).__name__}")

    try:
        return ExtractionResult.model_validate(obj), warnings
    except ValidationError as exc:
        raise StructuringError(SCHEMA_VALIDATION_FAILED, summarize_validation_error(exc)) from None


def summarize_validation_error(exc: ValidationError, limit: int = 5) -> str:
    """Locations and error types only: pydantic's default str() embeds the
    offending input values, which would put document data into logs."""
    parts = []
    for error in exc.errors(include_input=False, include_url=False)[:limit]:
        location = ".".join(str(p) for p in error.get("loc", ())) or "<root>"
        parts.append(f"{location}: {error.get('type')}")
    more = exc.error_count() - limit
    return "; ".join(parts) + (f"; (+{more} more)" if more > 0 else "")


def _balanced(text: str) -> bool:
    depth = 0
    in_string = False
    escaped = False
    for char in text:
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        elif char == '"':
            in_string = True
        elif char in "{[":
            depth += 1
        elif char in "}]":
            depth -= 1
    return depth <= 0 and not in_string
