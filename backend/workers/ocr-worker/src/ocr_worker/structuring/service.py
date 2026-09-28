"""OCR pages -> normalized representation -> chunks -> local LLM -> JSON
validation -> grounding -> merged, validated structured result.

Chunks are processed sequentially (one model, limited hardware). Per chunk:
invalid/non-schema JSON is retried with a repair note (LLM_MAX_RETRIES); a
reply cut off by the output-token limit, or a prompt the server had to
truncate, splits the chunk in two and retries each half -- a smaller input
means a smaller answer. A failure that would only repeat for every chunk
(model unavailable, timeout) stops the document early. At least one good
chunk makes the result `partial` rather than `failed`.

Logging here is metadata only (counts, durations, error codes). Document
text, prompts, model replies and extracted values are never logged.
"""
from __future__ import annotations

import logging
import re
import time
import unicodedata
from collections import Counter
from dataclasses import dataclass, field

from pydantic import TypeAdapter, ValidationError

from .chunking import Chunk, estimate_tokens, plan_chunks
from .document_model import build_normalized_document
from .errors import (
    CONTEXT_OVERFLOW,
    DOCUMENT_TOO_LARGE,
    EMPTY_OCR_RESULT,
    FATAL_CODES,
    INCOMPLETE_RESPONSE,
    INTERNAL_ERROR,
    MALFORMED_TABLE,
    StructuringError,
)
from .grounding import SourceIndex, ground
from .llm_client import LlmClient
from .prompts import SYSTEM_PROMPT, TargetField, build_messages, target_block
from .schema import ExtractedTable, ExtractionResult, FieldValue, StructuredResult, extraction_schema, parse_model_reply

logger = logging.getLogger(__name__)

_MAX_SPLIT_DEPTH = 4
# Chat-template + user-message wrapper tokens not covered by the estimate.
_PROMPT_WRAPPER_TOKENS = 160
_MIN_CHUNK_TOKENS = 256
_field_value_adapter = TypeAdapter(FieldValue)


@dataclass
class _ChunkOutcome:
    index: int
    result: ExtractionResult | None = None
    error: StructuringError | None = None
    warnings: list[dict] = field(default_factory=list)


@dataclass
class _Usage:
    calls: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    llm_ms: int = 0


def normalize_key(key: str) -> str:
    folded = unicodedata.normalize("NFKC", str(key)).strip().casefold()
    snake = re.sub(r"_+", "_", re.sub(r"[^\w]+", "_", folded)).strip("_")
    if not snake:
        snake = "field"
    if snake[0].isdigit():
        snake = f"field_{snake}"
    return snake[:100]


