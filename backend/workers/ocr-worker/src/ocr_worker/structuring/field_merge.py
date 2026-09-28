"""Folds the LLM's values for the DMS's requested fields into the
rule-based results (extraction.extract_requested_fields) that
document_ocr_field_results -- and therefore the upload form and the
document's metadata -- are built from.

Without this, structured extraction ran and was stored, but nothing the
DMS displays ever read it: every requested field kept its label-hint/regex
value. This is where the LLM's document understanding reaches the DMS.

Policy (LLM_FIELD_POLICY):
- "llm_first" (default): when structuring succeeded, a requested field
  takes the LLM's grounded value; where the LLM returned null, the field is
  not_found -- the LLM saw the whole layout and judged it absent (a blank
  label), which is exactly where the rule-based matcher tends to grab a
  neighbouring value. If structuring failed, rule results stay untouched.
- "fill_missing": rule results win; the LLM only fills fields the rules
  didn't find.

Only values that passed the grounding guard are used, so this cannot
introduce an invented value. A value the guard nulled is NOT the model
judging the field absent -- the model found something, it just couldn't be
matched to the source text -- so it never clears a rule result.

Values are coerced to what dms-core's metadata layer and the upload form
actually accept (DocumentMetadataServiceImpl#buildRow, document-fields-
form.tsx): ISO dates, plain numbers, "true"/"false", a configured option.
A value that can't be coerced keeps the rule result rather than being
written and then silently skipped by dms-core.
"""
from __future__ import annotations

import dataclasses
import json
import re
from decimal import Decimal, InvalidOperation

from ..extraction import FieldSpec, RequestedFieldResult, _match_enum, _normalize_date
from .errors import UNGROUNDED_VALUE

POLICIES = ("llm_first", "fill_missing")
SOURCE_LLM = "llm"
SOURCE_RULES = "rules"

_HAS_MATCH_BASIS = "match_basis" in {f.name for f in dataclasses.fields(RequestedFieldResult)}
_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_NUMBER = re.compile(r"-?\d[\d,]*(?:\.\d+)?")
_TRUE_WORDS = {"true", "yes", "y", "checked", "x", "✓", "✔", "☑"}
_FALSE_WORDS = {"false", "no", "n", "unchecked", "☐"}


def _as_number(value) -> str | None:
    """A plain decimal string ("150000.00") -- dms-core's BigDecimal parse
    and the form's type=number input both reject currency, units and (the
    input) thousands separators."""
    if isinstance(value, (int, float)):
        try:
            return format(Decimal(repr(value)), "f")
        except InvalidOperation:
            return None
    numbers = _NUMBER.findall(str(value))
    if len(numbers) != 1:
        return None  # none, or several ("10 x 25.00"): ambiguous
    return numbers[0].replace(",", "")


def _as_boolean(value) -> str | None:
    if isinstance(value, bool):
        return "true" if value else "false"
    word = str(value).strip().casefold()
    if word in _TRUE_WORDS:
        return "true"
    if word in _FALSE_WORDS:
        return "false"
    return None


def _as_field_value(spec: FieldSpec, value):
    """The LLM's value in the shape document_ocr_field_results expects, or
    None when it doesn't fit the field (then the rule result is kept)."""
    if value is None:
        return None
    if spec.data_type == "multi_select":
        items = value if isinstance(value, list) else [value]
        items = [str(v).strip() for v in items if isinstance(v, (str, int, float)) and not isinstance(v, bool) and str(v).strip()]
        if spec.options:
            # The form only renders configured options; anything else would be invisible.
            items = [m for m in (_match_enum(i, spec.options, spec.field_key) for i in items) if m]
            items = list(dict.fromkeys(items))
        return items or None
    if isinstance(value, list):
        scalars = [v for v in value if isinstance(v, (str, int, float)) and not isinstance(v, bool)]
        if len(scalars) != 1:
            return None  # several values for a single-value field: ambiguous, keep the rules' answer
        value = scalars[0]
    if isinstance(value, dict):
        # A grouped value (e.g. an address object) for a text field: its parts in document order.
        if spec.data_type not in ("text", "long_text"):
            return None
        parts = [str(v).strip() for v in value.values() if isinstance(v, (str, int, float)) and not isinstance(v, bool) and str(v).strip()]
        value = ", ".join(parts)
    if spec.data_type == "boolean":
        return _as_boolean(value)
    if isinstance(value, bool):
        return None  # true/false for a non-boolean field doesn't answer it
    if spec.data_type in ("number", "decimal"):
        return _as_number(value)
    text = str(value).strip()
    if not text:
        return None
    if spec.data_type in ("date", "datetime"):
        iso = _normalize_date(text)
        return iso if _ISO_DATE.match(iso) else None
    if spec.data_type == "dropdown" and spec.options:
        return _match_enum(text, spec.options, spec.field_key)
    return text


