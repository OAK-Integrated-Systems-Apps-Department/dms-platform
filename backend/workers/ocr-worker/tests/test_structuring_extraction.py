"""Model-reply parsing + JSON validation, the grounding (hallucination)
guard, and StructuredExtractionService's orchestration -- driven by a
scripted fake LLM client, so no model server is needed."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.config import Settings  # noqa: E402
from ocr_worker.structuring.errors import StructuringError  # noqa: E402
from ocr_worker.structuring.grounding import SourceIndex, ground  # noqa: E402
from ocr_worker.structuring.llm_client import LlmResponse  # noqa: E402
from ocr_worker.structuring.schema import EXTRACTION_JSON_SCHEMA, ExtractionResult, StructuredResult, parse_model_reply  # noqa: E402
from ocr_worker.structuring.service import StructuredExtractionService, normalize_key  # noqa: E402

EMPLOYEE_PAGE = {
    "page_number": 1,
    "method": "paddleocr",
    "confidence": 0.97,
    "tables": [],
    "blocks": [
        {"text": "EMPLOYMENT CONTRACT", "bbox": [400, 100, 1200, 160], "confidence": 0.99},
        {"text": "Employee Name: John Silva", "bbox": [150, 300, 900, 340], "confidence": 0.99},
        {"text": "Employee ID: EMP-1023", "bbox": [150, 360, 900, 400], "confidence": 0.98},
        {"text": "Department: Finance", "bbox": [150, 420, 900, 460], "confidence": 0.99},
        {"text": "Joining Date: 2026-09-01", "bbox": [150, 480, 900, 520], "confidence": 0.97},
        {"text": "Salary: LKR 150,000", "bbox": [150, 540, 900, 580], "confidence": 0.96},
    ],
}

EXPECTED_FIELDS = {
    "employee_name": "John Silva",
    "employee_id": "EMP-1023",
    "department": "Finance",
    "joining_date": "2026-09-01",
    "salary": "LKR 150,000",
}


def _reply(obj, finish="stop", prompt_tokens=900, completion_tokens=120) -> LlmResponse:
    content = obj if isinstance(obj, str) else json.dumps(obj)
    return LlmResponse(content=content, finish_reason=finish, prompt_tokens=prompt_tokens, completion_tokens=completion_tokens, duration_ms=5)


class ScriptedClient:
    provider = "fake"
    model = "fake-7b"

    def __init__(self, *replies):
        self._replies = list(replies)
        self.calls: list[list[dict]] = []

    def complete_json(self, messages, *, schema, max_tokens):
        self.calls.append(messages)
        reply = self._replies.pop(0) if len(self._replies) > 1 else self._replies[0]
        if isinstance(reply, Exception):
            raise reply
        return reply(messages) if callable(reply) else reply

    def health(self):
        return {"reachable": True, "model_available": True}

    def warmup(self):
        return None


def _settings(**overrides) -> Settings:
    return Settings(llm_extraction_mode="sync", **overrides)


def _service(client, **overrides) -> StructuredExtractionService:
    return StructuredExtractionService(_settings(**overrides), client)


# ----------------------------------------------------------------------
# parse_model_reply
# ----------------------------------------------------------------------
def test_valid_reply_parses_and_validates():
    result, warnings = parse_model_reply(json.dumps({"document_type": "invoice", "fields": {"total": "10.00"}, "tables": [], "sections": []}))
    assert result.document_type == "invoice" and result.fields == {"total": "10.00"} and warnings == []


def test_code_fence_and_trailing_prose_are_tolerated_with_a_warning():
    raw = '```json\n{"document_type": null, "fields": {}, "tables": [], "sections": []}\n```'
    assert parse_model_reply(raw)[1] == []
    _, warnings = parse_model_reply('{"document_type": null, "fields": {}, "tables": [], "sections": []} Hope this helps!')
    assert warnings[0]["code"] == "TRAILING_TEXT_IGNORED"


@pytest.mark.parametrize(
    "raw, code",
    [
        ("", "INCOMPLETE_RESPONSE"),
        ('{"document_type": "invoice", "fields": {"total": "10', "INCOMPLETE_RESPONSE"),
        ("Sorry, I cannot help with that.", "INVALID_JSON"),
        ('{"document_type": "invoice", "fields": {"a": 1,, }}', "INVALID_JSON"),
        ('["not", "an", "object"]', "INVALID_JSON"),
        ('{"document_type": 5, "fields": {}, "tables": [], "sections": []}', "SCHEMA_VALIDATION_FAILED"),
        ('{"fields": {"a": {"b": {"c": {"d": 1}}}}}', "SCHEMA_VALIDATION_FAILED"),
        ('{"tables": [{"headers": ["x"], "rows": ["not-a-row"]}]}', "SCHEMA_VALIDATION_FAILED"),
    ],
)
def test_unusable_replies_map_to_specific_error_codes(raw, code):
    with pytest.raises(StructuringError) as info:
        parse_model_reply(raw)
    assert info.value.code == code


def test_validation_error_messages_never_contain_document_values():
    with pytest.raises(StructuringError) as info:
        parse_model_reply('{"document_type": ["John Silva secret"], "fields": {}}')
    assert "John Silva" not in info.value.message
    assert "document_type" in info.value.message


def test_hand_written_json_schema_and_pydantic_models_agree_on_shape():
    # Every top-level key the schema requires is a model field, and the
    # schema forbids nothing the model allows at the top level.
    assert set(EXTRACTION_JSON_SCHEMA["required"]) == set(ExtractionResult.model_fields)
    table_schema = EXTRACTION_JSON_SCHEMA["properties"]["tables"]["items"]
    assert set(table_schema["required"]) == {"name", "page", "headers", "rows"}


# ----------------------------------------------------------------------
# Grounding
# ----------------------------------------------------------------------
def _source(*texts):
    return SourceIndex(list(texts))


@pytest.mark.parametrize(
    "value",
    ["John Silva", "john silva", "EMP-1023", "EMP1023", "LKR 150,000", "LKR 150000", "150000", 150000, "2026-09-01", "Finance"],
)
def test_values_present_in_the_source_are_supported(value):
    source = _source("Employee Name: John Silva", "Employee ID: EMP-1023", "Salary: LKR 150,000", "Joining Date: 01/09/2026", "Department: Finance")
    assert source.supports(value)


@pytest.mark.parametrize("value", ["Jane Perera", "EMP-9999", "LKR 200,000", "2025-01-01", "USD 150,000", True])
def test_values_absent_from_the_source_are_unsupported(value):
    source = _source("Employee Name: John Silva", "Employee ID: EMP-1023", "Salary: LKR 150,000", "Joining Date: 01/09/2026")
    assert not source.supports(value)


def test_date_normalization_is_accepted_only_when_the_source_date_matches():
    source = _source("Issued on 5 January 2026", "Due March 3, 2026", "Paid 2026.02.14")
    assert source.supports("2026-01-05") and source.supports("2026-03-03") and source.supports("2026-02-14")
    assert not source.supports("2026-01-06")


def test_long_text_uses_word_coverage_so_rejoined_lines_still_count():
    source = _source("The employee shall report to the Head of", "Finance and work forty hours per week.")
    assert source.supports("The employee shall report to the Head of Finance and work forty hours per week.")
    assert not source.supports("The employee shall receive a company car and fuel allowance every month.")


def test_ground_nullifies_invented_values_and_reports_paths_not_values():
    result = ExtractionResult(
        document_type="employee_contract",
        fields={"employee_name": "John Silva", "manager": "Jane Perera", "contacts": [{"phone": "0771234567"}]},
        tables=[{"name": None, "page": 1, "headers": ["Item"], "rows": [["Widget"], ["Gadget"]]}],
        sections=[{"heading": "Terms", "content": None}],
    )
    grounded, warnings = ground(result, _source("Employee Name: John Silva", "Widget", "Terms"), "nullify")
    assert grounded.fields == {"employee_name": "John Silva", "manager": None, "contacts": [{"phone": None}]}
    assert grounded.tables[0].rows == [["Widget"], [None]]
    assert grounded.document_type == "employee_contract"  # a classification, not a copied value
    paths = sorted(w["path"] for w in warnings)
    assert paths == ["fields.contacts[0].phone", "fields.manager", "tables[0].rows[1][0]"]
    assert all("Jane" not in w["message"] for w in warnings)


def test_ground_flag_policy_keeps_values_and_off_skips_the_check():
    result = ExtractionResult(fields={"manager": "Jane Perera"})
    flagged, warnings = ground(result, _source("nothing relevant"), "flag")
    assert flagged.fields == {"manager": "Jane Perera"} and len(warnings) == 1
    assert ground(result, _source("nothing relevant"), "off") == (result, [])


# ----------------------------------------------------------------------
# StructuredExtractionService
# ----------------------------------------------------------------------
def test_the_task_example_document_produces_the_expected_json():
    client = ScriptedClient(_reply({"document_type": "employee_contract", "fields": EXPECTED_FIELDS, "tables": [], "sections": []}))
    result = _service(client).extract([EMPLOYEE_PAGE], document_version_id="v1")
    assert result["status"] == "completed"
    assert result["document_type"] == "employee_contract"
    assert result["fields"] == EXPECTED_FIELDS
    assert result["error"] is None and result["warnings"] == []
    assert result["stats"]["llm_calls"] == 1
    StructuredResult.model_validate(result)  # the returned JSON is itself schema-valid

    user_message = client.calls[0][1]["content"]
    # Layout-aware input, not flattened OCR text.
    assert '<page n="1" source="paddleocr"' in user_message
    assert "L2 @150,300: Employee Name: John Silva" in user_message


def test_hallucinated_value_is_returned_as_null():
    fields = dict(EXPECTED_FIELDS, manager_name="Kamal Perera")
    client = ScriptedClient(_reply({"document_type": "employee_contract", "fields": fields, "tables": [], "sections": []}))
    result = _service(client).extract([EMPLOYEE_PAGE])
    assert result["fields"]["manager_name"] is None
    assert result["warnings"][0]["code"] == "UNGROUNDED_VALUE"


def test_invalid_json_is_retried_with_a_repair_note_then_succeeds():
    good = _reply({"document_type": None, "fields": {"department": "Finance"}, "tables": [], "sections": []})
    client = ScriptedClient(_reply("Here is the data: department = Finance"), good)
    result = _service(client, llm_max_retries=1).extract([EMPLOYEE_PAGE])
    assert result["status"] == "completed" and result["fields"] == {"department": "Finance"}
    assert len(client.calls) == 2
    assert "previous reply could not be used" in client.calls[1][1]["content"]


def test_invalid_json_after_all_retries_fails_with_invalid_json():
    client = ScriptedClient(_reply("not json at all"))
    result = _service(client, llm_max_retries=1).extract([EMPLOYEE_PAGE])
    assert result["status"] == "failed" and result["error"]["code"] == "INVALID_JSON"
    assert result["error"]["retryable"] is True


def test_reply_cut_off_by_output_limit_splits_the_chunk_and_merges_halves():
    def answer(messages):
        text = messages[1]["content"]
        if "Employee Name" in text and "Salary" in text:
            return _reply('{"document_type": "employee_contract", "fields": {"employee_na', finish="length")
        fields = {k: v for k, v in EXPECTED_FIELDS.items() if v in text}
        return _reply({"document_type": "employee_contract", "fields": fields, "tables": [], "sections": []})

    client = ScriptedClient(answer)
    result = _service(client).extract([EMPLOYEE_PAGE])
    assert result["status"] == "completed"
    assert result["fields"] == EXPECTED_FIELDS
    assert len(client.calls) == 3


def test_prompt_truncated_by_the_server_is_detected_and_split():
    def answer(messages):
        text = messages[1]["content"]
        if "Employee Name" in text and "Salary" in text:
            return _reply({"document_type": None, "fields": {}, "tables": [], "sections": []}, prompt_tokens=8190)
        return _reply({"document_type": None, "fields": {"department": "Finance"} if "Finance" in text else {}, "tables": [], "sections": []})

    result = _service(ScriptedClient(answer), llm_context_tokens=8192).extract([EMPLOYEE_PAGE])
    assert result["status"] == "completed" and result["fields"] == {"department": "Finance"}


@pytest.mark.parametrize("code", ["MODEL_UNAVAILABLE", "LLM_TIMEOUT"])
def test_fatal_errors_stop_after_the_first_chunk(code):
    pages = [dict(EMPLOYEE_PAGE, page_number=n) for n in range(1, 6)]
    client = ScriptedClient(StructuringError(code, "boom"))
    result = _service(client, llm_chunk_max_tokens=300, llm_chars_per_token=1.0).extract(pages)
    assert result["status"] == "failed" and result["error"]["code"] == code
    assert len(client.calls) == 1
    assert result["stats"]["chunks_processed"] > 1


def test_one_failed_chunk_among_good_ones_gives_a_partial_result():
    pages = [dict(EMPLOYEE_PAGE, page_number=n) for n in range(1, 4)]
    good = _reply({"document_type": "employee_contract", "fields": {"department": "Finance"}, "tables": [], "sections": []})
    client = ScriptedClient(good, _reply("garbage"), good)
    result = _service(client, llm_chunk_max_tokens=300, llm_chars_per_token=1.0, llm_max_retries=0).extract(pages)
    assert result["status"] == "partial"
    assert result["fields"] == {"department": "Finance"}
    assert any(w["code"] == "INVALID_JSON" and w["chunk"] == 2 for w in result["warnings"])


def test_empty_ocr_result_is_skipped_without_calling_the_model():
    client = ScriptedClient(_reply({}))
    result = _service(client).extract([{"page_number": 1, "method": "paddleocr", "blocks": [], "tables": []}])
    assert result["status"] == "skipped" and result["error"]["code"] == "EMPTY_OCR_RESULT"
    assert client.calls == []


def test_document_over_the_chunk_limit_is_partial_with_document_too_large():
    pages = [dict(EMPLOYEE_PAGE, page_number=n) for n in range(1, 7)]
    client = ScriptedClient(_reply({"document_type": None, "fields": {"department": "Finance"}, "tables": [], "sections": []}))
    result = _service(client, llm_chunk_max_tokens=300, llm_chars_per_token=1.0, llm_max_chunks=2).extract(pages)
    assert result["status"] == "partial"
    assert result["stats"]["chunks_planned"] > 2 and result["stats"]["chunks_processed"] == 2
    assert any(w["code"] == "DOCUMENT_TOO_LARGE" for w in result["warnings"])


def test_no_client_configured_reports_the_configuration_error():
    error = StructuringError("LLM_ENDPOINT_NOT_LOCAL", "LLM host 'api.example.com' is not local")
    result = StructuredExtractionService(_settings(), None, error).extract([EMPLOYEE_PAGE])
    assert result["status"] == "failed" and result["error"]["code"] == "LLM_ENDPOINT_NOT_LOCAL"


def test_unexpected_crash_becomes_internal_error_without_leaking_its_message():
    client = ScriptedClient(KeyError("John Silva"))
    result = _service(client).extract([EMPLOYEE_PAGE])
    assert result["status"] == "failed" and result["error"]["code"] == "INTERNAL_ERROR"
    assert "John" not in json.dumps(result)


def test_repeated_fields_across_chunks_become_arrays_and_split_tables_rejoin():
    pages = [dict(EMPLOYEE_PAGE, page_number=n) for n in range(1, 3)]
    replies = iter([
        _reply({"document_type": "Employee Contract", "fields": {"Employee Name": "John Silva", "department": "Finance"},
                "tables": [{"name": None, "page": 1, "headers": ["Department"], "rows": [["Finance"]]}], "sections": []}),
        _reply({"document_type": "employee_contract", "fields": {"employee_name": "EMP-1023", "department": "Finance"},
                "tables": [{"name": None, "page": 2, "headers": ["Department"], "rows": [["Finance"]]}], "sections": []}),
    ])
    client = ScriptedClient(lambda _: next(replies))
    result = _service(client, llm_chunk_max_tokens=300, llm_chars_per_token=1.0).extract(pages)
    assert result["document_type"] == "employee_contract"
    assert result["fields"]["employee_name"] == ["John Silva", "EMP-1023"]
    assert result["fields"]["department"] == "Finance"
    assert len(result["tables"]) == 1 and result["tables"][0]["rows"] == [["Finance"], ["Finance"]]


def test_ragged_model_table_rows_are_padded_and_flagged():
    reply = _reply({"document_type": None, "fields": {}, "tables": [{"name": None, "page": 1, "headers": ["A", "B"], "rows": [["Finance"]]}], "sections": []})
    result = _service(ScriptedClient(reply)).extract([EMPLOYEE_PAGE])
    assert result["tables"][0]["rows"] == [["Finance", None]]
    assert any(w["code"] == "MALFORMED_TABLE" for w in result["warnings"])


@pytest.mark.parametrize(
    "raw, expected",
    [("Employee Name", "employee_name"), ("Invoice No.", "invoice_no"), ("  VAT %  ", "vat"), ("2nd Address", "field_2nd_address"), ("---", "field"), ("නම", "නම")],
)
def test_normalize_key(raw, expected):
    assert normalize_key(raw) == expected


def test_chunk_budget_is_derived_from_context_and_capped_by_explicit_setting():
    service = _service(ScriptedClient(_reply({})), llm_context_tokens=8192, llm_max_output_tokens=2048)
    derived = service.chunk_budget_tokens()
    assert 3000 < derived < 8192 - 2048
    capped = _service(ScriptedClient(_reply({})), llm_chunk_max_tokens=1000).chunk_budget_tokens()
    assert capped == 1000
    too_big = _service(ScriptedClient(_reply({})), llm_chunk_max_tokens=100_000).chunk_budget_tokens()
    assert too_big == derived


def test_dates_glued_to_their_label_by_ocr_still_ground():
    source = _source("Invoice Date12 September 2026", "Due Date12/10/2026")
    assert source.supports("2026-09-12") and source.supports("2026-10-12")