class StructuredExtractionService:
    def __init__(self, settings, client: LlmClient | None, client_error: StructuringError | None = None):
        self._settings = settings
        self._client = client
        self._client_error = client_error

    @property
    def model_info(self) -> dict:
        return {"provider": self._settings.llm_provider, "name": self._settings.llm_model}

    @property
    def client(self) -> LlmClient | None:
        return self._client

    @property
    def client_error(self) -> StructuringError | None:
        return self._client_error

    def chunk_budget_tokens(self, targets: list[TargetField] | None = None) -> int:
        s = self._settings
        prompt = SYSTEM_PROMPT + (target_block(targets) if targets else "")
        derived = s.llm_context_tokens - s.llm_max_output_tokens - estimate_tokens(prompt, s.llm_chars_per_token) - _PROMPT_WRAPPER_TOKENS
        budget = min(s.llm_chunk_max_tokens, derived) if s.llm_chunk_max_tokens > 0 else derived
        return max(budget, _MIN_CHUNK_TOKENS)

    # ------------------------------------------------------------------
    def extract(
        self,
        pages: list[dict],
        *,
        document_version_id: str | None = None,
        ocr_output_ref: str | None = None,
        targets: list[TargetField] | None = None,
    ) -> dict:
        """`targets`: the DMS fields this document's type needs (requested
        fields); each comes back under its own key in `fields` (value or
        null). None = free-form extraction only."""
        started = time.monotonic()
        base = {"document_version_id": document_version_id, "ocr_output_ref": ocr_output_ref, "model": self.model_info,
                "requested_fields": [t.key for t in targets] if targets else None}
        try:
            envelope = self._extract(pages, base, started, targets or [])
        except Exception as exc:  # noqa: BLE001 - structuring must never take the OCR worker down; type only, no message (may hold content)
            logger.error("structured extraction crashed for version %s (%s)", document_version_id, type(exc).__name__)
            logger.debug("structured extraction traceback", exc_info=True)
            envelope = {
                **base,
                "status": "failed",
                "error": StructuringError(INTERNAL_ERROR, f"unexpected {type(exc).__name__} during structuring").to_dict(),
                "stats": {"duration_ms": int((time.monotonic() - started) * 1000)},
            }
        return validated_result(envelope)

    def _extract(self, pages: list[dict], base: dict, started: float, targets: list[TargetField]) -> dict:
        s = self._settings
        if self._client is None:
            error = self._client_error or StructuringError(INTERNAL_ERROR, "no LLM client configured")
            return {**base, "status": "failed", "error": error.to_dict()}

        document = build_normalized_document(pages, include_bboxes=s.llm_include_bboxes, low_confidence_threshold=s.llm_low_confidence_line_threshold)
        warnings: list[dict] = list(document.warnings)
        if document.is_empty:
            error = StructuringError(EMPTY_OCR_RESULT, "OCR produced no text on any page; nothing to structure")
            return {**base, "status": "skipped", "error": error.to_dict(), "warnings": warnings, "stats": {"pages": len(document.pages)}}

        budget = self.chunk_budget_tokens(targets)
        chunks = plan_chunks(document, budget_tokens=budget, chars_per_token=s.llm_chars_per_token)
        planned = len(chunks)
        truncated = planned > s.llm_max_chunks
        if truncated:
            chunks = chunks[: s.llm_max_chunks]
            last_page = chunks[-1].page_numbers[-1]
            warnings.append({
                "code": DOCUMENT_TOO_LARGE,
                "message": f"document needs {planned} chunks, limit is {s.llm_max_chunks} (LLM_MAX_CHUNKS); structured through page {last_page} only",
            })

        usage = _Usage()
        outcomes: list[_ChunkOutcome] = []
        fatal: StructuringError | None = None
        for index, chunk in enumerate(chunks):
            if fatal is not None:
                outcomes.append(_ChunkOutcome(index=index, error=StructuringError(fatal.code, f"skipped after an earlier chunk failed with {fatal.code}")))
                continue
            for outcome in self._process_chunk(chunk, index=index, total=len(chunks), usage=usage, targets=targets):
                outcomes.append(outcome)
                if outcome.error is not None and outcome.error.code in FATAL_CODES:
                    fatal = outcome.error

        successes = [o for o in outcomes if o.result is not None]
        failures = [o for o in outcomes if o.error is not None]
        for failure in failures:
            warnings.append({"code": failure.error.code, "message": f"chunk {failure.index + 1}: {failure.error.message}", "chunk": failure.index + 1})
        for success in successes:
            warnings.extend(success.warnings)

        stats = {
            "pages": len(document.pages),
            "lines": len(document.lines),
            "tables_detected": len(document.tables),
            "key_value_candidates": len(document.key_values),
            "chunks_planned": planned,
            "chunks_processed": len(chunks),
            "chunks_failed": len({f.index for f in failures}),
            "chunk_budget_tokens": budget,
            "llm_calls": usage.calls,
            "prompt_tokens": usage.prompt_tokens,
            "completion_tokens": usage.completion_tokens,
            "llm_ms": usage.llm_ms,
            "duration_ms": int((time.monotonic() - started) * 1000),
        }

        if not successes:
            error = (fatal or failures[0].error).to_dict()
            logger.warning("structured extraction failed for version %s: %s after %d call(s)", base["document_version_id"], error["code"], usage.calls)
            return {**base, "status": "failed", "error": error, "warnings": warnings, "stats": stats}

        merged, merge_warnings = _merge([(o.index, o.result) for o in successes], keep_keys={t.key for t in targets})
        warnings.extend(merge_warnings)
        status = "partial" if (failures or truncated) else "completed"
        logger.info(
            "structured extraction %s for version %s: %d field(s), %d table(s), %d chunk(s), %d call(s), %d ms",
            status, base["document_version_id"], len(merged.fields), len(merged.tables), len(chunks), usage.calls, stats["duration_ms"],
        )
        return {
            **base,
            "status": status,
            "document_type": merged.document_type,
            "fields": merged.fields,
            "tables": [t.model_dump() for t in merged.tables],
            "sections": [s_.model_dump() for s_ in merged.sections],
            "warnings": warnings,
            "stats": stats,
        }

    def _process_chunk(self, chunk: Chunk, *, index: int, total: int, usage: _Usage, targets: list[TargetField]) -> list[_ChunkOutcome]:
        s = self._settings
        schema = extraction_schema([t.key for t in targets]) if s.llm_use_json_schema else None
        repair_problem: str | None = None
        for attempt in range(s.llm_max_retries + 1):
            messages = build_messages(chunk.render(), part=index + 1, total=total, pages=chunk.page_numbers, repair_problem=repair_problem, targets=targets)
            try:
                response = self._client.complete_json(messages, schema=schema, max_tokens=s.llm_max_output_tokens)
            except StructuringError as exc:
                if exc.code == CONTEXT_OVERFLOW:
                    return self._split_or_fail(chunk, exc, index=index, total=total, usage=usage, targets=targets)
                return [_ChunkOutcome(index=index, error=exc)]
            usage.calls += 1
            usage.prompt_tokens += response.prompt_tokens or 0
            usage.completion_tokens += response.completion_tokens or 0
            usage.llm_ms += response.duration_ms

            if response.prompt_tokens is not None and response.prompt_tokens >= s.llm_context_tokens - 16:
                overflow = StructuringError(CONTEXT_OVERFLOW, f"prompt used {response.prompt_tokens} of {s.llm_context_tokens} context tokens and was likely truncated")
                return self._split_or_fail(chunk, overflow, index=index, total=total, usage=usage, targets=targets)
            if response.finish_reason == "length":
                cut = StructuringError(INCOMPLETE_RESPONSE, f"reply hit the {s.llm_max_output_tokens}-token output limit (LLM_MAX_OUTPUT_TOKENS)")
                return self._split_or_fail(chunk, cut, index=index, total=total, usage=usage, targets=targets)

            try:
                result, parse_warnings = parse_model_reply(response.content)
            except StructuringError as exc:
                if attempt < s.llm_max_retries:
                    repair_problem = exc.message
                    continue
                if exc.code == INCOMPLETE_RESPONSE:
                    return self._split_or_fail(chunk, exc, index=index, total=total, usage=usage, targets=targets)
                return [_ChunkOutcome(index=index, error=exc)]

            grounded, grounding_warnings = ground(result, SourceIndex(chunk.source_texts), s.llm_grounding_policy)
            return [_ChunkOutcome(index=index, result=grounded, warnings=parse_warnings + grounding_warnings)]
        raise AssertionError("unreachable")  # pragma: no cover

    def _split_or_fail(self, chunk: Chunk, error: StructuringError, *, index: int, total: int, usage: _Usage, targets: list[TargetField]) -> list[_ChunkOutcome]:
        if chunk.depth >= _MAX_SPLIT_DEPTH or not chunk.can_split():
            return [_ChunkOutcome(index=index, error=error)]
        first, second = chunk.split()
        return (self._process_chunk(first, index=index, total=total, usage=usage, targets=targets)
                + self._process_chunk(second, index=index, total=total, usage=usage, targets=targets))