def _ungrounded_keys(structured: dict) -> set[str]:
    """Requested-field keys whose value the grounding guard nulled (its
    warnings name the JSON path, never the value)."""
    keys = set()
    for warning in structured.get("warnings") or []:
        path = str(warning.get("path") or "")
        if warning.get("code") == UNGROUNDED_VALUE and path.startswith("fields."):
            keys.add(re.split(r"[.\[]", path[len("fields."):], maxsplit=1)[0])
    return keys


def merge_requested_fields(
    specs: list[FieldSpec],
    rule_results: list[RequestedFieldResult],
    structured: dict,
    *,
    policy: str = "llm_first",
    llm_confidence: float = 0.85,
) -> tuple[list[RequestedFieldResult], dict[str, str]]:
    """Returns (merged results, {field_key: "llm" | "rules"})."""
    by_key = {r.field_key: r for r in rule_results}
    sources = {r.field_key: SOURCE_RULES for r in rule_results}
    if structured.get("status") not in ("completed", "partial"):
        return list(rule_results), sources
    fields = structured.get("fields") or {}
    requested = set(structured.get("requested_fields") or [])
    ungrounded = _ungrounded_keys(structured)
    merged: list[RequestedFieldResult] = []
    for spec in specs:
        rule = by_key.get(spec.field_key) or RequestedFieldResult(field_key=spec.field_key, value=None, confidence=None, status="not_found")
        if spec.field_key not in requested:
            merged.append(rule)
            continue
        llm_value = _as_field_value(spec, fields.get(spec.field_key))
        rule_found = rule.status != "not_found" and rule.value not in (None, "", [])
        if llm_value is None:
            if (policy == "llm_first" and fields.get(spec.field_key) is None and structured.get("status") == "completed"
                    and spec.field_key not in ungrounded):
                # The model judged the value absent (and said so as null).
                merged.append(_replace(rule, value=None, confidence=None, status="not_found", page=None, bbox=None))
                sources[spec.field_key] = SOURCE_LLM
            else:
                merged.append(rule)
            continue
        if policy == "fill_missing" and rule_found:
            merged.append(rule)
            continue
        if rule_found and _same(_as_field_value(spec, rule.value) or rule.value, llm_value):
            # Agreement: keep the rule result's position/bbox, in the coerced form.
            merged.append(rule if rule.value == llm_value else dataclasses.replace(rule, value=llm_value))
            sources[spec.field_key] = SOURCE_LLM
            continue
        merged.append(_replace(rule, value=llm_value, confidence=llm_confidence, status="extracted", page=None, bbox=None))
        sources[spec.field_key] = SOURCE_LLM
    return merged, sources


def _replace(result: RequestedFieldResult, **changes) -> RequestedFieldResult:
    if _HAS_MATCH_BASIS:
        # No 'llm' value exists in match_basis's CHECK constraint yet (see
        # README FOLLOW-UP); NULL is allowed and the upload form shows no
        # basis note for it. Provenance is kept in the structured result's
        # `field_sources` instead.
        changes.setdefault("match_basis", None)
    return dataclasses.replace(result, **changes)


def _same(a, b) -> bool:
    norm = lambda v: json.dumps(v, sort_keys=True).casefold() if isinstance(v, list) else str(v).strip().casefold()  # noqa: E731
    return norm(a) == norm(b)