# ----------------------------------------------------------------------
# Merging chunk results
# ----------------------------------------------------------------------
def _merge(results: list[tuple[int, ExtractionResult]], keep_keys: set[str] = frozenset()) -> tuple[ExtractionResult, list[dict]]:
    """`keep_keys` (the DMS's requested field keys) are kept verbatim
    instead of normalized, so they still match their document_type field."""
    warnings: list[dict] = []
    fields: dict = {}
    tables: list[tuple[int, ExtractedTable]] = []
    sections = []
    document_types: Counter[str] = Counter()

    for chunk_index, result in results:
        if result.document_type:
            document_types[normalize_key(result.document_type)] += 1
        for raw_key, value in result.fields.items():
            key = raw_key if raw_key in keep_keys else normalize_key(raw_key)
            merged = _merge_value(fields.get(key), value)
            try:
                fields[key] = _field_value_adapter.validate_python(merged)
            except ValidationError:
                warnings.append({"code": "CONFLICTING_VALUES", "message": "differing values for one field could not be combined; the first was kept", "path": f"fields.{key}"})
        for table in result.tables:
            table = _square_table(table, len(tables), warnings)
            previous = tables[-1] if tables else None
            # A table split across a chunk boundary comes back as two tables
            # with identical headers from consecutive chunks -- rejoin it.
            if previous and previous[0] != chunk_index and table.headers and table.headers == previous[1].headers:
                tables[-1] = (chunk_index, previous[1].model_copy(update={"rows": previous[1].rows + table.rows}))
            else:
                tables.append((chunk_index, table))
        sections.extend(s for s in result.sections if s.heading or s.content)

    document_type = document_types.most_common(1)[0][0] if document_types else None
    return ExtractionResult(document_type=document_type, fields=fields, tables=[t for _, t in tables], sections=sections), warnings


def _merge_value(existing, new):
    if existing is None:
        return new
    if new is None or new == existing:
        return existing
    if isinstance(existing, dict) and isinstance(new, dict):
        combined = dict(existing)
        for key, value in new.items():
            if combined.get(key) is None:
                combined[key] = value
        return combined
    combined = list(existing) if isinstance(existing, list) else [existing]
    for value in new if isinstance(new, list) else [new]:
        if value not in combined:
            combined.append(value)
    return combined


def _square_table(table: ExtractedTable, position: int, warnings: list[dict]) -> ExtractedTable:
    width = len(table.headers)
    if not width:
        return table
    table = _drop_row_number_column(table, warnings, position)
    rows = []
    ragged = False
    for row in table.rows:
        if len(row) < width:
            row = row + [None] * (width - len(row))
            ragged = True
        elif len(row) > width:
            ragged = True
        rows.append(row)
    if ragged:
        warnings.append({"code": MALFORMED_TABLE, "message": "model returned rows whose length differs from the header count; short rows were padded with null", "path": f"tables[{position}]"})
    return table.model_copy(update={"rows": rows})


def _drop_row_number_column(table: ExtractedTable, warnings: list[dict], position: int) -> ExtractedTable:
    """Small models often prepend a row counter (0, 1, 2... or 1, 2, 3...)
    the document doesn't have, shifting every cell one column right of its
    header. Every row one cell longer than the headers, with a leading
    0- or 1-based sequence, is that artifact: the counter is dropped. (The
    grounding guard flags such counters too; this restores the alignment.)"""
    rows, width = table.rows, len(table.headers)
    if not rows or any(len(r) != width + 1 for r in rows):
        return table
    firsts = [r[0] for r in rows]
    for start in (0, 1):
        if all(str(v).strip() == str(start + i) for i, v in enumerate(firsts)):
            warnings.append({"code": MALFORMED_TABLE, "message": "model added a row-number column; removed", "path": f"tables[{position}]"})
            return table.model_copy(update={"rows": [r[1:] for r in rows]})
    return table


def validated_result(envelope: dict) -> dict:
    """Rule 6 of the task: the JSON is validated before it leaves this
    service. A failure here is a bug in merging, reported as such rather
    than returning a shape the schema doesn't allow."""
    try:
        return StructuredResult.model_validate(envelope).model_dump(mode="json")
    except ValidationError as exc:
        logger.error("structured result failed final validation (%d error(s))", exc.error_count())
        fallback = {
            "status": "failed",
            "document_version_id": envelope.get("document_version_id"),
            "ocr_output_ref": envelope.get("ocr_output_ref"),
            "model": envelope.get("model"),
            "error": StructuringError(INTERNAL_ERROR, "structured result failed final schema validation").to_dict(),
            "stats": envelope.get("stats") or {},
        }
        return StructuredResult.model_validate(fallback).model_dump(mode="json")
